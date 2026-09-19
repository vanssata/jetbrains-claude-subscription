package dev.vanssa.claudeacp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.io.File
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
        if (!provisionedThisSession.compareAndSet(false, true)) return
        provision()
    }

    /**
     * Writes the entry for the current settings. Also called by the settings page on
     * apply: the IDE watches `acp.json`, so a change takes effect without a restart.
     */
    fun provision() {
        val settings = ClaudeAcpSettings.getInstance()
        if (!settings.state.manageAgent) {
            LOG.info("Agent management disabled in settings; leaving ${AcpConfigFile.path} alone")
            return
        }

        val runtime = NodeRuntimeResolver.resolve()
        if (runtime == null) {
            LOG.warn("No node runtime found; cannot register the Claude ACP agent")
            notify(
                NotificationType.WARNING,
                "No Node.js runtime found",
                "The Claude Subscription agent needs Node.js. Install it, or run one of the " +
                    "bundled ACP agents once so the IDE downloads its own runtime, then restart.",
            )
            return
        }

        val entry = buildEntry(runtime, settings.packageSpec, settings.model)
        when (val outcome = AcpConfigFile.upsertAgent(settings.displayName, entry)) {
            AcpConfigFile.Outcome.UNCHANGED ->
                LOG.info("Claude ACP agent already up to date in ${AcpConfigFile.path}")

            AcpConfigFile.Outcome.WRITTEN -> {
                LOG.info("Registered Claude ACP agent in ${AcpConfigFile.path} using ${runtime.node}")
                notify(
                    NotificationType.INFORMATION,
                    "Claude Subscription agent ready",
                    "Added \"${settings.displayName}\" to the AI chat agent list. " +
                        "Pick it there and authenticate with your Claude subscription.",
                )
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
    private fun buildEntry(runtime: NodeRuntime, packageSpec: String, model: String?): JsonObject {
        val args = JsonArray().apply {
            add(runtime.npxCli.toString())
            add("-y")
            add(packageSpec)
        }

        // `npx-cli.js` spawns helpers via `#!/usr/bin/env node`, so the runtime's bin
        // directory has to be on PATH — an absolute `command` alone is not enough.
        val inheritedPath = System.getenv("PATH").orEmpty()
        val env = JsonObject().apply {
            addProperty(
                "PATH",
                if (inheritedPath.isEmpty()) runtime.binDir.toString()
                else runtime.binDir.toString() + File.pathSeparator + inheritedPath,
            )
            // The package ranks `ANTHROPIC_MODEL` above `settings.json`'s `model`, so
            // this is the one knob that changes the IDE session without touching the CLI.
            if (model != null) addProperty("ANTHROPIC_MODEL", model)
        }

        return JsonObject().apply {
            addProperty("command", runtime.node.toString())
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
        const val NOTIFICATION_GROUP = "Claude Subscription ACP"

        /** `ProjectActivity` runs per opened project; the config is application-wide. */
        val provisionedThisSession = AtomicBoolean(false)
    }
}
