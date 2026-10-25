import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.concurrent.*;

public final class ReflectionLookupTest {
    static class Parent {
        private int value(int n) { return n + 1; }
        private String text(String s) { return s == null ? "null" : s; }
        private void ambiguous(Number n) { }
        private void ambiguous(CharSequence s) { }
    }
    static class Child extends Parent { }
    public static class Fixture { public int value(int n) { return n; } }
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Child child = new Child();
        Method matched = XposedHelpers.findMethodBestMatch(Child.class, "value", Integer.class);
        check(matched == XposedHelpers.findMethodBestMatch(Child.class, "value", Integer.class));
        check(XposedHelpers.callMethod(child, "value", 3).equals(4));
        Method exact = XposedHelpers.findMethodExact(Child.class, "value", int.class);
        check(exact == XposedHelpers.findMethodExact(Child.class, "value", int.class));
        check(XposedHelpers.callMethod(child, "text", (Object) null).equals("null"));
        for (int i = 0; i < 2; i++) {
            try { XposedHelpers.findMethodBestMatch(Child.class, "ambiguous", (Class<?>) null); throw new AssertionError(); }
            catch (NoSuchMethodError expected) { check(expected.getMessage().contains("ambiguous")); }
        }
        byte[] bytes = Fixture.class.getResourceAsStream("ReflectionLookupTest$Fixture.class").readAllBytes();
        ClassLoader a = new ClassLoader(null) { Class<?> load() { return defineClass(Fixture.class.getName(), bytes, 0, bytes.length); } };
        ClassLoader b = new ClassLoader(null) { Class<?> load() { return defineClass(Fixture.class.getName(), bytes, 0, bytes.length); } };
        Method loadA = a.getClass().getDeclaredMethod("load"); loadA.setAccessible(true);
        Method loadB = b.getClass().getDeclaredMethod("load"); loadB.setAccessible(true);
        Class<?> ca = (Class<?>) loadA.invoke(a), cb = (Class<?>) loadB.invoke(b);
        check(ca != cb && ca.getName().equals(cb.getName()));
        check(XposedHelpers.findMethodBestMatch(ca, "value", Integer.class).getDeclaringClass() == ca);
        check(XposedHelpers.findMethodBestMatch(cb, "value", Integer.class).getDeclaringClass() == cb);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<?>[] jobs = new Future<?>[4];
            for (int t = 0; t < 4; t++) jobs[t] = pool.submit(() -> {
                for (int i = 0; i < 1000; i++) check(XposedHelpers.callMethod(child, "value", i).equals(i + 1));
            });
            for (Future<?> job : jobs) job.get();
        } finally { pool.shutdown(); }
        System.out.println("PASS: inherited/private, boxed, null, ambiguity, loader isolation, concurrent lookup");
    }
}
