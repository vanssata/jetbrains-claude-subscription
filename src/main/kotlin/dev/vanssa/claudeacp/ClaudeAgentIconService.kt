package dev.vanssa.claudeacp

import com.intellij.icons.AllIcons
import com.intellij.ml.llm.core.chat.ui.AgentIconService
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Gives the provisioned agent the Claude icon.
 *
 * `acp.json` has no icon field — icons exist only on registry catalog entries, which the
 * IDE downloads and manages itself. This extension point is the one remaining seam.
 *
 * **Why this delegates.** `AgentIconService.loadIcon` resolves through
 * `EP_NAME.extensionList.firstNotNullOf { it.loadIconForAgent(agentId) }`, but
 * `loadIconForAgent` is declared to return a non-null `Icon`. So the first registered
 * extension always answers and nothing ever falls through to the next one. Registering
 * with `order="first"` is therefore the only way to be consulted at all — and it puts
 * this class in the path of *every* agent's icon, not just ours. Everything we do not
 * own is handed straight back to whichever service would have answered otherwise.
 *
 * This is internal AI Assistant API, not a published contract — hence a build range
 * (`262`–`263.*`) that covers only the branches it has been verified on. If it breaks,
 * the agent itself keeps working; only the icon is lost.
 */
class ClaudeAgentIconService : AgentIconService {

    override fun loadIconForAgent(agentId: String): Icon {
        if (ClaudeAgent.matches(agentId, ClaudeAcpSettings.getInstance().displayName)) {
            return CLAUDE_ICON
        }
        return delegateToOthers(agentId)
    }

    private fun delegateToOthers(agentId: String): Icon =
        EP_NAME.extensionList
            .asSequence()
            .filter { it !== this }
            .mapNotNull { other -> runCatching { other.loadIconForAgent(agentId) }.getOrNull() }
            .firstOrNull()
            ?: fallback(agentId)

    private fun fallback(agentId: String): Icon {
        // Only reachable if this is the sole registered implementation. The platform's
        // own `firstNotNullOf` would have thrown NoSuchElementException here, so a
        // generic icon is strictly better than propagating that.
        LOG.warn("No other AgentIconService answered for '$agentId'; using a generic icon")
        return AllIcons.Nodes.Plugin
    }

    private companion object {
        val LOG = logger<ClaudeAgentIconService>()

        /**
         * Declared here rather than read off `AgentIconService.Companion`, so this does not
         * depend on the visibility of a member of internal API.
         */
        val EP_NAME: ExtensionPointName<AgentIconService> =
            ExtensionPointName.create("com.intellij.ml.llm.core.chat.ui.agentIconService")

        val CLAUDE_ICON: Icon = IconLoader.getIcon("/icons/claude.svg", ClaudeAgentIconService::class.java)
    }
}
