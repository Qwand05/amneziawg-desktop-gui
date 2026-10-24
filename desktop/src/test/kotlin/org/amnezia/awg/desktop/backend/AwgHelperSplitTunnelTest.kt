/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.amnezia.awg.crypto.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the real `awg-helper.sh` against a split tunnel with thousands of
 * AllowedIPs inside an unprivileged network namespace (`unshare -rn`), which
 * has its own interfaces and routes, so the host network is never touched.
 *
 * Regression test for tunnels that took minutes to come up and routed their
 * own endpoint into the tunnel, cutting the machine off the internet.
 * Skipped where namespaces or the AmneziaWG module are unavailable.
 */
class AwgHelperSplitTunnelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun available(vararg command: String): Boolean = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    @Test
    fun bringsUpLargeSplitTunnelQuicklyWithoutCapturingTheEndpoint() {
        assumeTrue("AmneziaWG kernel module not loaded", File("/sys/module/amneziawg").exists())
        assumeTrue("awg-quick missing", available("sh", "-c", "command -v awg-quick"))
        assumeTrue("unprivileged network namespaces unavailable", available("unshare", "-rn", "true"))

        val helper = File(tmp.root, "awg-helper.sh").apply {
            writeBytes(AwgHelperSplitTunnelTest::class.java.getResourceAsStream("/awg-helper.sh")!!.readBytes())
        }
        // 3000 networks; 203.0.112.0/23 covers the endpoint 203.0.113.10.
        val networks = (0 until 3000).map { "10.${it / 250}.${it % 250}.0/24" } + "203.0.112.0/23"
        val config = File(tmp.root, "awgtest.conf").apply {
            writeText(
                """
                [Interface]
                PrivateKey = ${KeyPair().privateKey.toBase64()}
                Address = 10.255.0.2/32
                Jc = 4
                Jmin = 40
                Jmax = 70

                [Peer]
                PublicKey = ${KeyPair().publicKey.toBase64()}
                AllowedIPs = ${networks.joinToString(", ")}
                Endpoint = 203.0.113.10:51820
                """.trimIndent() + "\n"
            )
            setReadable(false, false); setReadable(true, true)
        }
        val script = """
            set -u
            ip link set lo up
            ip link add uplink type dummy; ip link set uplink up
            ip addr add 192.0.2.10/24 dev uplink; ip route add default via 192.0.2.1 dev uplink
            status() { cut -f2; }
            start=${'$'}(date +%s%N)
            echo "up=${'$'}(printf '1\tup\t%s\n' "${'$'}CONF" | bash "${'$'}HELPER" | status)"
            echo "up_ms=${'$'}(( (${'$'}(date +%s%N) - start) / 1000000 ))"
            echo "routes=${'$'}(ip -4 route show dev awgtest | wc -l)"
            echo "peer_networks=${'$'}(awg show awgtest allowed-ips | cut -f2 | wc -w)"
            echo "endpoint_dev=${'$'}(ip -o route get 203.0.113.10 | grep -o 'dev [^ ]*' | cut -d' ' -f2)"
            echo "covered_dev=${'$'}(ip -o route get 10.5.5.5 | grep -o 'dev [^ ]*' | cut -d' ' -f2)"
            echo "down=${'$'}(printf '2\tdown\t%s\n' "${'$'}CONF" | bash "${'$'}HELPER" | status)"
            echo "iface_after=${'$'}(ip link show awgtest >/dev/null 2>&1 && echo yes || echo no)"
            echo "pin_after=${'$'}(ip route show 203.0.113.10 | wc -l)"
            echo "state_after=${'$'}(ls "${'$'}AWG_HELPER_STATE_DIR" | wc -l)"
        """.trimIndent()

        val process = ProcessBuilder("unshare", "-rn", "bash", "-c", script)
            .redirectErrorStream(true)
            .apply {
                environment()["HELPER"] = helper.absolutePath
                environment()["CONF"] = config.absolutePath
                environment()["AWG_HELPER_STATE_DIR"] = File(tmp.root, "state").absolutePath
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("helper run timed out:\n$output", process.waitFor(120, TimeUnit.SECONDS))
        val result = output.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

        assertEquals(output, "OK", result["up"])
        assertTrue("up took ${result["up_ms"]} ms", result["up_ms"]!!.toLong() < 20_000)
        assertEquals(output, "3001", result["routes"])
        assertEquals(output, "3001", result["peer_networks"])
        assertEquals("endpoint must stay outside the tunnel", "uplink", result["endpoint_dev"])
        assertEquals("awgtest", result["covered_dev"])
        assertEquals(output, "OK", result["down"])
        assertEquals("no", result["iface_after"])
        assertEquals("endpoint pin must be removed on down", "0", result["pin_after"])
        assertEquals("0", result["state_after"])
    }
}
