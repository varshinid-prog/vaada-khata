package com.vaada.models

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UiCatalogTest {
    private val regional = listOf(AppLanguage.HINDI, AppLanguage.TELUGU, AppLanguage.TAMIL, AppLanguage.KANNADA)

    @Test fun languageOrderMatchesCatalogColumns() {
        assertEquals(listOf("en-IN", "hi-IN", "te-IN", "ta-IN", "kn-IN"), AppLanguage.entries.map { it.locale })
    }

    @Test fun englishIsTheDefaultUiLanguage() {
        assertEquals("Assistant", UiStrings.of(AppLanguage.ENGLISH).navAssistant)
        assertEquals("Log in", UiCatalog.get("login", AppLanguage.ENGLISH))
    }

    @Test fun everyKeyUsedInTheSourcesExistsAndIsTranslatedIntoAllLanguages() {
        val root = File("src/main/java/com/vaada")
        val used = root.walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> Regex("""\btr\("([a-z_0-9]+)"""").findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue("no tr() usages found; wrong working directory?", used.isNotEmpty())
        for (key in used) {
            assertTrue("missing catalog key: $key", key in UiCatalog.keys)
            for (lang in AppLanguage.entries) {
                val text = UiCatalog.get(key, lang, "X", "Y")
                assertTrue("$key/$lang blank", text.isNotBlank())
                assertNotEquals("$key/$lang returned the key itself", key, text)
            }
        }
    }

    @Test fun regionalTextContainsNoStrayEnglishWords() {
        val allowed = setOf("PIN", "OTP", "Hey", "Jarvis")
        for (key in UiCatalog.keys) for (lang in regional) {
            val words = Regex("[A-Za-z]{2,}").findAll(UiCatalog.get(key, lang, "X", "Y")).map { it.value }.toSet() - allowed - setOf("X", "Y")
            assertTrue("$key/$lang has English words $words", words.isEmpty())
        }
    }

    @Test fun placeholdersMatchAcrossLanguages() {
        for (key in UiCatalog.keys) {
            val counts = AppLanguage.entries.map { Regex("%s").findAll(UiCatalog.get(key, it)).count() }.toSet()
            assertEquals("$key placeholder count differs", 1, counts.size)
        }
    }
}
