package dev.vanssa.claudeacp

import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * A node installation able to run npm's `npx-cli.js`.
 *
 * [binDir] has to end up on the agent process' `PATH`, not just be used to build an
 * absolute [node] path: `npx-cli.js` re-execs its helpers through
 * `#!/usr/bin/env node`, so the children look `node` up on `PATH` and otherwise die
 * with `env: 'node': No such file or directory`.
 */
data class NodeRuntime(val binDir: Path, val node: Path, val npxCli: Path) {

    /** The inherited `PATH` with [binDir] in front, for any process that runs `npx-cli.js`. */
    fun pathWithBinDir(): String {
        val inherited = System.getenv("PATH").orEmpty()
        return if (inherited.isEmpty()) binDir.toString() else binDir.toString() + File.pathSeparator + inherited
    }

    /** What follows [node] on the command line to run [packageSpec]. */
    fun npxArgs(packageSpec: String): List<String> = npxArgs(npxCli, globalPrefix(), packageSpec)

    /**
     * npm's global prefix for this runtime, or null when npm does not name a usable one.
     *
     * Asked rather than derived from [binDir]: a `prefix` line in the user's `.npmrc`, or
     * the Windows installer's `%APPDATA%\npm`, moves it away from the node install. Run
     * from [binDir] because `npm config` fails on the same `devEngines` check as npx when
     * its working directory is such a project.
     */
    private fun globalPrefix(): Path? = runCatching {
        val npmCli = npxCli.resolveSibling("npm-cli.js").toString()
        val process = ProcessBuilder(node.toString(), npmCli, "config", "get", "prefix")
            .directory(binDir.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["PATH"] = pathWithBinDir() }
            .start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        val prefix = Paths.get(process.inputStream.bufferedReader().readText().trim())
        // npm fails with `ENOENT … lstat` on a prefix that does not exist, and on Windows
        // `%APPDATA%\npm` does not until the first global install creates it.
        if (prefix.isAbsolute) Files.createDirectories(prefix) else null
    }.getOrNull()
}

/**
 * `--prefix` is there for projects whose `package.json` pins another package manager
 * through `devEngines` (issue #21). The IDE starts the agent in the project root, npm
 * takes the nearest `package.json` as its local prefix, and `npx` then refuses to run
 * with `EBADDEVENGINES`. Naming a prefix on the command line is the only way to point
 * npm elsewhere without changing the working directory, which the agent needs.
 *
 * It is the global prefix, not an empty directory of the plugin's own: npx exports
 * `--prefix` to the agent as `npm_config_prefix`, where every `npm` the agent runs reads
 * it as the *global* prefix. npx exports the global prefix under that name anyway, so
 * passing the same value changes nothing for the agent, while any other directory would
 * redirect its `npm install -g` and hide the global `npmrc`.
 *
 * Without a [prefix] the flag is left out: the agent then starts everywhere it did before.
 */
internal fun npxArgs(npxCli: Path, prefix: Path?, packageSpec: String): List<String> =
    buildList {
        add(npxCli.toString())
        if (prefix != null) {
            add("--prefix")
            add(prefix.toString())
        }
        add("-y")
        add(packageSpec)
    }

/**
 * Finds a node runtime without requiring the user to install one.
 *
 * A system node is preferred when present, then one installed by a version manager
 * or Homebrew outside `PATH`; either has to be node >= [MIN_NODE_MAJOR]. The fallback
 * is the runtime the IDE
 * downloads for its own ACP agents — on a machine whose only node is that one, this
 * is what makes the plugin work at all. Its path carries the node version
 * (`.../.runtimes/node/24.13.0/bin`), so it is resolved on every startup rather than
 * frozen into the agent config.
 */
object NodeRuntimeResolver {

    fun resolve(): NodeRuntime? = fromSystemPath() ?: fromVersionManagers() ?: fromIdeRuntimes()

