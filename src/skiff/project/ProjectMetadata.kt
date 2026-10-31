package skiff.project

import skiff.build.MainClassDetector
import skiff.maven.ModuleId
import java.nio.file.Files
import java.nio.file.Path

/**
 * The machine-readable description of a project printed by `skf metadata` (like `cargo metadata`), for tools such as
 * IDE plugins that should not parse `Skiff.toml` and `Skiff.lock` themselves. The result is a tree of maps, lists and
 * strings for `JsonWriter`; keys are kebab-case and all paths are absolute with forward slashes. Fields are only ever
 * added within a [FORMAT_VERSION], so consumers must ignore keys they do not know.
 */
object ProjectMetadata {
    const val FORMAT_VERSION = 1

    /**
     * @param lock the up-to-date lockfile, with [jars] the verified jar of each package that has one
     * @param pluginJars the jars of every enabled compiler plugin (named and by coordinates)
     * @param kaptClasspath the jars of the kapt processors and their dependencies
     * @param kspClasspath the jars of the KSP processors and their dependencies
     */
    fun build(
        manifest: Manifest,
        lock: Lockfile,
        jars: Map<ModuleId, Path>,
        pluginJars: List<Path>,
        kaptClasspath: List<Path>,
        kspClasspath: List<Path>,
    ): Map<String, Any?> = linkedMapOf(
        "format-version" to FORMAT_VERSION,
        "package" to linkedMapOf(
            "name" to manifest.name,
            "version" to manifest.version,
            "main-class" to mainClass(manifest),
            "directory" to slashes(manifest.dir),
            "manifest" to slashes(manifest.file),
            "lockfile" to slashes(manifest.lockFile),
        ),
        "kotlin" to linkedMapOf(
            "version" to manifest.kotlinVersion,
            "jvm-target" to manifest.jvmTarget,
            "plugins" to manifest.plugins.map { linkedMapOf("id" to it.plugin.id, "annotations" to it.annotations) } +
                manifest.externalPlugins.map { linkedMapOf("id" to it.module.toString(), "annotations" to emptyList<String>()) },
            "plugin-classpath" to pluginJars.map(::slashes),
            "plugin-options" to manifest.plugins.flatMap { it.options() } + manifest.externalPlugins.flatMap { it.options },
        ),
        "jdk" to linkedMapOf(
            "home" to slashes(Path.of(System.getProperty("java.home"))),
            "version" to "${Runtime.version().feature()}",
        ),
        "roots" to roots(manifest),
        "output" to linkedMapOf(
            "classes" to slashes(manifest.targetDir.resolve("classes")),
            "test-classes" to slashes(manifest.targetDir.resolve("test-classes")),
            "jar" to slashes(manifest.targetDir.resolve("${manifest.name}-${manifest.version}.jar")),
        ),
        "dependencies" to dependencies(manifest, lock, jars),
        "annotation-processing" to linkedMapOf(
            "kapt" to manifest.kapt?.let {
                linkedMapOf("classpath" to kaptClasspath.map(::slashes), "options" to it.options, "correct-error-types" to it.correctErrorTypes)
            },
            "ksp" to manifest.ksp?.let {
                linkedMapOf("classpath" to kspClasspath.map(::slashes), "options" to it.options, "version" to it.version)
            },
        ),
    )

    /** Source roots by kind: `source`, `resource`, `test-source` and `generated-source` (kapt and KSP output). */
    private fun roots(manifest: Manifest): List<Map<String, Any?>> {
        fun root(dir: Path, kind: String) = linkedMapOf("path" to slashes(dir), "kind" to kind, "exists" to Files.isDirectory(dir))
        return listOf(root(manifest.sourceDir, "source"), root(manifest.resourceDir, "resource"), root(manifest.testDir, "test-source")) +
            IdeaProject.generatedSourceDirs(manifest).map { root(manifest.dir.resolve(it), "generated-source") }
    }

    /**
     * Every locked package with its scope: `compile` when the program itself needs it, `test` when only the tests do.
     * `direct` marks the ones named in `Skiff.toml` (the Kotlin standard library counts, as it is implicit).
     */
    private fun dependencies(manifest: Manifest, lock: Lockfile, jars: Map<ModuleId, Path>): List<Map<String, Any?>> {
        val compile = lock.reachable(manifest.allDependencies().keys)
        val direct = manifest.allDependencies().keys + manifest.devDependencies.keys
        return lock.packages.map { pkg ->
            linkedMapOf(
                "id" to pkg.module.toString(),
                "version" to pkg.version,
                "jar" to jars[pkg.module]?.let(::slashes),
                "scope" to if (pkg.module in compile) "compile" else "test",
                "direct" to (pkg.module in direct),
                "dependencies" to pkg.dependencies.map { it.toString() },
            )
        }
    }

    private fun mainClass(manifest: Manifest): String? {
        manifest.main?.let { return it }
        if (!Files.isDirectory(manifest.sourceDir)) return null
        val sources = Files.walk(manifest.sourceDir).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() }
        return MainClassDetector.detect(manifest.sourceDir, sources)
    }

    private fun slashes(path: Path): String = path.toString().replace('\\', '/')
}
