#!/bin/sh
# Proves the point of the whole plugin, without needing the IDE.
#
# Resolves a node runtime the same way NodeRuntimeResolver does, starts the ACP agent
# the same way the provisioned config does, speaks one `initialize` request over stdio,
# and asserts that "Claude Subscription" (`claude-ai-login`) is offered.
#
# Run the same command with --hide-claude-auth appended and that method disappears —
# that single flag is the entire difference this plugin exists for.
set -eu

PACKAGE="${ACP_PACKAGE:-@agentclientprotocol/claude-agent-acp@0.85.1}"

# 1. A system node wins; otherwise fall back to the runtime the IDE downloads for its
#    own ACP agents (the only node present on some machines).
if command -v node >/dev/null 2>&1; then
    NODE_BIN=$(dirname "$(command -v node)")
else
    NODE_BIN=$(ls -d "$HOME"/.cache/JetBrains/*/acp-agents/.runtimes/node/*/bin \
        "$HOME"/Library/Caches/JetBrains/*/acp-agents/.runtimes/node/*/bin 2>/dev/null |
        sort -V | tail -1)
fi

if [ -z "${NODE_BIN:-}" ] || [ ! -x "${NODE_BIN}/node" ]; then
    echo "FAIL: no node runtime found" >&2
    exit 1
fi

NPX_CLI="${NODE_BIN}/../lib/node_modules/npm/bin/npx-cli.js"
if [ ! -f "${NPX_CLI}" ]; then
    echo "FAIL: npx-cli.js not found next to ${NODE_BIN}/node" >&2
    exit 1
fi

echo "node:    ${NODE_BIN}/node"
echo "package: ${PACKAGE}"

# 2. node must be on PATH, not merely invoked by absolute path: npx-cli.js re-execs its
#    helpers through `#!/usr/bin/env node`.
REQUEST='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{"fs":{"readTextFile":true,"writeTextFile":true},"terminal":false,"_meta":{"terminal-auth":true}}}}'

RESPONSE=$(printf '%s\n' "${REQUEST}" | env PATH="${NODE_BIN}:${PATH}" \
    "${NODE_BIN}/node" "${NPX_CLI}" -y "${PACKAGE}" 2>/dev/null | head -1)

if [ -z "${RESPONSE}" ]; then
    echo "FAIL: agent produced no response" >&2
    exit 1
fi

# 3. Assert on the auth methods actually offered.
printf '%s' "${RESPONSE}" | python3 -c '
import json, sys

result = json.load(sys.stdin)["result"]
methods = [m["id"] for m in result.get("authMethods", [])]

print("agent:   %s %s" % (result["agentInfo"]["title"], result["agentInfo"]["version"]))
print("methods: %s" % ", ".join(methods))

if "claude-ai-login" not in methods:
    sys.exit("FAIL: claude-ai-login missing — the subscription auth method is not offered")
print("PASS: claude-ai-login offered")
'
