package dev.vanssa.claudeacp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class NodeRuntimeTest {

    private val npxCli = Paths.get("/opt/node/lib/node_modules/npm/bin/npx-cli.js")

    /** npm reads everything after the package spec as the agent's own arguments. */
    @Test
    fun `the prefix comes before the package spec`() {
        assertEquals(
            listOf(npxCli.toString(), "--prefix", Paths.get("/opt/node").toString(), "-y", "pkg@1.0.0"),
            npxArgs(npxCli, Paths.get("/opt/node"), "pkg@1.0.0"),
        )
    }

    /** An empty or made-up `--prefix` value would stop the agent from starting at all. */
    @Test
    fun `no prefix flag when npm did not name one`() {
        assertEquals(listOf(npxCli.toString(), "-y", "pkg@1.0.0"), npxArgs(npxCli, null, "pkg@1.0.0"))
    }

    @Test
    fun `the major version is read from node's own output`() {
        assertEquals(24, NodeRuntimeResolver.parseMajor("v24.13.0\n"))
        assertEquals(14, NodeRuntimeResolver.parseMajor("v14.21.3\r\n"))
    }

    /** Null fails the version floor, so a shim that prints an error is not picked. */
    @Test
    fun `output that is not a version has no major`() {
        assertNull(NodeRuntimeResolver.parseMajor(""))
        assertNull(NodeRuntimeResolver.parseMajor("node: command not found"))
    }
}
