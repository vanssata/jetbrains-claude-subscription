package dev.vanssa.claudeacp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class AgentLauncherTest {

    private val posix = !System.getProperty("os.name").orEmpty().startsWith("Windows")

    /**
     * The login button runs the launcher with `--cli auth login --claudeai` (issue #34):
     * those have to land after the package spec, and the values must survive spaces and
     * quotes. `/bin/echo` stands in for node so the test needs no Node.js.
     */
    @Test
    fun `the posix launcher passes its own arguments on after the package`(@TempDir dir: Path) {
        assumeTrue(posix)
        val launcher = dir.resolve("agent")
        launcher.writeText(
            AgentLauncher.posixLauncher(
                "/bin/echo",
                "/opt/my node/bin",
                listOf("/x/npx-cli.js", "--prefix", "/it's", "-y", "pkg@1"),
            ),
        )

        val output = run("sh", launcher.toString(), "--cli", "auth", "login", "--claudeai")

        assertEquals("/x/npx-cli.js --prefix /it's -y pkg@1 --cli auth login --claudeai", output.trim())
    }

    @Test
    fun `the posix launcher puts node's directory first on PATH`(@TempDir dir: Path) {
        assumeTrue(posix)
        val launcher = dir.resolve("agent")
        launcher.writeText(AgentLauncher.posixLauncher("/bin/sh", "/opt/my node/bin", listOf("-c", "echo \"\$PATH\"")))

        assertTrue(run("sh", launcher.toString()).startsWith("/opt/my node/bin:"))
    }

    /** stdout is the JSON-RPC channel, so cmd must not echo the command line onto it. */
    @Test
    fun `the windows launcher is silent and quotes every value`() {
        val launcher = AgentLauncher.windowsLauncher(
            """C:\Program Files\nodejs\node.exe""",
            """C:\Program Files\nodejs""",
            listOf(
                """C:\Program Files\nodejs\node_modules\npm\bin\npx-cli.js""",
                "--prefix",
                """C:\Users\100%\npm""",
                "-y",
                "pkg@1",
            ),
        )
        val lines = launcher.split("\r\n").filter { it.isNotEmpty() }

        assertEquals("@echo off", lines.first())
        assertEquals("""set "PATH=C:\Program Files\nodejs;%PATH%"""", lines[lines.size - 2])
        assertEquals(
            """"C:\Program Files\nodejs\node.exe" "C:\Program Files\nodejs\node_modules\npm\bin\npx-cli.js" """ +
                """"--prefix" "C:\Users\100%%\npm" "-y" "pkg@1" %*""",
            lines.last(),
        )
    }

    /** Runs the installer as `wsl.exe … sh -s` would, against a scratch `HOME`. */
    @Test
    fun `the WSL installer writes the launcher unchanged and names its path`(@TempDir home: Path) {
        assumeTrue(posix)
        val launcher = AgentLauncher.posixLauncher("/usr/bin/node", "/usr/bin", listOf("/n/npx-cli.js", "-y", "pkg@\$1"))

        val process = ProcessBuilder("sh", "-s").apply { environment()["HOME"] = home.toString() }.start()
        process.outputStream.use { it.write(AgentLauncher.wslInstallScript(launcher).toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        val written = home.resolve(".jetbrains/claude-subscription/agent")
        assertEquals("__ACP_LAUNCHER__=$written", output.trim())
        assertEquals(launcher, written.readText())
        assertTrue(written.toFile().canExecute())
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }
}
