#!/usr/bin/env bash
#
# AmneziaWG desktop root helper.
#
# Runs as root (launched through `pkexec /bin/bash <this file>`) and executes
# privileged AmneziaWG operations for the desktop GUI. The GUI talks to this
# process over stdin/stdout so that a single polkit authentication covers the
# whole application lifetime instead of prompting for every operation.
#
# Protocol (one request per line, tab separated):
#
#   request : <id> TAB <command> [ TAB <arg> ... ]
#   response: <id> TAB OK  TAB <base64 payload>
#             <id> TAB ERR TAB <error code> TAB <base64 message>
#
# base64 is used for every payload so that arbitrary command output (which may
# contain tabs and newlines) never breaks the framing.
#
# Commands:
#   ping                       liveness check
#   version                    amnezia wg --version
#   ifaces                     awg show interfaces
#   dump <name>                awg show <name> dump
#   latest <name>              awg show <name> latest-handshakes
#   up <conf-path>             awg-quick up <conf-path> (see "Routes" below)
#   down <conf-path|name>      awg-quick down <conf-path|name>
#   journal <lines>            recent awg/awg-quick journal entries
#   quit                       terminate the helper

#
# Routes:
#
# For configurations without a default route (no 0.0.0.0/0 or ::/0 in
# AllowedIPs), awg-quick adds one route per AllowedIPs entry and checks each
# against all routes added so far, which takes minutes for split tunnels with
# thousands of networks. It also routes the peer's own endpoint into the
# tunnel when an AllowedIPs network covers it, so the handshake can never
# leave the machine and most of the internet becomes unreachable (awg-quick
# only protects the endpoint for /0 routes).
#
# For those configurations the helper therefore runs awg-quick on a copy with
# "Table = off" (interface, keys, MTU and DNS only), installs the routes in one
# `ip -batch` call, and pins every endpoint that ended up inside the tunnel to
# the route it used before. The pins are recorded in $STATE_DIR and removed on
# `down`. Any failure rolls the tunnel back so the machine is never left
# offline. Configurations with a default route use plain awg-quick, which
# handles the endpoint itself through policy routing.

set -u
umask 077

# Per-tunnel state (route-less config copies, endpoint pins). Overridable for
# tests, which run the helper in an unprivileged network namespace.
STATE_DIR="${AWG_HELPER_STATE_DIR:-/run/amnezia-awg}"

# Tunnel names accepted by awg(8) / the AmneziaWG GUI.
NAME_RE='^[a-zA-Z0-9_=+.-]{1,15}$'
# Absolute path to a *.conf file; conservative character class to keep root
# command execution predictable.
PATH_RE='^/[a-zA-Z0-9_./+-]+\.conf$'

b64enc() { printf '%s' "$1" | base64 -w0 2>/dev/null || printf '%s' "$1" | base64; }
b64dec() { printf '%s' "$1" | base64 -d 2>/dev/null; }

ok()   { printf '%s\tOK\t%s\n' "$1" "$(b64enc "$2")"; }
err()  { printf '%s\tERR\t%s\t%s\n' "$1" "$2" "$(b64enc "$3")"; }

# Run a command, capturing combined output and its exit status.
OUT=""
RC=0
capture() {
    OUT=$("$@" 2>&1)
    RC=$?
}

# AllowedIPs entries of a configuration file, one per line.
allowed_ips() {
    sed -n 's/^[[:space:]]*[Aa][Ll][Ll][Oo][Ww][Ee][Dd][Ii][Pp][Ss][[:space:]]*=//p' "$1" |
        tr ',' '\n' | tr -d ' \t\r' | grep -v '^$'
}

