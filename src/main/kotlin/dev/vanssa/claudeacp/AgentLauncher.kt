package dev.vanssa.claudeacp

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A script that starts the agent, written next to `acp.json` and named as the entry's
 * `command`, with no `args` (issues #34, #22).
 *
 * The chat's login button does not run what the package names in `_meta["terminal-auth"]`
 * (the full `node npx-cli.js … --cli auth login`). It takes the method's plain ACP form,
 * `args: ["--cli", "auth", "login", "--claudeai"]`, and runs it against the entry's
 * `command` alone, dropping the entry's `args`. With `node` as the command that is
 * `node --cli …`, and node stops with `bad option: --cli`. Air 263.8311 logs it as
 * `command=[…/bin/node, --cli, auth, login, --claudeai]`. A command that starts the agent
 * by itself and passes its own arguments on turns the same call into
 * `npx … <package> --cli auth login --claudeai`, which the package hands to the Claude CLI.
 *
 * The script also puts node's directory on `PATH` itself, because the login terminal is
 * not known to apply the entry's `env`, and `npx-cli.js` needs it there (see [NodeRuntime]).
 */
object AgentLauncher {

    /** Shared by every IDE, like `acp.json` itself: the last IDE to start writes it. */
    private val hostDir: Path = AcpConfigFile.path.resolveSibling(DIR_NAME)

    /** The launcher's path on the host, or null when it could not be written. */
    fun writeHost(runtime: NodeRuntime, npxArgs: List<String>): Path? = runCatching {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        val launcher = hostDir.resolve(if (windows) "agent.cmd" else "agent")
        val content = if (windows) {
            windowsLauncher(runtime.node.toString(), runtime.binDir.toString(), npxArgs)
        } else {
            posixLauncher(runtime.node.toString(), runtime.binDir.toString(), npxArgs)
        }
        // Left alone when unchanged: cmd.exe reads a batch file line by line while it runs,
        // so rewriting one a running agent was started from can make it run a fragment.
        if (!launcher.exists() || launcher.readText() != content) {
            Files.createDirectories(hostDir)
            // Renamed into place, so a running script keeps reading the file it opened.
            val temporary = Files.createTempFile(hostDir, "agent", ".tmp")
            temporary.writeText(content)
            temporary.toFile().setExecutable(true, false)
            Files.move(temporary, launcher, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        launcher
    }.onFailure { LOG.warn("Could not write the agent launcher in $hostDir", it) }.getOrNull()

    /**
     * Writes the launcher inside a WSL distribution and returns its path there, or null.
     *
     * The IDE runs a WSL entry's `command` inside the distribution as a path of it, so the
     * script has to live there too. Fed through `wsl.exe` on stdin for the reason
     * [WslNodeResolver] gives for its probe.
     */
    fun writeWsl(distribution: String, runtime: WslNodeRuntime, npxArgs: List<String>): String? = runCatching {
        val script = wslInstallScript(posixLauncher(runtime.node, runtime.binDir, npxArgs))
        val process = ProcessBuilder("wsl.exe", "-d", distribution, "-e", "sh", "-s")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.outputStream.use { it.write(script.toByteArray(Charsets.UTF_8)) }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader(Charsets.UTF_8).readText().lineSequence()
            .map { it.trimEnd('\r') }
            .firstNotNullOfOrNull { it.removePrefix(LAUNCHER_MARKER).takeIf { path -> path != it && path.startsWith("/") } }
    }.onFailure { LOG.warn("Could not write the agent launcher in WSL $distribution", it) }.getOrNull()

    /**
     * `exec`, so the agent replaces the shell and gets the IDE's signals directly.
     * Every value single-quoted: a path may hold spaces or `$`.
     */
    internal fun posixLauncher(node: String, binDir: String, npxArgs: List<String>): String = buildString {
        append("#!/bin/sh\n")
        append(HEADER.lines().joinToString("") { "# $it\n" })
        append("PATH=${shQuote(binDir)}:\"\$PATH\"\n")
        append("export PATH\n")
        append("exec ${(listOf(node) + npxArgs).joinToString(" ", transform = ::shQuote)} \"\$@\"\n")
    }

    /**
     * `@echo off` comes first because stdout is the agent's JSON-RPC channel: an echoed
     * command line would be read as a broken message. `%` is doubled because cmd expands
     * it even inside quotes.
     */
    internal fun windowsLauncher(node: String, binDir: String, npxArgs: List<String>): String = buildString {
        append("@echo off\r\n")
        append(HEADER.lines().joinToString("") { "rem $it\r\n" })
        append("set \"PATH=${cmdEscape(binDir)};%PATH%\"\r\n")
        append((listOf(node) + npxArgs).joinToString(" ") { "\"${cmdEscape(it)}\"" })
        append(" %*\r\n")
    }

    /**
     * The installer run inside a distribution. The launcher travels in a quoted heredoc,
     * which the shell copies without expanding anything in it.
     */
    internal fun wslInstallScript(launcher: String): String = buildString {
        append("dir=\"\$HOME/.jetbrains/$DIR_NAME\"\n")
        append("mkdir -p \"\$dir\" || exit 1\n")
        append("tmp=\"\$dir/agent.tmp.\$\$\"\n")
        append("cat > \"\$tmp\" <<'$HEREDOC_END'\n")
        append(launcher)
        append("$HEREDOC_END\n")
        append("chmod 755 \"\$tmp\" && mv -f \"\$tmp\" \"\$dir/agent\" && echo \"$LAUNCHER_MARKER\$dir/agent\"\n")
    }

    private fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun cmdEscape(value: String): String = value.replace("%", "%%")

    private const val DIR_NAME = "claude-subscription"
    private const val LAUNCHER_MARKER = "__ACP_LAUNCHER__="
    private const val HEREDOC_END = "__ACP_LAUNCHER_END__"
    private val HEADER = """
        Starts the Claude Subscription agent. Rewritten by the plugin on every IDE start.
        The IDE runs this with extra arguments, e.g. `--cli auth login`, for the login button.
    """.trimIndent()
    private val LOG = logger<AgentLauncher>()
}
