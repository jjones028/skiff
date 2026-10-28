package skiff.cli

import skiff.BuildInfo
import skiff.SkiffException
import skiff.build.Builder
import skiff.build.Runner
import skiff.build.TestRunner
import skiff.build.Toolchain
import skiff.maven.ArtifactStore
import skiff.maven.MavenMetadata
import skiff.maven.ModuleId
import skiff.maven.Repository
import skiff.project.IdeaProject
import skiff.project.Installer
import skiff.project.Lockfile
import skiff.project.Manifest
import skiff.project.ManifestEditor
import skiff.project.ProjectTemplate
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Parses the command line and runs a command. Returns the process exit code. */
class Cli {
    private var offline = false
    private var locked = false
    private var fat = false

    fun run(args: List<String>): Int = try {
        dispatch(args)
    } catch (e: SkiffException) {
        Term.error(e.message ?: "failed")
        1
    }

    private fun dispatch(args: List<String>): Int {
        val separator = args.indexOf("--")
        val own = if (separator >= 0) args.subList(0, separator) else args
        val passthrough = if (separator >= 0) args.subList(separator + 1, args.size) else emptyList()
        val flags = own.filter { it.startsWith("-") }
        val words = own.filterNot { it.startsWith("-") }

        for (flag in flags) {
            when (flag) {
                "--offline" -> offline = true
                "--locked" -> locked = true
                "--fat" -> fat = true
                "-h", "--help" -> return help()
                "-V", "--version" -> return version()
                else -> throw SkiffException("unknown option '$flag'\n\nRun `skf help` for usage.")
            }
        }
        val command = words.firstOrNull() ?: return help()
        val params = words.drop(1)
        return when (command) {
            "new" -> newProject(params)
            "init" -> init()
            "add" -> add(params)
            "remove", "rm" -> remove(params)
            "install", "i" -> install(update = false)
            "update" -> install(update = true)
            "build", "b" -> build()
            "run", "r" -> runProject(params + passthrough)
            "test", "t" -> test(passthrough)
            "idea" -> idea()
            "tree" -> tree()
            "classpath" -> classpath()
            "clean" -> clean()
            "help" -> help()
            "version" -> version()
            else -> throw SkiffException("no such command: `$command`\n\nRun `skf help` for a list of commands.")
        }
    }

    private fun newProject(params: List<String>): Int {
        val name = params.singleOrNull() ?: throw SkiffException("usage: skf new <name>")
        val dir = Path.of(name).toAbsolutePath()
        if (Files.exists(dir)) throw SkiffException("destination `$dir` already exists")
        ProjectTemplate.create(dir, dir.fileName.toString())
        Term.status("Created", "application `${dir.fileName}` package")
        return 0
    }

    private fun init(): Int {
        val dir = Path.of("").toAbsolutePath()
        if (Files.exists(dir.resolve(Manifest.FILE_NAME))) throw SkiffException("`${Manifest.FILE_NAME}` already exists in $dir")
        val name = dir.fileName.toString().replace(Regex("[^A-Za-z0-9_-]"), "-")
        ProjectTemplate.create(dir, name)
        Term.status("Created", "application `$name` package")
        return 0
    }

    private fun add(params: List<String>): Int {
        if (params.isEmpty()) throw SkiffException("usage: skf add <group:artifact[@version]>...")
        var manifest = project()
        val store = store(manifest)
        var text = Files.readString(manifest.file)
        for (spec in params) {
            val (module, requested) = parseSpec(spec)
            val version = requested ?: MavenMetadata.latest(store, module)
            store.get(module.filePath(version, "pom")) ?: throw store.notFound("$module:$version")
            text = ManifestEditor.setDependency(text, module, version)
            Term.status("Adding", "$module v$version to dependencies")
        }
        Files.writeString(manifest.file, text)
        manifest = Manifest.load(manifest.dir)
        Installer(manifest, store).install(locked = locked)
        return 0
    }

    private fun remove(params: List<String>): Int {
        if (params.isEmpty()) throw SkiffException("usage: skf remove <group:artifact>...")
        var manifest = project()
        var text = Files.readString(manifest.file)
        for (spec in params) {
            val module = parseSpec(spec).first
            text = ManifestEditor.removeDependency(text, module)
                ?: throw SkiffException("`$module` is not a dependency of `${manifest.name}`")
            Term.status("Removing", "$module from dependencies")
        }
        Files.writeString(manifest.file, text)
        manifest = Manifest.load(manifest.dir)
        Installer(manifest, store(manifest)).install(locked = locked)
        return 0
    }

    private fun install(update: Boolean): Int {
        val manifest = project()
        val classpath = Installer(manifest, store(manifest)).install(update = update, locked = locked)
        Term.status("Installed", "${classpath.size} jars")
        return 0
    }

    private fun build(): Int {
        val manifest = project()
        val store = store(manifest)
        val classpath = Installer(manifest, store).install(locked = locked)
        Builder(manifest, Toolchain(store)).build(classpath, fatJar = fat)
        return 0
    }

    private fun runProject(args: List<String>): Int {
        val manifest = project()
        val store = store(manifest)
        val classpath = Installer(manifest, store).install(locked = locked)
        val result = Builder(manifest, Toolchain(store)).build(classpath)
        return Runner.run(manifest, result, classpath, args)
    }