# "<public key> TAB <comma separated AllowedIPs>" for every peer.
peer_allowed_ips() {
    awk '
        function flush() { if (pk != "" && ips != "") print pk "\t" ips; pk = ""; ips = "" }
        /^[[:space:]]*\[/ { flush(); next }
        {
            line = $0; sub(/#.*/, "", line)
            eq = index(line, "="); if (!eq) next
            key = tolower(substr(line, 1, eq - 1)); gsub(/[[:space:]]/, "", key)
            value = substr(line, eq + 1); gsub(/[[:space:]\r]/, "", value)
            if (key == "publickey") pk = value
            else if (key == "allowedips" && value != "") ips = (ips == "" ? value : ips "," value)
        }
        END { flush() }
    ' "$1"
}

has_default_route() {
    allowed_ips "$1" | grep -Eq '/0$'
}

# Bring up a split tunnel: interface through awg-quick, routes in one batch,
# endpoints pinned outside the tunnel. Sets OUT and RC like capture().
up_split() {
    local conf="$1" name="$2" copy pins log ep pre post via dev host len rc4 rc6 out
    mkdir -p "$STATE_DIR" || { OUT="Cannot create $STATE_DIR"; RC=1; return; }
    copy="$STATE_DIR/$name.conf"
    pins="$STATE_DIR/$name.pins"
    rm -f "$pins"
    # awg-quick's bash parser needs minutes for a long AllowedIPs line, so it
    # gets a copy without them (and without routes); they are applied below.
    awk 'tolower($0) ~ /^[[:space:]]*allowedips[[:space:]]*=/ { next }
         { print }
         /^[[:space:]]*\[[Ii]nterface\][[:space:]]*$/ && !done { print "Table = off"; done = 1 }' \
        "$conf" > "$copy"

    capture awg-quick up "$copy"
    log="$OUT"
    if [ "$RC" -ne 0 ]; then
        rm -f "$copy"
        return
    fi

    local pk list
    while IFS=$'\t' read -r pk list; do
        [ -n "$pk" ] && [ -n "$list" ] || continue
        if ! out="$(awg set "$name" peer "$pk" allowed-ips "$list" 2>&1)"; then
            OUT="$log"$'\n'"awg set allowed-ips failed: $out"
            awg-quick down "$copy" > /dev/null 2>&1
            rm -f "$copy"
            RC=1
            return
        fi
    done < <(peer_allowed_ips "$conf")
    log="$log"$'\n'"[#] awg set $name peer ... allowed-ips ($(allowed_ips "$conf" | wc -l) networks)"

    # Where each endpoint is reached now, before the tunnel's routes exist.
    declare -A before=()
    while read -r _ ep; do
        host="${ep%:*}"; host="${host#[}"; host="${host%]}"
        [ -n "$host" ] && [ "$host" != "(none)" ] && before["$host"]="$(ip -o route get "$host" 2>/dev/null | head -n1)"
    done < <(awg show "$name" endpoints 2>/dev/null)

    allowed_ips "$conf" | grep -v ':' | sed "s|^|route add |; s|\$| dev $name|" > "$STATE_DIR/$name.routes4"
    allowed_ips "$conf" | grep ':' | sed "s|^|route add |; s|\$| dev $name|" > "$STATE_DIR/$name.routes6"
    # -force: keep going past entries that already have a route, which
    # awg-quick would also leave alone.
    ip -4 -force -batch "$STATE_DIR/$name.routes4" > "$STATE_DIR/$name.batch.log" 2>&1; rc4=$?
    ip -6 -force -batch "$STATE_DIR/$name.routes6" >> "$STATE_DIR/$name.batch.log" 2>&1; rc6=$?
    log="$log"$'\n'"[#] ip -batch: $(wc -l < "$STATE_DIR/$name.routes4") IPv4 and $(wc -l < "$STATE_DIR/$name.routes6") IPv6 routes"
    if [ "$rc4" -ne 0 ] || [ "$rc6" -ne 0 ]; then
        log="$log"$'\n'"$(grep -v 'File exists' "$STATE_DIR/$name.batch.log" | head -n 20)"
    fi
    rm -f "$STATE_DIR/$name.routes4" "$STATE_DIR/$name.routes6" "$STATE_DIR/$name.batch.log"

    # Pin endpoints that the new routes captured to their previous path.
    for host in "${!before[@]}"; do
        post="$(ip -o route get "$host" 2>/dev/null | head -n1)"
        case " $post " in *" dev $name "*) ;; *) continue ;; esac
        pre="${before[$host]}"
        dev="$(printf '%s' "$pre" | sed -n 's/.* dev \([^ ]*\).*/\1/p')"
        via="$(printf '%s' "$pre" | sed -n 's/.* via \([^ ]*\).*/\1/p')"
        if [ -z "$dev" ] || [ "$dev" = "$name" ]; then
            OUT="$log"$'\n'"No route to the endpoint outside the tunnel; refusing to route it into itself"
            awg-quick down "$copy" > /dev/null 2>&1
            rm -f "$copy" "$pins"
            RC=1
            return
        fi
        case "$host" in *:*) len=128 ;; *) len=32 ;; esac
        if ip route add "$host/$len" ${via:+via "$via"} dev "$dev" 2>/dev/null; then
            printf '%s\n' "$host/$len ${via:+via $via }dev $dev" >> "$pins"
            log="$log"$'\n'"[#] ip route add $host/$len ${via:+via $via }dev $dev (keep the endpoint outside the tunnel)"
        fi
    done

    OUT="$log"
    RC=0
}

