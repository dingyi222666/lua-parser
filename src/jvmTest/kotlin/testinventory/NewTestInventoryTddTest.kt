package testinventory

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.stream.Stream
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewTestInventoryTddTest {

    @Test
    fun reportsCurrentCommonAndJvmTestInventory() {
        val inventory = loadInventory()
        val baselineEntries = inventory.entries.filterNot { it.isInventoryFixture }

        assertEquals(expectedBaselineFiles, baselineEntries.size)
        assertEquals(expectedBaselineTestMethods, baselineEntries.sumOf { it.testMethods })
        assertTrue(baselineEntries.any { it.sourceSet == "commonTest" && it.area == "semantic" })
        assertTrue(baselineEntries.any { it.sourceSet == "commonTest" && it.area == "workspace" })
        assertTrue(baselineEntries.any { it.sourceSet == "jvmTest" && it.area == "parser" })
        assertTrue(baselineEntries.any { it.sourceSet == "jvmTest" && it.area == "semantic" })
        assertTrue(baselineEntries.any { it.sourceSet == "jvmTest" && it.area == "workspace" })
        assertTrue(baselineEntries.any { it.sourceSet == "jvmTest" && it.area == "interop" })
        assertTrue(baselineEntries.any { it.sourceSet == "jvmTest" && it.area == "lsp" })
        assertTrue(baselineEntries.any { it.sourceSet == "jvmTest" && it.area == "integration" })
        assertTrue(
            baselineEntries.none { it.area == "other" },
            baselineEntries.filter { it.area == "other" }.joinToString { it.normalizedPath }
        )

        println(inventory.report())
    }

    @Test
    fun documentsHowNewTddSuitesCountTowardFiveHundredTestGoal() {
        val strategy = repoRoot.resolve("docs/test-strategy.md").readText()
        val inventory = loadInventory()
        val campaignSuites = inventory.campaignSuites

        assertTrue(strategy.contains("Only Kotlin source files whose file name ends with `Test.kt` are counted"))
        assertTrue(strategy.contains("500-test goal counts new TDD coverage"))
        assertTrue(strategy.contains("campaign_total = sum(@Test methods in included *TddTest.kt files)"))
        assertTrue(strategy.contains("not under the `testinventory` package/path"))
        assertEquals(expectedCampaignFiles, campaignSuites.size)
        assertEquals(expectedCampaignTestMethods, campaignSuites.sumOf { it.testMethods })
        campaignSuites.forEach { entry ->
            assertTrue(entry.path.name.endsWith("TddTest.kt"), entry.path.toString())
            assertTrue("testinventory" !in entry.normalizedPath, entry.path.toString())
            assertTrue(entry.testMethods > 0, entry.path.toString())
        }

        println("TDD campaign suites: ${campaignSuites.size}, counted @Test methods: ${campaignSuites.sumOf { it.testMethods }}")
    }

    @Test
    fun documentsAnnotatedKotlinFilesExcludedByNamingPolicy() {
        val strategy = repoRoot.resolve("docs/test-strategy.md").readText()
        val inventory = loadInventory()
        val excludedFiles = inventory.excludedAnnotatedKotlinFiles
            .associate { it.normalizedPath to it.testMethods }

        assertEquals(expectedExcludedAnnotatedKotlinFiles, excludedFiles)
        expectedExcludedAnnotatedKotlinFiles.forEach { (path, testMethods) ->
            assertTrue(strategy.contains("`$path`"))
            assertTrue(strategy.contains("| `$path` | $testMethods |"))
        }
        assertTrue(strategy.contains("excluded from current inventory and campaign totals"))
    }

    @Test
    fun strategyDocumentTracksBaselineInventoryAndFixtureScope() {
        val strategyPath = repoRoot.resolve("docs/test-strategy.md")
        val strategy = strategyPath.readText()

        assertTrue(strategyPath.exists())
        assertTrue(strategy.contains("| **Total current inventory** | Common + JVM | **209** | **2752** |"))
        assertTrue(strategy.contains("current_campaign_files = 150"))
        assertTrue(strategy.contains("current_campaign_total = 2237"))
        assertTrue(strategy.contains("remaining_to_500 = 0"))

        assertTrue(strategy.contains("`src/commonTest/kotlin` is included"))
        assertTrue(strategy.contains("The fixture prints a concise inventory report"))
        assertTrue(strategy.contains("does not import or mutate production code"))
    }

    private fun loadInventory(): TestInventory {
        val roots = listOf(
            repoRoot.resolve("src/commonTest/kotlin"),
            repoRoot.resolve("src/jvmTest/kotlin")
        )
        val entries = roots.flatMap { root ->
            if (!root.exists()) {
                emptyList()
            } else {
                walkTestFiles(root)
                    .map { path -> TestEntry(root, path, testMethodCount(path.readText())) }
            }
        }.sortedBy { it.normalizedPath }

        val excludedAnnotatedKotlinFiles = roots.flatMap { root ->
            if (!root.exists()) {
                emptyList()
            } else {
                walkKotlinFiles(root)
                    .filterNot { path -> path.name.endsWith("Test.kt") }
                    .mapNotNull { path ->
                        val testMethods = testMethodCount(path.readText())
                        if (testMethods == 0) {
                            null
                        } else {
                            ExcludedAnnotatedKotlinFile(path, testMethods)
                        }
                    }
            }
        }.sortedBy { it.normalizedPath }

        return TestInventory(entries, excludedAnnotatedKotlinFiles)
    }

    private fun walkTestFiles(root: Path): List<Path> =
        walkKotlinFiles(root).filter { path -> path.name.endsWith("Test.kt") }

    private fun walkKotlinFiles(root: Path): List<Path> =
        Files.walk(root).use { stream: Stream<Path> ->
            stream
                .filter { path -> path.isRegularFile() && path.name.endsWith(".kt") }
                .collect(Collectors.toList())
        }

    private fun testMethodCount(source: String): Int =
        testAnnotationRegex.findAll(source).count()

    private data class TestInventory(
        val entries: List<TestEntry>,
        val excludedAnnotatedKotlinFiles: List<ExcludedAnnotatedKotlinFile>
    ) {
        val campaignSuites: List<TestEntry> = entries.filter { entry ->
            entry.path.name.endsWith("TddTest.kt") && !entry.isInventoryFixture
        }

        fun report(): String {
            val lines = mutableListOf<String>()
            lines += "Existing test inventory:"
            entries
                .filterNot { it.isInventoryFixture }
                .groupBy { "${it.area}/${it.sourceSet}" }
                .toSortedMap()
                .forEach { (key, group) ->
                    lines += "  $key: files=${group.size}, tests=${group.sumOf { it.testMethods }}"
                }
            lines += "  total: files=${entries.count { !it.isInventoryFixture }}, tests=${entries.filterNot { it.isInventoryFixture }.sumOf { it.testMethods }}"
            lines += "TDD campaign suites: files=${campaignSuites.size}, tests=${campaignSuites.sumOf { it.testMethods }}"
            lines += "Excluded annotated Kotlin files (non-*Test.kt): files=${excludedAnnotatedKotlinFiles.size}, tests=${excludedAnnotatedKotlinFiles.sumOf { it.testMethods }}"
            return lines.joinToString(System.lineSeparator())
        }
    }

    private data class ExcludedAnnotatedKotlinFile(
        val path: Path,
        val testMethods: Int
    ) {
        val normalizedPath: String = repoRoot.relativize(path).toString().replace('\\', '/')
    }

    private data class TestEntry(
        val root: Path,
        val path: Path,
        val testMethods: Int
    ) {
        val normalizedPath: String = repoRoot.relativize(path).toString().replace('\\', '/')
        val sourceSet: String = root.parent.name
        val area: String = when {
            "/parser/" in normalizedPath -> "parser"
            "/source/" in normalizedPath -> "parser"
            "/semantic/workspace/" in normalizedPath -> "workspace"
            "/semantic/" in normalizedPath -> "semantic"
            "/interop/" in normalizedPath -> "interop"
            "/lsp/" in normalizedPath -> "lsp"
            "/integration/" in normalizedPath -> "integration"
            else -> "other"
        }
        val isInventoryFixture: Boolean = normalizedPath == "src/jvmTest/kotlin/testinventory/NewTestInventoryTddTest.kt"
    }

    private companion object {
        val repoRoot: Path = Path.of("").toAbsolutePath().normalize()
        val testAnnotationRegex = Regex("""(?m)^\s*@Test\b""")
        // Synced to live inventory + docs/test-strategy.md (TASK-231 WAVE31, 2026-07-11; baseline 209/2752, campaign 150/2237).
        // Do not lower these bars; live-tree drift above these values needs a docs refresh first.
        const val expectedBaselineFiles = 209
        const val expectedBaselineTestMethods = 2752
        const val expectedCampaignFiles = 150
        const val expectedCampaignTestMethods = 2237

        val expectedExcludedAnnotatedKotlinFiles = mapOf(
            "src/commonTest/kotlin/parser.common.kt" to 2,
            "src/jvmTest/kotlin/parser.jvm.kt" to 2
        )
    }
}