    /**
     * Reads `PATH` through [EnvironmentUtil] rather than [System.getenv]: an IDE started
     * from the macOS Dock gets launchd's bare `/usr/bin:/bin:/usr/sbin:/sbin`, and only
     * the shell environment the platform loads at startup carries what `.zshrc` added.
     * Blocks until that load finishes, so callers stay off the EDT. The process `PATH`
     * is still scanned after it, so a node found before this change is never lost.
     *
     * A node on `PATH` is the user's choice, but one below [MIN_NODE_MAJOR] cannot be
     * honoured: with nvm-windows on node 14 its npx 6 rejected the arguments with
     * "You must supply a command" while the IDE's own runtime sat unused (issue #20).
     * Such an entry is skipped, not treated as the end of the search, so a newer node
     * further down `PATH` still wins.
     */
    private fun fromSystemPath(): NodeRuntime? {
        val paths = listOfNotNull(EnvironmentUtil.getValue("PATH"), System.getenv("PATH")).distinct()
        return paths.asSequence()
            .flatMap { it.splitToSequence(File.pathSeparatorChar) }
            .filter { it.isNotBlank() }
            // Inside runCatching: on Windows a PATH entry with quotes or other illegal
            // characters makes Paths.get throw, and one bad entry must not hide node.
            .mapNotNull { runCatching { runtimeAt(Paths.get(it.trim().removeSurrounding("\""))) }.getOrNull() }
            .distinct()
            .firstOrNull(::isNewEnough)
    }

    /**
     * The shell `PATH` is not always enough. A vfox user saw "No Node.js runtime found"
     * with node working in every terminal (issue #7): vfox and mise activate from a
     * prompt hook, which a shell started only to read its environment may never run,
     * and the platform skips the shell read entirely when it fails or times out. The
     * install directories are fixed, so they are probed directly, in roughly the order
     * a shell would put them on `PATH`: version managers before Homebrew.
     *
     * Nothing here is a choice the user made for this shell, so a stale install (an old
     * nvm `v16`, a leftover `/usr/local/bin/node`) is likely, and it must not shadow the
     * IDE's working runtime: each candidate has to report node >= [MIN_NODE_MAJOR].
     */
    private fun fromVersionManagers(): NodeRuntime? {
        if (IS_WINDOWS) return null
        val home = Paths.get(System.getProperty("user.home"))
        // vfox's global version: `sdks/nodejs` is a link to the selected install.
        val current = sequenceOf(home.resolve(".vfox/sdks/nodejs/bin"))
        val versioned = sequenceOf(
            ".local/share/mise/installs/node" to "bin",
            ".asdf/installs/nodejs" to "bin",
            ".nvm/versions/node" to "bin",
            ".local/share/fnm/node-versions" to "installation/bin",
            "Library/Application Support/fnm/node-versions" to "installation/bin",
            ".volta/tools/image/node" to "bin",
            ".nodenv/versions" to "bin",
        ).flatMap { (versionsDir, bin) ->
            home.resolve(versionsDir).childDirectories()
                .sortedWith(compareBy(VERSION_ORDER) { it.name })
                .reversed()
                .map { it.resolve(bin) }
        }
        // Only macOS: on Linux these are on the process PATH already, and a node there
        // that PATH does not reach is more likely stale than wanted.
        val homebrew = if (IS_MAC) sequenceOf(Paths.get("/opt/homebrew/bin"), Paths.get("/usr/local/bin")) else emptySequence()
        return (current + versioned + homebrew)
            .mapNotNull { runCatching { runtimeAt(it) }.getOrNull() }
            .firstOrNull(::isNewEnough)
    }

    private fun isNewEnough(runtime: NodeRuntime): Boolean = (majorVersion(runtime.node) ?: 0) >= MIN_NODE_MAJOR

    /**
     * Asks the binary rather than trusting a directory name: vfox's link and Homebrew's
     * `bin` carry no version at all. Null when node does not answer within the timeout.
     */
    private fun majorVersion(node: Path): Int? = runCatching {
        val process = ProcessBuilder(node.toString(), "--version").redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        parseMajor(process.inputStream.bufferedReader().readText())
    }.getOrNull()

