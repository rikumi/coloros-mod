#!/system/bin/sh
# backend-format=2
# Public font_data_file cache + a small set of XML mounts. No /system/fonts mirror.
set -eu
BASE=/data/adb/coloros-mod-fonts
SELF=$BASE/backend.sh
CACHE=/data/fonts/files/coloros-mod-webview
TAB=$(printf '\t')
fail() { echo "coloros-mod fonts: $*" >&2; exit 1; }
valid_version() { [ ${#1} -eq 64 ] && case "$1" in *[!0-9a-f]*) return 1;; *) return 0;; esac; }
config_target() {
    case "$1" in
        /system/etc/fonts.xml|/system/etc/font_fallback.xml|/system_ext/etc/fonts_base.xml|/product/etc/fonts_customization.xml) return 0;;
        *) return 1;;
    esac
}
# Bind sources must belong to the namespace being modified. /proc/1/root paths
# retain init's mount objects and are rejected by the kernel in other namespaces.
init_path() { echo "$BASE/v2/$1"; }
in_namespace() {
    target_pid=$1; shift
    # Toybox nsenter has no --root option. chroot also resets cwd to the target tree.
    nsenter -t "$target_pid" -m chroot "/proc/$target_pid/root" /system/bin/sh "$SELF" "$@"
}
# Root namespace-management daemons keep deliberately unmounted/private trees. They
# are not font consumers: entering them can make the bind appear successful while
# their mount filtering removes it, then MS_PRIVATE fails and rolls back every app.
# Include init and actual Zygotes as inheritance roots; otherwise select Android
# services/apps (including isolated WebView UIDs), never root housekeeping workers.
namespace_candidate() {
    [ "$1" = 1 ] && return 0
    name=$(tr '\000' '\n' < "/proc/$1/cmdline" 2>/dev/null | head -n 1)
    case "$name" in zygote|zygote64|webview_zygote|chrome_zygote|com.android.chrome_zygote) return 0;; esac
    uid=$(awk '$1 == "Uid:" { print $2; exit }' "/proc/$1/status" 2>/dev/null)
    case "$uid" in ''|0) return 1;; *) return 0;; esac
}
namespace_id() { readlink "/proc/$1/ns/mnt" 2>/dev/null | tr -cd '0-9'; }
namespace_matches() { [ -n "$2" ] && [ "$(namespace_id "$1")" = "$2" ]; }
# XML markers identify inherited mounts even when the namespace ID changes after Zygote restarts.
current_version() { sed -n 's/.*<!-- coloros-mod-fonts:\([0-9a-f]*\) -->.*/\1/p' /system/etc/fonts.xml; }
mounted_here() { awk -v target="$1" '$5 == target { found=1 } END { exit !found }' /proc/self/mountinfo; }
cleanup_legacy() {
    [ ! -f /system/fonts/.coloros-mod-fonts ] || fail 'restore the previous active font-directory backend first'
    for legacy in "$BASE"/prepared/*; do
        [ -d "$legacy" ] || continue
        valid_version "${legacy##*/}" || continue
        # V1 accidentally retained shared peers. Isolate each owned mount before detaching it.
        while mounted_here "$legacy/fonts"; do
            mount -t tmpfs -o rslave none "$legacy/fonts"
            umount -l "$legacy/fonts"
        done
        # Shared propagation could leave an additional child mount below the former self-bind.
        awk -v prefix="$legacy/fonts/" 'index($5,prefix)==1 {print $5}' /proc/self/mountinfo | sort -u |
        while IFS= read -r leftover; do
            while mounted_here "$leftover"; do
                mount -t tmpfs -o private none "$leftover"
                umount -l "$leftover"
            done
        done
    done
    if mounted_here "$BASE/stock-fonts"; then
        mount -t tmpfs -o rslave none "$BASE/stock-fonts"
        umount -l "$BASE/stock-fonts"
    fi
}

