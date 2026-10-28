package skiff.project

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes the IntelliJ IDEA project files for a skiff project (`skf idea`): a module with `src/`, `resources/`
 * and `test/` roots, and one library per locked jar. The files hold absolute paths into the jar cache, so they
 * are generated per machine and should not be committed.
 */
object IdeaProject {
    /** Returns the files that were written or changed. */
    fun generate(manifest: Manifest, runtimeJars: List<Path>, testJars: List<Path>): List<Path> {
        val module = manifest.dir.resolve("${manifest.name}.iml")
        val modules = manifest.dir.resolve(".idea").resolve("modules.xml")
        val written = mutableListOf<Path>()
        Files.writeString(module, moduleXml(runtimeJars, testJars.filter { it !in runtimeJars }))
        written.add(module)
        if (registerModule(modules, manifest.name)) written.add(modules)
        return written
    }

    internal fun moduleXml(runtimeJars: List<Path>, testOnlyJars: List<Path>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<module type="JAVA_MODULE" version="4">""")
        appendLine("""  <component name="NewModuleRootManager" inherit-compiler-output="false">""")
        appendLine("""    <output url="file://${'$'}MODULE_DIR${'$'}/target/idea/classes" />""")
        appendLine("""    <output-test url="file://${'$'}MODULE_DIR${'$'}/target/idea/test-classes" />""")
        appendLine("""    <exclude-output />""")
        appendLine("""    <content url="file://${'$'}MODULE_DIR${'$'}">""")
        appendLine("""      <sourceFolder url="file://${'$'}MODULE_DIR${'$'}/src" isTestSource="false" />""")
        appendLine("""      <sourceFolder url="file://${'$'}MODULE_DIR${'$'}/resources" type="java-resource" />""")
        appendLine("""      <sourceFolder url="file://${'$'}MODULE_DIR${'$'}/test" isTestSource="true" />""")
        appendLine("""      <excludeFolder url="file://${'$'}MODULE_DIR${'$'}/target" />""")
        appendLine("""    </content>""")
        appendLine("""    <orderEntry type="inheritedJdk" />""")
        appendLine("""    <orderEntry type="sourceFolder" forTests="false" />""")
        runtimeJars.forEach { appendLibrary(it, scope = null) }
        testOnlyJars.forEach { appendLibrary(it, scope = "TEST") }
        appendLine("""  </component>""")
        appendLine("""</module>""")
    }

    private fun StringBuilder.appendLibrary(jar: Path, scope: String?) {
        val url = "jar://" + jar.toAbsolutePath().toString().replace('\\', '/') + "!/"
        val scopeAttribute = if (scope == null) "" else """ scope="$scope""""
        appendLine("""    <orderEntry type="module-library"$scopeAttribute>""")
        appendLine("""      <library name="${escape(jar.fileName.toString().removeSuffix(".jar"))}">""")
        appendLine("""        <CLASSES>""")
        appendLine("""          <root url="${escape(url)}" />""")
        appendLine("""        </CLASSES>""")
        appendLine("""        <JAVADOC />""")
        appendLine("""        <SOURCES />""")
        appendLine("""      </library>""")
        appendLine("""    </orderEntry>""")
    }

    /** Adds the module to `.idea/modules.xml`, creating the file if needed. Returns false if it was already listed. */
    private fun registerModule(file: Path, name: String): Boolean {
        val entry = """<module fileurl="file://${'$'}PROJECT_DIR${'$'}/$name.iml" filepath="${'$'}PROJECT_DIR${'$'}/$name.iml" />"""
        if (!Files.isRegularFile(file)) {
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project version="4">
                  <component name="ProjectModuleManager">
                    <modules>
                      $entry
                    </modules>
                  </component>
                </project>

                """.trimIndent(),
            )
            return true
        }
        val text = Files.readString(file)
        if ("${'$'}PROJECT_DIR${'$'}/$name.iml" in text || "</modules>" !in text) return false
        Files.writeString(file, text.replace("</modules>", "  $entry\n    </modules>"))
        return true
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
}
