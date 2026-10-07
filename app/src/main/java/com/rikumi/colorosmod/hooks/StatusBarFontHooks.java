package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.widget.TextView;
import android.widget.ProgressBar;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;

/** 状态栏及通知、控制中心顶部信息的字体。 */
public final class StatusBarFontHooks {
    private static final String[] FAMILIES = {"系统默认", "Inter", "Manrope", "Rubik", "Lato"};
    private static final WeakHashMap<TextView, Original> originals = new WeakHashMap<>();
    private static final WeakHashMap<Drawable, Boolean> batteryDrawables = new WeakHashMap<>();
    private static final HashMap<Integer, Typeface> fonts = new HashMap<>();
    private static final ThreadLocal<Boolean> applying = new ThreadLocal<>();
    private static Class<?> clockClass, keyguardClockClass, panelClockClass, panelDateClass, panelCarrierClass;
    private static Context moduleContext;
    private static boolean fontErrorLogged;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    public static void hookStatusBarFont(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            clockClass = XposedHelpers.findClass("com.oplus.systemui.statusbar.widget.StatClock", pkg.classLoader);
            keyguardClockClass = XposedHelpers.findClass("com.oplus.systemui.statusbar.widget.OplusKeyguardStatusBarClock", pkg.classLoader);
            panelClockClass = XposedHelpers.findClass("com.oplus.systemui.qs.widget.OplusQSClock", pkg.classLoader);
            panelDateClass = XposedHelpers.findClass("com.oplus.systemui.qs.widget.OplusQSDateView", pkg.classLoader);
            panelCarrierClass = XposedHelpers.findClass("com.oplus.systemui.qs.widget.OplusQSCarrierText", pkg.classLoader);
            XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class, TextView.BufferType.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!Boolean.TRUE.equals(applying.get()) && isClock((TextView) p.thisObject))
                                p.args[0] = alignColons((CharSequence) p.args[0], family() > 0);
                        }
                    });
            XposedHelpers.findAndHookMethod(TextView.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    TextView view = (TextView) p.thisObject;
                    if (isTarget(view)) applyStatusBarFont(view);
                }
            });
            for (boolean styled : new boolean[]{false, true}) {
                XC_MethodHook hook = new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        TextView view = (TextView) p.thisObject;
                        if (Boolean.TRUE.equals(applying.get()) || !isTarget(view)) return;
                        Original original = remember(view);
                        Typeface nativeFont = (Typeface) p.args[0];
                        if (styled) nativeFont = Typeface.create(nativeFont, (Integer) p.args[1]);
                        original.font = nativeFont;
                        Typeface replacement = resolve(view, nativeFont);
                        if (replacement != null) {
                            p.args[0] = replacement;
                            if (styled) p.args[1] = Typeface.NORMAL;
                        }
                        // styled 重载内部还会调用单参数版本，防止把替换字体记作原生字体。
                        applying.set(true);
                        p.setObjectExtra("statusFontApplying", true);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!Boolean.TRUE.equals(p.getObjectExtra("statusFontApplying"))) return;
                        applying.remove();
                        applyStatusBarFont((TextView) p.thisObject);
                    }
                };
                if (styled) XposedHelpers.findAndHookMethod(TextView.class, "setTypeface", Typeface.class, int.class, hook);
                else XposedHelpers.findAndHookMethod(TextView.class, "setTypeface", Typeface.class, hook);
            }
            XposedHelpers.findAndHookMethod(TextView.class, "setFontVariationSettings", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    TextView view = (TextView) p.thisObject;
                    if (Boolean.TRUE.equals(applying.get()) || !isTarget(view)) return;
                    remember(view).variation = (String) p.args[0];
                    if (family() > 0) p.setResult(false);
                }
            });
            // hot reload 后已有时钟未必重新 attach；测量前识别已有实例并应用字体。
            XposedHelpers.findAndHookMethod(TextView.class, "onMeasure", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    TextView view = (TextView) p.thisObject;
                    if (isTarget(view)) applyStatusBarFont(view);
                }
            });
            XposedHelpers.findAndHookMethod(clockClass, "onMeasure", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { applyStatusBarFont((TextView) p.thisObject); }
            });
            XposedHelpers.findAndHookMethod(panelClockClass, "onMeasure", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { applyStatusBarFont((TextView) p.thisObject); }
            });
            XposedHelpers.findAndHookMethod(TextView.class, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    TextView view = (TextView) p.thisObject;
                    if (isTarget(view)) applyStatusBarFont(view);
                }
            });
            hookBatteryFont(pkg);
            log("HOOK OK TextView / StatClock (status bar font)");
        } catch (Throwable t) { log("HOOK FAIL status bar font: " + t); }
    }

    private static boolean isTarget(TextView view) {
        if (originals.containsKey(view) || isClock(view)
                || (panelDateClass != null && panelDateClass.isInstance(view))
                || (panelCarrierClass != null && panelCarrierClass.isInstance(view))) return true;
        if (view.getId() == View.NO_ID) return false;
        try {
            String id = view.getResources().getResourceEntryName(view.getId());
            return "battery_percentage_view".equals(id) || "battery_text".equals(id);
        }
        catch (RuntimeException ignored) { return false; }
    }

    private static boolean isClock(TextView view) {
        return (clockClass != null && clockClass.isInstance(view))
                || (keyguardClockClass != null && keyguardClockClass.isInstance(view))
                || (panelClockClass != null && panelClockClass.isInstance(view));
    }

    private static CharSequence alignColons(CharSequence text, boolean enabled) {
        if (text == null) return null;
        CenteredColonSpan[] existing = text instanceof Spanned spanned
                ? spanned.getSpans(0, text.length(), CenteredColonSpan.class) : new CenteredColonSpan[0];
        if (enabled && existing.length > 0) return text;
        if (!enabled && existing.length == 0) return text;
        SpannableStringBuilder result = new SpannableStringBuilder(text);
        for (CenteredColonSpan span : existing) result.removeSpan(span);
        if (enabled) for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (c == ':' || c == '\uFF1A') result.setSpan(new CenteredColonSpan(), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return enabled && result.getSpans(0, result.length(), CenteredColonSpan.class).length == 0 ? text : result;
    }

    /** 仅调整冒号的绘制基线，按当前字重和字号对齐数字的可见中心。 */
    private static final class CenteredColonSpan extends ReplacementSpan {
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.round(paint.measureText(text, start, end));
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                                   int top, int y, int bottom, Paint paint) {
            Rect digit = new Rect(), colon = new Rect();
            String glyph = text.subSequence(start, end).toString();
            String reference = "0";
            for (int i = start - 1; i >= 0; i--) if (Character.isDigit(text.charAt(i))) {
                reference = text.subSequence(i, i + 1).toString(); break;
            }
            paint.getTextBounds(reference, 0, reference.length(), digit);
            paint.getTextBounds(glyph, 0, glyph.length(), colon);
            float offset = (digit.top + digit.bottom - colon.top - colon.bottom) / 2f;
            canvas.drawText(glyph, x, y + offset, paint);
        }
    }

    private static Original remember(TextView view) {
        Original original = originals.get(view);
        if (original == null) { original = new Original(view); originals.put(view, original); }
        return original;
    }

    public static void applyStatusBarFont(TextView view) {
        if (Boolean.TRUE.equals(applying.get())) return;
        Original original = remember(view);
        Typeface selected = resolve(view, original.font);
        Typeface target = selected == null ? original.font : selected;
        applying.set(true);
        try {
            if (view.getTypeface() != target) view.setTypeface(target);
            if (selected == null && !Objects.equals(view.getFontVariationSettings(), original.variation))
                view.setFontVariationSettings(original.variation);
            if (isClock(view)) {
                CharSequence text = view.getText(), aligned = alignColons(text, selected != null);
                if (aligned != text) view.setText(aligned);
            }
        } finally { applying.remove(); }
    }

    private static int family() { return Math.max(0, Math.min(4, readInt(KEY_STATUS_BAR_FONT, 0))); }
    private static Typeface resolve(TextView view, Typeface nativeFont) {
        return resolve(view.getContext(), nativeFont);
    }

    private static Typeface resolve(Context context, Typeface nativeFont) {
        int family = family();
        if (family == 0) return null;
        try {
            int selectedWeight = readInt(KEY_STATUS_BAR_FONT_WEIGHT, 0) * 100;
            int weight = selectedWeight == 0 ? 600 : selectedWeight;
            weight = Math.max(100, Math.min(900, weight));
            if (family == 2) weight = Math.max(200, Math.min(800, weight));
            if (family == 3) weight = Math.max(300, weight);
            if (family == 4) weight = weight >= 700 ? 700 : weight >= 600 ? 600 : weight >= 500 ? 500 : 400;
            int key = family * 1000 + weight;
            Typeface font = fonts.get(key);
            if (font != null) return font;
            if (moduleContext == null) moduleContext = context.createPackageContext(MODULE_PACKAGE, 0);
            String asset = family == 4 ? weight >= 700 ? "Lato-Bold.ttf" : weight >= 600 ? "Lato-SemiBold.ttf" : weight >= 500 ? "Lato-Medium.ttf" : "Lato-Regular.ttf"
                    : FAMILIES[family] + ".ttf";
            Typeface.Builder builder = new Typeface.Builder(moduleContext.getAssets(), "clock_fonts/" + asset);
            if (family != 4) builder.setFontVariationSettings("'wght' " + weight + (family == 1 ? ", 'opsz' 32" : ""));
            font = builder.setWeight(weight).setItalic(false).build();
            if (font == null) throw new IllegalStateException("Cannot load " + asset);
            fonts.put(key, font);
            return font;
        } catch (Exception | LinkageError t) {
            if (!fontErrorLogged) { log("status bar font load error: " + t); fontErrorLogged = true; }
            return null;
        }
    }

    public static void refresh() {
        handler.post(() -> {
            for (TextView view : new ArrayList<>(originals.keySet())) applyStatusBarFont(view);
            for (Drawable drawable : new ArrayList<>(batteryDrawables.keySet())) drawable.invalidateSelf();
        });
    }

    public static Object captureHotReloadHosts() {
        return new Object[]{new ArrayList<>(originals.keySet()), new ArrayList<>(batteryDrawables.keySet())};
    }

    public static void restoreHotReloadHosts(Object state) {
        if (state instanceof Object[] hosts) {
            if (hosts.length > 0) restoreHotReloadHosts(hosts[0]);
            if (hosts.length > 1 && hosts[1] instanceof List<?> list) {
                for (Object item : list) if (item instanceof Drawable drawable) {
                    batteryDrawables.put(drawable, true);
                    drawable.invalidateSelf();
                }
            }
            return;
        }
        if (state instanceof List<?> list) for (Object item : list) if (item instanceof TextView view) applyStatusBarFont(view);
    }

    public static void cleanupForHotReload() {
        handler.removeCallbacksAndMessages(null);
        applying.set(true);
        try {
            for (TextView view : new ArrayList<>(originals.keySet())) {
                Original original = originals.get(view);
                view.setTypeface(original.font);
                view.setFontVariationSettings(original.variation);
                if (isClock(view)) {
                    CharSequence text = view.getText(), restored = alignColons(text, false);
                    if (restored != text) view.setText(restored);
                }
            }
        } finally { applying.remove(); }
        originals.clear(); batteryDrawables.clear(); fonts.clear(); moduleContext = null;
    }

    /** 模块创建的制式文字直接覆盖渲染入口，避免依赖宿主对 TextView 方法的调用。 */
    public static final class StatusBarTextView extends TextView {
        public StatusBarTextView(Context context) { super(context); }
        @Override protected void onMeasure(int width, int height) {
            applyStatusBarFont(this);
            super.onMeasure(width, height);
        }
        @Override protected void onDraw(Canvas canvas) {
            applyStatusBarFont(this);
            super.onDraw(canvas);
        }
    }

    private static void hookBatteryFont(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            Class<?> binder = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.binder.BatteryViewBinder", pkg.classLoader);
            Class<?> battery = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.view.StatBatteryMeterView", pkg.classLoader);
            Class<?> percent = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.model.PercentOutIcon", pkg.classLoader);
            XposedHelpers.findAndHookMethod(binder, "bind$updatePercentOutView", TextView.class, battery, percent,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.args[0] instanceof TextView view) applyStatusBarFont(view);
                        }
                    });
            XposedHelpers.findAndHookMethod(binder, "bind$updateOldHorizontalViewContent", ProgressBar.class,
                    TextView.class, XposedHelpers.findClass(
                            "com.oplus.systemui.statusbar.pipeline.battery.ui.model.OldHorizontal", pkg.classLoader),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.args[1] instanceof TextView view) applyStatusBarFont(view);
                        }
                    });
            Class<?> drawable = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.HorizontalBatteryContentDrawable", pkg.classLoader);
            XposedHelpers.findAndHookMethod(drawable, "drawContent", Canvas.class, RectF.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Drawable owner = (Drawable) p.thisObject;
                    batteryDrawables.put(owner, true);
                    Paint paint = (Paint) XposedHelpers.getObjectField(owner, "percentInPaint");
                    Rect textRect = (Rect) XposedHelpers.getObjectField(owner, "textRect");
                    p.setObjectExtra("batteryFontPaint", new Paint(paint));
                    p.setObjectExtra("batteryFontRect", new Rect(textRect));
                    p.setObjectExtra("batteryFontDefault", XposedHelpers.getBooleanField(owner, "isDefaultTypeface"));
                    Typeface selected = resolve((Context) XposedHelpers.callMethod(owner, "getContext"), paint.getTypeface());
                    if (selected != null) {
                        paint.setTypeface(selected);
                        XposedHelpers.setBooleanField(owner, "isDefaultTypeface", false);
                    }
                    String text = (String) XposedHelpers.getObjectField(owner, "levelString");
                    paint.getTextBounds(text, 0, text.length(), textRect);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.getObjectExtra("batteryFontPaint") instanceof Paint saved)) return;
                    ((Paint) XposedHelpers.getObjectField(p.thisObject, "percentInPaint")).set(saved);
                    ((Rect) XposedHelpers.getObjectField(p.thisObject, "textRect")).set(
                            (Rect) p.getObjectExtra("batteryFontRect"));
                    XposedHelpers.setBooleanField(p.thisObject, "isDefaultTypeface",
                            (Boolean) p.getObjectExtra("batteryFontDefault"));
                }
            });
            log("HOOK OK BatteryViewBinder / HorizontalBatteryContentDrawable (status bar font)");
        } catch (Throwable t) { log("HOOK FAIL battery status bar font: " + t); }
    }

    private static final class Original {
        Typeface font;
        String variation;
        Original(TextView view) {
            font = view.getTypeface();
            variation = view.getFontVariationSettings();
        }
    }
}
