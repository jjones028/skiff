package skiff.cli

/** Cargo-style status output on stderr, coloured when stderr is a terminal. */
object Term {
    private val color: Boolean = System.getenv("NO_COLOR") == null && isTerminal()

    fun status(verb: String, message: String) {
        System.err.println(paint(verb.padStart(12), "1;32") + " " + message)
    }

    fun warn(message: String) {
        System.err.println(paint("warning", "1;33") + ": " + message)
    }

    fun error(message: String) {
        System.err.println(paint("error", "1;31") + ": " + message)
    }

    private fun paint(text: String, code: String): String =
        if (color) "\u001b[${code}m$text\u001b[0m" else text

    // System.console() is non-null even when redirected on JDK 22+, so ask it directly.
    private fun isTerminal(): Boolean = System.console()?.isTerminal ?: false
}
