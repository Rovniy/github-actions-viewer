package games.xploit.ghactions.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import games.xploit.ghactions.auth.TokenProvider

class GhActionsConfigurable : BoundConfigurable("GitHub Actions") {
    private var tokenText: String = ""

    override fun createPanel(): DialogPanel {
        val state = GhActionsSettings.getInstance().state
        tokenText = GhActionsSettings.token.orEmpty()
        return panel {
            row("Personal access token:") {
                passwordField().bindText(::tokenText).columns(COLUMNS_LARGE)
            }
            row {
                comment(
                    "Optional. When empty, GITHUB_TOKEN / GH_TOKEN environment variables or the GitHub CLI " +
                        "(<code>gh auth token</code>) are used.<br>" +
                        "Classic token: <b>repo</b> scope. Fine-grained token: <b>Actions: Read and write</b>."
                )
            }
            row("Auto-refresh interval, seconds:") {
                intTextField(5..3600).bindIntText(state::refreshSeconds)
            }
            row("Number of runs to load:") {
                intTextField(1..100).bindIntText(state::runsPerPage)
            }
        }
    }

    override fun apply() {
        super.apply()
        GhActionsSettings.token = tokenText
        TokenProvider.invalidate()
    }
}
