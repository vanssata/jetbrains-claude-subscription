package dev.vanssa.claudeacp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.selected

/** `Settings → Tools → Claude Subscription`: the state that used to be XML-only. */
class ClaudeAcpConfigurable : BoundConfigurable("Claude Subscription") {

    private val settings = ClaudeAcpSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        lateinit var manage: JBCheckBox
        row {
            manage = checkBox("Manage the agent entry in ~/.jetbrains/acp.json")
                .bindSelected({ settings.state.manageAgent }, { settings.state.manageAgent = it })
                .comment("Off: the plugin stops touching the file and the entry is yours to edit.")
                .component
        }
        group("Agent") {
            row("Model:") {
                // Editable: the list is the aliases in common use, not every id the package
                // accepts, and a full model id typed here is passed through unchanged.
                comboBox(MODEL_CHOICES, textListCellRenderer { it?.ifEmpty { SETTINGS_JSON_DEFAULT } })
                    .applyToComponent { isEditable = true }
                    .bindItem({ settings.state.model.orEmpty() }, { settings.state.model = it?.trim().orEmpty() })
                    .comment(
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
                    .comment("Also the agent id the IDE derives, which the icon is matched on.")
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
        val MODEL_CHOICES = listOf("", "opus", "opus[1m]", "sonnet", "haiku", "fable[1m]")
        const val SETTINGS_JSON_DEFAULT = "(from ~/.claude/settings.json)"
    }
}
