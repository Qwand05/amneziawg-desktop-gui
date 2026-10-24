/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

/**
 * Guards the translations: a placeholder of the wrong type (`%d` instead of
 * `%s`) or an incomplete set of plural forms would only show up at runtime,
 * as a crash or a half-translated sentence.
 */
class I18nTest {
    private val english = I18n.read("strings")
    private val pluralSuffix = Regex("""\.(zero|one|two|few|many|other)$""")
    private val specifier = Regex("""%(\d+\$)?([-#+ 0,(]*)(\d+)?(\.\d+)?([a-zA-Z%])""")

    private fun base(key: String) = key.replace(pluralSuffix, "")

    /** Sample arguments matching the conversions of an English pattern. */
    private fun sampleArgs(pattern: String): Array<Any> {
        val args = sortedMapOf<Int, Any>()
        var next = 1
        for (match in specifier.findAll(pattern)) {
            val conversion = match.groupValues[5]
            if (conversion == "%" || conversion == "n") continue
            val index = match.groupValues[1].removeSuffix("$").toIntOrNull() ?: next++
            args[index] = when (conversion.lowercase()) {
                "d" -> 42
                "f", "e", "g" -> 1.5
                else -> "x"
            }
        }
        return args.values.toTypedArray()
    }

    @Test
    fun everyLanguageHasOnlyKnownKeysAndCompatiblePlaceholders() {
        val englishBases = english.keys.map(::base).toSet()
        for (language in I18n.languages.drop(1)) {
            val own = I18n.ownKeys(language)
            assertTrue("${language.tag} has no translations", own.size > 100)
            for ((key, value) in own) {
                assertTrue("${language.tag}: unknown key $key", base(key) in englishBases)
                val reference = english[key] ?: english["${base(key)}.other"]!!
                try {
                    String.format(language.locale, value, *sampleArgs(reference))
                } catch (e: Exception) {
                    fail("${language.tag}: $key = \"$value\" does not fit \"$reference\": $e")
                }
            }
        }
    }

    @Test
    fun pluralFormsAreCompleteWhenTranslated() {
        val pluralBases = english.keys.filter { pluralSuffix.containsMatchIn(it) }.map(::base).toSet()
        val counts = listOf(0L, 1L, 2L, 3L, 5L, 11L, 12L, 21L, 22L, 25L, 101L, 112L)
        for (language in I18n.languages) {
            val own = if (language.tag == "en") english else I18n.ownKeys(language)
            val needed = counts.map { I18n.pluralCategory(language.locale, it) }.toSet()
            for (base in pluralBases) {
                val forms = own.keys.filter { base(it) == base && it != base }.map { it.substringAfterLast('.') }.toSet()
                if (forms.isEmpty()) continue // falls back to English as a whole
                val missing = needed.filter { it !in forms && "other" !in forms }
                assertTrue("${language.tag}: $base lacks plural forms $missing", missing.isEmpty())
            }
        }
    }

    @Test
    fun pluralRules() {
        val ru = Locale.forLanguageTag("ru")
        assertEquals(
            listOf("one", "few", "many", "many", "one", "few", "many"),
            listOf(1L, 2L, 5L, 11L, 21L, 22L, 112L).map { I18n.pluralCategory(ru, it) },
        )
        val pl = Locale.forLanguageTag("pl")
        assertEquals(listOf("one", "few", "many", "many", "few"), listOf(1L, 3L, 12L, 21L, 22L).map { I18n.pluralCategory(pl, it) })
        assertEquals("one", I18n.pluralCategory(Locale.forLanguageTag("pt-BR"), 0))
        assertEquals("other", I18n.pluralCategory(Locale.forLanguageTag("pt-PT"), 0))
        assertEquals("one", I18n.pluralCategory(Locale.forLanguageTag("fr"), 0))
        assertEquals("other", I18n.pluralCategory(Locale.forLanguageTag("ja"), 1))
        assertEquals("other", I18n.pluralCategory(Locale.ENGLISH, 0))
    }

    @Test
    fun resolvesSystemLocales() {
        assertEquals("ru", I18n.resolve(Locale.forLanguageTag("ru-RU")).tag)
        assertEquals("pt-BR", I18n.resolve(Locale.forLanguageTag("pt")).tag)
        assertEquals("pt-PT", I18n.resolve(Locale.forLanguageTag("pt-PT")).tag)
        assertEquals("de", I18n.resolve(Locale.forLanguageTag("de-AT")).tag)
        assertEquals("en", I18n.resolve(Locale.forLanguageTag("xx")).tag)
    }

    @Test
    fun stringsSwitchLanguageAndFormatPlurals() {
        try {
            Strings.setLanguage(I18n.languages.first { it.tag == "ru" })
            assertEquals("Подключено", Strings.TUNNEL_STATUS_CONNECTED)
            assertEquals("Удалить 5 туннелей?", Strings.deleteConfirmTitle(5))
            assertEquals("Удалить 3 туннеля?", Strings.deleteConfirmTitle(3))
            assertEquals("1,50 КиБ", Strings.kibibytes(1.5))
            Strings.setLanguage(I18n.languages.first { it.tag == "en" })
            assertEquals("Delete 5 tunnels?", Strings.deleteConfirmTitle(5))
            assertEquals("Delete 1 tunnel?", Strings.deleteConfirmTitle(1))
        } finally {
            Strings.setLanguage(I18n.languages.first())
        }
    }
}
