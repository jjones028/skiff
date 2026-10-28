package skiff.build

import skiff.Hashing
import skiff.SkiffException
import skiff.cli.Term
import skiff.project.Manifest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Compiles `src/` with the Kotlin compiler (in a child JVM), copies `resources/`, and writes
 * `target/<name>-<version>.jar`. Skips everything when nothing that affects the output has changed.
 */
class Builder(private val manifest: Manifest, private val toolchain: Toolchain) {
    fun build(
        classpath: List<Path>,
        fatJar: Boolean = false,
        pluginClasspath: List<Path> = emptyList(),
        processors: Processors = Processors(),
    ): BuildResult {
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
        val stamp = stamp(sources + resources, classpath, mainClass, pluginClasspath, processors)
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
        val generated = process(compiler, sources, classpath, processors)
        compile(compiler, sources + generated.kotlin + generated.java, classpath, classes, "kotlinc", pluginClasspath = pluginClasspath)
        generated.outputDirs.forEach { copyInto(classes, it) }
        if (generated.java.isNotEmpty()) JavaCompilation.compile(generated.java, classpath, classes, manifest.jvmTarget)
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

    /** Runs KSP and kapt, in that order, and returns everything they generated. */
    private fun process(compiler: List<Path>, sources: List<Path>, classpath: List<Path>, processors: Processors): GeneratedSources {
        if (manifest.kapt == null && manifest.ksp == null) return GeneratedSources()
        val processing = AnnotationProcessing(manifest, toolchain)
        var generated = GeneratedSources()
        manifest.ksp?.let { generated += processing.runKsp(it, classpath, processors.ksp) }
        manifest.kapt?.let { kapt ->
            Term.status("Processing", "annotations with kapt")
            val stage = processing.kaptStageDir.also { it.toFile().deleteRecursively(); Files.createDirectories(it) }
            val kaptArgs = processing.kaptArguments(kapt, processors.kapt)
            compile(
                compiler, sources + generated.kotlin + generated.java, classpath, stage, "kapt", "${manifest.name}-kapt",
                extraArgs = kaptArgs, applyPlugins = false,
            )
            generated += processing.kaptOutput()
        }
        return generated
    }

    /** Copies the files under [from] (resources and class files written by processors) into [classes]. */
    private fun copyInto(classes: Path, from: Path) {
        for (file in filesUnder(from)) {
            val destination = classes.resolve(from.relativize(file).toString())
            Files.createDirectories(destination.parent)
            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Compiles `test/` into `target/test-classes` against [mainBuild] and [classpath] (which includes the
     * dev-dependencies), and returns that directory. Needs the main build to be current.
     */
    fun buildTests(mainBuild: BuildResult, classpath: List<Path>, pluginClasspath: List<Path> = emptyList()): Path {
        val sources = filesUnder(manifest.testDir).filter { it.toString().endsWith(".kt") }
        if (sources.isEmpty()) throw SkiffException("no Kotlin sources found in ${manifest.dir.relativize(manifest.testDir)}")
        val classes = manifest.targetDir.resolve("test-classes")
        val fullClasspath = listOf(mainBuild.classesDir) + classpath
        val mainStamp = manifest.targetDir.resolve(".build-stamp").let { if (Files.isRegularFile(it)) Files.readString(it) else "" }
        val stampFile = manifest.targetDir.resolve(".test-stamp")
        val stamp = stamp(sources, fullClasspath, mainBuild.mainClass, pluginClasspath) + mainStamp
        if (Files.isDirectory(classes) && Files.isRegularFile(stampFile) && Files.readString(stampFile) == stamp) return classes

        val compiler = toolchain.compilerClasspath(manifest.kotlinVersion)
        Term.status("Compiling", "${manifest.name} v${manifest.version} tests (${manifest.dir})")
        Files.deleteIfExists(stampFile)
        classes.toFile().deleteRecursively()
        Files.createDirectories(classes)
        compile(compiler, sources, fullClasspath, classes, "kotlinc-test", "${manifest.name}-test", pluginClasspath)
        Files.writeString(stampFile, stamp)
        return classes
    }

    private fun compile(
        compiler: List<Path>,
        sources: List<Path>,
        classpath: List<Path>,
        classes: Path,
        argFileName: String,
        moduleName: String = manifest.name,
        pluginClasspath: List<Path> = emptyList(),
        extraArgs: List<String> = emptyList(),
        applyPlugins: Boolean = true,
    ) {
        val target = manifest.targetDir
        val kotlincArgs = buildList {
            add("-d"); add(classes.toString())
            add("-classpath"); add(classpath.joinToString(File.pathSeparator))
            add("-jvm-target"); add(manifest.jvmTarget)
            // Compile against the target JDK's API, not the newer JDK skiff may be running on.
            if ((manifest.jvmTarget.toIntOrNull() ?: Int.MAX_VALUE) <= JavaProcess.featureVersion) {
                add("-Xjdk-release=${manifest.jvmTarget}")
            }
            add("-module-name"); add(moduleName)
            add("-no-stdlib")
            add("-no-reflect")
            if (applyPlugins) {
                toolchain.pluginJars(manifest.kotlinVersion, manifest.plugins.map { it.plugin }).forEach { add("-Xplugin=$it") }
                pluginClasspath.forEach { add("-Xplugin=$it") }
                (manifest.plugins.flatMap { it.options() } + manifest.externalPlugins.flatMap { it.options }).forEach { add("-P"); add(it) }
            }
            addAll(extraArgs)
            sources.forEach { add(it.toString()) }
        }
        val kotlincArgFile = target.resolve("$argFileName.args")
        JavaProcess.writeArgFile(kotlincArgFile, kotlincArgs)

        val jvmArgs = buildList {
            // The compiler still uses sun.misc.Unsafe; silence the JDK 24+ deprecation warnings.
            if (JavaProcess.featureVersion >= 24) add("--sun-misc-unsafe-memory-access=allow")
            add("-cp"); add(compiler.joinToString(File.pathSeparator))
            add(Toolchain.COMPILER_MAIN_CLASS)
            add("@$kotlincArgFile")
        }
        val exitCode = JavaProcess.run(jvmArgs, target.resolve("$argFileName.jvm.args"), manifest.dir)
        if (exitCode != 0) throw SkiffException("could not compile `$moduleName` due to the errors above")
    }

    private fun stamp(
        inputs: List<Path>,
        classpath: List<Path>,
        mainClass: String?,
        pluginClasspath: List<Path>,
        processors: Processors = Processors(),
    ): String = Hashing.sha256(buildString {
        appendLine("kotlin=${manifest.kotlinVersion} jvm-target=${manifest.jvmTarget} main=$mainClass")
        manifest.plugins.forEach { appendLine("plugin=${it.plugin.id} ${it.annotations}") }
        for (file in inputs) {
            appendLine("${manifest.dir.relativize(file).invariantSeparatorsPathString} ${Files.size(file)} ${Files.getLastModifiedTime(file).toMillis()}")
        }
        classpath.forEach { appendLine(it.toString()) }
        pluginClasspath.forEach { appendLine("plugin-jar=$it") }
        manifest.externalPlugins.forEach { appendLine("plugin-options=${it.options}") }
        manifest.kapt?.let { appendLine("kapt=${it.options} ${it.correctErrorTypes}") }
        manifest.ksp?.let { appendLine("ksp=${it.version} ${it.options}") }
        processors.kapt.forEach { appendLine("kapt-jar=$it") }
        processors.ksp.forEach { appendLine("ksp-jar=$it") }
    })

    private fun filesUnder(dir: Path): List<Path> =
        if (!Files.isDirectory(dir)) emptyList()
        else Files.walk(dir).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().toList() }
}
