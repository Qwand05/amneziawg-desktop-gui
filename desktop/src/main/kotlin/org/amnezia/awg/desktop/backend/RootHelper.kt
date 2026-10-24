/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.amnezia.awg.desktop.util.AppLog
import org.amnezia.awg.desktop.util.Strings
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Error reported by the root helper.
 */
class RootHelperException(val code: String, message: String) : Exception(message)

/**
 * Client for `awg-helper.sh`, a small privileged program that is started once
 * through polkit (`pkexec`) and then answers line-based requests on stdin.
 *
 * Starting the helper lazily and keeping it alive means the user only has to
 * authenticate once per application run instead of once per operation.
 */
class RootHelper private constructor(
    private val process: Process,
    private val writer: OutputStreamWriter,
    private val mode: String,
) : AutoCloseable {
    private val nextId = AtomicLong()
    private val pending = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<String>>()

    val isAlive: Boolean get() = process.isAlive

    /** Human readable description of how privileged commands are executed. */
    val description: String get() = when (mode) {
        MODE_DIRECT -> Strings.PRIVILEGES_ROOT
        MODE_SUDO -> Strings.PRIVILEGES_SUDO
        else -> Strings.PRIVILEGES_PKEXEC
    }

    /**
     * Execute a helper command and return its (decoded) payload.
     *
     * @throws RootHelperException when the helper reports an error
     * @throws IllegalStateException when the helper is no longer running
     */
    /**
     * True while a command is being executed. The helper handles one request
     * at a time, so callers that only poll (statistics) skip instead of
     * queueing behind a long `awg-quick` run.
     */
    @Volatile
    var isBusy: Boolean = false
        private set

    @Synchronized
    fun exec(vararg args: String, timeoutSeconds: Long = 30): String {
        isBusy = true
        try {
            return execLocked(args, timeoutSeconds)
        } finally {
            isBusy = false
        }
    }

    private fun execLocked(args: Array<out String>, timeoutSeconds: Long): String {
        check(process.isAlive) { "The privileged helper is no longer running" }
        val id = nextId.incrementAndGet().toString()
        val future = java.util.concurrent.CompletableFuture<String>()
        pending[id] = future
        try {
            writer.write(args.joinToString("\t", prefix = "$id\t") + "\n")
            writer.flush()
        } catch (e: Exception) {
            pending.remove(id)
            throw IllegalStateException("Failed to talk to the privileged helper: ${e.message}", e)
        }
        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            pending.remove(id)
            throw IllegalStateException("Timed out waiting for the privileged helper", e)
        } catch (e: java.util.concurrent.ExecutionException) {
            pending.remove(id)
            val cause = e.cause ?: throw IllegalStateException(e)
            throw cause
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        try {
            if (process.isAlive) {
                writer.write("${nextId.incrementAndGet()}\tquit\n")
                writer.flush()
                process.waitFor(3, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
        } finally {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun readLoop() {
        BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val parts = line.split('\t')
                if (parts.size < 3) continue
                val id = parts[0]
                val future = pending[id] ?: continue
                when (parts[1]) {
                    "OK" -> future.complete(b64Decode(parts[2]))
                    "ERR" -> future.completeExceptionally(
                        RootHelperException(parts[2], if (parts.size > 3) b64Decode(parts[3]) else "Unknown error")
                    )
                    else -> future.completeExceptionally(IllegalStateException("Malformed response: $line"))
                }
            }
        }
        // The helper exited: fail everything that is still in flight.
        if (process.isAlive || pending.isNotEmpty()) AppLog.w(TAG, "The privileged helper exited")
        val error = IllegalStateException("The privileged helper exited unexpectedly")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    companion object {
        private const val TAG = "RootHelper"
        const val MODE_PKEXEC = "pkexec"
        const val MODE_DIRECT = "direct"
        const val MODE_SUDO = "sudo"

        private const val HELPER_RESOURCE = "/awg-helper.sh"

        /** Root owned copy installed by the .deb package; preferred when present. */
        private val INSTALLED_HELPER = File("/usr/lib/amnezia-awg/awg-helper.sh")
        private var cachedScript: File? = null

        private fun b64Decode(value: String): String =
            String(Base64.getDecoder().decode(value.toByteArray(StandardCharsets.US_ASCII)), StandardCharsets.UTF_8)

        /**
         * Resolve the mode used to reach root.
         *
         * `AMNEZIA_AWG_HELPER=pkexec|sudo|direct` overrides the default, which
         * is useful for testing and for systems without a polkit agent.
         */
        fun resolveMode(): String = when (System.getenv("AMNEZIA_AWG_HELPER")?.lowercase()) {
            "direct" -> MODE_DIRECT
            "sudo" -> MODE_SUDO
            "pkexec" -> MODE_PKEXEC
            else -> MODE_PKEXEC
        }

        /** Extract the bundled helper script to a private temporary file. */
        @Synchronized
        fun helperScript(): File {
            if (INSTALLED_HELPER.isFile) return INSTALLED_HELPER
            cachedScript?.let { if (it.isFile) return it }
            val resource = checkNotNull(RootHelper::class.java.getResourceAsStream(HELPER_RESOURCE)) {
                "Bundled helper script $HELPER_RESOURCE is missing"
            }
            val file = File.createTempFile("amnezia-awg-helper-", ".sh")
            file.deleteOnExit()
            resource.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file.setReadable(true, false)
            file.setExecutable(true, false)
            cachedScript = file
            return file
        }

        /**
         * Start the helper. Suspends the caller while polkit asks the user to
         * authenticate, so it must not run on the UI thread.
         */
        fun start(): RootHelper {
            val script = helperScript()
            // The helper relies on bash features; /bin/sh is dash on Ubuntu.
            val command = when (resolveMode()) {
                MODE_DIRECT -> listOf("/bin/bash", script.absolutePath)
                MODE_SUDO -> listOf("sudo", "-n", "/bin/bash", script.absolutePath)
                else -> listOf("pkexec", "/bin/bash", script.absolutePath)
            }
            AppLog.i(TAG, "Starting the privileged helper (${resolveMode()}): ${script.absolutePath}")
            val process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val writer = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)
            val helper = RootHelper(process, writer, resolveMode())
            Thread(helper::readLoop, "awg-helper-reader").apply {
                isDaemon = true
                start()
            }
            // Fail fast (and surface a friendly error) if authentication was
            // refused or the helper could not be started at all.
            try {
                helper.exec("ping", timeoutSeconds = 300)
            } catch (e: Exception) {
                helper.close()
                AppLog.w(TAG, "Privileged helper did not start: authentication cancelled or failed", e)
                throw RootHelperException("auth", Strings.AUTH_FAILED).apply { initCause(e) }
            }
            AppLog.i(TAG, "Privileged helper running")
            return helper
        }
    }
}
