package dev.vanssa.claudeacp

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(
    name = "ClaudeSubscriptionAcpSettings",
    storages = [Storage("claude-subscription-acp.xml")],
)
class ClaudeAcpSettings : SimplePersistentStateComponent<ClaudeAcpSettings.State>(State()) {

    class State : BaseState() {
        /** When off, the plugin stops touching `acp.json` and leaves the entry to the user. */
        var manageAgent: Boolean by property(true)

        /** Also the source of the agent id the IDE derives — see [ClaudeAgent.matches]. */
        var displayName: String? by string(DEFAULT_DISPLAY_NAME)

        /**
         * Pinned rather than floating: the guard this plugin works around lives in this
         * package, so an unattended major bump could change behaviour silently.
         */
        var packageSpec: String? by string(DEFAULT_PACKAGE_SPEC)

        /**
         * Main-session model for the IDE agent, passed as `ANTHROPIC_MODEL`. Blank means
         * no override: the package then takes `model` from `~/.claude/settings.json`, which
         * is shared with the CLI and may hold a choice meant for the terminal only (e.g.
         * a 1M-context variant). The env var only moves the main thread — subagents keep
         * the `model:` from their own definitions, and those without one inherit this.
         */
        var model: String? by string("")
    }

    val displayName: String get() = state.displayName ?: DEFAULT_DISPLAY_NAME
    val packageSpec: String get() = state.packageSpec ?: DEFAULT_PACKAGE_SPEC
    val model: String? get() = state.model?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val DEFAULT_DISPLAY_NAME: String = "Claude Subscription"
        const val DEFAULT_PACKAGE_SPEC: String = "@agentclientprotocol/claude-agent-acp@0.62.0"

        fun getInstance(): ClaudeAcpSettings = service()
    }
}

object ClaudeAgent {

    /**
     * Whether [agentId] is the agent this plugin provisions.
     *
     * The IDE derives the id from the display name — "Claude Subscription" was observed
     * to become `acp.claude-subscription`. The exact slug rule is not documented, so the
     * comparison strips the `acp.` prefix and every separator on both sides instead of
     * reimplementing a guess at it.
     */
    fun matches(agentId: String, displayName: String): Boolean =
        normalize(agentId.removePrefix("acp.")) == normalize(displayName)

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }
}
