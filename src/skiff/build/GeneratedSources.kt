package skiff.build

import java.nio.file.Path

/**
 * What annotation processing produced: [kotlin] and [java] sources to compile, and [outputDirs] holding
 * resources and class files that go into the jar as they are.
 */
data class GeneratedSources(
    val kotlin: List<Path> = emptyList(),
    val java: List<Path> = emptyList(),
    val outputDirs: List<Path> = emptyList(),
) {
    operator fun plus(other: GeneratedSources) =
        GeneratedSources(kotlin + other.kotlin, java + other.java, outputDirs + other.outputDirs)
}
