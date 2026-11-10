package dev.vanssa.claudeacp

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The IDE's local-agent config, `~/.jetbrains/acp.json`.
 *
 * The IDE resolves this path itself as `user.home` + `.jetbrains` + `acp.json` and
 * watches the file, so a write is picked up without restarting.
 *
 * Everything here is a *merge*: the file is shared with any other locally defined
 * agent and with `default_mcp_settings`, so only our own key under `agent_servers`
 * is ever touched.
 */
object AcpConfigFile {

    private const val AGENT_SERVERS = "agent_servers"

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    val path: Path = Paths.get(System.getProperty("user.home"), ".jetbrains", "acp.json")

    /** Kept as the pristine pre-plugin state — written once and never overwritten. */
    private val backupPath: Path = path.resolveSibling("acp.json.before-claude-subscription-plugin")

    enum class Outcome {
        /** The file already held exactly this entry. */
        UNCHANGED,
        WRITTEN,

        /**
         * The file exists but could not be parsed. Nothing was written: overwriting it
         * would silently drop the user's other agents and MCP settings.
         */
        REFUSED_UNPARSEABLE,
    }

    /** Stores [entry] under [name], leaving every other key intact. */
    @Synchronized
    fun upsertAgent(name: String, entry: JsonObject): Outcome {
        val root = read() ?: return Outcome.REFUSED_UNPARSEABLE
        val servers = root.getAsJsonObject(AGENT_SERVERS) ?: JsonObject().also { root.add(AGENT_SERVERS, it) }

        if (servers.getAsJsonObject(name) == entry) return Outcome.UNCHANGED

        servers.add(name, entry)
        write(root)
        return Outcome.WRITTEN
    }

    /** Drops our entry, leaving the rest of the file alone. */
    fun removeAgent(name: String): Outcome = removeAgents { it == name }

    /** Drops every entry whose name matches [which], leaving the rest of the file alone. */
    @Synchronized
    fun removeAgents(which: (String) -> Boolean): Outcome {
        val root = read() ?: return Outcome.REFUSED_UNPARSEABLE
        val servers = root.getAsJsonObject(AGENT_SERVERS) ?: return Outcome.UNCHANGED
        val names = servers.keySet().filter(which)
        if (names.isEmpty()) return Outcome.UNCHANGED

        names.forEach(servers::remove)
        write(root)
        return Outcome.WRITTEN
    }

    /**
     * Returns the parsed root, an empty object when the file does not exist yet, or
     * null when it exists but is not a readable JSON object — the caller must not
     * write in that case.
     */
    private fun read(): JsonObject? {
        if (!path.exists()) return JsonObject()

        val parsed = runCatching { JsonParser.parseString(path.readText()) }.getOrNull() ?: return null
        return parsed.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun write(root: JsonObject) {
        path.createParentDirectories()

        // Preserve whatever was there before this plugin ever ran, exactly once.
        if (path.exists() && !backupPath.exists()) {
            runCatching { Files.copy(path, backupPath) }
        }

        path.writeText(gson.toJson(root) + "\n")
    }
}
