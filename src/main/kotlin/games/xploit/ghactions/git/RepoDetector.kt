package games.xploit.ghactions.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRemote
import git4idea.repo.GitRepositoryManager

data class GitHubRepoCoordinates(
    val host: String,
    val owner: String,
    val name: String,
    val root: VirtualFile?,
) {
    val apiBaseUrl: String
        get() = if (host.equals("github.com", ignoreCase = true)) "https://api.github.com" else "https://$host/api/v3"
    val webUrl: String get() = "https://$host/$owner/$name"
    val fullName: String get() = "$owner/$name"

    override fun toString(): String = fullName
}

object RepoDetector {
    // https://host/owner/repo(.git), git@host:owner/repo(.git), ssh://git@host(:port)/owner/repo(.git)
    private val REMOTE_URL = Regex("""^(?:[a-z+]+://)?(?:[^@/]+@)?([^/:]+)(?::\d+)?[:/]+([^/]+)/([^/]+?)(?:\.git)?/?$""")

    data class Parsed(val host: String, val owner: String, val name: String)

    fun parseRemoteUrl(url: String): Parsed? {
        val m = REMOTE_URL.matchEntire(url.trim()) ?: return null
        val (host, owner, name) = m.destructured
        return Parsed(host.lowercase(), owner, name)
    }

    /** GitHub repositories behind the git roots of the project ("origin" remote preferred). */
    fun detect(project: Project): List<GitHubRepoCoordinates> {
        val manager = GitRepositoryManager.getInstance(project)
        return manager.repositories.mapNotNull { repo ->
            val remotes = repo.remotes.sortedBy {
                when (it.name) {
                    GitRemote.ORIGIN -> 0
                    "upstream" -> 1
                    else -> 2
                }
            }
            remotes.asSequence()
                .flatMap { it.urls.asSequence() }
                .mapNotNull { parseRemoteUrl(it) }
                .firstOrNull { it.host.contains("github") }
                ?.let { GitHubRepoCoordinates(it.host, it.owner, it.name, repo.root) }
        }.distinctBy { it.fullName + "@" + it.host }
    }

    fun currentBranch(project: Project, repo: GitHubRepoCoordinates): String? {
        val root = repo.root ?: return null
        return GitRepositoryManager.getInstance(project).getRepositoryForRoot(root)?.currentBranchName
    }
}
