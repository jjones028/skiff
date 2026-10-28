package skiff.build

import skiff.SkiffException
import skiff.cli.Term
import skiff.project.KaptConfig
import skiff.project.KspConfig
import skiff.project.Manifest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * The annotation processing stages that run before the main compilation: KSP in its own JVM, and the
 * arguments that make the Kotlin compiler run kapt (which only generates stubs and sources, and compiles nothing).
 */
class AnnotationProcessing(private val manifest: Manifest, private val toolchain: Toolchain) {
    private val generated: Path = manifest.targetDir.resolve("generated")

    /** Scratch output of the compiler pass that runs kapt; kapt itself writes no classes there. */
    val kaptStageDir: Path = generated.resolve("kapt-stage")

    /** Runs the KSP processors in [processors] over `src/` and returns what they generated. */
    fun runKsp(config: KspConfig, classpath: List<Path>, processors: List<Path>): GeneratedSources {
        val out = generated.resolve("ksp")
        out.toFile().deleteRecursively()
        val kotlin = Files.createDirectories(out.resolve("kotlin"))
        val java = Files.createDirectories(out.resolve("java"))
        val classes = Files.createDirectories(out.resolve("classes"))
        val resources = Files.createDirectories(out.resolve("resources"))
        val caches = Files.createDirectories(out.resolve("caches"))
        val languageVersion = manifest.kotlinVersion.split('.').take(2).joinToString(".")

        Term.status("Processing", "annotations with KSP ${config.version}")
        val kspArgs = buildList {
            add("-module-name=${manifest.name}")
            add("-source-roots=${manifest.sourceDir}")
            add("-libraries=${classpath.joinToString(File.pathSeparator)}")
            add("-project-base-dir=${manifest.dir}")
            add("-output-base-dir=$out")
            add("-caches-dir=$caches")
            add("-kotlin-output-dir=$kotlin")
            add("-java-output-dir=$java")
            add("-class-output-dir=$classes")
            add("-resource-output-dir=$resources")
            add("-jvm-target=${manifest.jvmTarget}")
            add("-language-version=$languageVersion")
            add("-api-version=$languageVersion")
            if (config.options.isNotEmpty()) {
                add("-processor-options=" + config.options.entries.joinToString(File.pathSeparator) { "${it.key}=${it.value}" })
            }
            add(processors.joinToString(File.pathSeparator))
        }
        val jvmArgs = buildList {
            if (JavaProcess.featureVersion >= 24) add("--sun-misc-unsafe-memory-access=allow")
            add("-cp"); add(toolchain.kspClasspath(config.version).joinToString(File.pathSeparator))
            add(KSP_MAIN_CLASS)
            addAll(kspArgs)
        }
        val exitCode = JavaProcess.run(jvmArgs, generated.resolve("ksp.args"), manifest.dir)
        if (exitCode != 0) throw SkiffException("KSP failed due to the errors above")
        return GeneratedSources(sourcesIn(kotlin, ".kt"), sourcesIn(java, ".java"), listOf(classes, resources))
    }

    /** The directory where kapt processors write generated Kotlin (`kapt.kotlin.generated`). */
    val kaptKotlinDir: Path = generated.resolve("kapt").resolve("kotlin")

    /** Prepares kapt's output directories and returns the compiler arguments that enable it. */
    fun kaptArguments(config: KaptConfig, processors: List<Path>): List<String> {
        val out = generated.resolve("kapt")
        out.toFile().deleteRecursively()
        val sources = Files.createDirectories(out.resolve("sources"))
        val classes = Files.createDirectories(out.resolve("classes"))
        val stubs = Files.createDirectories(out.resolve("stubs"))
        val incremental = Files.createDirectories(out.resolve("incremental"))
        Files.createDirectories(kaptKotlinDir)
        val option = "plugin:$KAPT_PLUGIN_ID"
        return buildList {
            add("-Xplugin=${toolchain.kaptPluginJar(manifest.kotlinVersion)}")
            fun plugin(value: String) { add("-P"); add("$option:$value") }
            plugin("aptMode=stubsAndApt")
            plugin("sources=$sources")
            plugin("classes=$classes")
            plugin("stubs=$stubs")
            plugin("incrementalData=$incremental")
            plugin("correctErrorTypes=${config.correctErrorTypes}")
            processors.forEach { plugin("apclasspath=$it") }
            plugin("apoptions=" + encodeOptions(mapOf("kapt.kotlin.generated" to kaptKotlinDir.toString()) + config.options))
        }
    }

    /** kapt takes all processor options as one Base64 string: the count, then each key and value as UTF strings. */
    fun encodeOptions(options: Map<String, String>): String {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { out ->
            out.writeInt(options.size)
            for ((key, value) in options) {
                out.writeUTF(key)
                out.writeUTF(value)
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    /** What kapt generated, after the compiler pass that ran it. */
    fun kaptOutput(): GeneratedSources {
        val out = generated.resolve("kapt")
        return GeneratedSources(sourcesIn(kaptKotlinDir, ".kt"), sourcesIn(out.resolve("sources"), ".java"), listOf(out.resolve("classes")))
    }

    private fun sourcesIn(dir: Path, extension: String): List<Path> =
        if (!Files.isDirectory(dir)) emptyList()
        else Files.walk(dir).use { paths -> paths.filter { Files.isRegularFile(it) && it.toString().endsWith(extension) }.sorted().toList() }

    private companion object {
        const val KSP_MAIN_CLASS = "com.google.devtools.ksp.cmdline.KSPJvmMain"
        const val KAPT_PLUGIN_ID = "org.jetbrains.kotlin.kapt3"
    }
}
