/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties

/** A user interface language offered in Settings. */
data class Language(val tag: String, val nativeName: String) {
    val locale: Locale get() = Locale.forLanguageTag(tag)
}

/**
 * Translation tables for the desktop GUI.
 *
 * Strings live in `/i18n/strings.properties` (English, complete) and
 * `/i18n/strings_<language>[_<COUNTRY>].properties` (UTF-8), most of them
 * imported from the Android app's `values-*` resources by
 * `desktop/tools/import_android_translations.py`. Missing keys fall back to
 * English. Plural forms use `key.one`, `key.few`, `key.many` and `key.other`.
 */
object I18n {
    /** Languages with a translation, in the order shown in Settings. */
    val languages = listOf(
        Language("en", "English"),
        Language("de", "Deutsch"),
        Language("es", "Español"),
        Language("fr", "Français"),
        Language("it", "Italiano"),
        Language("pl", "Polski"),
        Language("pt-BR", "Português (Brasil)"),
        Language("pt-PT", "Português (Portugal)"),
        Language("tr", "Türkçe"),
        Language("ru", "Русский"),
        Language("uk", "Українська"),
        Language("ja", "日本語"),
        Language("zh-CN", "简体中文"),
    )

    /** Country used when only the language is known (e.g. `LANG=pt`). */
    private val defaultCountry = mapOf("pt" to "BR", "zh" to "CN")

    /** The supported language closest to [locale], or English. */
    fun resolve(locale: Locale): Language {
        val language = locale.language
        val country = locale.country.ifEmpty { defaultCountry[language].orEmpty() }
        return languages.firstOrNull { it.locale.language == language && it.locale.country == country }
            ?: languages.firstOrNull { it.locale.language == language && it.locale.country.isEmpty() }
            ?: languages.firstOrNull { it.locale.language == language }
            ?: languages.first()
    }

    class Table internal constructor(val language: Language, private val strings: Map<String, String>) {
        val locale: Locale get() = language.locale

        operator fun get(key: String): String = strings[key] ?: key

        /** The plural form of [key] for [count], following CLDR rules. */
        fun plural(key: String, count: Long): String =
            strings["$key.${pluralCategory(locale, count)}"] ?: strings["$key.other"] ?: key

        fun keys(): Set<String> = strings.keys
    }

    fun load(language: Language): Table {
        val strings = HashMap(read("strings"))
        val locale = language.locale
        if (locale.language != "en") {
            strings.putAll(read("strings_${locale.language}"))
            if (locale.country.isNotEmpty()) strings.putAll(read("strings_${locale.language}_${locale.country}"))
        }
        return Table(language, strings)
    }

    /** Only the keys defined by [language]'s own files, without the English fallback. */
    fun ownKeys(language: Language): Map<String, String> {
        val locale = language.locale
        return HashMap(read("strings_${locale.language}")).apply {
            if (locale.country.isNotEmpty()) putAll(read("strings_${locale.language}_${locale.country}"))
        }
    }

    internal fun read(name: String): Map<String, String> {
        val stream = I18n::class.java.getResourceAsStream("/i18n/$name.properties") ?: return emptyMap()
        val properties = Properties()
        InputStreamReader(stream, StandardCharsets.UTF_8).use { properties.load(it) }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }

    /**
     * CLDR plural category of an integer for the supported languages
     * (https://www.unicode.org/cldr/charts/latest/supplemental/language_plural_rules.html).
     */
    fun pluralCategory(locale: Locale, n: Long): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when (locale.language) {
            "ru", "uk" -> when {
                mod10 == 1L && mod100 != 11L -> "one"
                mod10 in 2..4 && mod100 !in 12..14 -> "few"
                else -> "many"
            }
            "pl" -> when {
                n == 1L -> "one"
                mod10 in 2..4 && mod100 !in 12..14 -> "few"
                else -> "many"
            }
            "fr" -> if (n == 0L || n == 1L) "one" else "other"
            // Brazilian Portuguese treats 0 like 1; European Portuguese does not.
            "pt" -> if (n == 1L || (n == 0L && locale.country != "PT")) "one" else "other"
            "ja", "zh" -> "other"
            else -> if (n == 1L) "one" else "other"
        }
    }
}
