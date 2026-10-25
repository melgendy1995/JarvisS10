package com.jarvis.core.brain

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.streams.toList
import org.junit.Assert.fail
import org.junit.Test

/**
 * The desktop JVM accepts regex flags and constructs Android's ART (ICU) rejects:
 * UNICODE_CHARACTER_CLASS threw Unsupported flags: 256 at runtime after install.
 * JVM unit tests cannot catch that, so this scans the shipped Kotlin sources for
 * the JVM-only spellings.
 */
class AndroidRegexCompatTest {
    private val forbidden = listOf("UNICODE_CHARACTER_CLASS", "(?U)", "CANON_EQ")

    @Test
    fun shippedSourcesUseOnlyAndroidSupportedRegex() {
        for (dir in moduleSources()) {
            for (line in ktSources(dir)) {
                for (token in forbidden) {
                    if (token in line) fail("$dir: forbidden regex syntax \"$token\":\n$line")
                }
            }
        }
    }

    /** The core and app module sources, resolved whether cwd is repo root, a module dir, or deeper. */
    private fun moduleSources(): List<Path> {
        var dir: Path? = Paths.get("").toAbsolutePath()
        repeat(5) {
            dir ?: return@repeat
            (listOf(dir!!.resolve("core/src/main"), dir!!.resolve("app/src/main")))
                .filter { it.exists() }
                .takeIf { list -> list.size == 2 }
                ?.let { return it }
            dir = dir!!.parent
        }
        fail("could not locate the core and app module sources from ${Paths.get("").toAbsolutePath()}")
        return emptyList()
    }

    private fun ktSources(dir: Path): List<String> {
        val walk = Files.walk(dir)
        return walk.use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.toList()
                .flatMap { Files.readAllLines(it) }
        }
    }
}
