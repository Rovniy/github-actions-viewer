package games.xploit.ghactions.auth

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.logger
import games.xploit.ghactions.settings.GhActionsSettings
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class NoTokenException : Exception(
    "GitHub token not found. Set it in Settings | Tools | GitHub Actions, or log in with the GitHub CLI: gh auth login"
)

/**
 * Resolves a GitHub token: plugin settings → GITHUB_TOKEN / GH_TOKEN → `gh auth token`.
 * Blocking; call from a background thread.
 */
object TokenProvider {
    private val log = logger<TokenProvider>()
    private val ghCliCache = ConcurrentHashMap<String, String>()

    fun token(host: String): String? {
        GhActionsSettings.token?.takeIf { it.isNotBlank() }?.let { return it }
        for (env in listOf("GITHUB_TOKEN", "GH_TOKEN")) {
            System.getenv(env)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        ghCliCache[host]?.let { return it }
        return ghCliToken(host)?.also { ghCliCache[host] = it }
    }

    fun invalidate() = ghCliCache.clear()

    private fun ghCliToken(host: String): String? = try {
        val cmd = GeneralCommandLine("gh", "auth", "token", "--hostname", host).withCharset(StandardCharsets.UTF_8)
        val out = ExecUtil.execAndGetOutput(cmd, 10_000)
        if (out.exitCode == 0) out.stdout.trim().takeIf { it.isNotEmpty() } else null
    } catch (e: Exception) {
        log.debug("gh CLI is not available", e)
        null
    }
}
