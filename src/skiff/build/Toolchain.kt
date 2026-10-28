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
    fun compilerClasspath(kotlinVersion: String): List<Path> {
        val cacheFile = SkiffHome.toolchains.resolve("kotlin-$kotlinVersion.classpath")
        if (Files.isRegularFile(cacheFile)) {
            val cached = Files.readAllLines(cacheFile).filter { it.isNotBlank() }.map { Path.of(it) }
            if (cached.isNotEmpty() && cached.all { Files.isRegularFile(it) }) return cached
        }
        Term.status("Installing", "Kotlin compiler $kotlinVersion")
        val resolved = DependencyResolver(store).resolve(mapOf(COMPILER to kotlinVersion))
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
        const val COMPILER_MAIN_CLASS = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
    }
}
