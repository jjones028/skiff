package skiff.project

import skiff.Hashing
import skiff.SkiffException
import skiff.cli.Term
import skiff.maven.ArtifactStore
import skiff.maven.DependencyResolver
import skiff.maven.ModuleId
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
    fun install(update: Boolean = false, locked: Boolean = false, tests: Boolean = false): List<Path> {
        val existing = Lockfile.read(manifest.lockFile)
        val fingerprint = manifest.resolutionFingerprint()
        val lock = if (!update && existing != null && existing.fingerprint == fingerprint) {
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

        val needed = reachable(lock, if (tests) manifest.testDependencies().keys else manifest.allDependencies().keys)
        val packages = lock.packages.filter { it.module in needed }
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
            jar
        }
    }

    private fun reachable(lock: Lockfile, roots: Collection<ModuleId>): Set<ModuleId> {
        val byModule = lock.packages.associateBy { it.module }
        val seen = LinkedHashSet<ModuleId>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val module = queue.removeFirst()
            if (byModule.containsKey(module) && seen.add(module)) queue += byModule.getValue(module).dependencies
        }
        return seen
    }

    private fun resolve(fingerprint: String, previous: Lockfile?): Lockfile {
        Term.status("Resolving", "dependencies")
        val resolved = DependencyResolver(store).resolve(manifest.testDependencies())
        val jars = store.getAll(resolved.filter { it.hasJar }.map { it.jarPath })
        val packages = resolved.map { module ->
            LockedPackage(
                module = module.module,
                version = module.version,
                sha256 = jars[module.jarPath]?.let(Hashing::sha256),
                dependencies = module.dependencies.sorted(),
            )
        }
        val lock = Lockfile(fingerprint, packages)
        reportChanges(previous?.packages.orEmpty(), packages)
        if (previous?.render() != lock.render()) Files.writeString(manifest.lockFile, lock.render())
        return lock
    }

    private fun reportChanges(before: List<LockedPackage>, after: List<LockedPackage>) {
        val old = before.associateBy { it.module }
        val new = after.associateBy { it.module }
        for (pkg in after) {
            val previous = old[pkg.module]
            when {
                previous == null -> Term.status("Adding", "${pkg.module} v${pkg.version}")
                previous.version != pkg.version ->
                    Term.status("Updating", "${pkg.module} v${previous.version} -> v${pkg.version}")
            }
        }
        for (pkg in before) {
            if (pkg.module !in new) Term.status("Removing", "${pkg.module} v${pkg.version}")
        }
    }
}
