# Subscription ACP Agent

> **Unofficial plugin.** A personal community project — not affiliated with, endorsed by,
> or supported by JetBrains or Anthropic. Install it from
> [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/33150) or from the release zip
> below.

A JetBrains plugin that adds a **Claude Subscription** agent to AI chat — one that
authenticates with a Claude.ai Pro/Max subscription instead of demanding Anthropic
Console API billing.

Install it and the agent appears. There is nothing else to run.

## The problem it solves

JetBrains launches its bundled Claude agent like this:

```text
npx -y @agentclientprotocol/claude-agent-acp@0.85.1 --hide-claude-auth
```

That flag does two things inside the package:

1. removes `claude-ai-login` ("Claude Subscription") from the offered `authMethods`,
   leaving only Anthropic Console and gateway auth;
2. rejects subscription credentials outright when a session starts:

```js
if (shouldHideClaudeAuth() && initializationResult.account.subscriptionType && !this.gatewayAuthRequest) {
    throw RequestError.authRequired(undefined, "This integration does not support using claude.ai subscriptions.");
}
```

On a consumer Max/Pro account the Console login then just re-issues a subscription
token, the guard fires again, and the IDE shows:

> Authentication was reset. Please create a new chat for the change to take effect.

Creating a new chat cannot help — it is not a stale-session problem.

This plugin registers **the same official package** as a local agent, without the flag.
Nothing is patched or bypassed: the package supports subscriptions by default, which is
how other ACP clients use it. The restriction is JetBrains-side, applied to their own
bundled entry.

## Installation

### Before you start

