#!/system/bin/sh
# One-time removal of the retired module registration; preserve the font snapshots.
set -eu
FONTS=/data/adb/coloros-mod-fonts
current=
had_disable=0
trap 'status=$?; if [ -n "$current" ] && [ -d "$current" ] && [ "$had_disable" = 0 ]; then rm -f "$current/disable"; fi; exit "$status"' EXIT
for m in /data/adb/modules/coloros_mod /data/adb/modules/coloros_mod_bootanimation; do
    [ -d "$m" ] || continue
    current=$m
    had_disable=0; [ ! -e "$m/disable" ] || had_disable=1
    touch "$m/disable"
    # Stop only this module's watcher. Do not mark it for manager deletion while fonts are still inside it.
    for command in /proc/[0-9]*/cmdline; do
        [ -r "$command" ] || continue
        if tr '\000' '\n' < "$command" | grep -Fxq "$m/runtime.sh" \
            && tr '\000' '\n' < "$command" | grep -Fxq watch; then
            pid=${command#/proc/}; pid=${pid%%/*}
            [ "$pid" = "$$" ] || kill -TERM "$pid" 2>/dev/null || true
        fi
    done
    if [ -f "$m/runtime.sh" ]; then
        /system/bin/sh "$m/runtime.sh" boot >"$m/retirement.log" 2>&1 || exit 1
    fi
    exec 8>"$m/service.lock"
    flock -x 8 8>&8
    if [ -d "$m/fonts" ]; then
        exec 7>"$m/fonts/watch.lock"
        flock -x 7 7>&7
        exec 9>"$m/fonts/transaction.lock"
        flock -x 9 9>&9
        if [ -L "$FONTS" ] && [ "$(readlink "$FONTS")" = "$m/fonts" ]; then
            rm "$FONTS"
            if ! mv "$m/fonts" "$FONTS"; then ln -s "$m/fonts" "$FONTS"; exit 1; fi
        elif [ ! -e "$FONTS" ]; then
            mv "$m/fonts" "$FONTS"
        else
            # Never remove a second snapshot store when the user has already moved the active one.
            for file in "$m/fonts"/* "$m/fonts"/.[!.]*; do
                [ -e "$file" ] || [ -L "$file" ] || continue
                case "${file##*/}" in watch.lock|transaction.lock) ;; *) exit 1;; esac
            done
        fi
        exec 9>&-
        exec 7>&-
    fi
    rm -rf "$m"
    exec 8>&-
    current=
done
if [ -L "$FONTS" ] && [ ! -e "$FONTS" ]; then
    case "$(readlink "$FONTS")" in
        /data/adb/modules/coloros_mod/fonts|/data/adb/modules/coloros_mod_bootanimation/fonts) rm "$FONTS";;
    esac
fi
