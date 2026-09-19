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

## Verifying a change

There are **no JVM tests** — `./gradlew test` compiles nothing meaningful and proves nothing.
Do not report a change as verified because Gradle succeeded.

Real verification is manual: build the zip, then
`Settings → Plugins → ⚙ → Install Plugin from Disk…`, restart, and check that
**Claude Subscription** appears in the AI chat agent picker with the Claude icon.

`test/handshake.sh` is the one automated check. It starts the ACP package outside the IDE and
asserts that the `claude-ai-login` auth method is offered — i.e. that the thing this plugin
exists for still works. Run it after any change to the package spec or node resolution.

## Gotchas

- **The pinned ACP package version lives in four places** and they must not drift:
  `ClaudeAcpSettings.DEFAULT_PACKAGE_SPEC`, `test/handshake.sh`, and two spots in `README.md`.
  Use `/bump-acp` rather than editing by hand.
- **A release bumps the version in two places**: `version` in `build.gradle.kts` and
  `updatePlugins.xml` (`version` and the asset `url`), plus the download link in `README.md`.
  The IDE polls `updatePlugins.xml` on `master` and downloads from its `url` at once, so it
  must reach `master` no earlier than the release asset it points to exists.
- **The `262.*` build range is deliberate.** `AgentIconService` is internal AI Assistant API,
  not a published contract. Do not widen `untilBuild` to gain forward compatibility that has
  not been tested.
- **`~/.jetbrains/acp.json` is shared.** Every write must merge — it holds other users' agents
  and `default_mcp_settings`. Never overwrite it, and keep the refuse-on-unparseable behaviour
  in `AcpConfigFile`.
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
