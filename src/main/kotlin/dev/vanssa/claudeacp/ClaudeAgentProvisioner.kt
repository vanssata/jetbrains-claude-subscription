package dev.vanssa.claudeacp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers the Claude ACP agent in `~/.jetbrains/acp.json` on startup.
 *
 * The whole point of the plugin: the agent JetBrains bundles is launched with
 * `--hide-claude-auth`, which removes the "Claude Subscription" auth method *and*
 * rejects subscription credentials outright at session start. Running the very same
 * official package without that flag restores both.
 *
 * The command is recomputed on every startup instead of being frozen, because the
 * fallback node runtime lives at a version-pinned path that changes when the IDE
 * updates it.
 */
class ClaudeAgentProvisioner : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (provisionedThisSession.compareAndSet(false, true)) {
            warnWithoutHost()
            provisionHost()
        }

        // Each WSL distribution gets its entry the first time one of its projects opens,
        // not for every installed distribution: an entry for a distribution the user never
        // opens a project in would only be a dead item in the agent picker.
        val distribution = wslDistributionOf(project.basePath) ?: return
        if (wslDistributions.add(distribution)) provisionWsl(distribution)
    }

    /**
     * Writes the entries for the current settings. Also called by the settings page on
     * apply: the IDE watches `acp.json`, so a change takes effect without a restart.
     */
    fun provision() {
        provisionHost()
        wslDistributions.forEach(::provisionWsl)
    }

    /**
     * Neither host is a required dependency (issue #24), so the plugin also loads where no
     * chat can show the agent. Said once rather than left silent; the entry is still written,
     * so installing a host later needs no restart of this plugin's work.
     */
    private fun warnWithoutHost() {
        // `isLoaded`, not `getPlugin(...).isEnabled`: the verifier flags `getPlugin` as internal
        // API and `isEnabled` as deprecated. A plugin is loaded only when it is enabled.
        if (HOST_PLUGINS.any { PluginManagerCore.isLoaded(PluginId.getId(it)) }) return
        LOG.warn("Neither AI Assistant nor Air is enabled; no chat will list the agent")
        notify(
            NotificationType.WARNING,
            "No AI chat to show the agent in",
            "The Claude Subscription agent appears in the chat of the JetBrains AI Assistant or Air plugin. " +
                "Install or enable one of them.",
        )
    }

    private fun provisionHost() {
        val settings = ClaudeAcpSettings.getInstance()
        if (!settings.state.manageAgent) {
            LOG.info("Agent management disabled in settings; leaving ${AcpConfigFile.path} alone")
            return
        }

        val runtime = NodeRuntimeResolver.resolve()
        if (runtime == null) {
            val floor = NodeRuntimeResolver.MIN_NODE_MAJOR
            LOG.warn("No node >= $floor found; cannot register the Claude ACP agent")
            notify(
                NotificationType.WARNING,
                "No Node.js $floor or later found",
                "The Claude Subscription agent needs Node.js $floor or later. Install it, or run one of the " +
                    "bundled ACP agents once so the IDE downloads its own runtime, then restart.",
            )
            return
        }

        val entry =
            buildEntry(runtime.node.toString(), runtime.npxArgs(settings.packageSpec), runtime.pathWithBinDir(), settings.model)
        write(settings.displayName, entry, runtime.node.toString()) {
            "Added \"${settings.displayName}\" to the AI chat agent list. " +
                "Pick it there and authenticate with your Claude subscription."
        }
    }

    /**
     * The IDE reads only the host's `acp.json`, also for a project inside WSL, and runs the
     * entry's `command` inside the distribution. The host entry names a Windows node there
     * and cannot start (issue #22), so the distribution gets an entry of its own, with its
     * own node, under a name the user picks in that project's chat.
     */
    private fun provisionWsl(distribution: String) {
        val settings = ClaudeAcpSettings.getInstance()
        if (!settings.state.manageAgent) return

        val name = wslAgentName(settings.displayName, distribution)
        val runtime = WslNodeResolver.resolve(distribution)
        if (runtime == null) {
            val floor = NodeRuntimeResolver.MIN_NODE_MAJOR
            LOG.warn("No node >= $floor found in WSL $distribution; cannot register $name")
            notify(
                NotificationType.WARNING,
                "No Node.js $floor or later found in WSL $distribution",
                "Projects in $distribution run the agent inside the distribution, with its own Node.js. " +
                    "Install Node.js $floor or later there, then reopen the project.",
            )
            return
        }

        val entry = buildEntry(runtime.node, runtime.npxArgs(settings.packageSpec), runtime.path(), settings.model)
        write(name, entry, "${runtime.node} in WSL $distribution") {
            "Added \"$name\" to the AI chat agent list. Pick it in projects inside $distribution — " +
                "\"${settings.displayName}\" runs on Windows and cannot start there. " +
                "The agent signs in separately inside the distribution."
        }
    }

    private fun write(name: String, entry: JsonObject, nodeDescription: String, added: () -> String) {
        when (val outcome = AcpConfigFile.upsertAgent(name, entry)) {
            AcpConfigFile.Outcome.UNCHANGED ->
                LOG.info("$name already up to date in ${AcpConfigFile.path}")

            AcpConfigFile.Outcome.WRITTEN -> {
                LOG.info("Registered $name in ${AcpConfigFile.path} using $nodeDescription")
                notify(NotificationType.INFORMATION, "Claude Subscription agent ready", added())
            }

            AcpConfigFile.Outcome.REFUSED_UNPARSEABLE -> {
                LOG.warn("Refusing to rewrite unparseable ${AcpConfigFile.path} (outcome=$outcome)")
                notify(
                    NotificationType.ERROR,
                    "Could not register the Claude Subscription agent",
                    "${AcpConfigFile.path} is not valid JSON. It was left untouched so nothing " +
                        "else in it is lost — fix or delete the file and restart.",
                )
            }
        }
    }

    /**
     * Note what is *absent*: `--hide-claude-auth`. Everything else mirrors how the IDE
     * launches the bundled agent.
     */
    private fun buildEntry(command: String, npxArgs: List<String>, path: String, model: String?): JsonObject {
        val args = JsonArray().apply { npxArgs.forEach(::add) }

        // `npx-cli.js` spawns helpers via `#!/usr/bin/env node`, so the runtime's bin
        // directory has to be on PATH — an absolute `command` alone is not enough.
        val env = JsonObject().apply {
            addProperty("PATH", path)
            // The package ranks `ANTHROPIC_MODEL` above `settings.json`'s `model`, so
            // this is the one knob that changes the IDE session without touching the CLI.
            if (model != null) addProperty("ANTHROPIC_MODEL", model)
        }

        return JsonObject().apply {
            addProperty("command", command)
            add("args", args)
            add("env", env)
            addProperty("use_idea_mcp", true)
            addProperty("use_custom_mcp", true)
        }
    }

    private fun notify(type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(null)
    }

    private companion object {
        val LOG = logger<ClaudeAgentProvisioner>()
        const val NOTIFICATION_GROUP = "Subscription ACP Agent"

        /** `ProjectActivity` runs per opened project; the config is application-wide. */
        val provisionedThisSession = AtomicBoolean(false)

        /** AI Assistant and Air: both read `~/.jetbrains/acp.json` for local agents. */
        val HOST_PLUGINS = listOf("com.intellij.ml.llm", "com.intellij.air")

        /** Distributions whose entry this session wrote, rewritten on settings apply. */
        val wslDistributions: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
