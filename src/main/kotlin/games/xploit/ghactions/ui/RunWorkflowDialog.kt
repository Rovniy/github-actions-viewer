package games.xploit.ghactions.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import games.xploit.ghactions.api.Workflow
import games.xploit.ghactions.util.WorkflowDispatchInfo
import games.xploit.ghactions.util.WorkflowInputsParser
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Asks for the workflow, the ref and the `workflow_dispatch` inputs (read from the local workflow file).
 * @param readWorkflowFile returns the local content of the workflow file, or null if it is not in the project.
 */
class RunWorkflowDialog(
    project: Project,
    workflows: List<Workflow>,
    initial: Workflow?,
    defaultRef: String,
    private val readWorkflowFile: (Workflow) -> String?,
) : DialogWrapper(project) {

    private val workflowCombo = ComboBox(workflows.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { "${it.name}  (${it.path.substringAfterLast('/')})" }
    }
    private val refField = JBTextField(defaultRef)
    private val inputsPanel = JPanel(BorderLayout())
    private val inputGetters = linkedMapOf<String, () -> String>()
    private var dispatchInfo: WorkflowDispatchInfo? = null

    val selectedWorkflow: Workflow get() = workflowCombo.selectedItem as Workflow
    val ref: String get() = refField.text.trim()
    val inputs: Map<String, String> get() = inputGetters.mapValues { it.value() }.filterValues { it.isNotEmpty() }

    init {
        title = "Run Workflow"
        setOKButtonText("Run")
        if (initial != null && initial in workflows) workflowCombo.selectedItem = initial
        workflowCombo.addActionListener { rebuildInputs() }
        init()
        rebuildInputs()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Workflow:", workflowCombo)
            .addLabeledComponent("Branch or tag:", refField)
            .addComponent(inputsPanel)
            .panel
            .apply { preferredSize = JBUI.size(520, preferredSize.height) }

    override fun getPreferredFocusedComponent(): JComponent = refField

    private fun rebuildInputs() {
        inputsPanel.removeAll()
        inputGetters.clear()
        val workflow = workflowCombo.selectedItem as? Workflow ?: return
        val text = readWorkflowFile(workflow)
        val info = text?.let(WorkflowInputsParser::parse)
        dispatchInfo = info

        val form = FormBuilder.createFormBuilder()
        when {
            info == null -> form.addComponent(hint("${workflow.path} is not found locally, inputs can't be detected."))
            !info.hasDispatch -> form.addComponent(
                hint("This workflow has no 'workflow_dispatch' trigger, GitHub will reject the request.")
            )
            info.inputs.isNotEmpty() -> form.addSeparator()
        }
        for (input in info?.inputs.orEmpty()) {
            val component: JComponent = when (input.type) {
                "boolean" -> JBCheckBox(null, input.default.equals("true", ignoreCase = true)).also { cb ->
                    inputGetters[input.name] = { cb.isSelected.toString() }
                }
                "choice" -> ComboBox(input.options.toTypedArray()).also { combo ->
                    input.default?.let { combo.selectedItem = it }
                    inputGetters[input.name] = { combo.selectedItem?.toString().orEmpty() }
                }
                else -> JBTextField(input.default.orEmpty()).also { field ->
                    inputGetters[input.name] = { field.text }
                }
            }
            component.toolTipText = input.description
            form.addLabeledComponent(input.name + (if (input.required) " *" else "") + ":", component)
            input.description?.let { form.addComponentToRightColumn(hint(it), 0) }
        }
        inputsPanel.add(form.panel, BorderLayout.CENTER)
        inputsPanel.revalidate()
        inputsPanel.repaint()
        if (isShowing) pack()
    }

    override fun doValidate(): ValidationInfo? {
        if (ref.isEmpty()) return ValidationInfo("Specify a branch or tag", refField)
        val missing = dispatchInfo?.inputs.orEmpty()
            .filter { it.required && it.type != "boolean" && inputGetters[it.name]?.invoke().isNullOrBlank() }
        if (missing.isNotEmpty()) return ValidationInfo("Required inputs are empty: ${missing.joinToString { it.name }}")
        return null
    }

    private fun hint(text: String) = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }
}
