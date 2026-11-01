package games.xploit.ghactions.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import javax.swing.Icon

object StatusIcons {
    fun of(status: String?, conclusion: String?): Icon = when (status) {
        "completed" -> when (conclusion) {
            "success" -> AllIcons.RunConfigurations.TestPassed
            "failure", "timed_out", "startup_failure" -> AllIcons.RunConfigurations.TestFailed
            "cancelled" -> AllIcons.RunConfigurations.TestTerminated
            "skipped" -> AllIcons.RunConfigurations.TestSkipped
            "neutral" -> AllIcons.RunConfigurations.TestIgnored
            "action_required" -> AllIcons.General.Warning
            else -> AllIcons.RunConfigurations.TestUnknown
        }
        "in_progress" -> AnimatedIcon.Default.INSTANCE
        else -> AllIcons.RunConfigurations.TestNotRan // queued, waiting, pending, requested
    }

    fun label(status: String?, conclusion: String?): String =
        if (status == "completed") conclusion ?: "completed" else (status ?: "unknown").replace('_', ' ')
}
