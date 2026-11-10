---
name: bump-acp
description: Bump the pinned @agentclientprotocol/claude-agent-acp version everywhere it is written down, then run the end-to-end checks. Use for "/bump-acp", "/bump-acp 0.100.0", or any request to update the ACP package.
---

# Bump the pinned ACP package

The version lives in four places that must not drift. Change all of them in one commit.

## 1. Pick the version

Use the version given as the argument. Without one, take `latest`:

```bash
npm view @agentclientprotocol/claude-agent-acp version engines time.modified
```

Stop and ask before going on if `engines.node` is no longer `>=22`: `MIN_NODE_MAJOR` in
`NodeRuntimeResolver.kt` and the README mirror that floor. A version published in the last
day or two has had no users yet; say so and prefer one that has.

## 2. Edit the four places

Replace the old `@agentclientprotocol/claude-agent-acp@<old>` with the new version in:

1. `src/main/kotlin/dev/vanssa/claudeacp/ClaudeAcpSettings.kt` — `DEFAULT_PACKAGE_SPEC`
2. `test/handshake.sh` — the `PACKAGE=` default (`test/models.sh` reads it from there)
3. `README.md` — the `acp.json` example under "What it writes"
4. `README.md` — the `packageSpec` row of the settings table

Then confirm nothing was missed:

```bash
grep -rn "claude-agent-acp@" README.md src test
```

One hit is meant to stay behind unless you know better: the command under "The problem it
solves" in `README.md` shows what *JetBrains* bundles, not what this plugin pins. Change it
only when the version the IDE ships has been looked up.

## 3. Check

```bash
sh test/handshake.sh
```

```bash
sh test/models.sh
```

`handshake.sh` must print `PASS: claude-ai-login offered`. `models.sh` must print `PASS`, or
`SKIP` without a Claude login — a `SKIP` leaves the model dropdown unchecked, so say so.

In a remote shell (`SSH_CONNECTION`, `NO_BROWSER` or `CLAUDE_CODE_REMOTE` set) the package
offers `claude-login` instead and `handshake.sh` fails for that reason alone. Run it with
those variables unset (`env -u CLAUDE_CODE_REMOTE sh test/handshake.sh`).

Then build and run the unit tests as `CLAUDE.md` describes.

## 4. Commit

`feat: bump claude-agent-acp to <version>`, with what the new version brings in the body.
A bump changes the default for every user, so it needs a release to reach them — see the
release gotcha in `CLAUDE.md`.
