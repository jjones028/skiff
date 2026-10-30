package skiff.project

import skiff.build.MainClassDetector
import skiff.json.JsonWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes `workspace.json` for a skiff project (`skf vscode`), the build-system-agnostic import of the Kotlin language
 * server behind the "Kotlin by JetBrains" VS Code extension (kotlin-lsp, `workspace-import/.../json/model.kt`): one
 * module with `src/`, `resources/` and `test/` roots, and one library per locked jar. Jar and JDK paths are absolute,
 * so the file is generated per machine and should not be committed. Compiler plugins and `jvm-target` are not passed
 * on (the server's `kotlinSettings` take a serialized compiler-arguments string that skiff does not produce).
 */
object VsCodeProject {
    const val FILE_NAME = "workspace.json"
    private const val BUILD_TASK = "skf: build"

    fun workspaceFile(manifest: Manifest): Path = manifest.dir.resolve(FILE_NAME)

    /** True once `skf vscode` has written the file, so later dependency changes should refresh it. */
    fun isGenerated(manifest: Manifest): Boolean = Files.isRegularFile(workspaceFile(manifest))

    /** Returns the file written. [testJars] may repeat [runtimeJars]; those are kept as compile dependencies. */
    fun generate(manifest: Manifest, runtimeJars: List<Path>, testJars: List<Path>): Path {
        val generated = IdeaProject.generatedSourceDirs(manifest)
        generated.forEach { Files.createDirectories(manifest.dir.resolve(it)) }
        val sourceRoots = buildList {
            if (Files.isDirectory(manifest.dir.resolve("src"))) add(sourceRoot("src", "java-source"))
            if (Files.isDirectory(manifest.dir.resolve("resources"))) add(sourceRoot("resources", "java-resource"))
            if (Files.isDirectory(manifest.dir.resolve("test"))) add(sourceRoot("test", "java-test"))
            generated.forEach { add(sourceRoot(it, "java-source", generated = true)) }
        }

        val runtime = runtimeJars.distinct()
        val testOnly = testJars.distinct().filter { it !in runtime }
        // Library names must be unique; two jars with the same file name from different directories get a counter.
        val names = HashMap<String, Int>()
        val named = (runtime.map { it to "compile" } + testOnly.map { it to "test" }).map { (jar, scope) ->
            val base = jar.fileName.toString().removeSuffix(".jar")
            val n = names.merge(base, 1, Int::plus)!!
            Triple(if (n == 1) base else "$base-$n", jar, scope)
        }

        val jdkName = "skiff-jdk-${Runtime.version().feature()}"
        val workspace = linkedMapOf<String, Any?>(
            "modules" to listOf(
                linkedMapOf(
                    "name" to manifest.name,
                    "dependencies" to listOf(
                        mapOf("type" to "moduleSource"),
                        mapOf("type" to "sdk", "name" to jdkName, "kind" to "JavaSDK"),
                    ) + named.map { (name, _, scope) -> mapOf("type" to "library", "name" to name, "scope" to scope) },
                    "contentRoots" to listOf(mapOf("path" to ".", "sourceRoots" to sourceRoots)),
                    "facets" to emptyList<Any>(),
                ),
            ),
            "libraries" to named.map { (name, jar, _) ->
                linkedMapOf("name" to name, "type" to null, "roots" to listOf(mapOf("path" to slashes(jar))), "excludedRoots" to emptyList<Any>())
            },
            "sdks" to listOf(
                linkedMapOf(
                    "name" to jdkName,
                    "type" to "JavaSDK",
                    "version" to "${Runtime.version().feature()}",
                    "homePath" to slashes(Path.of(System.getProperty("java.home"))),
                    "additionalData" to "",
                ),
            ),
            "kotlinSettings" to emptyList<Any>(),
        )
        val file = workspaceFile(manifest)
        Files.writeString(file, JsonWriter.write(workspace))
        writeLaunchConfig(manifest, runtime)
        writeBuildTask(manifest)
        return file
    }

    /**
     * Writes `.vscode/launch.json` once (never overwrites a user's file). The debugger refuses to start with "No JDK
     * configured for the project" when it cannot resolve the module SDK, so `javaExec` pins the JDK skiff itself runs on.
     * The model has no compiler output directory, so `classPaths` names `target/classes` and the locked jars, and the
     * `skf: build` task compiles first.
     */
    private fun writeLaunchConfig(manifest: Manifest, runtimeJars: List<Path>) {
        val launch = manifest.dir.resolve(".vscode/launch.json")
        val srcDir = manifest.dir.resolve("src")
        if (Files.exists(launch) || !Files.isDirectory(srcDir)) return
        val sources = Files.walk(srcDir).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() }
        val mainFile = MainClassDetector.findFile(srcDir, sources) ?: return
        val mainClass = MainClassDetector.detect(srcDir, sources) ?: return
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val config = linkedMapOf<String, Any?>(
            "version" to "0.2.0",
            "configurations" to listOf(
                linkedMapOf(
                    "type" to "intellij_jvm",
                    "request" to "launch",
                    "name" to manifest.name,
                    "mainClass" to mainClass,
                    // File facade classes such as MainKt do not exist until compiled, so name the declaring source.
                    "file" to slashes(mainFile),
                    "javaExec" to slashes(Path.of(System.getProperty("java.home")).resolve("bin").resolve(exe)),
                    "classPaths" to listOf(slashes(manifest.dir.resolve("target/classes"))) + runtimeJars.distinct().map(::slashes),
                    "preLaunchTask" to BUILD_TASK,
                ),
            ),
        )
        Files.createDirectories(launch.parent)
        Files.writeString(launch, JsonWriter.write(config))
    }

    /** Writes `.vscode/tasks.json` once, with the `skf build` task the launch configuration runs first. */
    private fun writeBuildTask(manifest: Manifest) {
        val tasks = manifest.dir.resolve(".vscode/tasks.json")
        if (Files.exists(tasks) || !Files.isDirectory(manifest.dir.resolve("src"))) return
        val config = linkedMapOf<String, Any?>(
            "version" to "2.0.0",
            "tasks" to listOf(
                linkedMapOf("label" to BUILD_TASK, "type" to "shell", "command" to "skf build", "problemMatcher" to emptyList<Any>()),
            ),
        )
        Files.createDirectories(tasks.parent)
        Files.writeString(tasks, JsonWriter.write(config))
    }

    private fun sourceRoot(path: String, type: String, generated: Boolean = false): Map<String, Any?> =
        linkedMapOf("path" to path, "type" to type, "generated" to generated)

    private fun slashes(path: Path): String = path.toString().replace('\\', '/')
}
