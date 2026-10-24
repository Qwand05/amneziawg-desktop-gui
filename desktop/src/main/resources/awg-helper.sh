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
#   up <conf-path>             awg-quick up <conf-path>
#   down <conf-path|name>      awg-quick down <conf-path|name>
#   journal <lines>            recent awg/awg-quick journal entries
#   quit                       terminate the helper

set -u

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

        up|down)
            target="${req[2]:-}"
            if ! printf '%s' "$target" | grep -Eq "$PATH_RE" &&
               ! printf '%s' "$target" | grep -Eq "$NAME_RE"; then
                err "$id" "bad-target" "Invalid config path or tunnel name: $target"
                continue
            fi
            capture awg-quick "$cmd" "$target"
            if [ "$RC" -eq 0 ]; then ok "$id" "$OUT"; else err "$id" "awg-quick-$cmd" "$OUT"; fi
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
