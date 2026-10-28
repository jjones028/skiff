package skiff

/** An expected failure whose message is shown to the user without a stack trace. */
class SkiffException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
