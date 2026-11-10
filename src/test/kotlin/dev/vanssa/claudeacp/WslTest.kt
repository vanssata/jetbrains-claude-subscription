package dev.vanssa.claudeacp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WslTest {

    /** The path from the report in issue #22, in both spellings the IDE uses. */
    @Test
    fun `the distribution is read from a WSL project path`() {
        assertEquals("NixOS", wslDistributionOf("//wsl.localhost/NixOS/home/maicol07/Projects/Web/osm_custom"))
        assertEquals("NixOS", wslDistributionOf("""\\wsl.localhost\NixOS\home\maicol07\Projects\Web\osm_custom"""))
        assertEquals("Ubuntu-24.04", wslDistributionOf("//wsl$/Ubuntu-24.04/home/me"))
        assertEquals("Ubuntu", wslDistributionOf("//WSL.LOCALHOST/Ubuntu"))
    }

    @Test
    fun `other projects have no distribution`() {
        assertNull(wslDistributionOf(null))
        assertNull(wslDistributionOf("C:/Users/me/project"))
        assertNull(wslDistributionOf("/home/me/project"))
        assertNull(wslDistributionOf("//server/share/project"))
    }

    @Test
    fun `WSL entries are recognised by the display name they derive from`() {
        val name = wslAgentName("Claude Subscription", "NixOS")
        assertEquals("Claude Subscription (WSL: NixOS)", name)
        assertTrue(isWslAgentName(name, "Claude Subscription"))
        assertFalse(isWslAgentName("Claude Subscription", "Claude Subscription"))
        assertFalse(isWslAgentName(name, "Claude"))
    }

    /** Banner and rc-file noise around the markers must not matter, nor CRLF line ends. */
    @Test
    fun `the probe output is parsed into Linux paths`() {
        val output = """
            Welcome to NixOS!
            __ACP_PATH__=/run/wrappers/bin:/etc/profiles/per-user/me/bin
            __ACP_PREFIX__=/home/me/.npm-global
            __ACP_BIN__=/etc/profiles/per-user/me/bin
            __ACP_NPX__=/nix/store/abc-nodejs-22.11.0/lib/node_modules/npm/bin/npx-cli.js
        """.trimIndent().replace("\n", "\r\n")

        val runtime = WslNodeResolver.parseProbe(output)!!
        assertEquals("/etc/profiles/per-user/me/bin/node", runtime.node)
        assertEquals("/etc/profiles/per-user/me/bin:/run/wrappers/bin:/etc/profiles/per-user/me/bin", runtime.path())
        assertEquals(
            listOf(
                "/nix/store/abc-nodejs-22.11.0/lib/node_modules/npm/bin/npx-cli.js",
                "--prefix",
                "/home/me/.npm-global",
                "-y",
                "pkg@1.0.0",
            ),
            runtime.npxArgs("pkg@1.0.0"),
        )
    }

    @Test
    fun `no runtime when the probe found no node`() {
        assertNull(WslNodeResolver.parseProbe("__ACP_PATH__=/usr/bin:/bin\n"))
        assertNull(WslNodeResolver.parseProbe(""))
        // A relative value would be resolved against the IDE's working directory.
        assertNull(WslNodeResolver.parseProbe("__ACP_BIN__=bin\n__ACP_NPX__=/x/npx-cli.js\n"))
    }

    @Test
    fun `the prefix flag is left out when npm named none`() {
        val runtime = WslNodeResolver.parseProbe("__ACP_BIN__=/usr/bin\n__ACP_NPX__=/usr/lib/node_modules/npm/bin/npx-cli.js\n")!!
        assertEquals(listOf("/usr/lib/node_modules/npm/bin/npx-cli.js", "-y", "pkg"), runtime.npxArgs("pkg"))
        assertEquals("/usr/bin", runtime.path())
    }
}
