package dev.vanssa.claudeacp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.selected
import javax.swing.DefaultComboBoxModel

/** `Settings → Tools → Subscription ACP Agent`: the state that used to be XML-only. */
class ClaudeAcpConfigurable : BoundConfigurable("Subscription ACP Agent") {

    private val settings = ClaudeAcpSettings.getInstance()
    private val modelItems = DefaultComboBoxModel<String>()
    private val names = mutableMapOf<String, String>()
    private var modelCombo: ComboBox<String>? = null

    override fun createPanel(): DialogPanel {
        showModels(settings.knownModels)
        refreshModels()
        return createSettingsPanel()
    }

    private fun createSettingsPanel(): DialogPanel = panel {
        lateinit var manage: JBCheckBox
        row {
            manage = checkBox("Manage the agent entry in ~/.jetbrains/acp.json")
                .bindSelected({ settings.state.manageAgent }, { settings.state.manageAgent = it })
                .comment("Off: the plugin stops touching the file and the entry is yours to edit.")
                .component
        }
        group("Agent") {
            row("Model:") {
                // Not editable: only ids the agent itself offered can be chosen, so a typo or
                // a retired alias never ends up in ANTHROPIC_MODEL.
                comboBox(modelItems, textListCellRenderer { label(it) })
                    .applyToComponent { modelCombo = this }
                    .bindItem({ settings.state.model.orEmpty() }, { settings.state.model = it?.trim().orEmpty() })
                    .comment(
                        "Listed by the agent for your plan, refreshed each time this page opens; " +
                            "empty until it has answered once while you are logged in. " +
                            "Blank uses <code>model</code> from ~/.claude/settings.json, shared with the CLI. " +
                            "Sets the main session only; a model picked in the chat panel still wins.",
                    )
            }
            row("Display name:") {
                textField()
                    .align(AlignX.FILL)
                    .bindText(
                        { settings.displayName },
                        { settings.state.displayName = it.trim().ifEmpty { ClaudeAcpSettings.DEFAULT_DISPLAY_NAME } },
                    )
                    .comment("Also the agent id the IDE derives. Renaming makes the IDE treat it as a new agent.")
            }
            row("ACP package:") {
                textField()
                    .align(AlignX.FILL)
                    .bindText(
                        { settings.packageSpec },
                        { settings.state.packageSpec = it.trim().ifEmpty { ClaudeAcpSettings.DEFAULT_PACKAGE_SPEC } },
                    )
                    .comment("Pinned on purpose. Default: ${ClaudeAcpSettings.DEFAULT_PACKAGE_SPEC}")
            }
        }.enabledIf(manage.selected)
        row {
            comment(
                "Effort and permission mode are session options of the agent itself; " +
                    "pick them in the chat panel.",
            )
        }
    }

    /**
     * Replaces the dropdown with what the agent reports. Runs on every page open rather
     * than on a schedule: starting the agent costs seconds of CPU, and this page is the
     * only place the list is shown.
     */
    private fun refreshModels() {
        val packageSpec = settings.packageSpec
        ApplicationManager.getApplication().executeOnPooledThread {
            val runtime = NodeRuntimeResolver.resolve() ?: return@executeOnPooledThread
            val models = AcpModelCatalog.fetch(runtime, packageSpec) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                settings.knownModels = models
                showModels(models)
            }, ModalityState.any())
        }
    }

    private fun showModels(models: List<ModelChoice>) {
        names.clear()
        models.forEach { names[it.id] = it.name }
        // Swapping the items resets the selection; the user may already have picked
        // something on this page, so it is carried over rather than re-read from the
        // settings.
        val combo = modelCombo
        val current = (combo?.selectedItem as? String) ?: settings.state.model.orEmpty()
        modelItems.removeAllElements()
        modelItems.addAll(dropdownItems(models.map { it.id }, current))
        if (combo != null) combo.selectedItem = current
    }

    private fun label(id: String?): String = when {
        id.isNullOrEmpty() -> SETTINGS_JSON_DEFAULT
        else -> names[id]?.let { "$it — $id" } ?: "$id $NOT_OFFERED"
    }

    override fun apply() {
        val previousName = settings.displayName
        super.apply()
        if (!settings.state.manageAgent) return

        ApplicationManager.getApplication().executeOnPooledThread {
            // Entries are keyed by display name, so a rename would otherwise leave the old
            // agent behind in the picker, still pointing at the old command.
            if (previousName != settings.displayName) AcpConfigFile.removeAgent(previousName)
            ClaudeAgentProvisioner().provision()
        }
    }

    private companion object {
        const val NOT_OFFERED = "(not offered by the agent)"
        const val SETTINGS_JSON_DEFAULT = "(from ~/.claude/settings.json)"
    }
}

/**
 * Blank first, then what the agent offers. A [current] value the agent no longer lists —
 * saved by an older version, or from a plan that has since changed — is kept as an
 * entry: the combo cannot show a value outside its items, and dropping it would let the
 * next Apply silently clear the user's choice.
 */
internal fun dropdownItems(offered: List<String>, current: String): List<String> {
    val items = listOf("") + offered.distinct()
    return if (current in items) items else items + current
}