# Take a tunnel down and remove what up_split added. Sets OUT and RC.
down_tunnel() {
    local target="$1" name copy pins spec
    name="$(basename "$target" .conf)"
    copy="$STATE_DIR/$name.conf"
    pins="$STATE_DIR/$name.pins"
    if [ -f "$copy" ]; then capture awg-quick down "$copy"; else capture awg-quick down "$target"; fi
    if [ -f "$pins" ]; then
        while read -r spec; do
            # shellcheck disable=SC2086 # spec is "prefix [via gw] dev dev"
            [ -n "$spec" ] && ip route del $spec 2>/dev/null
        done < "$pins"
    fi
    rm -f "$copy" "$pins"
}

while IFS=$'\t' read -r -a req; do
    [ "${#req[@]}" -eq 0 ] && continue
    id="${req[0]}"
    cmd="${req[1]:-}"

    case "$cmd" in
        ping)
            ok "$id" "pong"
            ;;

        version)
            if [ -r /sys/module/amneziawg/version ]; then
                ok "$id" "$(cat /sys/module/amneziawg/version)"
            else
                capture awg --version
                if [ "$RC" -eq 0 ]; then ok "$id" "$OUT"; else err "$id" "version" "$OUT"; fi
            fi
            ;;

        ifaces)
            capture awg show interfaces
            if [ "$RC" -eq 0 ]; then ok "$id" "$OUT"; else err "$id" "ifaces" "$OUT"; fi
            ;;

        dump|latest)
            name="${req[2]:-}"
            if ! printf '%s' "$name" | grep -Eq "$NAME_RE"; then
                err "$id" "bad-name" "Invalid tunnel name: $name"
                continue
            fi
            capture awg show "$name" "$cmd"
            if [ "$RC" -eq 0 ]; then ok "$id" "$OUT"; else err "$id" "$cmd" "$OUT"; fi
            ;;

        up)
            target="${req[2]:-}"
            if ! printf '%s' "$target" | grep -Eq "$PATH_RE"; then
                err "$id" "bad-target" "Invalid config path: $target"
                continue
            fi
            name="$(basename "$target" .conf)"
            if ! printf '%s' "$name" | grep -Eq "$NAME_RE"; then
                err "$id" "bad-name" "Invalid tunnel name: $name"
                continue
            fi
            if has_default_route "$target"; then
                capture awg-quick up "$target"
            else
                up_split "$target" "$name"
            fi
            if [ "$RC" -eq 0 ]; then ok "$id" "$OUT"; else err "$id" "awg-quick-up" "$OUT"; fi
            ;;

        down)
            target="${req[2]:-}"
            if ! printf '%s' "$target" | grep -Eq "$PATH_RE" &&
               ! printf '%s' "$target" | grep -Eq "$NAME_RE"; then
                err "$id" "bad-target" "Invalid config path or tunnel name: $target"
                continue
            fi
            down_tunnel "$target"
            if [ "$RC" -eq 0 ]; then ok "$id" "$OUT"; else err "$id" "awg-quick-down" "$OUT"; fi
            ;;

        journal)
            lines="${req[2]:-200}"
            case "$lines" in ''|*[!0-9]*) lines=200 ;; esac
            [ "$lines" -gt 5000 ] && lines=5000
            capture journalctl --no-pager -o short-iso -n "$lines" -t awg-quick -t awg -t amnezia
            if [ "$RC" -eq 0 ]; then ok "$id" "$OUT"; else err "$id" "journal" "$OUT"; fi
            ;;

        quit)
            ok "$id" "bye"
            exit 0
            ;;

        "")
            # Empty line: ignore.
            ;;

        *)
            err "$id" "unknown-command" "Unknown command: $cmd"
            ;;
    esac
done