| You need | Notes |
| --- | --- |
| A JetBrains IDE **2026.2 or 2026.3** (build 262.\* or 263.\*) | IntelliJ IDEA, PhpStorm, WebStorm, PyCharm and the other IDEs on those branches. |
| The **AI Assistant** plugin or the **Air** plugin, enabled | Either one is enough. Both read the agent from the same `~/.jetbrains/acp.json`. |
| A **Claude.ai Pro or Max** subscription | This agent exists to sign in with it. |
| **Node.js 22 or later** | Usually nothing to do: if you have none, the plugin uses the runtime the IDE downloads for its own agents. See [Node.js](#nodejs) below. |

### Step 1 — Install the plugin

Pick one of the three ways.

**A. From JetBrains Marketplace (recommended).** In the IDE open
`Settings → Plugins → Marketplace`, search for **Subscription ACP Agent**, click
**Install** and restart the IDE. Or open the
[Marketplace page](https://plugins.jetbrains.com/plugin/33150) and use **Install to IDE**.
Updates then arrive like for any other plugin.

**B. From the release zip.** Download

**[⬇ jetbrains-claude-subscription-0.5.2.zip](https://github.com/vanssata/jetbrains-claude-subscription/releases/download/v0.5.2/jetbrains-claude-subscription-0.5.2.zip)**
— or the newest zip from the [releases page](https://github.com/vanssata/jetbrains-claude-subscription/releases).

In the IDE open `Settings → Plugins → ⚙ → Install Plugin from Disk…`, choose the zip
(**do not unzip it**) and restart the IDE.

A plugin installed from disk is never checked for updates. To be told about new releases,
add this repository once under `Settings → Plugins → ⚙ → Manage Plugin Repositories…`:

```text
https://raw.githubusercontent.com/vanssata/jetbrains-claude-subscription/master/updatePlugins.xml
```

**C. Build it yourself.**

```bash
JAVA_HOME=/path/to/a/jdk-21 ./gradlew buildPlugin \
  -PplatformLocalPath=/path/to/your/ide \
  -PaiAssistantPluginPath=/path/to/your/ide-config/ml-llm
# -> build/distributions/jetbrains-claude-subscription-<version>.zip
```

`gradle.properties` points `platformLocalPath` and `aiAssistantPluginPath` at a locally
installed IDE so the build does not download a whole platform; pass your own paths as
above. Without an IDE at hand, take the zip from the artifacts of a
[Build workflow](https://github.com/vanssata/jetbrains-claude-subscription/actions/workflows/build.yml)
run, which builds every pushed branch against a downloaded IDE. Install it as in **B**.

### Step 2 — Check that the agent was registered

After the restart a notification says **Claude Subscription agent ready**. The plugin has
then written the agent to `~/.jetbrains/acp.json` (`%USERPROFILE%\.jetbrains\acp.json` on
Windows) and a start script to `~/.jetbrains/claude-subscription/` — see
[What it writes](#what-it-writes).

If you see **No Node.js 22 or later found** instead, see [Node.js](#nodejs).

### Step 3 — Pick the agent and sign in

1. Open the chat:
   - **AI Assistant:** the AI Chat tool window, then the agent picker above the input.
   - **Air:** the Agent Sessions tool window, then the arrow next to **New Session**.
2. Choose **Claude Subscription**.
3. When the chat asks you to sign in, choose **Claude Subscription** (not *Anthropic
   Console*). A terminal opens and then your browser; sign in with the claude.ai account
   that has the Pro or Max plan.
4. Start a **new chat** after signing in.

Already signed in to Claude Code on this machine? The agent uses the same credentials
(`~/.claude`), so it may skip the sign-in.

**If the sign-in button does nothing or fails**, sign in once from any terminal and
start a new chat. The agent reads the same credentials:

```bash
claude auth login --claudeai
# without Claude Code installed:
npx @anthropic-ai/claude-code auth login --claudeai
```

### Step 4 — Check that it works

Send a short message. The answer comes from your subscription; there is no API billing.
To pick the starting model, see [Settings](#settings).

### Node.js

The plugin looks for node 22 or later in this order and uses the first it finds:

1. your `PATH` (the shell's, also for an IDE started from the macOS Dock);
2. the default install locations of vfox, mise, asdf, nvm, fnm, Volta, nodenv and, on
   macOS, Homebrew;
3. the runtime the IDE downloads for its own agents — start any bundled agent once (e.g.
   the IDE's own Claude Agent or Codex) and it is there.

A node older than 22 is skipped. If none is found, install Node.js 22+ from
[nodejs.org](https://nodejs.org) or your version manager and restart the IDE.

### Projects inside WSL

Projects inside a WSL distribution get their own agent, e.g.
**Claude Subscription (WSL: Ubuntu)**, added the first time such a project opens. It needs
Node.js 22+ *inside* the distribution and its own sign-in there. Details in
[WSL projects](#wsl-projects).

### Upgrading from 0.5.1 or earlier

Restart the IDE once after the update: the plugin rewrites the agent entry by itself. Then
start a **new chat** and sign in again if asked. This is what fixes
`node: bad option: --cli` on sign-in (issue #34).

### Uninstalling

Uninstall the plugin under `Settings → Plugins`. The IDE does not let a plugin clean up
after itself, so the entries stay behind. Remove them by hand:

- the `"Claude Subscription"` entries (WSL ones included) under `agent_servers` in
  `~/.jetbrains/acp.json` — leave the rest of the file alone, it holds other agents;
- the folder `~/.jetbrains/claude-subscription/` (inside each WSL distribution as well).

The file as it was before the plugin first ran is kept as
`~/.jetbrains/acp.json.before-claude-subscription-plugin`.

## What it writes

One entry in `~/.jetbrains/acp.json`, which the IDE reads for locally defined agents and
watches for changes:

```json
{
  "agent_servers": {
    "Claude Subscription": {
      "command": "~/.jetbrains/claude-subscription/agent",
      "args": [],
      "env": { "PATH": "<node bin>:<inherited PATH>" },
      "use_idea_mcp": true,
      "use_custom_mcp": true
    }
  }
}
```

and the script it names, `~/.jetbrains/claude-subscription/agent` (`agent.cmd` on Windows):

```sh
#!/bin/sh
PATH='<node bin>':"$PATH"
export PATH
exec '<node>' '<npx-cli.js>' '--prefix' '<npm global prefix>' '-y' '@agentclientprotocol/claude-agent-acp@0.100.0' "$@"
```

The file is **merged**, never overwritten — other agents and `default_mcp_settings`
survive. The pre-plugin contents are copied once to
`acp.json.before-claude-subscription-plugin`. If the file exists but is not valid JSON
the plugin refuses to write at all and says so, rather than silently dropping whatever
was in it.

### Why the command looks like that

Four findings drove the shape:

- **The command is a script, not `node`.** The chat's login button runs the entry's
  `command` with the login method's arguments (`--cli auth login --claudeai`) and without
  the entry's `args`. With `node` as the command that is `node --cli …`, which fails with
  `bad option: --cli` (issue #34). The script starts the agent and passes those arguments
  on, so the same call reaches the Claude CLI's login.

- **`npx` is not enough on its own.** `npx-cli.js` re-execs its helpers through
  `#!/usr/bin/env node`, so the node binary must be on `PATH`. Calling it by absolute
  path fails with `env: 'node': No such file or directory`. Hence the explicit `PATH`.
- **The path is recomputed on every startup.** If no system node exists, the fallback is
  the runtime the IDE downloads for its own ACP agents
  (`~/.cache/JetBrains/<IDE>/acp-agents/.runtimes/node/<version>/bin`). That path carries
  a version number, so freezing it would break on the next runtime update. The plugin
  re-resolves and rewrites only when the result actually changed.

- **`--prefix` keeps npm out of the project.** The IDE starts the agent in the project
  root. In a project whose `package.json` pins pnpm, yarn or bun through `devEngines`, npx
  would stop there with `EBADDEVENGINES` before the agent ever ran. `--prefix` points npm
  away from the project while the agent keeps it as its working directory. The value is
  npm's own global prefix, so the `npm` commands the agent runs behave as they did
  before.

A node older than 22 is skipped wherever it is found, on `PATH` included: the ACP package
needs 22, and an old npx fails on the arguments instead of saying so.

An IDE started from the macOS Dock does not see what your shell profile adds to `PATH`,
so before falling back to the IDE runtime the plugin also checks the default install
locations of vfox, mise, asdf, nvm, fnm, Volta, nodenv and (on macOS) Homebrew, taking
the newest node 22 or later it finds there.

If neither a node 22 or later nor an IDE runtime is found, you get a notification explaining
what to install — not a silent failure.

## WSL projects

The IDE reads only the Windows `acp.json`, also for a project inside a WSL distribution,
and starts that project's agent *inside* the distribution. The Windows entry names a
Windows `node.exe` there and cannot start ("The agent could not connect").

So the first time a project from a distribution opens, the plugin adds a second entry,
named after the distribution, e.g. **Claude Subscription (WSL: Ubuntu)**, with a node found inside that
distribution: on the `PATH` of your login shell there, then in the default locations of
vfox, mise, asdf, nvm, fnm, Volta and nodenv, then in the runtime the IDE downloads into the
distribution for its own agents. The same node 22 floor applies. Pick that entry in the
chat of projects inside the distribution; the plain entry stays for Windows projects.

- Node.js 22 or later has to be available inside the distribution. Without one you get a
  notification naming the distribution, and no WSL entry is written.
- The agent signs in separately there: it reads the credentials of Claude Code inside the
  distribution (`~/.claude` there), not the Windows ones.
- The entry's `command` is a launcher script written inside the distribution, at
  `~/.jetbrains/claude-subscription/agent` there, for the reason given under
  [Why the command looks like that](#why-the-command-looks-like-that).
- If the chat's login button still fails there, sign in once from a terminal inside the
  distribution and choose the Claude subscription login:

  ```bash
  claude /login        # or: npx @anthropic-ai/claude-code /login
  ```
- On NixOS, the IDE-downloaded runtime only runs with `nix-ld`; a node from your profile
  (`home.packages`, `environment.systemPackages`) works without it.

## Settings

Edit them under `Settings → Tools → Subscription ACP Agent`. Applying rewrites the agent entry
immediately; the IDE watches `acp.json`, so no restart is needed. The model dropdown is
filled by the agent itself — each time the page opens, the plugin starts the ACP package in
the background and reads the models it offers your plan (e.g. Opus 5.5, Sonnet 5.5,
Fable 5.1), so new models appear without a plugin update. Only what the agent lists can be
picked — the plugin carries no model list of its own, so the dropdown stays empty until
the agent has answered once while you are logged in. The last list is remembered, and a
model saved earlier that the agent no longer offers stays selected, marked as such, until
you change it.
Renaming the agent removes the entries under the old name, WSL ones included. State is stored in
`claude-subscription-acp.xml`:

| Key | Default | Meaning |
| --- | --- | --- |
| `manageAgent` | `true` | Turn off to stop the plugin touching `acp.json` and manage the entry yourself. |
| `displayName` | `Claude Subscription` | Also determines the agent id the IDE derives, which chat history and per-agent session options follow. Renaming it starts the agent over. |
| `packageSpec` | `@agentclientprotocol/claude-agent-acp@0.100.0` | Pinned deliberately — the guard being worked around lives in this package. |
| `model` | *(blank)* | Starting model for the IDE session, written to the agent's `env` as `ANTHROPIC_MODEL` (e.g. `opus`). Blank means the `model` from `~/.claude/settings.json`. |

`model` exists because `~/.claude/settings.json` is shared with the CLI. Use it when the IDE
should start on a different model than the terminal does. It only sets the main session.
Subagents still use the `model:` in their own definitions, and a subagent without one
inherits the session model. A model you pick in the chat panel still overrides it for that
session. Effort and permission mode are **not** managed here. They are ACP session config
options that the IDE stores per agent id, so pick them once in the chat panel after the
agent appears.

## Known limits

- **The agent shows the IDE's generic icon.** `acp.json` has no icon field. The only other
  way to set one is an internal AI Assistant extension point, which earlier versions used
  and this one deliberately does not: it is not a published contract, and it put the
  plugin in the icon path of every other agent.
- **The build range is pinned to verified branches (`262`–`263.*`).** The plugin uses no
  AI Assistant classes, but it relies on how AI Assistant reads `acp.json` and launches
  local agents, which can change between IDE branches.
- **Windows is lightly tested.** Node resolution understands the flat Windows layout
  (`node.exe` next to `node_modules\npm`, as installed by the official installer and
  nvm-windows) and looks for IDE runtimes under `%LOCALAPPDATA%\JetBrains`, but it is
  developed and verified on Linux. The WSL entries were verified against the IDE's launch
  code and a Linux shell, not yet on a Windows machine.
- **The login button for WSL agents is not verified on Windows yet.** Older IDE builds
  converted the entry's `command` as a Windows path for the login terminal and left the
  window empty (issue #22). Newer ones run it inside the distribution, where the launcher
  script handles it; if yours does not, sign in from a terminal in the distribution (see
  [WSL projects](#wsl-projects)).
- **Sessions are listed twice in Air's Agent Sessions.** Air lists Claude Code sessions
  from `~/.claude` itself and asks this agent for the same ones, and does not merge the
  two. The package offers no way to turn its listing off; this needs a fix in Air.
- **Two agents in the picker with WSL.** `acp.json` has one `command` per entry and no
  way to tell the IDE which entry belongs to which machine, so both are listed everywhere.
- **Unsupported by JetBrains.** They disabled this deliberately in their bundled entry.
  An IDE update can change the behaviour this relies on.
- A harmless `AcpModeManager - Agent ... has no registered modes` warning appears in the
  log. The bundled agent logs it too; the Claude ACP wrapper does not use ACP session
  modes.

## Troubleshooting

### `Internal error: Failed to authenticate: OAuth session expired and could not be refreshed`

Your claude.ai sign-in expired and Claude Code could not renew it. The message comes from
Claude Code inside the ACP package, not from this plugin. The package turns only a
"Please run /login" error into the IDE's login prompt, so this one reaches the chat as a
bare internal error with no way to sign in again from there.

Sign in again outside the IDE, then start a new chat (or restart the IDE):

```bash
claude /login        # or: npx @anthropic-ai/claude-code /login
```

Choose the Claude subscription login. The agent reads the same stored credentials as
Claude Code in your terminal (`~/.claude/.credentials.json`, or the Keychain on macOS), so
one sign-in fixes both.

If you set `CLAUDE_CONFIG_DIR` in a shell profile, an IDE started from the macOS Dock
does not see it. The agent then reads the default location, which can hold an old,
expired login while your terminal uses a different one. Sign in once without
`CLAUDE_CONFIG_DIR` set, or start the IDE from that shell.

## License

[MIT](LICENSE). Third-party names and the non-commercial notice: [TRADEMARKS.md](TRADEMARKS.md).
