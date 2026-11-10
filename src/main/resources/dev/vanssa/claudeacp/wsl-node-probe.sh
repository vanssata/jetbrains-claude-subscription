# Finds a node runtime inside a WSL distribution. Fed to `wsl.exe -d <distro> -e sh -s -- <floor>`
# on stdin, so no Windows command-line quoting ever touches it. POSIX sh only: the distribution's
# /bin/sh may be dash or busybox.
#
# Prints `__ACP_<KEY>__=<value>` lines that WslNodeResolver parses; anything else on stdout
# (login banners, rc-file noise) is ignored.

floor=${1:-22}

# The IDE starts the agent with the environment of the user's login shell, so that is the PATH
# to search and to hand on. Asked of the shell itself because nvm, fnm and friends are
# activated from rc files a plain `sh` never reads. `env` rather than `echo $PATH`: fish
# would print its PATH list space-separated. stdin is /dev/null so the interactive shell does
# not read the rest of this script as its own input.
shell=$(getent passwd "$(id -un)" 2>/dev/null | cut -d: -f7)
[ -x "$shell" ] || shell=${SHELL:-/bin/sh}
login_path=$("$shell" -lic env </dev/null 2>/dev/null | sed -n 's/^PATH=//p' | tail -n 1)
[ -n "$login_path" ] || login_path=$PATH
echo "__ACP_PATH__=$login_path"

# Version directories newest first. `sort -V` is not in every busybox, so the numeric fields
# are sorted after the leading `v` nvm puts on its directory names is moved out of the way.
newest_first() {
    ls -1 "$1" 2>/dev/null |
        sed 's/^v//' |
        sort -t. -k1,1nr -k2,2nr -k3,3nr |
        while read -r name; do
            if [ -d "$1/v$name" ]; then echo "$1/v$name"; else echo "$1/$name"; fi
        done
}

candidates() {
    old_ifs=$IFS
    IFS=:
    for dir in $login_path; do
        [ -n "$dir" ] && echo "$dir"
    done
    IFS=$old_ifs

    # Same install locations NodeRuntimeResolver probes on the host, for the same reason:
    # a version manager activated from a prompt hook may not reach the login PATH.
    echo "$HOME/.vfox/sdks/nodejs/bin"
    for pair in \
        ".local/share/mise/installs/node:bin" \
        ".asdf/installs/nodejs:bin" \
        ".nvm/versions/node:bin" \
        ".local/share/fnm/node-versions:installation/bin" \
        ".volta/tools/image/node:bin" \
        ".nodenv/versions:bin"; do
        newest_first "$HOME/${pair%%:*}" | while read -r version_dir; do
            echo "$version_dir/${pair#*:}"
        done
    done

    # The runtime the IDE downloads into the distribution for its own agents. On NixOS this
    # generic build only runs with nix-ld, which the version check below sorts out.
    for ide in "$HOME"/.cache/JetBrains/*; do
        newest_first "$ide/acp-agents/.runtimes/node" | while read -r version_dir; do
            echo "$version_dir/bin"
        done
    done
}

candidates | while read -r bin; do
    node="$bin/node"
    [ -f "$node" ] && [ -x "$node" ] || continue

    version=$("$node" --version </dev/null 2>/dev/null)
    major=${version#v}
    major=${major%%.*}
    case $major in '' | *[!0-9]*) continue ;; esac
    [ "$major" -ge "$floor" ] || continue

    # Next to the PATH entry for a tarball layout; next to the resolved binary for NixOS and
    # Homebrew-style profiles, where bin/node is a link into a store and the profile's own
    # lib/ may not carry npm.
    real=$(readlink -f "$node" 2>/dev/null)
    npx_cli=
    for root in "$bin/.." "${real%/*}/.."; do
        if [ -f "$root/lib/node_modules/npm/bin/npx-cli.js" ]; then
            npx_cli=$(readlink -f "$root/lib/node_modules/npm/bin/npx-cli.js")
            break
        fi
    done
    [ -n "$npx_cli" ] || continue

    # Asked from the bin directory for the reason NodeRuntime.globalPrefix gives: run inside a
    # project that pins another package manager, `npm config` fails like npx does.
    prefix=$(cd "$bin" && "$node" "${npx_cli%/*}/npm-cli.js" config get prefix </dev/null 2>/dev/null)
    case $prefix in
        /*) mkdir -p "$prefix" 2>/dev/null && echo "__ACP_PREFIX__=$prefix" ;;
    esac

    echo "__ACP_BIN__=$bin"
    echo "__ACP_NPX__=$npx_cli"
    # Only the first usable candidate; `exit` leaves the pipeline's subshell, which is enough.
    exit 0
done