if [ "${1:-}" = worker ]; then
    action=$2; version=$3
    # Check inside nsenter too: an app can unshare between the parent's check and entry.
    expected_namespace=${4:-}
    if [ -n "$expected_namespace" ] && ! namespace_matches self "$expected_namespace"; then
        echo 'coloros-mod fonts: process namespace changed before worker entry' >&2
        exit 75
    fi
    cleanup_legacy
    if [ "$action" = prepare ]; then
        valid_version "$version" || fail 'invalid snapshot'
        input=$BASE/snapshots/$version
        stage=$BASE/v2/$version
        # Recovery only publishes immutable hash-named files. Never truncate a mapped font.
        mkdir -p "$CACHE" "$stage/configs"
        chmod 0755 "$CACHE"
        chcon u:object_r:font_data_file:s0 "$CACHE"
        for file in "$input"/font-*.ttf; do
            [ -f "$file" ] || continue
            name=${file##*/}; name=${name#font-}; name=${name%.ttf}
            case "$name" in *[!0-9a-f]*) fail 'invalid font hash';; esac
            [ ${#name} -eq 64 ] || fail 'invalid font hash'
            if [ ! -f "$CACHE/$name.font" ]; then
                cp "$file" "$CACHE/$name.tmp"
                chmod 0644 "$CACHE/$name.tmp"
                chcon u:object_r:font_data_file:s0 "$CACHE/$name.tmp"
                mv "$CACHE/$name.tmp" "$CACHE/$name.font"
            fi
        done
        if [ -f "$stage/ready" ]; then exit 0; fi
        : > "$stage/configs.list"
        while IFS="$TAB" read -r target name; do
            config_target "$target" || fail 'unexpected configuration target'
            case "$name" in cfg-[0-3].xml) ;; *) fail 'invalid configuration name';; esac
            [ -f "$target" ] || fail "missing configuration: $target"
            # The APK inserts and validates the marker as a DOM node. Never edit XML text here:
            # its first line can already contain the beginning of a multiline stock comment.
            grep -q "<!-- coloros-mod-fonts:$version -->" "$input/$name" || fail 'missing snapshot marker'
            cp "$input/$name" "$stage/configs/$name"
            chmod 0444 "$stage/configs/$name"
            chcon u:object_r:system_file:s0 "$stage/configs/$name"
            printf '%s\t%s\n' "$target" "$name" >> "$stage/configs.list"
        done < "$input/configs.list"
        grep -q '^/system/etc/fonts.xml' "$stage/configs.list" || fail 'missing primary XML'
        touch "$stage/ready"
        exit 0
    fi
    old=$(current_version)
    [ -z "$old" ] || valid_version "$old" || fail 'unrecognized font XML mount'
    list_version=${version:-${old:-$(cat "$BASE/requested" 2>/dev/null || true)}}
    [ -n "$list_version" ] || exit 0
    valid_version "$list_version" || fail 'invalid transaction snapshot'
    target_list=$(init_path "$list_version")/configs.list
    [ -f "$target_list" ] || fail 'missing transaction snapshot'
    version_at() { sed -n 's/.*<!-- coloros-mod-fonts:\([0-9a-f]*\) -->.*/\1/p' "$1"; }
    is_mountpoint() {
        canonical=$(readlink -f "$1")
        awk -v target="$canonical" '$5 == target { found=1 } END { exit !found }' /proc/self/mountinfo
    }
    owns_all() {
        [ -n "$1" ] || return 0
        src=$(init_path "$1")
        [ -f "$src/ready" ] || return 1
        while IFS="$TAB" read -r target name; do
            [ "$target" -ef "$src/configs/$name" ] || return 1
        done < "$src/configs.list"
    }
    detach() {
        while IFS="$TAB" read -r target name; do
            tag=$(version_at "$target")
            if [ -n "$tag" ]; then umount "$target" || return 1; fi
        done < "$target_list"
    }
    attach() {
        [ -n "$1" ] || return 0
        src=$(init_path "$1")
        [ -f "$src/ready" ] || return 1
        while IFS="$TAB" read -r target name; do
            mount --bind "$src/configs/$name" "$target" || return 1
            # Bind sources and destinations must not join the /system shared peer group.
            # Toybox treats a one-argument propagation command as an fstab lookup. Supplying a
            # dummy source and explicit type bypasses both fstab and bind auto-detection; the kernel
            # ignores source/type for MS_PRIVATE. XML is immutable and published with mode 0444.
            # Keep the just-bound mount pinned while changing propagation. Re-resolving
            # the pathname can race with inherited/propagated mount changes at startup.
            exec 8<"$target" || return 1
            if ! [ /proc/self/fd/8 -ef "$src/configs/$name" ]; then
                exec 8<&-
                echo "coloros-mod fonts: bind source changed: $target" >&2
                return 1
            fi
            result=0
            mount -t tmpfs -o private none "/proc/$$/fd/8" || result=$?
            exec 8<&-
            if [ "$result" != 0 ]; then
                echo "coloros-mod fonts: private failed: pid=$$ ns=$(readlink /proc/self/ns/mnt) target=$target" >&2
                return 1
            fi
        done < "$src/configs.list"
    }
    remove_partial() {
        [ -n "$1" ] || return 0
        src=$(init_path "$1")
        while IFS="$TAB" read -r target name; do
            if [ "$target" -ef "$src/configs/$name" ]; then umount "$target" || return 1; fi
        done < "$src/configs.list"
    }
    [ -z "$version" ] || valid_version "$version" || fail 'invalid snapshot'
    # A killed worker can leave a mix of stock and different owned XML snapshots. Preflight every
    # file, then remove only recognized owned mounts. Retrying repairs that mix instead of stacking.
    owned_count=0
    while IFS="$TAB" read -r target name; do
        tag=$(version_at "$target")
        if [ -n "$tag" ]; then
            owned_count=$((owned_count + 1))
            valid_version "$tag" || fail 'unrecognized font XML mount'
            owner=$(init_path "$tag")
            [ "$target" -ef "$owner/configs/$name" ] || fail 'mount ownership changed; left it untouched'
        elif [ -n "$old" ] && is_mountpoint "$target"; then
            fail 'configuration replaced externally; left it untouched'
        fi
    done < "$target_list"
    if [ "$old" = "$version" ] && owns_all "$version"; then
        if [ -n "$version" ] || [ "$owned_count" = 0 ]; then exit 0; fi
    fi
    if ! detach "$old"; then
        remove_partial "$old" || fail 'cannot remove old partial snapshot'
        attach "$old" || fail 'cannot restore previous snapshot'
        fail 'cannot detach previous snapshot'
    fi
    if ! attach "$version"; then
        remove_partial "$version" || fail 'cannot remove partial snapshot'
        attach "$old" || fail 'cannot restore previous snapshot'
        fail 'mount failed; restored previous snapshot'
    fi
    owns_all "$version" || fail 'XML mount verification failed'
    exit 0
