package skiff.project

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes the IntelliJ IDEA project files for a skiff project (`skf idea`): a module with `src/`, `resources/`
 * and `test/` roots, one library per locked jar, the Kotlin facet (language level, JVM target and compiler plugins)
 * and, when kapt or KSP is used, their generated sources as generated source roots. The files hold absolute paths
 * into the jar cache, so they are generated per machine and should not be committed.
 */
object IdeaProject {
    /** Where kapt and KSP write the sources they generate, relative to the project (see `AnnotationProcessing`). */
    private val GENERATED_KSP = listOf("target/generated/ksp/kotlin", "target/generated/ksp/java")
    private val GENERATED_KAPT = listOf("target/generated/kapt/sources", "target/generated/kapt/kotlin")

    /**
     * Returns the files that were written or changed. [pluginJars] are the jars of the enabled compiler plugins
     * (the named ones and those given by coordinates).
     */
    fun generate(manifest: Manifest, runtimeJars: List<Path>, testJars: List<Path>, pluginJars: List<Path> = emptyList()): List<Path> {
        val module = manifest.dir.resolve("${manifest.name}.iml")
        val modules = manifest.dir.resolve(".idea").resolve("modules.xml")
        val written = mutableListOf<Path>()
        val generated = generatedSourceDirs(manifest)
        // The roots must exist for the IDE to pick them up; the first build fills them.
        generated.forEach { Files.createDirectories(manifest.dir.resolve(it)) }
        Files.writeString(module, moduleXml(manifest, runtimeJars, testJars.filter { it !in runtimeJars }, pluginJars, generated))
        written.add(module)
        if (registerModule(modules, manifest.name)) written.add(modules)
        return written
    }

    /** The generated source directories of the processors in use, relative to the project. */
    fun generatedSourceDirs(manifest: Manifest): List<String> =
        (if (manifest.ksp != null) GENERATED_KSP else emptyList()) + (if (manifest.kapt != null) GENERATED_KAPT else emptyList())

    internal fun moduleXml(
        manifest: Manifest,
        runtimeJars: List<Path>,
        testOnlyJars: List<Path>,
        pluginJars: List<Path>,
        generatedDirs: List<String>,
    ): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<module type="JAVA_MODULE" version="4">""")
        appendKotlinFacet(manifest, pluginJars)
        appendLine("""  <component name="NewModuleRootManager" inherit-compiler-output="false">""")
        appendLine("""    <output url="file://${'$'}MODULE_DIR${'$'}/target/idea/classes" />""")
        appendLine("""    <output-test url="file://${'$'}MODULE_DIR${'$'}/target/idea/test-classes" />""")
        appendLine("""    <exclude-output />""")
        appendLine("""    <content url="file://${'$'}MODULE_DIR${'$'}">""")
        appendLine("""      <sourceFolder url="file://${'$'}MODULE_DIR${'$'}/src" isTestSource="false" />""")
        appendLine("""      <sourceFolder url="file://${'$'}MODULE_DIR${'$'}/resources" type="java-resource" />""")
        appendLine("""      <sourceFolder url="file://${'$'}MODULE_DIR${'$'}/test" isTestSource="true" />""")
        generatedDirs.forEach {
            appendLine("""      <sourceFolder url="file://${'$'}MODULE_DIR${'$'}/$it" isTestSource="false" generated="true" />""")
        }
        appendLine("""      <excludeFolder url="file://${'$'}MODULE_DIR${'$'}/target" />""")
        appendLine("""    </content>""")
        appendLine("""    <orderEntry type="inheritedJdk" />""")
        appendLine("""    <orderEntry type="sourceFolder" forTests="false" />""")
        runtimeJars.forEach { appendLibrary(it, scope = null) }
        testOnlyJars.forEach { appendLibrary(it, scope = "TEST") }
        appendLine("""  </component>""")
        appendLine("""</module>""")
    }

    /**
     * The Kotlin facet, in the format the Kotlin plugin writes: language level and JVM target as string arguments, and
     * the compiler plugins as `pluginClasspaths` and `pluginOptions` array arguments (a single value is element text,
     * several values are `<arg>` children).
     */
    private fun StringBuilder.appendKotlinFacet(manifest: Manifest, pluginJars: List<Path>) {
        val languageVersion = manifest.kotlinVersion.split('.').take(2).joinToString(".")
        val pluginOptions = manifest.plugins.flatMap { it.options() } + manifest.externalPlugins.flatMap { it.options }
        appendLine("""  <component name="FacetManager">""")
        appendLine("""    <facet type="kotlin-language" name="Kotlin">""")
        appendLine("""      <configuration version="5" platform="JVM ${escape(manifest.jvmTarget)}" allPlatforms="JVM [${escape(manifest.jvmTarget)}]" useProjectSettings="false">""")
        appendLine("""        <compilerSettings />""")
        appendLine("""        <compilerArguments>""")
        appendLine("""          <stringArguments>""")
        appendLine("""            <stringArg name="jvmTarget" arg="${escape(manifest.jvmTarget)}" />""")
        appendLine("""            <stringArg name="languageVersion" arg="${escape(languageVersion)}" />""")
        appendLine("""            <stringArg name="apiVersion" arg="${escape(languageVersion)}" />""")
        appendLine("""          </stringArguments>""")
        val arrays = listOf(
            "pluginClasspaths" to pluginJars.map { it.toAbsolutePath().toString().replace('\\', '/') },
            "pluginOptions" to pluginOptions,
        ).filter { it.second.isNotEmpty() }
        if (arrays.isNotEmpty()) {
            appendLine("""          <arrayArguments>""")
            for ((name, values) in arrays) {
                appendLine("""            <arrayArg name="$name">""")
                if (values.size == 1) {
                    appendLine("""              <args>${escape(values.single())}</args>""")
                } else {
                    appendLine("""              <args>""")
                    values.forEach { appendLine("""                <arg>${escape(it)}</arg>""") }
                    appendLine("""              </args>""")
                }
                appendLine("""            </arrayArg>""")
            }
            appendLine("""          </arrayArguments>""")
        }
        appendLine("""        </compilerArguments>""")
        appendLine("""      </configuration>""")
        appendLine("""    </facet>""")
        appendLine("""  </component>""")
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

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
