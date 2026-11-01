package com.rikumi.colorosmod;

/** Device type, independent of window size, density and fold state. */
public final class TabletDevice {
    private static final boolean IS_TABLET = resolveTablet();

    private TabletDevice() {}

    public static boolean isTablet() {
        return IS_TABLET;
    }

    private static boolean resolveTablet() {
        try {
            Class<?> manager = Class.forName("com.oplus.content.OplusFeatureConfigManager");
            Object instance = manager.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(manager.getMethod("hasFeature", String.class)
                    .invoke(instance, "oplus.hardware.type.tablet"));
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            // Unknown devices must not expose tablet-only options.
            return false;
        }
    }
}
