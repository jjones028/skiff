package skiff.build

import java.nio.file.Path

data class BuildResult(val classesDir: Path, val jar: Path, val mainClass: String?)