fi

[ "$(id -u)" = 0 ] || fail 'root is required'
mkdir -p "$BASE"
chmod 0700 "$BASE"
# Kernel-managed lock: startup recovery and preference updates must wait for one
# another, and a crashed process must never leave a permanent busy marker.
exec 9>"$BASE/transaction.lock"
# mksh marks exec-opened descriptors close-on-exec; explicitly pass this one.
flock -x 9 9>&9 || fail 'cannot acquire transaction lock'
# Let a transaction started by an older APK finish before migrating its mkdir lock.
legacy_owner=$(cat "$BASE/lock/pid" 2>/dev/null || true)
case "$legacy_owner" in
    ''|*[!0-9]*) ;;
    *) while [ "$legacy_owner" != "$$" ] && kill -0 "$legacy_owner" 2>/dev/null \
            && grep -Fq "$SELF" "/proc/$legacy_owner/cmdline" 2>/dev/null; do sleep 1; done;;
esac
rm -rf "$BASE/lock"
trap 'exit 1' HUP INT TERM
mode=${1:-resume}
case "$mode" in
    apply)
        input=$2; version=$3
        valid_version "$version" || fail 'invalid snapshot'
        if [ ! -f "$BASE/snapshots/$version/complete" ]; then
            rm -rf "$BASE/snapshots/$version.tmp"
            mkdir -p "$BASE/snapshots/$version.tmp"
            cp "$input"/* "$BASE/snapshots/$version.tmp/"
            mv "$BASE/snapshots/$version.tmp" "$BASE/snapshots/$version"
        fi
        # Persist intent before mounting. An interrupted attempt must still recover on next startup.
        printf '%s\n' "$version" > "$BASE/requested.tmp"
        mv "$BASE/requested.tmp" "$BASE/requested"
        ;;
    resume)
        version=$(cat "$BASE/requested" 2>/dev/null || true)
        [ -n "$version" ] || exit 0
        valid_version "$version" || fail 'invalid saved snapshot'
        ;;
    restore) version= ;;
    *) fail 'invalid action';;
esac
[ -z "$version" ] || in_namespace 1 worker prepare "$version"
: > "$BASE/transaction"
seen=' '
for pid in 1 $(pidof zygote zygote64 webview_zygote chrome_zygote 2>/dev/null || true) $(
    for process in /proc/[0-9]*; do
        [ -f "$process/root/system/etc/fonts.xml" ] && echo "${process##*/}"
    done
); do
    [ -r "/proc/$pid/ns/mnt" ] || continue
    namespace_candidate "$pid" || continue
    ns=$(namespace_id "$pid")
    [ -n "$ns" ] || continue
    case "$seen" in *" $ns "*) continue;; esac
    if ! old=$(sed -n 's/.*<!-- coloros-mod-fonts:\([0-9a-f]*\) -->.*/\1/p' "/proc/$pid/root/system/etc/fonts.xml" 2>/dev/null); then
        namespace_matches "$pid" "$ns" || continue
        fail "cannot read font XML: pid=$pid ns=$ns"
    fi
    namespace_matches "$pid" "$ns" || continue
    [ -z "$old" ] || valid_version "$old" || fail 'unrecognized inherited XML'
    # Mark seen only after a stable read, allowing another process in the same namespace to represent it.
    seen="$seen$ns "
    printf '%s\t%s\t%s\n' "$pid" "$ns" "$old" >> "$BASE/transaction"
done
okay=1
while IFS="$TAB" read -r pid ns old; do
    namespace_matches "$pid" "$ns" || continue
    if ! in_namespace "$pid" worker apply "$version" "$ns"; then
        namespace_matches "$pid" "$ns" || continue
        echo "coloros-mod fonts: failed namespace pid=$pid" >&2
        okay=0; break
    fi
done < "$BASE/transaction"
if [ "$okay" != 1 ]; then
    rollback=1
    while IFS="$TAB" read -r pid ns old; do
        namespace_matches "$pid" "$ns" || continue
        if ! in_namespace "$pid" worker apply "${old:-}" "$ns"; then
            namespace_matches "$pid" "$ns" || continue
            rollback=0
        fi
    done < "$BASE/transaction"
    fail "namespace update failed; rollback completed=$rollback"
fi
printf '%s\n' "$version" > "$BASE/current.tmp"
mv "$BASE/current.tmp" "$BASE/current"
if [ "$mode" = restore ]; then rm -f "$BASE/requested"; fi
echo "coloros-mod fonts: applied ${version:-stock} to$seen"
