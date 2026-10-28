package skiff.build

import skiff.cli.Term
import skiff.project.Manifest
import java.io.File
import java.nio.file.Path

/** Runs the compiled tests with the JUnit Platform console launcher. */
object TestRunner {
    private const val LAUNCHER_MAIN_CLASS = "org.junit.platform.console.ConsoleLauncher"

    fun run(manifest: Manifest, mainBuild: BuildResult, testClasses: Path, classpath: List<Path>, args: List<String>): Int {
        Term.status("Running", "tests of `${manifest.name}`")
        val runtimeClasspath = (listOf(testClasses, mainBuild.classesDir) + classpath).joinToString(File.pathSeparator)
        return JavaProcess.run(
            listOf("-cp", runtimeClasspath, LAUNCHER_MAIN_CLASS, "execute", "--scan-classpath=$testClasses", "--disable-banner") + args,
            manifest.targetDir.resolve("test.args"),
            manifest.dir,
        )
    }
}
