import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Exercise the actual native bridge's ownership transfer with isolated module generations. */
public final class NativeFontReloadTest {
    private static final String NAME = "com.rikumi.colorosmod.hooks.NativeFontHooks";
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static Class<?> generation(URL[] path) throws Exception {
        return Class.forName(NAME, true, new URLClassLoader(path, ClassLoader.getPlatformClassLoader()));
    }
    public static void main(String[] args) throws Exception {
        URL[] path = {Path.of(args[0]).toUri().toURL(), Path.of(args[1]).toUri().toURL()};
        Class<?> first = generation(path);
        check(first.getMethod("captureHotReloadState").invoke(null) == null, "Inactive bridge has no native owner");
        first.getMethod("restoreHotReloadState", Object.class).invoke(null, String.class);
        check(!(Boolean) first.getMethod("isActive").invoke(null), "Unrelated owner cannot activate native hooks");
        Field active = first.getDeclaredField("active"), owner = first.getDeclaredField("libraryOwner");
        active.setAccessible(true); owner.setAccessible(true);
        // Simulate a successful native configure in the first generation only.
        active.setBoolean(null, true); owner.set(null, first);
        Object saved = first.getMethod("captureHotReloadState").invoke(null);
        for (int i = 0; i < 8; i++) {
            Class<?> next = generation(path);
            check(next != first, "Generation has a distinct class loader");
            next.getMethod("restoreHotReloadState", Object.class).invoke(null, saved);
            check((Boolean) next.getMethod("isActive").invoke(null), "Restored generation reuses native hooks");
            saved = next.getMethod("captureHotReloadState").invoke(null);
            check(saved == first, "Every generation retains only the original library owner");
            Class<?> module = Class.forName("com.rikumi.colorosmod.XposedInit", false, next.getClassLoader());
            // This would fail without early reuse: no Android runtime or native library is loaded.
            next.getMethod("hook", module).invoke(null, new Object[]{null});
            check(saved == next.getMethod("captureHotReloadState").invoke(null), "Hook does not replace/reinstall retained bridge");
        }
        System.out.println("PASS: " + checks + " native font reload checks across isolated class loaders");
    }
}
