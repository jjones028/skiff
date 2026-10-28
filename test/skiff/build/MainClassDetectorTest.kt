package skiff.build

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MainClassDetectorTest {
    private val src: Path = Files.createTempDirectory("skiff-main-test")

    @AfterTest
    fun cleanUp() {
        src.toFile().deleteRecursively()
    }

    private fun source(path: String, text: String): Path {
        val file = src.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return file
    }

    private fun detect(vararg files: Path) = MainClassDetector.detect(src, files.toList())

    @Test
    fun usesThePackageAndFileNameOfTheMainFile() {
        val main = source("com/example/Main.kt", "package com.example\n\nfun main() {}\n")
        assertEquals("com.example.MainKt", detect(main))
    }

    @Test
    fun handlesTheDefaultPackage() {
        assertEquals("AppKt", detect(source("app.kt", "fun main(args: Array<String>) {}")))
    }

    @Test
    fun honoursFileJvmName() {
        val main = source("Main.kt", "@file:JvmName(\"Launcher\")\npackage demo\n\nfun main() {}\n")
        assertEquals("demo.Launcher", detect(main))
    }

    @Test
    fun prefersMainKtThenTheShallowestFile() {
        val deep = source("a/b/Deep.kt", "package a.b\nfun main() {}")
        val shallow = source("Shallow.kt", "fun main() {}")
        val named = source("x/y/Main.kt", "package x.y\nfun main() {}")
        assertEquals("ShallowKt", detect(deep, shallow))
        assertEquals("x.y.MainKt", detect(deep, shallow, named))
    }

    @Test
    fun ignoresNonMainFunctionsAndIndentedOrCommentedOnes() {
        val other = source("Util.kt", "package u\nfun mainly() {}\nclass A { fun main() {} }\n")
        assertNull(detect(other))
    }

    @Test
    fun returnsNullWhenThereAreNoSources() {
        assertNull(detect())
    }
}