    /** `v24.13.0` -> 24. Null for anything else, e.g. the error text of a broken shim. */
    internal fun parseMajor(versionOutput: String): Int? =
        versionOutput.trim().removePrefix("v").substringBefore('.').toIntOrNull()

    private fun fromIdeRuntimes(): NodeRuntime? =
        ideCacheRoots()
            .flatMap { root -> root.childDirectories() }
            .map { ide -> ide.resolve("acp-agents/.runtimes/node") }
            .filter { it.isDirectory() }
            .flatMap { it.childDirectories() }
            .sortedWith(compareBy(VERSION_ORDER) { it.name })
            .reversed()
            .firstNotNullOfOrNull { dir ->
                // The official Windows distribution has no bin/ directory, so the
                // version directory itself is tried as well.
                runCatching { runtimeAt(dir.resolve("bin")) ?: runtimeAt(dir) }.getOrNull()
            }

    /**
     * Both POSIX locations are checked because the IDE cache moves on macOS; Windows
     * keeps it under `%LOCALAPPDATA%\JetBrains`.
     */
    private fun ideCacheRoots(): List<Path> {
        val home = Paths.get(System.getProperty("user.home"))
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: home.resolve("AppData/Local")
        return listOf(
            home.resolve(".cache/JetBrains"),
            home.resolve("Library/Caches/JetBrains"),
            localAppData.resolve("JetBrains"),
        ).filter { it.isDirectory() }
    }

    /**
     * Returns a runtime rooted at [binDir], or null when it is not a usable node install.
     *
     * Two layouts exist. POSIX tarballs put `bin/node` next to `lib/node_modules/npm`.
     * The Windows distribution (and nvm-windows / nvm4w, whose `nodejs` directory is a
     * link to one) is flat: `node.exe` and `node_modules\npm` share one directory.
     * Checking only the POSIX layout is what made Windows report "No Node.js runtime
     * found" with node plainly on PATH (issue #1).
     */
    private fun runtimeAt(binDir: Path): NodeRuntime? {
        // Only `node.exe` on Windows: there isExecutable is true for any readable file,
        // so an extensionless `node` shell stub (Git Bash, npm shims) would be picked.
        val node = binDir.resolve(if (IS_WINDOWS) "node.exe" else "node")
        if (!node.isRegularFile() || !node.isExecutable()) return null

        val npxCli = listOfNotNull(
            binDir.parent?.resolve("lib/node_modules/npm/bin/npx-cli.js"),
            binDir.resolve("node_modules/npm/bin/npx-cli.js").takeIf { IS_WINDOWS },
        ).firstOrNull { it.isRegularFile() } ?: return null

        // Debian's `share/nodejs/npm` layout is deliberately not accepted: those packages
        // ship node 18, the ACP package needs >= 22, and npx only warns about it — so
        // matching it would shadow the IDE's newer runtime and crash the agent.
        return NodeRuntime(binDir = binDir, node = node, npxCli = npxCli)
    }

    private val IS_WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val IS_MAC = System.getProperty("os.name").orEmpty().startsWith("Mac")

    /** The ACP package's `engines` floor; npx only warns below it and the agent then crashes. */
    internal const val MIN_NODE_MAJOR = 22

    private fun Path.childDirectories(): List<Path> =
        runCatching { listDirectoryEntries().filter { it.isDirectory() } }.getOrElse { emptyList() }

    /**
     * Orders `24.9.0` before `24.13.0`; a plain string sort would not. Non-numeric
     * segments sort as 0 so an odd directory name cannot throw. The leading `v` is
     * dropped because nvm names its directories `v24.13.0`.
     */
    private val VERSION_ORDER: Comparator<String> = Comparator { left, right ->
        val a = left.removePrefix("v").split('.')
        val b = right.removePrefix("v").split('.')
        var result = 0
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.toIntOrNull() ?: 0
            result = x.compareTo(y)
            if (result != 0) break
        }
        result
    }
}
