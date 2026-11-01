package games.xploit.ghactions.ui

import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import games.xploit.ghactions.api.Job
import games.xploit.ghactions.api.Step
import games.xploit.ghactions.api.WorkflowRun
import games.xploit.ghactions.util.TimeFormat
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** Payload of run/job/step tree nodes. [key] identifies the node across refreshes, [version] changes with its state. */
sealed interface NodeData {
    val key: String
    val version: String
}

data class RunData(val run: WorkflowRun) : NodeData {
    override val key get() = "run:${run.id}"
    override val version get() = "${run.status}/${run.conclusion}/${run.runAttempt}"
    override fun toString() = run.displayTitle ?: run.name ?: "#${run.runNumber}"
}

data class JobData(val run: WorkflowRun, val job: Job) : NodeData {
    override val key get() = "job:${job.id}"
    override val version get() = "${job.status}/${job.conclusion}/${job.steps.orEmpty().count { it.status == "completed" }}"
    override fun toString() = job.name
}

data class StepData(val run: WorkflowRun, val job: Job, val step: Step) : NodeData {
    override val key get() = "step:${job.id}:${step.number}"
    override val version get() = "${step.status}/${step.conclusion}/${job.status}"
    override fun toString() = step.name
}

object LoadingData {
    override fun toString() = "Loading…"
}

data class MessageData(val text: String) {
    override fun toString() = text
}

class RunTreeRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        when (val data = (value as? DefaultMutableTreeNode)?.userObject) {
            is RunData -> {
                val r = data.run
                icon = StatusIcons.of(r.status, r.conclusion)
                append(data.toString())
                val details = listOfNotNull(
                    "${r.name ?: "workflow"} #${r.runNumber}" + if (r.runAttempt > 1) " (attempt ${r.runAttempt})" else "",
                    r.headBranch,
                    r.event,
                    TimeFormat.pretty(r.runStartedAt ?: r.createdAt),
                    TimeFormat.duration(r.runStartedAt, if (r.isCompleted) r.updatedAt else null),
                )
                append("   " + details.joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is JobData -> {
                val j = data.job
                icon = StatusIcons.of(j.status, j.conclusion)
                append(j.name)
                TimeFormat.duration(j.startedAt, j.completedAt)?.let { append("   $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
            is StepData -> {
                val s = data.step
                icon = StatusIcons.of(s.status, s.conclusion)
                append("${s.number}. ${s.name}")
                TimeFormat.duration(s.startedAt, s.completedAt)?.let { append("   $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
            is LoadingData -> {
                icon = AnimatedIcon.Default.INSTANCE
                append(data.toString(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is MessageData -> append(data.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
