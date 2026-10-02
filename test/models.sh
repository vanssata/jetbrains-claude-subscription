#!/bin/sh
# Proves the settings dropdown has something to show, without needing the IDE.
#
# Speaks the same two requests AcpModelCatalog does — `initialize`, then `session/new` —
# and asserts that the `model` config option in the response lists at least one model
# besides the agent's own "default". ACP has no listing call; this response is the only
# place the agent reports its models, and it does so only for a logged-in account.
#
# Needs a Claude login (`claude` in a terminal, or the IDE chat once). Not logged in is
# reported as SKIP, not FAIL: the plugin then keeps the dropdown blank, which is correct.
set -eu

# Taken from handshake.sh rather than repeated: the pinned version already lives in four
# places that /bump-acp keeps in step, and a fifth would be the one that drifts.
PINNED=$(sed -n 's/^PACKAGE="${ACP_PACKAGE:-\(.*\)}"$/\1/p' "$(dirname "$0")/handshake.sh")
PACKAGE="${ACP_PACKAGE:-${PINNED}}"

# Resolved through symlinks: npx-cli.js sits next to the real binary, not next to a
# link such as ~/.local/bin/node. This only finds *a* node to talk to the agent with;
# which one the plugin picks is NodeRuntimeResolver's business, not tested here.
if command -v node >/dev/null 2>&1; then
    NODE_BIN=$(dirname "$(realpath "$(command -v node)")")
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
echo "node:    ${NODE_BIN}/node"
echo "package: ${PACKAGE}"

# session/new makes the Claude runtime record a project for its cwd; a throwaway
# directory keeps this repository's CLAUDE.md out of the session.
WORK=$(mktemp -d)
trap 'rm -rf "${WORK}"' EXIT

INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}'
SESSION='{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"'"${WORK}"'","mcpServers":[]}}'

# stdin stays open until the session answers: closing it early makes the agent exit
# before session/new completes. python reads until id 2 arrives, then stops the agent.
cd "${WORK}"
env PATH="${NODE_BIN}:${PATH}" python3 - "${NODE_BIN}/node" "${NPX_CLI}" "${PACKAGE}" "${INIT}" "${SESSION}" <<'EOF'
import json, subprocess, sys, threading

node, npx, package, init, session = sys.argv[1:]
agent = subprocess.Popen([node, npx, "-y", package], stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                         start_new_session=True)
timer = threading.Timer(120, agent.kill)
timer.start()

def call(request, wanted):
    agent.stdin.write(request + "\n")
    agent.stdin.flush()
    for line in agent.stdout:
        try:
            message = json.loads(line)
        except ValueError:
            continue
        if message.get("id") == wanted:
            return message
    sys.exit("FAIL: agent closed its output before answering request %d" % wanted)

try:
    call(init, 1)
    response = call(session, 2)
finally:
    timer.cancel()
    import os, signal
    try:
        os.killpg(agent.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass

if "error" in response:
    error = response["error"]
    if error.get("code") == -32000 or "auth" in json.dumps(error).lower():
        print("SKIP: not logged in (%s)" % error.get("message"))
        sys.exit(0)
    sys.exit("FAIL: session/new returned %s" % json.dumps(error))

options = response["result"].get("configOptions", [])
model = next((o for o in options if o.get("id") == "model"), None)
if model is None:
    sys.exit("FAIL: no `model` config option in session/new — AcpModelCatalog would find nothing")

models = [(o["value"], o.get("name", o["value"])) for o in model.get("options", []) if o.get("value") != "default"]
for value, name in models:
    print("model:   %-22s %s" % (value, name))
if not models:
    sys.exit("FAIL: the model option lists nothing besides `default`")
print("PASS: %d models offered" % len(models))
EOF
