package games.xploit.ghactions.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import games.xploit.ghactions.git.GitHubRepoCoordinates
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class GitHubApiException(val statusCode: Int, message: String) : IOException(message)

/**
 * Minimal blocking client for the GitHub Actions REST API. Call it from a background thread only.
 */
class GitHubApiClient(repo: GitHubRepoCoordinates, private val token: String) {
    private val base = "${repo.apiBaseUrl}/repos/${repo.owner}/${repo.name}"

    fun workflows(): List<Workflow> =
        get("$base/actions/workflows?per_page=100", WorkflowsResponse::class.java).workflows

    fun runs(workflowId: Long?, branch: String?, perPage: Int): List<WorkflowRun> {
        val path = if (workflowId != null) "$base/actions/workflows/$workflowId/runs" else "$base/actions/runs"
        val query = buildString {
            append("?per_page=").append(perPage)
            if (!branch.isNullOrBlank()) append("&branch=").append(encode(branch))
        }
        return get(path + query, RunsResponse::class.java).workflowRuns
    }

    fun jobs(runId: Long): List<Job> =
        get("$base/actions/runs/$runId/jobs?per_page=100&filter=latest", JobsResponse::class.java).jobs

    /** Plain-text log of a job. The API answers with a redirect to a signed blob URL, which must be fetched without auth. */
    fun jobLog(jobId: Long): String {
        val response = send(request("$base/actions/jobs/$jobId/logs").GET().build())
        return when (response.statusCode()) {
            200 -> response.body()
            301, 302, 303, 307, 308 -> {
                val location = response.headers().firstValue("Location")
                    .orElseThrow { GitHubApiException(response.statusCode(), "Redirect without Location header") }
                val blob = httpClient.send(
                    HttpRequest.newBuilder(URI(location)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                )
                if (blob.statusCode() != 200) throw GitHubApiException(blob.statusCode(), "Failed to download log (HTTP ${blob.statusCode()})")
                blob.body()
            }
            else -> throw error(response)
        }
    }

    fun dispatch(workflowId: Long, ref: String, inputs: Map<String, String>) =
        post("$base/actions/workflows/$workflowId/dispatches", mapOf("ref" to ref, "inputs" to inputs))

    fun rerun(runId: Long, debug: Boolean) =
        post("$base/actions/runs/$runId/rerun", mapOf("enable_debug_logging" to debug))

    fun rerunFailedJobs(runId: Long, debug: Boolean) =
        post("$base/actions/runs/$runId/rerun-failed-jobs", mapOf("enable_debug_logging" to debug))

    fun rerunJob(jobId: Long, debug: Boolean) =
        post("$base/actions/jobs/$jobId/rerun", mapOf("enable_debug_logging" to debug))

    fun cancel(runId: Long) = post("$base/actions/runs/$runId/cancel", null)

    // ---------------------------------------------------------------------------------------------------------------

    private fun <T> get(url: String, type: Class<T>): T {
        val response = send(request(url).GET().build())
        if (response.statusCode() != 200) throw error(response)
        return gson.fromJson(response.body(), type)
    }

    private fun post(url: String, body: Any?) {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8)
        val response = send(request(url).header("Content-Type", "application/json").POST(publisher).build())
        if (response.statusCode() !in 200..299) throw error(response)
    }

    private fun request(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI(url))
            .timeout(TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "webstorm-gh-actions-plugin")

    private fun send(request: HttpRequest): HttpResponse<String> =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

    private fun error(response: HttpResponse<String>): GitHubApiException {
        val apiMessage = runCatching {
            JsonParser.parseString(response.body()).asJsonObject.get("message")?.asString
        }.getOrNull()
        val code = response.statusCode()
        val hint = when (code) {
            401 -> " — check the GitHub token"
            403, 404 -> " — the token may lack access to this repository or the 'repo'/'actions' permission"
            else -> ""
        }
        return GitHubApiException(code, "HTTP $code: ${apiMessage ?: "request failed"}$hint")
    }

    private fun encode(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(30)
        private val gson = Gson()

        // The IDE installs its own default ProxySelector, so IDE proxy settings apply.
        private val httpClient: HttpClient = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }
}
