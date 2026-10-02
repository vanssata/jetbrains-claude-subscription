package dev.vanssa.claudeacp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** One entry of the agent's model selector: [id] is what `ANTHROPIC_MODEL` takes. */
data class ModelChoice(val id: String, val name: String) {

    /** `id<TAB>name`: ids such as `opus[1m]` carry brackets but never a tab. */
    fun encode(): String = "$id\t$name"

    companion object {
        fun decode(entry: String): ModelChoice? =
            entry.split('\t', limit = 2).takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { ModelChoice(it[0], it[1]) }
    }
}

/**
 * Asks the ACP agent itself which models it offers, so the settings dropdown follows
 * what Anthropic ships instead of a list compiled into the plugin.
 *
 * There is no listing call in ACP: the agent reports its models only as the `model`
 * config option in the `session/new` response. That list is also account-specific —
 * it comes from the Claude Code runtime inside the package, which filters by plan —
 * so it cannot be reproduced by hard-coding or by Anthropic's public models endpoint,
 * which needs an API key a subscription user does not have.
 */
object AcpModelCatalog {

    /** Null when the agent could not be started, is not logged in, or answered oddly. */
    fun fetch(runtime: NodeRuntime, packageSpec: String): List<ModelChoice>? {
        val process = runCatching {
            ProcessBuilder(runtime.node.toString(), runtime.npxCli.toString(), "-y", packageSpec)
                .directory(workDir())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment()["PATH"] = runtime.pathWithBinDir() }
                .start()
        }.onFailure { LOG.warn("Could not start the ACP agent to list models", it) }.getOrNull() ?: return null

        // The first run downloads the package, so the budget is generous; killing the
        // process is also what unblocks readLine() below if the agent never answers.
        val watchdog = AppExecutorUtil.getAppScheduledExecutorService()
            .schedule({ destroyTree(process) }, TIMEOUT_SECONDS, TimeUnit.SECONDS)

        return try {
            val input = process.inputStream.bufferedReader()
            val output = process.outputStream.bufferedWriter()
            fun call(id: Int, method: String, params: String): JsonObject? {
                output.write("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
                output.newLine()
                output.flush()
                // Notifications (auth status, available commands) arrive interleaved
                // with the response and are skipped.
                while (true) {
                    val line = input.readLine() ?: return null
                    val message = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                    if (message.get("id")?.takeIf { it.isJsonPrimitive }?.asInt == id) return message
                }
            }

            call(1, "initialize", """{"protocolVersion":1,"clientCapabilities":{}}""") ?: return null
            val sessionParams = JsonObject().apply {
                addProperty("cwd", workDir().toString())
                add("mcpServers", JsonArray())
            }
            val session = call(2, "session/new", sessionParams.toString())
            session?.getAsJsonObject("result")?.let(::modelsOf)
                .also { if (it == null) LOG.info("Agent did not report models: ${session?.get("error")}") }
        } catch (e: Exception) {
            LOG.warn("Listing models from the ACP agent failed", e)
            null
        } finally {
            watchdog.cancel(false)
            destroyTree(process)
        }
    }

    /**
     * The models in a `session/new` result, or null when there are none. Null rather than
     * empty so a caller cannot mistake an odd answer for "this plan has no models" and
     * wipe the remembered list.
     */
    internal fun modelsOf(result: JsonObject): List<ModelChoice>? {
        val option = result.getAsJsonArray("configOptions")
            ?.map { it.asJsonObject }
            ?.firstOrNull { it.get("id")?.asString == "model" }
            ?: return null
        return option.getAsJsonArray("options")
            ?.map { it.asJsonObject }
            ?.mapNotNull { entry ->
                val id = entry.get("value")?.asString ?: return@mapNotNull null
                // "default" is the agent's own "no choice" marker; the blank entry in the
                // dropdown already means that, and the env var would not accept it.
                if (id == "default") return@mapNotNull null
                ModelChoice(id, entry.get("name")?.asString ?: id)
            }
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * `session/new` makes the Claude runtime create a project folder under
     * `~/.claude/projects` for its cwd. A fixed directory keeps that to one folder,
     * instead of one per query, and keeps any project's CLAUDE.md out of the session.
     */
    private fun workDir(): File =
        PathManager.getSystemDir().resolve("claude-subscription-models").toFile()
            .also { Files.createDirectories(it.toPath()) }

    /** `npx` runs the agent as a child, which in turn starts the Claude runtime. */
    private fun destroyTree(process: Process) {
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
    }

    private const val TIMEOUT_SECONDS = 90L
    private val LOG = logger<AcpModelCatalog>()
}
