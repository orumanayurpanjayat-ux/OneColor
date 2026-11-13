package com.fahadali.oneos.core.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The redesign's screens never name a system in fixed text (owner: OneOS runs
 * any system; `{system}` is the image table's name). This is the narrow
 * first part of plan step 7's test: the strings and Kotlin of the new Home,
 * the Switch confirm, Advanced, the screens Advanced opens (Repair or start fresh, Use
 * a downloaded file, Free up space, Last session, For the OneOS team, All
 * checks and Test run, and this edition's About and Before you start texts)
 * and the fix actions. Step 7 widens it to every string except the credits
 * and the legal line.
 */
class StringsNeutralTest {
    private val src = Shipped.appSources

    private val names = Regex("""ColorOS|OPPO|Lineage|Pixel|Find N6|Find X9|lineage_stage""")

    /** The keys of the new screens (full edition). */
    private val newKeys = Regex("""^(home_|confirm_|advanced_|menu_|fix_).*""")

    private fun strings(f: File) = Regex("""<string name="(\w+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }

    /** The full edition's own strings: strings.xml and strings_advanced.xml (Advanced and the screens it opens). */
    private fun fullStrings() = strings(File(src, "full/res/values/strings.xml")) + strings(File(src, "full/res/values/strings_advanced.xml"))

    @Test
    fun theNewScreensStringsNameNoSystem() {
        val full = fullStrings()
        val keys = full.keys.filter { newKeys.matches(it) }
        assertTrue("no new keys found", keys.size > 40)
        for (k in keys) {
            val hit = names.find(full.getValue(k))
            assertEquals("$k: ${full[k]}", null, hit?.value)
        }
        // Home, the Switch sheet and Welcome back (the redesign's cards): every string.
        val home = strings(File(src, "full/res/values/strings_home.xml"))
        assertTrue("no Home strings found", home.size > 40)
        for ((k, v) in home) assertEquals("$k: $v", null, names.find(v)?.value)
    }

    /**
     * Advanced and every screen it opens (the redesign's card look): every
     * string and plural of strings_advanced.xml, the overrides of main's About,
     * Before you start and Phone details texts among them. The row titles are
     * the screens' own titles, so Advanced and For the OneOS team name each
     * screen as it is titled.
     */
    @Test
    fun theAdvancedStringsNameNoSystem() {
        val f = File(src, "full/res/values/strings_advanced.xml")
        assertTrue("${f.name} is missing", f.isFile)
        val adv = strings(f)
        assertTrue("no Advanced strings found", adv.size > 150)
        for ((k, v) in adv) assertEquals("$k: $v", null, names.find(v)?.value)
        val items = Regex("""<item quantity="\w+">(.*?)</item>""").findAll(f.readText()).map { it.groupValues[1] }.toList()
        assertTrue("no plurals found", items.isNotEmpty())
        for (v in items) assertEquals(v, null, names.find(v)?.value)
        // The screens' titles: what Advanced's rows and the team's rows say.
        val titles = mapOf(
            "prepare_title" to "Repair or start fresh", "image_title" to "Load custom GSI", "space_title" to "Free up space",
            "team_title" to "For the OneOS team", "switch_title" to "All checks and Test run", "report_title" to "Phone details",
            "prepare_title_team" to "Setup details", "image_title_team" to "File details and checks",
            // Not "Run tests" next to "Test run" on the OneOS team's screen.
            "tests_title" to "Phone tests",
        )
        for ((k, v) in titles) assertEquals(k, v, adv[k])
        // One name for the run that stops before the system starts.
        for ((k, v) in adv) assertTrue("$k: $v", !v.contains("Dry run"))
    }

    /**
     * 0.3.7 (the Pixel image next to ColorOS): the older screens' fixed texts too, the ones the Pixel flow
     * shows (Before you start, Use a downloaded file, Set up again, All checks and Test run, Last session,
     * Free up space): `%1$s` is the image table's name, or they say "the other system". 0.4.0 (Advanced
     * redesigned): no exception is left; the older system's data and the old folder name are said without
     * naming them.
     */
    @Test
    fun theOlderScreensStringsNameNoSystem() {
        val full = fullStrings()
        val older = Regex("""^(before_|image_|prepare_|switch_|last_|old_|space_|team_).*""")
        val keys = full.keys.filter { older.matches(it) }
        assertTrue("no older keys found", keys.size > 100)
        for (k in keys) {
            val hit = names.find(full.getValue(k))
            assertEquals("$k: ${full[k]}", null, hit?.value)
        }
        // The legal line names every system's owner's trademarks (main edition).
        val main = strings(File(src, "main/res/values/strings.xml"))
        for (n in listOf("Galaxy", "One UI", "ColorOS", "Pixel", "Android")) assertTrue(n, main.getValue("about_legal_summary").contains(n))
    }

    @Test
    fun theNewScreensKotlinNamesNoSystem() {
        val ui = File(src, "full/kotlin/com/fahadali/oneos/ui")
        val files = listOf(
            "EditionHome.kt", "SwitchConfirm.kt", "FixActions.kt", "AdvancedActivity.kt",
            // Advanced's screens (the card look): Set up again, Use a downloaded file, Free up space,
            // Last session, For the OneOS team, All checks and Test run, and their shared pieces.
            "CardScreen.kt", "TeamActivity.kt", "PrepareActivity.kt", "ImageActivity.kt", "OldStagesActivity.kt",
            "LastSessionActivity.kt", "SwitchActivity.kt",
        ).map { File(ui, it) } +
            File(src, "full/kotlin/com/fahadali/oneos/session/Readiness.kt") +
            listOf(
                "activity_home.xml", "dialog_switch.xml", "home_content.xml", "home_hero.xml", "home_progress.xml",
                "home_stat_tile.xml", "home_welcome_logs.xml", "home_way_tile.xml", "home_switch_ack.xml",
                "oneos_cards_screen.xml", "oneos_storage_bar.xml",
            ).map { File(src, "full/res/layout/$it") } +
            listOf("activity_home.xml", "home_content.xml", "home_hero.xml").map { File(src, "full/res/layout-w600dp-h480dp/$it") } +
            File(src, "full/res/menu/home.xml")
        for (f in files) {
            assertTrue("${f.name} is missing", f.isFile)
            // Comments may say what the text was; the code's own strings may not name a system.
            val code = f.readLines().filterNot { l -> l.trimStart().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") || it.startsWith("<!--") } }
            for ((i, l) in code.withIndex()) {
                val hit = names.find(l)
                assertEquals("${f.name}:${i + 1}: $l", null, hit?.value)
            }
        }
    }
}
