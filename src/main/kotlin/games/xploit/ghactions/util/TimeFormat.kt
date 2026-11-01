package games.xploit.ghactions.util

import com.intellij.util.text.DateFormatUtil
import java.time.Duration
import java.time.Instant

object TimeFormat {
    fun parse(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** "1m 23s"; an absent end means "until now". */
    fun duration(start: String?, end: String?): String? {
        val s = parse(start) ?: return null
        val e = parse(end) ?: Instant.now()
        val sec = Duration.between(s, e).seconds.coerceAtLeast(0)
        return when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}m ${sec % 60}s"
            else -> "${sec / 3600}h ${sec % 3600 / 60}m"
        }
    }

    fun pretty(s: String?): String? = parse(s)?.let { DateFormatUtil.formatPrettyDateTime(it.toEpochMilli()) }
}
