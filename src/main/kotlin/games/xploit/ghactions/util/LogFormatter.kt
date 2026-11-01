package games.xploit.ghactions.util

import games.xploit.ghactions.api.Step
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class LogLineKind { NORMAL, GROUP, COMMAND, ERROR, WARNING, NOTICE, DEBUG }

data class LogLine(val time: Instant?, val raw: String) {
    val kind: LogLineKind
        get() = when {
            raw.startsWith("##[group]") || raw.startsWith("##[section]") -> LogLineKind.GROUP
            raw.startsWith("##[command]") -> LogLineKind.COMMAND
            raw.startsWith("##[error]") -> LogLineKind.ERROR
            raw.startsWith("##[warning]") -> LogLineKind.WARNING
            raw.startsWith("##[notice]") -> LogLineKind.NOTICE
            raw.startsWith("##[debug]") -> LogLineKind.DEBUG
            else -> LogLineKind.NORMAL
        }

    val isHidden: Boolean get() = raw.startsWith("##[endgroup]")

    /** Text for display: workflow-command markers replaced, ANSI escape sequences removed. */
    val text: String
        get() {
            val body = when (kind) {
                LogLineKind.GROUP -> "▼ " + raw.substringAfter(']')
                LogLineKind.ERROR -> "Error: " + raw.substringAfter(']')
                LogLineKind.WARNING -> "Warning: " + raw.substringAfter(']')
                LogLineKind.NOTICE -> "Notice: " + raw.substringAfter(']')
                LogLineKind.COMMAND, LogLineKind.DEBUG -> raw.substringAfter(']')
                LogLineKind.NORMAL -> raw
            }
            return body.replace(ANSI, "")
        }

    companion object {
        private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")
    }
}

object LogFormatter {
    private val TIMESTAMP = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z) ?""")

    /** Splits a raw job log; lines without a timestamp inherit the previous one. */
    fun parse(raw: String): List<LogLine> {
        var last: Instant? = null
        return raw.removePrefix("﻿").lineSequence().map { line ->
            val m = TIMESTAMP.find(line)
            if (m != null) {
                val t = runCatching { Instant.parse(m.groupValues[1]) }.getOrNull()
                if (t != null) last = t
                LogLine(t ?: last, line.substring(m.range.last + 1))
            } else {
                LogLine(last, line)
            }
        }.toList().dropLastWhile { it.raw.isEmpty() }
    }

    /**
     * Lines of a single step. The job log has no step markers, so lines are matched by the step's time window
     * (step timestamps have second precision, so a neighbouring step's lines from the same second may leak in).
     */
    fun linesForStep(lines: List<LogLine>, step: Step): List<LogLine> {
        val start = TimeFormat.parse(step.startedAt)?.truncatedTo(ChronoUnit.SECONDS) ?: return emptyList()
        val end = TimeFormat.parse(step.completedAt)?.truncatedTo(ChronoUnit.SECONDS)?.plusSeconds(1)
        return lines.filter { line ->
            val t = line.time ?: return@filter false
            t >= start && (end == null || t < end)
        }
    }
}
