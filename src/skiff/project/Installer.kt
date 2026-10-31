package skiff.project

import skiff.Hashing
import skiff.SkiffException
import skiff.cli.Term
import skiff.maven.ArtifactStore
import skiff.maven.DependencyResolver
import skiff.maven.ModuleId
import skiff.maven.ResolvedModule
import java.nio.file.Files
import java.nio.file.Path

/** Keeps `Skiff.lock` in sync with `Skiff.toml` and makes sure every locked jar is downloaded and intact. */
class Installer(private val manifest: Manifest, private val store: ArtifactStore) {
    /**
     * Returns the project's dependency classpath.
     *
     * @param update re-resolve even if the lockfile is current (`skf update`).
     * @param locked fail instead of changing the lockfile (`--locked`, for CI).
     * @param tests include the dev-dependencies and the test runner; otherwise only what the project itself needs.
     */
    fun install(update: Boolean = false, locked: Boolean = false, tests: Boolean = false): List<Path> =
        jars(lock(update, locked), if (tests) manifest.testDependencies().keys else manifest.allDependencies().keys)

    /**
     * Returns the jars of an isolated [configuration] (see [Manifest.isolatedRoots]), resolved separately from the
     * project's own dependencies. With [withoutCompilerLibraries] the Kotlin standard library and compiler are left
     * out, since a compiler plugin runs inside a compiler that already has them.
     */
    fun isolatedClasspath(configuration: String, locked: Boolean = false, withoutCompilerLibraries: Boolean = false): List<Path> {
        if (configuration !in manifest.isolatedRoots()) return emptyList()
        val packages = lock(update = false, locked = locked).isolated[configuration].orEmpty()
            .filterNot { withoutCompilerLibraries && it.module.group == "org.jetbrains.kotlin" && it.module.artifact in COMPILER_PROVIDED }
        return verifiedJars(packages)
    }

    /** The jars of the `[[kotlin.compiler-plugin]]` entries and their dependencies. */
    fun pluginClasspath(locked: Boolean = false): List<Path> =
        isolatedClasspath(Manifest.PLUGINS, locked, withoutCompilerLibraries = true)

    private fun lock(update: Boolean, locked: Boolean): Lockfile {
        val existing = Lockfile.read(manifest.lockFile)
        val fingerprint = manifest.resolutionFingerprint()
        return if (!update && existing != null && existing.fingerprint == fingerprint) {
            existing
        } else {
            if (locked) {
                throw SkiffException(
                    if (existing == null) "${Lockfile.FILE_NAME} is missing and --locked was given"
                    else "${Lockfile.FILE_NAME} needs to be updated but --locked was given",
                )
            }
            resolve(fingerprint, existing)
        }
    }

    private fun jars(lock: Lockfile, roots: Collection<ModuleId>): List<Path> {
        val needed = lock.reachable(roots)
        return verifiedJars(lock.packages.filter { it.module in needed })
    }

    /**
     * The up-to-date lockfile (including the dev-dependencies) with the verified jar of every package that has one,
     * for `skf metadata`.
     */
    fun lockedJars(locked: Boolean = false): Pair<Lockfile, Map<ModuleId, Path>> {
        val lock = lock(update = false, locked = locked)
        return lock to jarsByModule(lock.packages)
    }

    private fun verifiedJars(packages: List<LockedPackage>): List<Path> = jarsByModule(packages).values.toList()

    /** Downloads the jars of [packages] if needed and checks them against the locked checksums. */
    private fun jarsByModule(packages: List<LockedPackage>): Map<ModuleId, Path> {
        val jars = store.getAll(packages.filter { it.sha256 != null }.map { it.jarPath })
        return packages.mapNotNull { pkg ->
            val expected = pkg.sha256 ?: return@mapNotNull null
            val jar = jars.getValue(pkg.jarPath)
            val actual = Hashing.sha256(jar)
            if (actual != expected) {
                throw SkiffException(
                    "checksum of ${pkg.module}:${pkg.version} does not match ${Lockfile.FILE_NAME}\n" +
                        "  expected $expected\n  found    $actual ($jar)",
                )
            }
            pkg.module to jar
        }.toMap()
    }

    private fun resolve(fingerprint: String, previous: Lockfile?): Lockfile {
        Term.status("Resolving", "dependencies")
        val resolver = DependencyResolver(store)
        val packages = lockedPackages(resolver.resolve(manifest.testDependencies()))
        // Each isolated group gets its own resolution, so its versions cannot leak into the project's classpath.
        val isolated = manifest.isolatedRoots().mapValues { (_, roots) -> lockedPackages(resolver.resolve(roots)) }
        val lock = Lockfile(fingerprint, packages, isolated)
        reportChanges(previous?.packages.orEmpty(), packages, label = "")
        for (configuration in isolated.keys + previous?.isolated?.keys.orEmpty()) {
            reportChanges(previous?.isolated?.get(configuration).orEmpty(), isolated[configuration].orEmpty(), " ($configuration)")
        }
        if (previous?.render() != lock.render()) Files.writeString(manifest.lockFile, lock.render())
        return lock
    }

    private fun lockedPackages(resolved: List<ResolvedModule>): List<LockedPackage> {
        val jars = store.getAll(resolved.filter { it.hasJar }.map { it.jarPath })
        return resolved.map { module ->
            LockedPackage(
                module = module.module,
                version = module.version,
                sha256 = jars[module.jarPath]?.let(Hashing::sha256),
                dependencies = module.dependencies.sorted(),
            )
        }
    }

    private fun reportChanges(before: List<LockedPackage>, after: List<LockedPackage>, label: String) {
        val old = before.associateBy { it.module }
        val new = after.associateBy { it.module }
        for (pkg in after) {
            val previous = old[pkg.module]
            when {
                previous == null -> Term.status("Adding", "${pkg.module} v${pkg.version}$label")
                previous.version != pkg.version ->
                    Term.status("Updating", "${pkg.module} v${previous.version} -> v${pkg.version}$label")
            }
        }
        for (pkg in before) {
            if (pkg.module !in new) Term.status("Removing", "${pkg.module} v${pkg.version}$label")
        }
    }

    private companion object {
        val COMPILER_PROVIDED = setOf("kotlin-stdlib", "kotlin-compiler-embeddable")
    }
}
