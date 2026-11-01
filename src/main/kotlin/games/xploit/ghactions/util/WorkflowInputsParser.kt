package games.xploit.ghactions.util

data class WorkflowInput(
    val name: String,
    val description: String?,
    val required: Boolean,
    val default: String?,
    val type: String,
    val options: List<String>,
)

data class WorkflowDispatchInfo(val hasDispatch: Boolean, val inputs: List<WorkflowInput>)

/**
 * Extracts `on.workflow_dispatch.inputs` from a workflow file with a small indentation-based scan.
 * Good enough for the usual block-style YAML; flow-style mappings for inputs are not supported.
 */
object WorkflowInputsParser {
    private val DISPATCH_ANYWHERE = Regex("""(^|[\s\[,{])workflow_dispatch\b""")
    private val DISPATCH_KEY = Regex("""^\s*workflow_dispatch\s*:\s*(\{\s*})?\s*$""")

    fun parse(text: String): WorkflowDispatchInfo {
        val lines = text.lines().map(::stripComment)
        if (lines.none { DISPATCH_ANYWHERE.containsMatchIn(it) }) return WorkflowDispatchInfo(false, emptyList())

        val dispatchIdx = lines.indexOfFirst { DISPATCH_KEY.matches(it) }
        if (dispatchIdx < 0) return WorkflowDispatchInfo(true, emptyList())
        val dispatchIndent = indent(lines[dispatchIdx])

        var inputsIdx = -1
        for (i in dispatchIdx + 1 until lines.size) {
            val l = lines[i]
            if (l.isBlank()) continue
            if (indent(l) <= dispatchIndent) break
            if (l.trim() == "inputs:") {
                inputsIdx = i
                break
            }
        }
        if (inputsIdx < 0) return WorkflowDispatchInfo(true, emptyList())
        val inputsIndent = indent(lines[inputsIdx])

        val result = mutableListOf<WorkflowInput>()
        var childIndent = -1
        var propIndent = -1
        var name: String? = null
        var props = mutableMapOf<String, String>()
        var options = mutableListOf<String>()
        var inOptions = false

        fun flush() {
            val n = name ?: return
            result += WorkflowInput(
                name = n,
                description = props["description"]?.takeIf { it.isNotEmpty() },
                required = props["required"].equals("true", ignoreCase = true),
                default = props["default"],
                type = props["type"]?.lowercase() ?: if (options.isNotEmpty()) "choice" else "string",
                options = options.toList(),
            )
        }

        for (i in inputsIdx + 1 until lines.size) {
            val l = lines[i]
            if (l.isBlank()) continue
            val ind = indent(l)
            if (ind <= inputsIndent) break
            val t = l.trim()
            if (childIndent < 0) childIndent = ind
            when {
                ind == childIndent -> {
                    flush()
                    name = t.substringBefore(':').trim().unquote()
                    props = mutableMapOf()
                    options = mutableListOf()
                    inOptions = false
                    propIndent = -1
                }
                ind < childIndent -> break
                t.startsWith("- ") || t == "-" -> if (inOptions) options += t.removePrefix("-").trim().unquote()
                else -> {
                    if (propIndent < 0) propIndent = ind
                    if (ind != propIndent) continue // continuation of a block scalar or a nested mapping
                    val key = t.substringBefore(':').trim()
                    val value = t.substringAfter(':', "").trim()
                    inOptions = key == "options" && value.isEmpty()
                    if (key == "options" && value.startsWith("[")) {
                        options += value.trim('[', ']').split(',').map { it.trim().unquote() }.filter { it.isNotEmpty() }
                    } else if (ind == propIndent) {
                        props[key] = if (value in BLOCK_SCALARS) "" else value.unquote()
                    }
                }
            }
        }
        flush()
        return WorkflowDispatchInfo(true, result)
    }

    private val BLOCK_SCALARS = setOf("|", ">", "|-", ">-", "|+", ">+")

    private fun indent(s: String) = s.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) s.length else it }

    /** Removes a `#` comment that starts at the line beginning or after whitespace, outside of quotes. */
    private fun stripComment(line: String): String {
        var quote: Char? = null
        for ((i, c) in line.withIndex()) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '#' && (i == 0 || line[i - 1].isWhitespace()) -> return line.substring(0, i).trimEnd()
            }
        }
        return line
    }

    private fun String.unquote(): String =
        if (length >= 2 && ((startsWith('"') && endsWith('"')) || (startsWith('\'') && endsWith('\'')))) substring(1, length - 1)
        else this
}
