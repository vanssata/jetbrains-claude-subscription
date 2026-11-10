# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

`JAVA_HOME` is not set in the shell and the only JDK on this machine is the JetBrains
Runtime shipped with PhpStorm. Always build with:

```bash
JAVA_HOME=/home/vanssa/.local/share/JetBrains/Toolbox/apps/phpstorm/jbr ./gradlew buildPlugin
# -> build/distributions/jetbrains-claude-subscription-<version>.zip
```

No Gradle toolchain is configured on purpose (`build.gradle.kts` explains why) — the build
compiles with whatever JDK Gradle runs on and targets JVM 21 bytecode.

ktlint runs through the kotlinter plugin — `./gradlew lintKotlin` (same `JAVA_HOME` prefix),
or `formatKotlin` to apply fixes. Two rules are disabled in `.editorconfig` because the
existing code deliberately does otherwise; read the comment there before re-enabling them or
reformatting around them.

The build resolves the platform and the AI Assistant plugin from **local installs** via the
`platformLocalPath` and `aiAssistantPluginPath` properties in `gradle.properties`. Those are
absolute machine-specific paths; do not "fix" them to coordinates or downloads.

Without that machine, `.github/workflows/build.yml` is the build: it downloads an IDE and
the matching AI Assistant, passes both paths with `-P`, and uploads the zip as a run
artifact on every push. Run by hand with `draft_release` ticked, it also attaches the zip
to a draft release `v<version>`. The same works in any Linux shell — the workflow's steps
are the recipe, with the downloaded IDE's `jbr` as `JAVA_HOME`.

## Verifying a change

`./gradlew test` runs plain JUnit 5 tests (no platform test framework, no running IDE) over
the pure logic: parsing the agent's model list, its persisted form, the dropdown items.
They say nothing about the plugin loading in the IDE — do not report a change as verified
because Gradle succeeded.

Real verification is manual: build the zip, then
`Settings → Plugins → ⚙ → Install Plugin from Disk…`, restart, and check that
**Claude Subscription** appears in the AI chat agent picker.

`test/handshake.sh` is the automated end-to-end check. It starts the ACP package outside the IDE and
asserts that the `claude-ai-login` auth method is offered — i.e. that the thing this plugin
exists for still works. Run it after any change to the package spec or node resolution.
It starts the agent from a project that pins pnpm through `devEngines`, so it also fails
when the `--prefix` argument stops doing its job. In a remote shell (`SSH_CONNECTION`,
`NO_BROWSER` or `CLAUDE_CODE_REMOTE` set) the package offers `claude-login` instead and the
script fails for that reason alone; unset those to run it there.

`test/models.sh` does the same for the model dropdown: it sends `initialize` and
`session/new` and asserts the agent lists models. It needs a Claude login and prints SKIP
without one. Run it after a package bump — the dropdown has no fallback list, so a package
that stops reporting models leaves it empty.

## Gotchas

- **The pinned ACP package version lives in four places** and they must not drift:
  `ClaudeAcpSettings.DEFAULT_PACKAGE_SPEC`, `test/handshake.sh`, and two spots in `README.md`.
  Use `/bump-acp` (`.claude/skills/bump-acp`) rather than editing by hand.
- **A release bumps the version in two places**: `version` in `build.gradle.kts` and
  `updatePlugins.xml` (`version` and the asset `url`), plus the download link in `README.md`.
  The IDE polls `updatePlugins.xml` on `master` and downloads from its `url` at once, so it
  must reach `master` no earlier than the release asset it points to exists.
  Publishing the GitHub release also uploads its zip to Marketplace (`upload` job in
  `verify-release.yml`, after the verifier passes). A Marketplace update can be neither
  edited nor replaced, so publish only a zip that has been tried in an IDE.
- **The `262`–`263.*` build range is deliberate.** The plugin relies on how AI Assistant reads
  `acp.json` and launches local agents, which is IDE behaviour, not a published contract. Do
  not widen `untilBuild` to gain forward compatibility that has not been tested. Adding a
  branch means: run the plugin verifier against that branch's IDE, confirm the
  `acp.schema.json` bundled with that branch's AI Assistant still accepts the entry the
  plugin writes, then add the branch to both `PLATFORM_BRANCHES` and the `verify` matrix in
  `.github/workflows/verify-release.yml`, and to the README requirements.
- **Use no AI Assistant classes.** Everything under `com.intellij.ml.llm` is internal API
  (JetBrains Marketplace guideline 2.1). The agent icon was dropped for this reason: its only
  seam, `agentIconService`, also put the plugin in every other agent's icon path (guideline
  2.2). AI Assistant (`com.intellij.ml.llm`) and Air (`com.intellij.air`) are both
  *optional* dependencies with empty config files: either one reads `~/.jetbrains/acp.json`
  (Air carries its own copy of the same ACP runtime), and a required AI Assistant kept
  Air-only users out (issue #24). The plugin links against neither; with neither enabled the
  provisioner only says so.
- **The plugin name carries no third-party trademark** (guideline 1.2, Anthropic trademark
  guidelines), and the plugin ships no Claude logo. The agent's default display name
  "Claude Subscription" stays as it is, for two reasons. The agent id, and with it existing
  chats, derives from that name. And a menu of agents naming the model is the use
  Anthropic's Agent SDK branding guidelines allow.
- **`~/.jetbrains/acp.json` is shared.** Every write must merge — it holds other users' agents
  and `default_mcp_settings`. Never overwrite it, and keep the refuse-on-unparseable behaviour
  in `AcpConfigFile`.
- **WSL projects get their own entry** (`Claude Subscription (WSL: <distro>)`). AI Assistant
  reads only the host's `acp.json` (`user.home`), also for a WSL project, and runs the
  entry's `command` inside the distribution, resolved as a path there; the host entry's
  Windows node fails with `EelPathException` (issue #22). `"command": "npx"` would make
  the IDE pick its own runtime per machine, but loses `--prefix` (#21) and the user's node,
  and its generic Linux node does not run on NixOS. Node inside the distribution is found
  by `wsl-node-probe.sh`, fed to `wsl.exe` on stdin; it is POSIX sh on purpose.
- **The `--prefix` value is npm's global prefix on purpose.** npx exports it to the agent as
  `npm_config_prefix`, which every `npm` the agent runs reads as the global prefix. An
  empty directory would fix `EBADDEVENGINES` just as well and quietly redirect the agent's
  `npm install -g`.
- **The node `PATH` entry is load-bearing.** `npx-cli.js` re-execs helpers through
  `#!/usr/bin/env node`, so an absolute node path alone fails. The path is also re-resolved on
  every startup because the IDE's fallback runtime path carries a version number.
- The Kotlin stdlib is deliberately not bundled (`kotlin.stdlib.default.dependency=false`);
  adding it back causes classloader conflicts at runtime.

## Code style

This codebase documents **why**, not what. KDoc and comments here record the observed platform
behaviour that forced a decision (why `order="first"`, why delegation, why a value is pinned).
Match that when adding code: if a line looks odd, the comment must say what made it necessary.
Do not add comments that restate the code.

## Git

Conventional Commits (`feat:`, `fix:`, `docs:`). Work on a feature branch rather than
committing to `master`. The remote is `github.com/vanssata/jetbrains-claude-subscription`
(public); releases carry the built plugin zip as a direct-download asset, so tagging a
release means attaching a freshly built `build/distributions/*.zip`.
