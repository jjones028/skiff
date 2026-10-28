package skiff.build

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.invariantSeparatorsPathString

/** Writes jars with entries in a stable order, so the same input gives the same jar. */
object JarWriter {
    /** Jars [classesDir]; when [dependencies] is given, their contents are merged in to make a fat jar. */
    fun write(classesDir: Path, jar: Path, mainClass: String?, dependencies: List<Path> = emptyList()) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Created-By")] = "skiff"
            if (mainClass != null) mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
        }
        Files.createDirectories(jar.parent)
        val written = HashSet<String>()
        JarOutputStream(Files.newOutputStream(jar), manifest).use { out ->
            written += JarFile.MANIFEST_NAME
            val files = Files.walk(classesDir).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().toList() }
            for (file in files) {
                val name = classesDir.relativize(file).invariantSeparatorsPathString
                if (written.add(name)) {
                    out.putNextEntry(JarEntry(name))
                    Files.copy(file, out)
                    out.closeEntry()
                }
            }
            for (dependency in dependencies) {
                JarFile(dependency.toFile()).use { source ->
                    for (entry in source.entries().toList().sortedBy { it.name }) {
                        // First one wins; signatures and module descriptors of the merged jars don't apply to the fat jar.
                        if (entry.isDirectory || isExcludedFromFatJar(entry.name) || !written.add(entry.name)) continue
                        out.putNextEntry(JarEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
        }
    }

    private fun isExcludedFromFatJar(name: String): Boolean {
        val upper = name.uppercase()
        return upper == JarFile.MANIFEST_NAME ||
            name.endsWith("module-info.class") ||
            (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".DSA") || upper.endsWith(".RSA") || upper.endsWith(".EC")))
    }
}
