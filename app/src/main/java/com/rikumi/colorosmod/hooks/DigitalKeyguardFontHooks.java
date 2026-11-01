package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.widget.TextView;
import android.view.View;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Keep ColorOS 17 clock measurement, RT Paint and AOD transitions on the same font. */
final class DigitalKeyguardFontHooks {
    private static final HashSet<Class<?>> installed = new HashSet<>();
    private static final WeakHashMap<View, Boolean> hosts = new WeakHashMap<>();
    private static boolean active = true;
    private static boolean textHooked;
    private static final WeakHashMap<TextView, Boolean> fontPadding = new WeakHashMap<>();
    private static final ThreadLocal<View> layoutClock = new ThreadLocal<>();
    private static final WeakHashMap<TextView, String> fontFeatures = new WeakHashMap<>();

    static void trackText(View host, TextView text) {
        track(host);
        if (active && readIntCached("keyguard_clock_font", 1) > 0) prepareText(text);
    }

    private static void prepareText(TextView text) {
        fontPadding.putIfAbsent(text, text.getIncludeFontPadding());
        if (text.getIncludeFontPadding()) text.setIncludeFontPadding(false);
        CharSequence styled = glyphText(text, text.getText());
        if (styled != text.getText()) text.setText(styled);
    }

    static void track(View host) {
        if (!active || hosts.containsKey(host)) return;
        try {
            Class<?> digit = XposedHelpers.findClass(
                    "com.oplus.keyguard.clock.digital.ui.view.DigitalTimeView", host.getClass().getClassLoader());
            if (installed.add(digit)) {
                if (!textHooked) {
                    textHooked = true;
                    XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class,
                            TextView.BufferType.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            TextView text = (TextView) p.thisObject;
                            if (active && fontPadding.containsKey(text)) p.args[0] = glyphText(text, (CharSequence) p.args[0]);
                        }
                    });
                }
                hookNativeSpacing(host.getClass().getClassLoader());
                for (var method : digit.getDeclaredMethods()) {
                    if (!method.getName().equals("setFontStyle") && !method.getName().equals("setFixedFontStyle")) continue;
                    if (method.getParameterCount() < 4 || method.getParameterTypes()[3] != Typeface.class) continue;
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            if (!active) return;
                            View view = (View) p.thisObject;
                            View clock = clockHost(view);
                            if (clock == null || !KeyguardClockFontHooks.defaultTheme(clock)) return;
                            int family = Math.max(0, Math.min(4, readIntCached("keyguard_clock_font", 1)));
                            int weight = Math.max(0, Math.min(9, readIntCached("keyguard_clock_weight", 3))) * 100;
                            if (family == 0 && weight == 0) return;
                            p.args[3] = KeyguardClockFontHooks.resolve(view.getContext(), family, weight, (Typeface) p.args[3]);
                            if (weight > 0) p.args[1] = weight;
                            // Inter and other replacement fonts have no HGHT axis. Native
                            // tunable-font height constraints would crop their natural glyphs.
                            if (family != 0) p.args[0] = "ColorOSModFont" + family;
                        }
                    });
                }
            }
            hosts.put(host, true);
            refreshHost(host);
        } catch (Throwable error) { log("digital keyguard font hook failed: " + error); }
    }

    private static View clockHost(View view) {
        for (View current = view; current != null;
                current = current.getParent() instanceof View parent ? parent : null)
            if (current.getClass().getName().equals("com.oplus.keyguard.clock.digital.ui.view.ClockTimeView")) return current;
        return null;
    }

    private static void refreshHost(View host) {
        // Capture before the native setter changes both visible and animation digits.
        for (Object digit : (Iterable<?>) XposedHelpers.callMethod(host, "getDigitalTimeViews"))
            for (String getter : new String[]{"getVisibleTextView", "getInVisibleTextView"})
                if (XposedHelpers.callMethod(digit, getter) instanceof TextView text
                        && !fontFeatures.containsKey(text)) fontFeatures.put(text, text.getFontFeatureSettings());
        XposedHelpers.callMethod(host, "applyFontStyle", false, false,
                XposedHelpers.getIntField(host, "appliedFontWeight"), XposedHelpers.getIntField(host, "appliedFontHeight"));
        // Drop a native typeface retained only for animation measurement.
        XposedHelpers.callMethod(host, "setFixedFontStyle",
                XposedHelpers.getIntField(host, "appliedFontWeight"), XposedHelpers.getIntField(host, "appliedFontHeight"), true);
        if (active && KeyguardClockFontHooks.defaultTheme(host))
            XposedHelpers.callMethod(host, "applyFontFeatureSettings", readBoolCached("keyguard_clock_monospace", true));
        for (Object digit : (Iterable<?>) XposedHelpers.callMethod(host, "getDigitalTimeViews")) {
            for (String getter : new String[]{"getVisibleTextView", "getInVisibleTextView"}) {
                Object child = XposedHelpers.callMethod(digit, getter);
                if (!(child instanceof TextView text)) continue;
                int family = readIntCached("keyguard_clock_font", 1);
                if (active && family > 0 && KeyguardClockFontHooks.defaultTheme(host)) {
                    prepareText(text);
                } else {
                    Boolean padding = fontPadding.remove(text);
                    if (padding != null) {
                        text.setIncludeFontPadding(padding);
                        removeGlyphSpans(text);
                    }
                }
            }
        }
        host.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnPreDrawListener(this);
                if (active) try { relayoutNative(host); }
                catch (Throwable error) { log("digital clock native relayout failed: " + error); }
                return true;
            }
        });
        host.requestLayout();
        host.invalidate();
    }

    private static final String PIPELINE = "com.oplus.keyguard.clock.digital.ui.pipeline.";

    private static void hookNativeSpacing(ClassLoader loader) {
        Class<?> scenes = XposedHelpers.findClass("com.oplus.keyguard.clock.digital.domain.state.Scene", loader);
        String[][] handlers = {{"KeyguardBigViewLayoutHandler", "KEYGUARD_BIG"},
                {"KeyguardSmallViewLayoutHandler", "KEYGUARD_SMALL"}, {"AodViewLayoutHandler", "AOD"}};
        for (String[] handler : handlers) {
            Class<?> type = XposedHelpers.findClass(PIPELINE + "handler." + handler[0], loader);
            Object scene = XposedHelpers.getStaticObjectField(scenes, handler[1]);
            for (String name : new String[]{"applyCenterHorizontalLayout", "applyStartHorizontalLayout", "applyCenterVerticalLayout"})
                XposedBridge.hookAllMethods(type, name, new XC_MethodHook() {});
            XposedBridge.hookAllMethods(type, "applyClockStyleLayout", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra("previousClockSpacing", layoutClock.get());
                    layoutClock.remove();
                    if (!active || readIntCached("keyguard_clock_font", 1) == 0) return;
                    Object map = XposedHelpers.callMethod(p.args[0], "getSceneViewMap");
                    Object entry = ((java.util.Map<?, ?>) map).get(scene);
                    if (entry == null) return;
                    Object single = XposedHelpers.callMethod(entry, "getSingleClockView");
                    Object clock = single == null ? null : XposedHelpers.callMethod(single, "getClockTimeView");
                    if (clock instanceof View view && KeyguardClockFontHooks.defaultTheme(view)) layoutClock.set(view);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object previous = p.getObjectExtra("previousClockSpacing");
                    if (previous instanceof View view) layoutClock.set(view); else layoutClock.remove();
                }
            });
            String gapMethod = handler[0].startsWith("Aod") ? "getAodTimeGap" : "getClockTimeMargin";
            XposedBridge.hookAllMethods(type, gapMethod, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Float gap = nativeGap();
                    if (gap != null && !p.hasThrowable()) {
                        if (gapMethod.equals("getAodTimeGap")) p.setResult(Math.round(gap));
                        else p.setResult(gap);
                    }
                }
            });
        }
        XposedBridge.hookAllMethods(XposedHelpers.findClass(PIPELINE + "RenderPipelineContext", loader),
                "getDimenSize", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View clock = layoutClock.get();
                Float gap = nativeGap();
                if (clock == null || gap == null || p.hasThrowable()) return;
                String name = clock.getResources().getResourceEntryName((Integer) p.args[0]);
                if (name.startsWith("digital_clock_") && name.contains("_time_margin_start"))
                    p.setResult(Math.round(gap));
            }
        });
    }

    private static Float nativeGap() {
        View clock = layoutClock.get();
        if (!active || clock == null || !readBoolCached("keyguard_clock_spacing_enabled", true)) return null;
        for (Object digit : (Iterable<?>) XposedHelpers.callMethod(clock, "getDigitalTimeViews"))
            if (XposedHelpers.callMethod(digit, "getVisibleTextView") instanceof TextView text) {
                float tracking = (Math.max(-6, Math.min(14, readIntCached("keyguard_clock_spacing", 0))) - 4) / 100f;
                return text.getTextSize() * tracking;
            }
        return null;
    }

    private static void relayoutNative(View host) {
        for (View parent = host; parent != null; parent = parent.getParent() instanceof View view ? view : null) {
            if (!parent.getClass().getName().equals("com.oplus.keyguard.clock.digital.ui.view.ClockViewRoot")) continue;
            Object state = XposedHelpers.callMethod(parent, "getRenderedViewState");
            if (state == null) return;
            ClassLoader loader = parent.getClass().getClassLoader();
            Object context = XposedHelpers.newInstance(XposedHelpers.findClass(PIPELINE + "RenderPipelineContext", loader),
                    parent, state, state, false, state);
            for (String name : new String[]{"KeyguardBigViewLayoutHandler", "KeyguardSmallViewLayoutHandler", "AodViewLayoutHandler"}) {
                Class<?> type = XposedHelpers.findClass(PIPELINE + "handler." + name, loader);
                XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(type, "INSTANCE"),
                        "runTransitionSyncLayout$KeyguardPersonalityClocks_release", context);
            }
            return;
        }
    }

    private static CharSequence glyphText(TextView owner, CharSequence text) {
        if (text == null || text.length() == 0 || text instanceof Spanned spans
                && spans.getSpans(0, text.length(), GlyphSpan.class).length > 0) return text;
        SpannableString result = new SpannableString(text);
        result.setSpan(new GlyphSpan(owner), 0, result.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }

    /** Use the digit ink bounds for line height, rather than the font's tall ascent. */
    private static final class GlyphSpan extends ReplacementSpan {
        final java.lang.ref.WeakReference<TextView> owner;
        GlyphSpan(TextView text) { owner = new java.lang.ref.WeakReference<>(text); }
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                Rect bounds = new Rect();
                paint.getTextBounds("0123456789", 0, 10, bounds);
                fm.top = fm.ascent = bounds.top;
                fm.bottom = fm.descent = bounds.bottom;
                fm.leading = 0;
            }
            return Math.max(1, Math.round(paint.measureText(text, start, end)));
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int y, int bottom, Paint paint) {
            String glyph = text.subSequence(start, end).toString();
            float offset = 0;
            if (glyph.equals(":") || glyph.equals("：")) {
                Rect digits = new Rect(), colon = new Rect();
                paint.getTextBounds("0123456789", 0, 10, digits);
                paint.getTextBounds(glyph, 0, glyph.length(), colon);
                offset = digits.exactCenterY() - colon.exactCenterY();
            }
            canvas.drawText(glyph, x, y + offset, paint);
        }
    }

    static void refresh() {
        if (active) for (View host : new ArrayList<>(hosts.keySet())) refreshHost(host);
    }

    static ArrayList<View> captureHosts() { return new ArrayList<>(hosts.keySet()); }

    private static void removeGlyphSpans(TextView text) {
        if (!(text.getText() instanceof Spanned spans)) return;
        GlyphSpan[] glyphs = spans.getSpans(0, spans.length(), GlyphSpan.class);
        if (glyphs.length == 0) return;
        SpannableString restored = new SpannableString(spans);
        for (GlyphSpan glyph : glyphs) restored.removeSpan(glyph);
        text.setText(restored);
    }

    static void cleanup() {
        active = false;
        for (View host : new ArrayList<>(hosts.keySet())) refreshHost(host);
        // Animation can retain text views that are no longer returned by the host.
        // Remove every old-generation span so those views cannot retain its loader.
        for (var entry : fontPadding.entrySet()) {
            entry.getKey().setIncludeFontPadding(entry.getValue());
            removeGlyphSpans(entry.getKey());
        }
        for (var entry : fontFeatures.entrySet()) entry.getKey().setFontFeatureSettings(entry.getValue());
        fontFeatures.clear();
        hosts.clear();
        fontPadding.clear();
    }
}
