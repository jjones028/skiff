package skiff.build

import skiff.SkiffException
import skiff.cli.Term
import skiff.project.Manifest
import java.io.File
import java.nio.file.Path

/** Runs the built project's main class with its dependency classpath. */
object Runner {
    fun run(manifest: Manifest, build: BuildResult, classpath: List<Path>, args: List<String>): Int {
        val mainClass = build.mainClass
            ?: throw SkiffException("no `fun main` found; set `main` under [package] in ${Manifest.FILE_NAME}")
        Term.status("Running", "`${(listOf(manifest.name) + args).joinToString(" ")}`")
        val runtimeClasspath = (listOf(build.jar) + classpath).joinToString(File.pathSeparator)
        return JavaProcess.run(
            listOf("-cp", runtimeClasspath, mainClass) + args,
            manifest.targetDir.resolve("run.args"),
            manifest.dir,
        )
    }
}