    private fun test(args: List<String>): Int {
        val manifest = project()
        if (!manifest.hasTests) throw SkiffException("no Kotlin sources found in ${manifest.dir.relativize(manifest.testDir)}")
        val store = store(manifest)
        val installer = Installer(manifest, store)
        val builder = Builder(manifest, Toolchain(store))
        val mainBuild = builder.build(installer.install(locked = locked))
        val classpath = installer.install(locked = locked, tests = true)
        val testClasses = builder.buildTests(mainBuild, classpath)
        return TestRunner.run(manifest, mainBuild, testClasses, classpath, args)
    }

    private fun idea(): Int {
        val manifest = project()
        val installer = Installer(manifest, store(manifest))
        val runtimeJars = installer.install(locked = locked)
        val testJars = installer.install(locked = locked, tests = true)
        val written = IdeaProject.generate(manifest, runtimeJars, testJars)
        Term.status("Generated", written.joinToString(", ") { manifest.dir.relativize(it).toString() })
        Term.status("Note", "open the project in IntelliJ IDEA and pick a JDK; these files are machine-specific, do not commit them")
        return 0
    }

    private fun tree(): Int {
        val manifest = project()
        Installer(manifest, store(manifest)).install(locked = locked)
        val packages = Lockfile.read(manifest.lockFile)!!.packages.associateBy { it.module }
        val expanded = HashSet<ModuleId>()
        // Windows consoles often use a legacy code page that has no box-drawing characters.
        val unicode = System.getProperty("stdout.encoding", "UTF-8").equals("UTF-8", ignoreCase = true)
        val (branch, lastBranch, pipe) = if (unicode) Triple("├── ", "└── ", "│   ") else Triple("|-- ", "`-- ", "|   ")
        println("${manifest.name} v${manifest.version}")
        fun printNode(module: ModuleId, prefix: String, last: Boolean) {
            val pkg = packages[module] ?: return
            val repeated = !expanded.add(module) && pkg.dependencies.isNotEmpty()
            println(prefix + (if (last) lastBranch else branch) + "$module v${pkg.version}" + if (repeated) " (*)" else "")
            if (repeated) return
            val children = pkg.dependencies.filter { it in packages }
            children.forEachIndexed { i, child -> printNode(child, prefix + if (last) "    " else pipe, i == children.lastIndex) }
        }
        val roots = manifest.allDependencies().keys.sorted()
        roots.forEachIndexed { i, root -> printNode(root, "", i == roots.lastIndex) }
        return 0
    }

    private fun classpath(): Int {
        val manifest = project()
        println(Installer(manifest, store(manifest)).install(locked = locked).joinToString(File.pathSeparator))
        return 0
    }

    private fun clean(): Int {
        val manifest = project()
        if (manifest.targetDir.toFile().deleteRecursively()) Term.status("Removed", manifest.dir.relativize(manifest.targetDir).toString())
        return 0
    }

    private fun version(): Int {
        println("skiff ${BuildInfo.VERSION}")
        return 0
    }

    private fun help(): Int {
        println(
            """
            Skiff ${BuildInfo.VERSION} - a package manager and build tool for Kotlin

            Usage: skf <command> [options] [-- args]

            Commands:
                new <name>         Create a new project in a new directory
                init               Create a new project in the current directory
                add <dep>...       Add dependencies (group:artifact or group:artifact@version)
                remove <dep>...    Remove dependencies
                install            Resolve dependencies, update Skiff.lock and download jars
                update             Re-resolve dependencies even if Skiff.lock is current
                build              Compile the project into target/<name>-<version>.jar
                run [-- args]      Build and run the project
                test [-- args]     Compile and run the tests in test/ (extra args go to the JUnit console launcher)
                tree               Show the dependency tree
                classpath          Print the dependency classpath
                clean              Delete the target directory
                idea               Generate IntelliJ IDEA project files (module + libraries)

            Options:
                --locked           Fail if Skiff.lock would change (for CI)
                --offline          Use only the local cache
                --fat              With build: also bundle dependencies into target/<name>-<version>-all.jar
                -h, --help         Show this help
                -V, --version      Show the version
            """.trimIndent(),
        )
        return 0
    }

    private fun project(): Manifest = Manifest.find(Path.of(""))

    private fun store(manifest: Manifest?): ArtifactStore =
        ArtifactStore(manifest?.repositories ?: listOf(Repository.MAVEN_CENTRAL), offline = offline).apply {
            onDownload = { path -> if (path.endsWith(".jar")) Term.status("Downloaded", path.substringAfterLast('/')) }
        }

    /** Accepts `group:artifact`, `group:artifact@version` and `group:artifact:version`. */
    private fun parseSpec(spec: String): Pair<ModuleId, String?> {
        val (coordinates, atVersion) = spec.split('@', limit = 2).let { it[0] to it.getOrNull(1) }
        val parts = coordinates.split(':')
        return when (parts.size) {
            2 -> ModuleId.parse(coordinates) to atVersion
            3 -> ModuleId.parse("${parts[0]}:${parts[1]}") to (atVersion ?: parts[2])
            else -> throw SkiffException("invalid dependency '$spec', expected group:artifact[@version]")
        }
    }
}
