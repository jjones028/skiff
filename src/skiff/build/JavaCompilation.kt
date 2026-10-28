package skiff.build

import skiff.SkiffException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

/** Compiles Java sources that annotation processors generated, with the JDK's own compiler. */
object JavaCompilation {
    fun compile(sources: List<Path>, classpath: List<Path>, output: Path, jvmTarget: String) {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: throw SkiffException("compiling generated Java sources needs a JDK, but this Java runtime has no Java compiler")
        Files.createDirectories(output)
        val args = buildList {
            add("-proc:none")
            add("-nowarn")
            add("-d"); add(output.toString())
            add("-classpath"); add((listOf(output) + classpath).joinToString(File.pathSeparator))
            if ((jvmTarget.toIntOrNull() ?: Int.MAX_VALUE) <= JavaProcess.featureVersion) {
                add("--release"); add(jvmTarget)
            }
            sources.forEach { add(it.toString()) }
        }
        if (compiler.run(null, null, null, *args.toTypedArray()) != 0) {
            throw SkiffException("could not compile the generated Java sources due to the errors above")
        }
    }
}
