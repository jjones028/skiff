package skiff.build

import skiff.SkiffException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JavaCompilationTest {
    private val dir: Path = Files.createTempDirectory("skiff-javac-test")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun source(name: String, text: String): Path = dir.resolve("src/$name.java").also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    @Test
    fun compilesIntoTheOutputDirectory() {
        val out = dir.resolve("out")
        JavaCompilation.compile(listOf(source("Hello", "public class Hello { public String hi() { return \"hi\"; } }")), emptyList(), out, "21")
        assertTrue(Files.isRegularFile(out.resolve("Hello.class")))
    }

    @Test
    fun seesClassesAlreadyInTheOutputDirectory() {
        val out = dir.resolve("out")
        JavaCompilation.compile(listOf(source("A", "public class A {}")), emptyList(), out, "21")
        // B depends on A, which is only available as a class file in the output directory.
        JavaCompilation.compile(listOf(source("B", "public class B extends A {}")), emptyList(), out, "21")
        assertTrue(Files.isRegularFile(out.resolve("B.class")))
    }

    @Test
    fun targetsTheRequestedJavaRelease() {
        val out = dir.resolve("out")
        JavaCompilation.compile(listOf(source("Hello", "public class Hello {}")), emptyList(), out, "17")
        val bytes = Files.readAllBytes(out.resolve("Hello.class"))
        assertEquals(61, ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff), "class file major version of Java 17")
    }

    @Test
    fun doesNotRunAnnotationProcessors() {
        val out = dir.resolve("out")
        // A processor on the classpath would be picked up by javac's service loading without -proc:none.
        JavaCompilation.compile(listOf(source("Plain", "public class Plain {}")), listOf(dir), out, "21")
        assertEquals(listOf("Plain.class"), Files.list(out).use { files -> files.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun reportsCompileErrors() {
        val error = assertFailsWith<SkiffException> {
            JavaCompilation.compile(listOf(source("Broken", "public class Broken { int x = \"no\"; }")), emptyList(), dir.resolve("out"), "21")
        }
        assertTrue("generated Java sources" in error.message!!, error.message)
    }
}
