# Claude Subscription ACP Agent

> **Unofficial plugin.** A personal community project — not affiliated with, endorsed by,
> or supported by JetBrains or Anthropic. It is not distributed on JetBrains Marketplace;
> install it from the release zip below.

A JetBrains plugin that adds a **Claude Subscription** agent to AI chat — one that
authenticates with a Claude.ai Pro/Max subscription instead of demanding Anthropic
Console API billing.

Install it and the agent appears. There is nothing else to run.

## The problem it solves

JetBrains launches its bundled Claude agent like this:

```text
npx -y @agentclientprotocol/claude-agent-acp@0.62.0 --hide-claude-auth
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

## Requirements

- A JetBrains IDE on the **262.\*** branch (2026.2) with the AI Assistant plugin.
- A Claude.ai Pro or Max subscription.
- Node.js — but see below, one is usually already present.

## Install

**[⬇ Download jetbrains-claude-subscription-0.2.1.zip](https://github.com/vanssata/jetbrains-claude-subscription/releases/download/v0.2.1/jetbrains-claude-subscription-0.2.1.zip)**
— or pick the newest zip from the [releases page](https://github.com/vanssata/jetbrains-claude-subscription/releases).

Then in the IDE: `Settings → Plugins → ⚙ → Install Plugin from Disk…`, choose the zip
(do not unzip it), and restart.

### Update notifications

A plugin installed from disk is never checked for updates. To be notified in the IDE,
add this repository once under `Settings → Plugins → ⚙ → Manage Plugin Repositories…`:

```text
https://raw.githubusercontent.com/vanssata/jetbrains-claude-subscription/master/updatePlugins.xml
```

The IDE then offers each new release like a Marketplace update.

### Building from source instead

```bash
JAVA_HOME=/path/to/a/jdk ./gradlew buildPlugin
# build/distributions/jetbrains-claude-subscription-0.2.1.zip
```

`gradle.properties` sets `platformLocalPath` and `aiAssistantPluginPath` to a locally
installed IDE so the build does not download a full platform. Point them at your own
installation.

On first startup the agent is registered and a notification confirms it. Select
**Claude Subscription** in the AI chat agent picker and authenticate — the browser flow
now offers `--claudeai` login instead of `--console`.

## What it writes

One entry in `~/.jetbrains/acp.json`, which the IDE reads for locally defined agents and
watches for changes:

```json
{
  "agent_servers": {
    "Claude Subscription": {
      "command": "<node>",
      "args": ["<npx-cli.js>", "-y", "@agentclientprotocol/claude-agent-acp@0.62.0"],
      "env": { "PATH": "<node bin>:<inherited PATH>" },
      "use_idea_mcp": true,
      "use_custom_mcp": true
    }
  }
}
```

The file is **merged**, never overwritten — other agents and `default_mcp_settings`
survive. The pre-plugin contents are copied once to
`acp.json.before-claude-subscription-plugin`. If the file exists but is not valid JSON
the plugin refuses to write at all and says so, rather than silently dropping whatever
was in it.

### Why the command looks like that

Two findings drove the shape:

- **`npx` is not enough on its own.** `npx-cli.js` re-execs its helpers through
  `#!/usr/bin/env node`, so the node binary must be on `PATH`. Calling it by absolute
  path fails with `env: 'node': No such file or directory`. Hence the explicit `PATH`.
- **The path is recomputed on every startup.** If no system node exists, the fallback is
  the runtime the IDE downloads for its own ACP agents
  (`~/.cache/JetBrains/<IDE>/acp-agents/.runtimes/node/<version>/bin`). That path carries
  a version number, so freezing it would break on the next runtime update. The plugin
  re-resolves and rewrites only when the result actually changed.

If neither a system node nor an IDE runtime is found, you get a notification explaining
what to install — not a silent failure.

## Settings

Edit them under `Settings → Tools → Claude Subscription`. Applying rewrites the agent entry
immediately; the IDE watches `acp.json`, so no restart is needed. The model is a dropdown
(`opus`, `opus[1m]`, `sonnet`, `haiku`, `fable[1m]`) that also accepts any typed model id.
Renaming the agent removes the entry under the old name. State is stored in
`claude-subscription-acp.xml`:

| Key | Default | Meaning |
| --- | --- | --- |
| `manageAgent` | `true` | Turn off to stop the plugin touching `acp.json` and manage the entry yourself. |
| `displayName` | `Claude Subscription` | Also determines the agent id the IDE derives, and therefore icon matching. |
| `packageSpec` | `@agentclientprotocol/claude-agent-acp@0.62.0` | Pinned deliberately — the guard being worked around lives in this package. |
| `model` | *(blank)* | Starting model for the IDE session, written to the agent's `env` as `ANTHROPIC_MODEL` (e.g. `opus`). Blank means the `model` from `~/.claude/settings.json`. |

`model` exists because `~/.claude/settings.json` is shared with the CLI. Use it when the IDE
should start on a different model than the terminal does. It only sets the main session.
Subagents still use the `model:` in their own definitions, and a subagent without one
inherits the session model. A model you pick in the chat panel still overrides it for that
session. Effort and permission mode are **not** managed here. They are ACP session config
options that the IDE stores per agent id, so pick them once in the chat panel after the
agent appears.

## Known limits

- **The icon uses internal API, and sits in every agent's icon path.** `acp.json` has no
  icon field, so the icon comes from the AI Assistant extension point
  `com.intellij.ml.llm.core.chat.ui.agentIconService` — internal, not a published
  contract, hence the `262.*` build range.

  Worth knowing how it behaves: icons resolve through
  `EP_NAME.extensionList.firstNotNullOf { it.loadIconForAgent(agentId) }`, but
  `loadIconForAgent` returns a non-null `Icon`. The first registered extension therefore
  always answers and nothing falls through. Being consulted at all requires
  `order="first"`, which means this plugin is asked for *every* agent's icon. It answers
  for its own agent and hands every other one back to the service that would have
  answered otherwise. If that delegation ever fails, other agents fall back to a generic
  icon — the failure is cosmetic and logged, never fatal.

  If the extension point breaks outright, the agent itself keeps working; only the icon
  is lost.
- **Windows is lightly tested.** Node resolution understands the flat Windows layout
  (`node.exe` next to `node_modules\npm`, as installed by the official installer and
  nvm-windows) and looks for IDE runtimes under `%LOCALAPPDATA%\JetBrains`, but it is
  developed and verified on Linux.
- **Unsupported by JetBrains.** They disabled this deliberately in their bundled entry.
  An IDE update can change the behaviour this relies on.
- A harmless `AcpModeManager - Agent ... has no registered modes` warning appears in the
  log. The bundled agent logs it too; the Claude ACP wrapper does not use ACP session
  modes.

## Attribution

`icons/claude.svg` is Anthropic's Claude mark, taken from the official Claude Code
JetBrains plugin so the agent is visually recognisable. This project is a personal
integration tool, not affiliated with or endorsed by Anthropic or JetBrains, and the mark
remains Anthropic's.

## License

[MIT](LICENSE) — except `icons/claude.svg`, which is Anthropic's mark (see Attribution).
