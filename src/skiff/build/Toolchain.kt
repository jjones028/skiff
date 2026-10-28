package skiff.build

import skiff.SkiffHome
import skiff.cli.Term
import skiff.maven.ArtifactStore
import skiff.maven.DependencyResolver
import skiff.maven.ModuleId
import skiff.project.KotlinPlugin
import java.nio.file.Files
import java.nio.file.Path

/** Downloads the Kotlin compiler for a Kotlin version from Maven Central, so no local `kotlinc` is needed. */
class Toolchain(private val store: ArtifactStore) {
    fun compilerClasspath(kotlinVersion: String): List<Path> =
        cachedClasspath("kotlin-$kotlinVersion", COMPILER, kotlinVersion, "Kotlin compiler $kotlinVersion")

    /** The classpath of the KSP command line tool, which runs the KSP processors in their own JVM. */
    fun kspClasspath(kspVersion: String): List<Path> =
        cachedClasspath("ksp-$kspVersion", KSP_RUNTIME, kspVersion, "KSP $kspVersion")

    /** The compiler plugin that runs kapt, published with the same version as the compiler. */
    fun kaptPluginJar(kotlinVersion: String): Path {
        val path = KAPT_PLUGIN.filePath(kotlinVersion, "jar")
        return store.getAll(listOf(path)).getValue(path)
    }

    /** Resolves [module] with its dependencies once and remembers the jars in `~/.skiff/toolchains/<cacheName>.classpath`. */
    private fun cachedClasspath(cacheName: String, module: ModuleId, version: String, description: String): List<Path> {
        val cacheFile = SkiffHome.toolchains.resolve("$cacheName.classpath")
        if (Files.isRegularFile(cacheFile)) {
            val cached = Files.readAllLines(cacheFile).filter { it.isNotBlank() }.map { Path.of(it) }
            if (cached.isNotEmpty() && cached.all { Files.isRegularFile(it) }) return cached
        }
        Term.status("Installing", description)
        val resolved = DependencyResolver(store).resolve(mapOf(module to version))
        val jars = store.getAll(resolved.filter { it.hasJar }.map { it.jarPath }).values.toList()
        Files.createDirectories(cacheFile.parent)
        Files.write(cacheFile, jars.map { it.toString() })
        return jars
    }

    /** The jars of the enabled compiler [plugins] for [kotlinVersion], downloaded into the shared cache on first use. */
    fun pluginJars(kotlinVersion: String, plugins: List<KotlinPlugin>): List<Path> {
        if (plugins.isEmpty()) return emptyList()
        val paths = plugins.map { it.module.filePath(kotlinVersion, "jar") }
        return store.getAll(paths).let { jars -> paths.map(jars::getValue) }
    }

    companion object {
        val COMPILER = ModuleId("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
        private val KSP_RUNTIME = ModuleId("com.google.devtools.ksp", "symbol-processing-aa-embeddable")
        private val KAPT_PLUGIN = ModuleId("org.jetbrains.kotlin", "kotlin-annotation-processing-embeddable")
        const val COMPILER_MAIN_CLASS = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
    }
}
