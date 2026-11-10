package dev.vanssa.claudeacp

import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.TimeUnit

/**
 * A node runtime inside a WSL distribution, as paths of that distribution.
 *
 * Strings rather than [java.nio.file.Path]: the IDE runs this entry's `command` inside the
 * distribution and parses it as a Linux path there (issue #22 failed on exactly that, with
 * `C:\Program Files\nodejs\node.exe: Not a valid absolute path on WslEelDescriptor`). A
 * Windows `Path` would turn every `/` into `\`.
 */
data class WslNodeRuntime(val binDir: String, val npxCli: String, val prefix: String?, val loginPath: String) {

    val node: String get() = "$binDir/node"

    /**
     * The IDE lays the entry's `env` over the login shell's environment, so a PATH here
     * replaces the shell's. It is rebuilt from it with [binDir] in front, for the
     * `#!/usr/bin/env node` re-exec described on [NodeRuntime].
     */
    fun path(): String = if (loginPath.isEmpty()) binDir else "$binDir:$loginPath"

    fun npxArgs(packageSpec: String): List<String> = npxArgs(npxCli, prefix, packageSpec)
}

/**
 * Finds node inside a WSL distribution by running [PROBE] there through `wsl.exe`.
 *
 * Probing from the Windows side over `\\wsl.localhost` cannot work: the files are readable,
 * but a Linux binary cannot be started from Windows to ask its version, and the PATH that
 * matters is the one the distribution's login shell builds. The IDE launches the agent
 * with that same environment, so the probe asks for it rather than guessing.
 */
object WslNodeResolver {

    fun resolve(distribution: String): WslNodeRuntime? = runCatching {
        // CRLF stripped: a checkout with `core.autocrlf` would hand sh `\r` at every line end.
        val script = WslNodeResolver::class.java.getResourceAsStream(PROBE)
            ?.use { it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8) }
            ?: return@runCatching null
        // Fed on stdin: a script passed as an argument would go through Windows command-line
        // quoting, which mangles its quotes and newlines.
        val process = ProcessBuilder(
            "wsl.exe",
            "-d",
            distribution,
            "-e",
            "sh",
            "-s",
            "--",
            NodeRuntimeResolver.MIN_NODE_MAJOR.toString(),
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { it.write(script) }
        // Generous: the first call after Windows starts boots the distribution, and the probe
        // starts an interactive login shell, which runs the user's rc files.
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            LOG.warn("Node probe in WSL $distribution timed out")
            return@runCatching null
        }
        parseProbe(process.inputStream.bufferedReader(Charsets.UTF_8).readText())
    }.onFailure { LOG.warn("Node probe in WSL $distribution failed", it) }.getOrNull()

    /** Null unless the probe found a usable node; [WslNodeRuntime.prefix] is optional. */
    internal fun parseProbe(output: String): WslNodeRuntime? {
        val values = output.lineSequence()
            .map { it.trimEnd('\r') }
            .mapNotNull { line -> MARKER.matchEntire(line)?.destructured?.let { (key, value) -> key to value } }
            .toMap()
        val bin = values["BIN"]?.takeIf { it.startsWith("/") } ?: return null
        val npx = values["NPX"]?.takeIf { it.startsWith("/") } ?: return null
        return WslNodeRuntime(
            binDir = bin.trimEnd('/').ifEmpty { "/" },
            npxCli = npx,
            prefix = values["PREFIX"]?.takeIf { it.startsWith("/") },
            loginPath = values["PATH"].orEmpty(),
        )
    }

    private val MARKER = Regex("""__ACP_([A-Z]+)__=(.*)""")
    private const val PROBE = "/dev/vanssa/claudeacp/wsl-node-probe.sh"
    private val LOG = logger<WslNodeResolver>()
}

/**
 * The WSL distribution a project lives in, or null for anything else.
 *
 * The IDE opens a WSL project through its UNC path, `\\wsl.localhost\<distro>\...` or the
 * older `\\wsl$\<distro>\...`; `Project.basePath` carries it with forward slashes. Parsed
 * here rather than through the platform's WSL classes so the plugin stays on API the
 * plugin verifier passes on every IDE in the build range.
 */
internal fun wslDistributionOf(projectPath: String?): String? {
    if (projectPath == null) return null
    val match = WSL_UNC.find(projectPath.replace('\\', '/')) ?: return null
    return match.groupValues[1].takeIf { it.isNotBlank() }
}

private val WSL_UNC = Regex("""^//(?:wsl\.localhost|wsl\$)/([^/]+)(?:/.*)?$""", RegexOption.IGNORE_CASE)

/**
 * The name of the agent entry for [distribution]. A separate entry rather than a second
 * command in the one entry: the IDE reads a single `acp.json`, the one on the Windows host,
 * for every project, and an entry has exactly one `command`, run as a path of whichever
 * machine the project is on.
 */
internal fun wslAgentName(displayName: String, distribution: String): String = "$displayName$WSL_INFIX$distribution)"

/** True for the WSL entries [wslAgentName] derives from [displayName]. */
internal fun isWslAgentName(name: String, displayName: String): Boolean =
    name.startsWith("$displayName$WSL_INFIX") && name.endsWith(")")

private const val WSL_INFIX = " (WSL: "
