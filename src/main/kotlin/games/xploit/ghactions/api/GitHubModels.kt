package games.xploit.ghactions.api

import com.google.gson.annotations.SerializedName

// All fields have defaults so Kotlin generates a no-arg constructor and Gson keeps the defaults for absent fields.

data class GhUser(val login: String = "")

data class Workflow(
    val id: Long = 0,
    val name: String = "",
    val path: String = "",
    val state: String = "",
    @SerializedName("html_url") val htmlUrl: String = "",
) {
    override fun toString(): String = name
}

data class WorkflowsResponse(val workflows: List<Workflow> = emptyList())

data class WorkflowRun(
    val id: Long = 0,
    val name: String? = null,
    @SerializedName("display_title") val displayTitle: String? = null,
    @SerializedName("run_number") val runNumber: Int = 0,
    @SerializedName("run_attempt") val runAttempt: Int = 1,
    val event: String = "",
    val status: String? = null,
    val conclusion: String? = null,
    @SerializedName("head_branch") val headBranch: String? = null,
    @SerializedName("head_sha") val headSha: String = "",
    @SerializedName("html_url") val htmlUrl: String = "",
    val path: String? = null,
    @SerializedName("workflow_id") val workflowId: Long = 0,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("run_started_at") val runStartedAt: String? = null,
    val actor: GhUser? = null,
) {
    val isCompleted: Boolean get() = status == Status.COMPLETED
    val isFailed: Boolean get() = isCompleted && conclusion in Status.FAILED_CONCLUSIONS
}

data class RunsResponse(
    @SerializedName("total_count") val totalCount: Int = 0,
    @SerializedName("workflow_runs") val workflowRuns: List<WorkflowRun> = emptyList(),
)

data class Step(
    val name: String = "",
    val status: String? = null,
    val conclusion: String? = null,
    val number: Int = 0,
    @SerializedName("started_at") val startedAt: String? = null,
    @SerializedName("completed_at") val completedAt: String? = null,
)

data class Job(
    val id: Long = 0,
    @SerializedName("run_id") val runId: Long = 0,
    val name: String = "",
    val status: String? = null,
    val conclusion: String? = null,
    @SerializedName("started_at") val startedAt: String? = null,
    @SerializedName("completed_at") val completedAt: String? = null,
    @SerializedName("html_url") val htmlUrl: String? = null,
    val steps: List<Step>? = null,
) {
    val isCompleted: Boolean get() = status == Status.COMPLETED
}

data class JobsResponse(val jobs: List<Job> = emptyList())

object Status {
    const val COMPLETED = "completed"
    const val IN_PROGRESS = "in_progress"
    val FAILED_CONCLUSIONS = setOf("failure", "timed_out", "startup_failure", "cancelled")
}
