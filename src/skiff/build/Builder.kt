package skiff.build

import skiff.Hashing
import skiff.SkiffException
import skiff.cli.Term
import skiff.project.Manifest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Compiles `src/` with the Kotlin compiler (in a child JVM), copies `resources/`, and writes
 * `target/<name>-<version>.jar`. Skips everything when nothing that affects the output has changed.
 */
class Builder(private val manifest: Manifest, private val toolchain: Toolchain) {
    fun build(classpath: List<Path>, fatJar: Boolean = false): BuildResult {
        val sources = filesUnder(manifest.sourceDir).filter { it.toString().endsWith(".kt") }
        if (sources.isEmpty()) throw SkiffException("no Kotlin sources found in ${manifest.dir.relativize(manifest.sourceDir)}")
        val resources = filesUnder(manifest.resourceDir)
        val target = manifest.targetDir
        val classes = target.resolve("classes")
        val suffix = if (fatJar) "-all" else ""
        val jar = target.resolve("${manifest.name}-${manifest.version}$suffix.jar")
        val mainClass = manifest.main ?: MainClassDetector.detect(manifest.sourceDir, sources)
        val label = "${manifest.name} v${manifest.version} (${manifest.dir})"

        val stampFile = target.resolve(".build-stamp$suffix")
        val stamp = stamp(sources + resources, classpath, mainClass)
        if (Files.isRegularFile(jar) && Files.isRegularFile(stampFile) && Files.readString(stampFile) == stamp) {
            Term.status("Fresh", label)
            return BuildResult(classes, jar, mainClass)
        }

        val compiler = toolchain.compilerClasspath(manifest.kotlinVersion)
        Term.status("Compiling", label)
        val started = System.nanoTime()
        Files.deleteIfExists(stampFile)
        classes.toFile().deleteRecursively()
        Files.createDirectories(classes)
        compile(compiler, sources, classpath, classes)
        for (resource in resources) {
            val destination = classes.resolve(manifest.resourceDir.relativize(resource).toString())
            Files.createDirectories(destination.parent)
            Files.copy(resource, destination)
        }
        JarWriter.write(classes, jar, mainClass, if (fatJar) classpath else emptyList())
        Files.writeString(stampFile, stamp)
        Term.status("Finished", "${manifest.dir.relativize(jar)} in ${"%.1f".format((System.nanoTime() - started) / 1e9)}s")
        return BuildResult(classes, jar, mainClass)
    }

    private fun compile(compiler: List<Path>, sources: List<Path>, classpath: List<Path>, classes: Path) {
        val target = manifest.targetDir
        val kotlincArgs = buildList {
            add("-d"); add(classes.toString())
            add("-classpath"); add(classpath.joinToString(File.pathSeparator))
            add("-jvm-target"); add(manifest.jvmTarget)
            // Compile against the target JDK's API, not the newer JDK skiff may be running on.
            if ((manifest.jvmTarget.toIntOrNull() ?: Int.MAX_VALUE) <= JavaProcess.featureVersion) {
                add("-Xjdk-release=${manifest.jvmTarget}")
            }
            add("-module-name"); add(manifest.name)
            add("-no-stdlib")
            add("-no-reflect")
            sources.forEach { add(it.toString()) }
        }
        val kotlincArgFile = target.resolve("kotlinc.args")
        JavaProcess.writeArgFile(kotlincArgFile, kotlincArgs)

        val jvmArgs = buildList {
            // The compiler still uses sun.misc.Unsafe; silence the JDK 24+ deprecation warnings.
            if (JavaProcess.featureVersion >= 24) add("--sun-misc-unsafe-memory-access=allow")
            add("-cp"); add(compiler.joinToString(File.pathSeparator))
            add(Toolchain.COMPILER_MAIN_CLASS)
            add("@$kotlincArgFile")
        }
        val exitCode = JavaProcess.run(jvmArgs, target.resolve("kotlinc.jvm.args"), manifest.dir)
        if (exitCode != 0) throw SkiffException("could not compile `${manifest.name}` due to the errors above")
    }

    private fun stamp(inputs: List<Path>, classpath: List<Path>, mainClass: String?): String = Hashing.sha256(buildString {
        appendLine("kotlin=${manifest.kotlinVersion} jvm-target=${manifest.jvmTarget} main=$mainClass")
        for (file in inputs) {
            appendLine("${manifest.dir.relativize(file).invariantSeparatorsPathString} ${Files.size(file)} ${Files.getLastModifiedTime(file).toMillis()}")
        }
        classpath.forEach { appendLine(it.toString()) }
    })

    private fun filesUnder(dir: Path): List<Path> =
        if (!Files.isDirectory(dir)) emptyList()
        else Files.walk(dir).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().toList() }
}
