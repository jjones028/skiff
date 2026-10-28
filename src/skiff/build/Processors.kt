package skiff.build

import java.nio.file.Path

/** The resolved annotation processor jars (with their dependencies) for kapt and KSP. */
data class Processors(val kapt: List<Path> = emptyList(), val ksp: List<Path> = emptyList())
