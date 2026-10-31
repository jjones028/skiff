package skiff.build

import java.nio.file.Files
import java.nio.file.Path

/**
 * Starts child JVMs on the same Java runtime skiff runs on. Arguments go through an `@argfile`
 * because classpaths easily exceed the Windows command-line limit.
 */
object JavaProcess {
    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    val executable: Path = Path.of(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java")

    /** The feature version of the running JDK, e.g. 25. */
    val featureVersion: Int = Runtime.version().feature()

    fun run(args: List<String>, argFile: Path, workDir: Path): Int {
        writeArgFile(argFile, args)
        val process = ProcessBuilder(executable.toString(), "@$argFile")
            .directory(workDir.toFile())
            .inheritIO()
            .start()
        return process.waitFor()
    }

    /** Like [run], but returns what the process printed (stdout and stderr together) instead of passing it through. */
    fun capture(args: List<String>, argFile: Path, workDir: Path): Pair<Int, String> {
        writeArgFile(argFile, args)
        val process = ProcessBuilder(executable.toString(), "@$argFile")
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        return process.waitFor() to output
    }

    /** Writes one quoted argument per line, in the format both `java` and `kotlinc` accept. */
    fun writeArgFile(file: Path, args: List<String>) {
        Files.createDirectories(file.parent)
        Files.write(file, args.map { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
    }
}
