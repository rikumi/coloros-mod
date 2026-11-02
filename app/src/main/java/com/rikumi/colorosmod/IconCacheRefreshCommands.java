package com.rikumi.colorosmod;

/** Commands are fixed to icon-consuming system applications, never app data directories. */
public final class IconCacheRefreshCommands {
    public static final String ACTION = "com.rikumi.colorosmod.action.REFRESH_ICON_CACHE";
    public static final String CLEARED = "COLOROSMOD_LAUNCHER_ICONS_CLEARED";
    public static final String DONE = "COLOROSMOD_ICON_CACHE_REFRESHED";

    public static String command(int user) {
        if (user < 0) throw new IllegalArgumentException("Invalid Android user");
        return String.format(java.util.Locale.ROOT, """
                [ "$(id -u)" = 0 ] || exit 1
                response=$(am broadcast --user %d --receiver-foreground -a %s -p com.android.launcher) || exit 1
                printf '%%s\\n' "$response"
                case "$response" in *%s*) ;; *) exit 1 ;; esac
                for package in com.android.launcher com.android.systemui com.android.settings com.oplus.safecenter com.oplus.securitypermission com.android.packageinstaller; do
                    for pid in $(pidof "$package"); do
                        case "$pid" in ''|*[!0-9]*|0|1) exit 1 ;; esac
                        [ -r "/proc/$pid/status" ] || continue
                        uid=$(awk '/^Uid:/ { print $2 }' "/proc/$pid/status")
                        case "$uid" in ''|*[!0-9]*) exit 1 ;; esac
                        if [ "$((uid / 100000))" = %d ] || [ "$package" = com.android.systemui ]; then
                            kill "$pid" || { [ ! -d "/proc/$pid" ] || exit 1; }
                        fi
                    done
                done
                echo %s
                """, user, ACTION, CLEARED, user, DONE);
    }

    private IconCacheRefreshCommands() {}
}
