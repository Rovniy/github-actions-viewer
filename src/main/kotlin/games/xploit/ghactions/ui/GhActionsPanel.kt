package games.xploit.ghactions.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.browsers.OpenUrlHyperlinkInfo
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import games.xploit.ghactions.api.GitHubApiClient
import games.xploit.ghactions.api.GitHubApiException
import games.xploit.ghactions.api.Job
import games.xploit.ghactions.api.Step
import games.xploit.ghactions.api.Workflow
import games.xploit.ghactions.api.WorkflowRun
import games.xploit.ghactions.auth.NoTokenException
import games.xploit.ghactions.auth.TokenProvider
import games.xploit.ghactions.git.GitHubRepoCoordinates
import games.xploit.ghactions.git.RepoDetector
import games.xploit.ghactions.settings.GhActionsConfigurable
import games.xploit.ghactions.settings.GhActionsSettings
import games.xploit.ghactions.util.LogFormatter
import games.xploit.ghactions.util.LogLine
import games.xploit.ghactions.util.LogLineKind
import games.xploit.ghactions.util.TimeFormat
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class GhActionsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private class WorkflowItem(val workflow: Workflow?) {
        override fun toString() = workflow?.name ?: "All workflows"
    }

    private val settings get() = GhActionsSettings.getInstance().state

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val console: ConsoleView =
        TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
    private val repoCombo = ComboBox<GitHubRepoCoordinates>()
    private val workflowCombo = ComboBox<WorkflowItem>(DefaultComboBoxModel(arrayOf(WorkflowItem(null))))

    private var repos: List<GitHubRepoCoordinates> = emptyList()
    private var workflows: List<Workflow> = emptyList()
    private var lastBranch: String? = null
    private val logCache = ConcurrentHashMap<Long, String>()

    private var shownKey: String? = null
    private var shownVersion: String? = null
    private val detailsRequest = AtomicInteger()

    private var refreshing = false
    private var rebuilding = false
    private var suppressComboEvents = false
    private var lastRefresh = 0L
    @Volatile private var disposed = false
    private var ticker: ScheduledFuture<*>? = null

    private fun setup() {
        Disposer.register(this, console)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = RunTreeRenderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject as? RunData ?: return
                if (needsLoading(node)) loadJobs(node, data.run)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        tree.addTreeSelectionListener { showDetails(force = false) }
        TreeSpeedSearch.installOn(tree)
        PopupHandler.installPopupMenu(tree, createContextGroup(), "GhActionsTreePopup")

        repoCombo.addActionListener {
            if (suppressComboEvents) return@addActionListener
            workflows = emptyList()
            refresh(force = true)
        }
        workflowCombo.addActionListener { if (!suppressComboEvents) refresh() }

        val filters = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(repoCombo)
            add(workflowCombo)
        }
        val left = JPanel(BorderLayout()).apply {
            add(filters, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(false, "GhActions.splitter", 0.45f).apply {
            firstComponent = left
            secondComponent = console.component
        }
        setContent(splitter)

        val toolbar = ActionManager.getInstance().createActionToolbar("GhActionsToolbar", createToolbarGroup(), true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        project.messageBus.connect(this).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { ApplicationManager.getApplication().invokeLater { onGitChanged() } },
        )

        ticker = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            ApplicationManager.getApplication().invokeLater { autoRefreshTick() }
        }, 5, 5, TimeUnit.SECONDS)

        updateRepos(RepoDetector.detect(project))
        refresh(force = true)
    }

    override fun dispose() {
        disposed = true
        ticker?.cancel(false)
    }

    // ---------------------------------------------------------------- repositories & refresh

    private fun currentRepo(): GitHubRepoCoordinates? = repoCombo.selectedItem as? GitHubRepoCoordinates

    private fun updateRepos(newRepos: List<GitHubRepoCoordinates>) {
        val previous = currentRepo()
        repos = newRepos
        suppressComboEvents = true
        try {
            repoCombo.model = DefaultComboBoxModel(newRepos.toTypedArray())
            repoCombo.selectedItem = newRepos.firstOrNull { it.fullName == previous?.fullName } ?: newRepos.firstOrNull()
            repoCombo.isVisible = newRepos.size > 1
        } finally {
            suppressComboEvents = false
        }
        lastBranch = currentRepo()?.let { RepoDetector.currentBranch(project, it) }
    }

    private fun onGitChanged() {
        if (disposed) return
        val detected = RepoDetector.detect(project)
        if (detected.map { it.fullName } != repos.map { it.fullName }) {
            updateRepos(detected)
            workflows = emptyList()
            refresh(force = true)
            return
        }
        val branch = currentRepo()?.let { RepoDetector.currentBranch(project, it) }
        if (branch != lastBranch) {
            lastBranch = branch
            if (settings.currentBranchOnly) refresh()
        }
    }

    private fun autoRefreshTick() {
        if (disposed || !isShowing || refreshing) return
        val interval = settings.refreshSeconds.coerceAtLeast(5) * 1000L
        if (System.currentTimeMillis() - lastRefresh >= interval) refresh()
    }

    private data class RefreshResult(
        val workflows: List<Workflow>?,
        val runs: List<WorkflowRun>,
        val jobs: Map<Long, List<Job>>,
    )

    fun refresh(force: Boolean = false) {
        val repo = currentRepo()
        if (repo == null) {
            root.removeAllChildren()
            model.reload()
            tree.emptyText.text = "No GitHub remote found in this project"
            return
        }
        if (refreshing) return
        refreshing = true

        val workflowId = (workflowCombo.selectedItem as? WorkflowItem)?.workflow?.id
        val branch = if (settings.currentBranchOnly) RepoDetector.currentBranch(project, repo) else null
        val expandedRuns = expandedRunIds()
        val perPage = settings.runsPerPage.coerceIn(1, 100)
        val loadWorkflows = force || workflows.isEmpty()
        if (root.childCount == 0) tree.emptyText.text = "Loading…"

        background(repo, { api ->
            val wf = if (loadWorkflows) api.workflows() else null
            val runs = api.runs(workflowId, branch, perPage)
            val jobs = runs.filter { it.id in expandedRuns }.associate { it.id to api.jobs(it.id) }
            RefreshResult(wf, runs, jobs)
        }, onSuccess = { result ->
            refreshing = false
            lastRefresh = System.currentTimeMillis()
            if (currentRepo() != repo) return@background
            result.workflows?.let(::updateWorkflows)
            rebuildTree(result.runs, result.jobs, expandedRuns)
            tree.emptyText.text = if (result.runs.isEmpty()) "No workflow runs" else ""
        }, onError = { e ->
            refreshing = false
            lastRefresh = System.currentTimeMillis()
            showTreeError(e)
            if (force && e !is NoTokenException) notify("Failed to load runs: ${e.message}", NotificationType.ERROR)
        })
    }

    private fun showTreeError(e: Throwable) {
        root.removeAllChildren()
        model.reload()
        tree.emptyText.clear()
        tree.emptyText.appendLine(e.message ?: e.toString(), SimpleTextAttributes.ERROR_ATTRIBUTES, null)
        if (e is NoTokenException || (e is GitHubApiException && e.statusCode == 401)) {
            tree.emptyText.appendLine("Open settings", SimpleTextAttributes.LINK_ATTRIBUTES) { openSettings() }
        }
        tree.emptyText.appendLine("Retry", SimpleTextAttributes.LINK_ATTRIBUTES) { refresh(force = true) }
    }

    private fun updateWorkflows(list: List<Workflow>) {
        workflows = list.sortedBy { it.name.lowercase() }
        val selectedId = (workflowCombo.selectedItem as? WorkflowItem)?.workflow?.id
        val items = listOf(WorkflowItem(null)) + workflows.map { WorkflowItem(it) }
        suppressComboEvents = true
        try {
            workflowCombo.model = DefaultComboBoxModel(items.toTypedArray())
            workflowCombo.selectedItem = items.firstOrNull { it.workflow?.id == selectedId } ?: items.first()
        } finally {
            suppressComboEvents = false
        }
    }

    // ---------------------------------------------------------------- tree

    private fun runNodes(): List<DefaultMutableTreeNode> =
        root.children().toList().filterIsInstance<DefaultMutableTreeNode>()

    private fun expandedRunIds(): Set<Long> = runNodes()
        .filter { tree.isExpanded(TreePath(it.path)) }
        .mapNotNull { (it.userObject as? RunData)?.run?.id }
        .toSet()

    private fun expandedJobIds(): Set<Long> = runNodes()
        .flatMap { it.children().toList().filterIsInstance<DefaultMutableTreeNode>() }
        .filter { tree.isExpanded(TreePath(it.path)) }
        .mapNotNull { (it.userObject as? JobData)?.job?.id }
        .toSet()

    private fun rebuildTree(runs: List<WorkflowRun>, jobsByRun: Map<Long, List<Job>>, expandedRuns: Set<Long>) {
        val selectedKey = selectedData()?.key
        val expandedJobs = expandedJobIds()
        rebuilding = true
        try {
            root.removeAllChildren()
            for (run in runs) {
                val runNode = DefaultMutableTreeNode(RunData(run))
                val jobs = jobsByRun[run.id]
                if (jobs != null) fillJobs(runNode, run, jobs) else runNode.add(DefaultMutableTreeNode(LoadingData))
                root.add(runNode)
            }
            model.reload()
            for (runNode in runNodes()) {
                val run = (runNode.userObject as RunData).run
                if (run.id !in expandedRuns) continue
                tree.expandPath(TreePath(runNode.path))
                for (jobNode in runNode.children().toList().filterIsInstance<DefaultMutableTreeNode>()) {
                    val job = (jobNode.userObject as? JobData)?.job ?: continue
                    if (job.id in expandedJobs) tree.expandPath(TreePath(jobNode.path))
                }
            }
            selectedKey?.let(::findNode)?.let { tree.selectionPath = TreePath(it.path) }
        } finally {
            rebuilding = false
        }
        showDetails(force = false)
    }

    private fun findNode(key: String): DefaultMutableTreeNode? =
        root.depthFirstEnumeration().toList().filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { (it.userObject as? NodeData)?.key == key }

    private fun fillJobs(runNode: DefaultMutableTreeNode, run: WorkflowRun, jobs: List<Job>) {
        if (jobs.isEmpty()) {
            runNode.add(DefaultMutableTreeNode(MessageData("No jobs yet")))
            return
        }
        for (job in jobs) {
            val jobNode = DefaultMutableTreeNode(JobData(run, job))
            job.steps.orEmpty().forEach { jobNode.add(DefaultMutableTreeNode(StepData(run, job, it))) }
            runNode.add(jobNode)
        }
    }

    private fun needsLoading(node: DefaultMutableTreeNode): Boolean =
        node.childCount == 1 && (node.firstChild as DefaultMutableTreeNode).userObject === LoadingData

    private fun loadJobs(node: DefaultMutableTreeNode, run: WorkflowRun) {
        val repo = currentRepo() ?: return
        background(repo, { it.jobs(run.id) }, onSuccess = { jobs ->
            if (node.parent == null) return@background // tree was rebuilt meanwhile
            node.removeAllChildren()
            fillJobs(node, run, jobs)
            model.nodeStructureChanged(node)
            tree.expandPath(TreePath(node.path))
        }, onError = { e ->
            if (node.parent == null) return@background
            node.removeAllChildren()
            node.add(DefaultMutableTreeNode(MessageData("Failed to load jobs: ${e.message}")))
            model.nodeStructureChanged(node)
        })
    }

    private fun selectedData(): NodeData? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? NodeData

    private fun selectedRun(): WorkflowRun? = when (val d = selectedData()) {
        is RunData -> d.run
        is JobData -> d.run
        is StepData -> d.run
        null -> null
    }

    private fun selectedJob(): Job? = when (val d = selectedData()) {
        is JobData -> d.job
        is StepData -> d.job
        else -> null
    }

    // ---------------------------------------------------------------- details / logs

    private fun showDetails(force: Boolean) {
        if (rebuilding) return
        val data = selectedData() ?: return
        if (!force && data.key == shownKey && data.version == shownVersion) return
        shownKey = data.key
        shownVersion = data.version
        val request = detailsRequest.incrementAndGet()
        when (data) {
            is RunData -> printRunSummary(data.run)
            is JobData -> showLog(data.job, null, request, force)
            is StepData -> showLog(data.job, data.step, request, force)
        }
    }

    private fun printRunSummary(run: WorkflowRun) {
        console.clear()
        fun line(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            console.print(label.padEnd(11), ConsoleViewContentType.SYSTEM_OUTPUT)
            console.print(value + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
        }
        line("Workflow:", run.name)
        line("Title:", run.displayTitle)
        line("Run:", "#${run.runNumber}" + if (run.runAttempt > 1) " (attempt ${run.runAttempt})" else "")
        line("Status:", StatusIcons.label(run.status, run.conclusion))
        line("Event:", run.event)
        line("Branch:", run.headBranch)
        line("Commit:", run.headSha.take(10))
        line("Actor:", run.actor?.login)
        line("Started:", TimeFormat.pretty(run.runStartedAt ?: run.createdAt))
        line("Duration:", TimeFormat.duration(run.runStartedAt, if (run.isCompleted) run.updatedAt else null))
        console.print("URL:".padEnd(11), ConsoleViewContentType.SYSTEM_OUTPUT)
        console.printHyperlink(run.htmlUrl, OpenUrlHyperlinkInfo(run.htmlUrl))
        console.print("\n\nSelect a job or a step to see its log.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    private fun showLog(job: Job, step: Step?, request: Int, force: Boolean) {
        val cached = if (force) null else logCache[job.id]
        if (cached != null) {
            renderLog(cached, job, step)
            return
        }
        console.clear()
        console.print("Loading log of '${job.name}'…\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        val repo = currentRepo() ?: return
        background(repo, { it.jobLog(job.id) }, onSuccess = { log ->
            if (job.isCompleted) logCache[job.id] = log
            if (request == detailsRequest.get()) renderLog(log, job, step)
        }, onError = { e ->
            if (request != detailsRequest.get()) return@background
            console.clear()
            if (!job.isCompleted) {
                console.print(
                    "Job is ${StatusIcons.label(job.status, job.conclusion)}. GitHub serves logs through the API " +
                        "once the job is finished; the view updates automatically.\n\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT,
                )
                printSteps(job)
            } else {
                console.print("Failed to load log: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
            job.htmlUrl?.let {
                console.print("\nOpen in browser: ", ConsoleViewContentType.SYSTEM_OUTPUT)
                console.printHyperlink(it, OpenUrlHyperlinkInfo(it))
                console.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
            }
        })
    }

    private fun printSteps(job: Job) {
        for (s in job.steps.orEmpty()) {
            console.print(
                "  ${s.number}. ${s.name} — ${StatusIcons.label(s.status, s.conclusion)}\n",
                ConsoleViewContentType.NORMAL_OUTPUT,
            )
        }
    }

    private fun renderLog(raw: String, job: Job, step: Step?) {
        console.clear()
        val all = LogFormatter.parse(raw)
        val lines = if (step != null) LogFormatter.linesForStep(all, step) else all
        if (step != null) {
            console.print(
                "── ${job.name} › ${step.number}. ${step.name} (${StatusIcons.label(step.status, step.conclusion)}) ──\n",
                ConsoleViewContentType.SYSTEM_OUTPUT,
            )
            if (lines.isEmpty()) {
                console.print("No log output for this step.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                return
            }
        }
        printLines(lines)
    }

    private fun printLines(lines: List<LogLine>) {
        val timestamps = settings.showTimestamps
        val buffer = StringBuilder()
        var bufferType: ConsoleViewContentType? = null
        fun flush() {
            if (buffer.isNotEmpty()) console.print(buffer.toString(), bufferType ?: ConsoleViewContentType.NORMAL_OUTPUT)
            buffer.setLength(0)
        }
        for (line in lines) {
            if (line.isHidden) continue
            val type = when (line.kind) {
                LogLineKind.GROUP, LogLineKind.COMMAND -> ConsoleViewContentType.SYSTEM_OUTPUT
                LogLineKind.ERROR -> ConsoleViewContentType.ERROR_OUTPUT
                LogLineKind.WARNING -> ConsoleViewContentType.LOG_WARNING_OUTPUT
                LogLineKind.NOTICE -> ConsoleViewContentType.LOG_INFO_OUTPUT
                LogLineKind.DEBUG -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
                LogLineKind.NORMAL -> ConsoleViewContentType.NORMAL_OUTPUT
            }
            if (type != bufferType) {
                flush()
                bufferType = type
            }
            if (timestamps && line.time != null) buffer.append(line.time.toString().take(19).replace('T', ' ')).append("  ")
            buffer.append(line.text).append('\n')
        }
        flush()
    }

    // ---------------------------------------------------------------- commands

    private fun runWorkflow() {
        val repo = currentRepo() ?: return
        val candidates = workflows.filter { it.state == "active" && !it.path.startsWith("dynamic/") }
            .filter { wf -> readWorkflowFile(repo, wf)?.let { "workflow_dispatch" in it } ?: true }
        if (candidates.isEmpty()) {
            Messages.showInfoMessage(project, "No workflows with a 'workflow_dispatch' trigger found.", "Run Workflow")
            return
        }
        val initial = (workflowCombo.selectedItem as? WorkflowItem)?.workflow
            ?: selectedRun()?.let { run -> candidates.firstOrNull { it.id == run.workflowId } }
        val defaultRef = RepoDetector.currentBranch(project, repo) ?: selectedRun()?.headBranch ?: "main"
        val dialog = RunWorkflowDialog(project, candidates, initial, defaultRef) { readWorkflowFile(repo, it) }
        if (!dialog.showAndGet()) return
        val workflow = dialog.selectedWorkflow
        val ref = dialog.ref
        val inputs = dialog.inputs
        apiCommand("Run '${workflow.name}' on $ref") { it.dispatch(workflow.id, ref, inputs) }
    }

    private fun readWorkflowFile(repo: GitHubRepoCoordinates, workflow: Workflow): String? =
        findWorkflowFile(repo, workflow.path)?.let { file ->
            runReadActionBlocking { runCatching { VfsUtilCore.loadText(file) }.getOrNull() }
        }

    private fun findWorkflowFile(repo: GitHubRepoCoordinates, path: String?): VirtualFile? {
        if (path.isNullOrBlank()) return null
        return repo.root?.findFileByRelativePath(path.substringBefore('@'))
    }

    private fun openWorkflowFile() {
        val repo = currentRepo() ?: return
        val path = selectedRun()?.path ?: (workflowCombo.selectedItem as? WorkflowItem)?.workflow?.path
        val file = findWorkflowFile(repo, path)
        if (file == null) {
            notify("Workflow file ${path ?: ""} not found in the project", NotificationType.WARNING)
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun cancelRun() {
        val run = selectedRun() ?: return
        val answer = Messages.showYesNoDialog(
            project, "Cancel run #${run.runNumber} of '${run.name}'?", "Cancel Workflow Run", Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) apiCommand("Cancel run #${run.runNumber}") { it.cancel(run.id) }
    }

    private fun apiCommand(title: String, call: (GitHubApiClient) -> Unit) {
        val repo = currentRepo() ?: return
        background(repo, call, onSuccess = {
            notify("$title: request accepted", NotificationType.INFORMATION)
            refreshSoon()
        }, onError = { e ->
            notify("$title failed: ${e.message}", NotificationType.ERROR)
        })
    }

    private fun refreshSoon() {
        // GitHub needs a moment to register a new run/attempt.
        for (delay in listOf(3L, 10L)) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                ApplicationManager.getApplication().invokeLater { if (!disposed) refresh() }
            }, delay, TimeUnit.SECONDS)
        }
    }

    private fun openSettings() =
        ShowSettingsUtil.getInstance().showSettingsDialog(project, GhActionsConfigurable::class.java)

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("GitHub Actions")
            .createNotification(message, type)
            .notify(project)
    }

    /** Runs [task] on a pooled thread with an API client; callbacks run on EDT unless the panel is disposed. */
    private fun <T> background(
        repo: GitHubRepoCoordinates,
        task: (GitHubApiClient) -> T,
        onSuccess: (T) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val token = TokenProvider.token(repo.host) ?: throw NoTokenException()
                task(GitHubApiClient(repo, token))
            }
            if ((result.exceptionOrNull() as? GitHubApiException)?.statusCode == 401) TokenProvider.invalidate()
            ApplicationManager.getApplication().invokeLater {
                if (!disposed) result.fold(onSuccess, onError)
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun action(text: String, icon: Icon?, enabled: () -> Boolean = { true }, perform: () -> Unit): AnAction =
        object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private fun toggle(text: String, icon: Icon, get: () -> Boolean, set: (Boolean) -> Unit): AnAction =
        object : DumbAwareToggleAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = get()
            override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
        }

    private val refreshAction = action("Refresh", AllIcons.Actions.Refresh) {
        logCache.clear()
        refresh(force = true)
        showDetails(force = true)
    }
    private val runWorkflowAction = action("Run Workflow…", AllIcons.Actions.Execute, { currentRepo() != null }) { runWorkflow() }
    private val rerunAllAction = action("Re-run All Jobs", AllIcons.Actions.Restart, { selectedRun()?.isCompleted == true }) {
        selectedRun()?.let { run -> apiCommand("Re-run #${run.runNumber}") { it.rerun(run.id, debug = false) } }
    }
    private val rerunFailedAction = action(
        "Re-run Failed Jobs", AllIcons.RunConfigurations.RerunFailedTests, { selectedRun()?.isFailed == true },
    ) {
        selectedRun()?.let { run -> apiCommand("Re-run failed jobs of #${run.runNumber}") { it.rerunFailedJobs(run.id, debug = false) } }
    }
    private val rerunJobAction = action(
        "Re-run Selected Job", AllIcons.Actions.Rerun, { selectedJob()?.isCompleted == true && selectedRun()?.isCompleted == true },
    ) {
        selectedJob()?.let { job -> apiCommand("Re-run job '${job.name}'") { it.rerunJob(job.id, debug = false) } }
    }
    private val rerunDebugAction = action(
        "Re-run with Debug Logging", AllIcons.Actions.StartDebugger, { selectedRun()?.isCompleted == true },
    ) {
        val job = selectedJob()
        val run = selectedRun()
        when {
            job != null -> apiCommand("Re-run job '${job.name}' with debug logging") { it.rerunJob(job.id, debug = true) }
            run != null -> apiCommand("Re-run #${run.runNumber} with debug logging") { it.rerun(run.id, debug = true) }
        }
    }
    private val cancelAction = action("Cancel Run", AllIcons.Actions.Suspend, { selectedRun()?.let { !it.isCompleted } == true }) {
        cancelRun()
    }
    private val openInBrowserAction = action("Open on GitHub", AllIcons.Ide.External_link_arrow, { currentRepo() != null }) {
        val url = selectedJob()?.htmlUrl ?: selectedRun()?.htmlUrl ?: currentRepo()?.let { "${it.webUrl}/actions" }
        url?.let(BrowserUtil::browse)
    }
    private val openFileAction = action("Open Workflow File", AllIcons.Actions.EditSource, {
        selectedRun() != null || (workflowCombo.selectedItem as? WorkflowItem)?.workflow != null
    }) { openWorkflowFile() }

    private fun createToolbarGroup() = DefaultActionGroup().apply {
        add(refreshAction)
        addSeparator()
        add(runWorkflowAction)
        add(rerunAllAction)
        add(rerunFailedAction)
        add(rerunJobAction)
        add(rerunDebugAction)
        add(cancelAction)
        addSeparator()
        add(openInBrowserAction)
        add(openFileAction)
        addSeparator()
        add(toggle("Current Branch Only", AllIcons.Vcs.Branch, { settings.currentBranchOnly }) {
            settings.currentBranchOnly = it
            refresh()
        })
        add(toggle("Show Timestamps in Log", AllIcons.Vcs.History, { settings.showTimestamps }) {
            settings.showTimestamps = it
            shownVersion = null
            showDetails(force = false)
        })
        add(action("Settings", AllIcons.General.Settings) { openSettings() })
    }

    private fun createContextGroup() = DefaultActionGroup().apply {
        add(rerunAllAction)
        add(rerunFailedAction)
        add(rerunJobAction)
        add(rerunDebugAction)
        add(cancelAction)
        addSeparator()
        add(openInBrowserAction)
        add(openFileAction)
        addSeparator()
        add(refreshAction)
    }

    // Must stay last: actions above are initialized in declaration order.
    init {
        setup()
    }
}
