package com.greenart7c3.citrine.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layering rules the compiler cannot check. Every allow-list here is a ratchet:
 * an entry has to disappear when the code it excuses is fixed (the test fails
 * when a listed file stops violating the rule), and nothing may be added to it
 * without deleting the offending code.
 *
 * The target layering is described in docs/architecture.md.
 */
class ArchitectureTest {
    private val scope = Konsist.scopeFromProject()

    private val appMain = scope.files.filter { it.moduleName == "app" && it.isProduction() }

    private fun KoFileDeclaration.isProduction(): Boolean = path.replace('\\', '/').contains("/src/main/")

    private fun KoFileDeclaration.relativePath(): String {
        val normalized = path.replace('\\', '/')
        return normalized.substringAfter("/com/greenart7c3/citrine/", normalized)
    }

    private fun assertRatchet(
        rule: String,
        actual: Set<String>,
        allowed: Set<String>,
    ) {
        val added = (actual - allowed).sorted()
        val fixed = (allowed - actual).sorted()
        assertTrue(
            "$rule\n" +
                "New violations (fix the code or move it to an allowed layer, do not extend the list):\n" +
                added.joinToString("\n"),
            added.isEmpty(),
        )
        assertTrue(
            "$rule\n" +
                "These allow-list entries no longer violate the rule, delete them:\n" +
                fixed.joinToString("\n"),
            fixed.isEmpty(),
        )
    }

    @Test
    fun `the app sources are found`() {
        assertTrue("no :app sources were found", appMain.isNotEmpty())
        assertTrue("no ui/ sources were found", appMain.any { it.relativePath().startsWith("ui/") })
    }

    @Test
    fun `the ui package does not talk to the database`() {
        val actual = appMain
            .filter { it.relativePath().startsWith("ui/") }
            .filterNot { it.relativePath().endsWith("ViewModel.kt") }
            .filter { file -> file.imports.any { it.name.startsWith("com.greenart7c3.citrine.database") } || databaseOrDaoCallEvenWithoutImport.containsMatchIn(file.text) }
            .map { it.relativePath() }
            .toSet()

        assertRatchet("ui/ files must load data through a ViewModel or service, not a Room DAO:", actual, databaseInUi)
    }

    @Test
    fun `Context citrine usage can only shrink`() {
        val unimportedCitrineExtensionNotInAHyphenatedName = Regex("""(?<![\w-])citrine(?![\w-])""")
        val containerCastWithoutTheExtension = Regex("""\bas\??\s+(com\.greenart7c3\.citrine\.)?Citrine\b""")
        val actual = appMain
            .filter { it.relativePath() != "Citrine.kt" }
            .filter { file ->
                file.imports.any { it.name == "com.greenart7c3.citrine.citrine" } ||
                    containerCastWithoutTheExtension.containsMatchIn(file.text) ||
                    (
                        file.packagee?.name == "com.greenart7c3.citrine" &&
                            unimportedCitrineExtensionNotInAHyphenatedName.containsMatchIn(file.text.lines().filterNot { it.startsWith("import ") || it.startsWith("package ") }.joinToString("\n"))
                        )
            }
            .map { it.relativePath() }
            .toSet()

        assertRatchet(
            "Context.citrine (or a cast to Citrine) hands out the whole container: take the dependency through a constructor " +
                "or parameter instead. Entry points the system constructs are the exception.",
            actual,
            contextCitrineUsers,
        )
    }

    @Test
    fun `service and database do not render UI`() {
        val actual = appMain
            .filter { file -> file.relativePath().startsWith("service/") || file.relativePath().startsWith("database/") }
            .filter { file -> file.imports.any { it.name.isComposeUiAndNotText() } }
            .map { it.relativePath() }
            .toSet()

        assertRatchet(
            "service/ and database/ must not depend on Compose UI (androidx.compose.ui.text is data, not UI):",
            actual,
            composeUiInService,
        )
    }

    @Test
    fun `ViewModels are named ViewModel`() {
        val misnamed = scope.classes()
            .filter { koClass -> koClass.hasParentWithName("ViewModel") || koClass.hasParentWithName("AndroidViewModel") }
            .filterNot { it.name.endsWith("ViewModel") }
            .map { "${it.containingFile.moduleName}/${it.containingFile.relativePath()}: ${it.name}" }

        assertTrue("ViewModel subclasses must end in ViewModel:\n" + misnamed.joinToString("\n"), misnamed.isEmpty())
    }

    private fun String.isComposeUiAndNotText(): Boolean = startsWith("androidx.compose.ui.") && !startsWith("androidx.compose.ui.text.")

    private companion object {
        val databaseOrDaoCallEvenWithoutImport = Regex("""\.(\w*D|d)aos?\(|\bget(History|Log)?Database\(""")

        val databaseInUi = emptySet<String>()

        val contextCitrineUsers = setOf(
            "MainActivity.kt",
            "provider/CitrineContentProvider.kt",
            "service/ClipboardReceiver.kt",
            "service/EventBroadcastWorker.kt",
            "service/WebSocketServerService.kt",
        )

        val composeUiInService = setOf(
            "service/crashreports/CrashReportScreen.kt",
        )
    }
}
