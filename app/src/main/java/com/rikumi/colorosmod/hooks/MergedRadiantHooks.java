package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.util.TypedValue;
import android.text.TextUtils;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextSwitcher;
import android.widget.TextView;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.WeakHashMap;

/** Merged-shade colorful icons; highlight-card activation is confined to the icon circle. */
public final class MergedRadiantHooks {
    private static Class<?> highlightClass, normalClass, blurProvider, seekBarBlurManager, colorUtil, iconColorState, tileState, qsHelper;
    private static int activeAttr, inactiveAttr, inoperableAttr, unavailableAttr;
    private static boolean restoring, settingTint;
    private static final WeakHashMap<View, Card> cards = new WeakHashMap<>();
    private static final WeakHashMap<View, WeakReference<Object>> icons = new WeakHashMap<>();
    private static final WeakHashMap<Object, ColorStateList> nativeTints = new WeakHashMap<>();
    private static final HashMap<Integer, ColorStateList> tints = new HashMap<>();
    private static final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable refreshTask = MergedRadiantHooks::refreshNow;

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            highlightClass = XposedHelpers.findClass("com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView", pkg.classLoader);
            normalClass = XposedHelpers.findClass("com.oplus.systemui.qs.base.tile.OplusQSTileBaseView", pkg.classLoader);
            blurProvider = XposedHelpers.findClass("com.oplusos.systemui.common.util.QSBlurConfigProvider", pkg.classLoader);
            seekBarBlurManager = XposedHelpers.findClass("com.oplus.systemui.qs.base.util.QsSeekBarBlurManager", pkg.classLoader);
            colorUtil = XposedHelpers.findClass("com.oplus.systemui.qs.base.util.QsColorUtil", pkg.classLoader);
            qsHelper = XposedHelpers.findClass("com.oplus.systemui.qs.helper.QSFragmentHelper", pkg.classLoader);
            iconColorState = XposedHelpers.findClass("com.oplus.systemui.qs.base.res.model.TileIconColorState", pkg.classLoader);
            Class<?> triple = XposedHelpers.findClass("kotlin.Triple", pkg.classLoader);
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.base.res.drawable.MixColorTileDrawable",
                    pkg.classLoader, "updateColor", triple, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Drawable drawable = (Drawable) p.thisObject;
                            View tile = tileOwner(drawableOwner(drawable));
                            if (tile == null || !enabled(tile) || circleEnabled(tile)) return;
                            boolean active = false;
                            for (int state : drawable.getState())
                                if (state == activeAttr || state == inoperableAttr) active = true;
                            if (!active) return;
                            Object config = XposedHelpers.callStaticMethod(blurProvider, "getSeekBarActiveBlurConfig",
                                    tile.getResources().getConfiguration().isNightModeActive());
                            p.args[0] = XposedHelpers.newInstance(triple, config, 0,
                                    XposedHelpers.callMethod(p.args[0], "getThird"));
                        }
                    });
            Class<?> flags = XposedHelpers.findClass("com.oplus.systemui.qs.base.res.model.TileViewFlag", pkg.classLoader);
            activeAttr = attr(flags, "Active"); inactiveAttr = attr(flags, "Inactive");
            inoperableAttr = attr(flags, "Inoperable"); unavailableAttr = attr(flags, "Unavailable");
            XposedHelpers.findAndHookDeclaredMethod(highlightClass, "init", View.OnClickListener.class,
                    View.OnClickListener.class, View.OnLongClickListener.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            View tile = (View) p.thisObject;
                            bind(tile);
                            Card card = cards.get(tile);
                            View.OnClickListener primary = (View.OnClickListener) p.args[0];
                            View.OnClickListener secondary = (View.OnClickListener) p.args[1];
                            card.primary = new WeakReference<>(primary);
                            card.secondary = new WeakReference<>(secondary);
                            p.args[0] = routedClick(tile, card, primary, secondary);
                        }
                    });
            XposedHelpers.findAndHookDeclaredMethod(View.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View tile = (View) p.thisObject;
                    if (!highlightClass.isInstance(tile) || !circleEnabled(tile)) return;
                    Card card = cards.get(tile);
                    MotionEvent event = (MotionEvent) p.args[0];
                    if (card == null) return;
                    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        card.circleClick = false;
                        return;
                    }
                    if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                    View circle = card.circle == null ? null : card.circle.get();
                    if (circle == null || !(circle.getParent() instanceof View frame)) { card.circleClick = false; return; }
                    float dx = event.getX() - frame.getX() - circle.getX() - circle.getWidth() / 2f;
                    float dy = event.getY() - frame.getY() - circle.getY() - circle.getHeight() / 2f;
                    float radius = circle.getWidth() / 2f;
                    card.circleClick = dx * dx + dy * dy <= radius * radius;
                }
            });
            Class<?> state = XposedHelpers.findClass("com.android.systemui.plugins.qs.QSTile$State", pkg.classLoader);
            tileState = state;
            Class<?> proxy = XposedHelpers.findClass("com.oplus.systemui.qs.base.res.widget.QSIconViewProxy", pkg.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(proxy, "setTintList", ColorStateList.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View icon = (View) XposedHelpers.getObjectField(p.thisObject, "iconHostView");
                    if (!merged(icon)) return;
                    icons.put(icon, new WeakReference<>(p.thisObject));
                    if (!settingTint) nativeTints.put(p.thisObject, (ColorStateList) p.args[0]);
                    if (enabled(icon) || circleEnabled(icon)) p.args[0] = tintForIcon(icon);
                }
            });
            XposedHelpers.findAndHookDeclaredMethod(proxy, "setIcon", state, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View icon = (View) XposedHelpers.getObjectField(p.thisObject, "iconHostView");
                    if (!merged(icon)) return;
                    icons.put(icon, new WeakReference<>(p.thisObject));
                    if (!nativeTints.containsKey(p.thisObject))
                        nativeTints.put(p.thisObject, (ColorStateList) XposedHelpers.getObjectField(p.thisObject, "tintList"));
                    if (enabled(icon) || circleEnabled(icon)) setTint(p.thisObject,
                            tint(enabled(icon) ? XposedHelpers.getIntField(p.args[0], "colorfulConfig") : Color.WHITE));
                }
            });
            XposedHelpers.findAndHookDeclaredMethod(highlightClass, "handleStateChanged", state, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View tile = (View) p.thisObject;
                    if (merged(tile)) bind(tile);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View tile = (View) p.thisObject;
                    if (merged(tile)) updateCard(tile);
                }
            });
            XposedHelpers.findAndHookDeclaredMethod(highlightClass, "onDrawableUpdate", java.util.List.class,
                    boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            View tile = (View) p.thisObject;
                            if (merged(tile)) { bind(tile); updateCard(tile); }
                        }
                    });
            for (String name : new String[]{"MixColorTileDrawable", "GradientTileDrawable"})
                XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.base.res.drawable." + name,
                        pkg.classLoader, "onStateChange", int[].class, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (!iconOnlyBackground((Drawable) p.thisObject)) return;
                                int[] source = (int[]) p.args[0];
                                int[] result = source.clone();
                                for (int i = 0; i < result.length; i++)
                                    if (result[i] == activeAttr || result[i] == inoperableAttr) result[i] = inactiveAttr;
                                p.args[0] = result;
                            }
                        });
            log("HOOK OK merged control center radiant style");
        } catch (Throwable t) { log("HOOK FAIL merged control center radiant style: " + t); }
    }

    private static int attr(Class<?> flags, String name) {
        return (Integer) XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(flags, name), "getAttr");
    }

    private static boolean merged(View view) {
        for (View current = view; current != null; current = current.getParent() instanceof View parent ? parent : null) {
            String name = current.getClass().getName();
            if (name.equals("com.oplus.systemui.qs.OplusQSTileMediaContainer")
                    || name.equals("com.oplus.systemui.qs.widget.OplusHeaderTileLayout")
                    || name.equals("com.android.systemui.qs.TileLayout")
                    || name.equals("com.android.systemui.qs.SideLabelTileLayout")) return true;
        }
        return false;
    }
    private static boolean enabled(View view) {
        return !restoring && readBool(KEY_QS_MERGED_HOLLOW, false)
                && readBool(KEY_QS_MERGED_RADIANT, false) && merged(view);
    }
    private static boolean circleEnabled(View view) {
        if (restoring || !readBool(KEY_QS_MERGED_HOLLOW, false) || !merged(view)) return false;
        View tile = tileOwner(view);
        return tile != null && highlightClass.isInstance(tile);
    }
    private static View tileOwner(View view) {
        for (View current = view; current != null; current = current.getParent() instanceof View parent ? parent : null)
            if (highlightClass != null && (highlightClass.isInstance(current) || normalClass.isInstance(current))) return current;
        return null;
    }
    private static View drawableOwner(Drawable drawable) {
        if (drawable.getClass().getSimpleName().equals("MixColorTileDrawable")) {
            Object blur = XposedHelpers.getObjectField(drawable, "autoBlurDrawable");
            Object proxy = XposedHelpers.callMethod(blur, "getViewBlurProxy");
            return (View) XposedHelpers.callMethod(proxy, "getView");
        }
        Drawable.Callback callback = drawable.getCallback();
        for (int i = 0; callback instanceof Drawable parent && i < 12; i++) callback = parent.getCallback();
        return callback instanceof View view ? view : null;
    }
    static boolean iconOnlyBackground(Drawable drawable) {
        if (restoring || !readBool(KEY_QS_MERGED_HOLLOW, false)) return false;
        View owner = drawableOwner(drawable), tile = tileOwner(owner);
        return tile != null && highlightClass.isInstance(tile) && circleEnabled(tile)
                && owner == XposedHelpers.getObjectField(tile, "mBg");
    }
    static Integer activeBackground(Drawable drawable) {
        if (restoring || !readBool(KEY_QS_MERGED_RADIANT, false)) return null;
        View tile = tileOwner(drawableOwner(drawable));
        return tile != null && enabled(tile) && !circleEnabled(tile)
                ? (drawable.getClass().getSimpleName().equals("MixColorTileDrawable") ? 0 : activeWhite(tile)) : null;
    }
    private static int activeWhite(View view) {
        // Match OplusQsVerticalSeekBar.updateColor when mix-color rendering is unavailable.
        boolean theme = (Boolean) XposedHelpers.callStaticMethod(colorUtil, "isGlobalThemeApplied", view.getContext());
        boolean dark = (Boolean) XposedHelpers.callStaticMethod(colorUtil, "isNeedDarkThemeColor", view.getContext(), false);
        boolean lightIcons = (Boolean) XposedHelpers.callStaticMethod(colorUtil, "isIconNeedUseLightColor", view.getContext(), false);
        boolean nightIcons = (Boolean) XposedHelpers.callStaticMethod(colorUtil, "isIconNeedUseLightColorWhenDarkMode", view.getContext(), false);
        if (dark || (!theme && (lightIcons || nightIcons)))
            return (Integer) XposedHelpers.getStaticObjectField(colorUtil, "QS_TILE_BG_INACTIVE_COLOR");
        int id = view.getResources().getIdentifier("status_bar_qs_brightness_slider_progress_color", "color", "com.android.systemui");
        return view.getContext().getColor(id);
    }
    private static View.OnClickListener routedClick(View tile, Card card,
            View.OnClickListener primary, View.OnClickListener secondary) {
        return view -> {
            if (circleEnabled(tile) && !card.circleClick && secondary != null) secondary.onClick(view);
            else if (primary != null) primary.onClick(view);
            card.circleClick = false;
        };
    }

    private static ColorStateList tintForIcon(View icon) {
        if (!enabled(icon)) return tint(Color.WHITE);
        View tile = tileOwner(icon);
        Object state = tile == null ? null : XposedHelpers.getObjectField(tile, "mTempState");
        int active = state == null ? (Integer) XposedHelpers.getStaticObjectField(tileState, "DEFAULT_ICON_COLOR")
                : XposedHelpers.getIntField(state, "colorfulConfig");
        return tint(active);
    }
    private static ColorStateList tint(int active) {
        return tints.computeIfAbsent(active, color -> (ColorStateList) XposedHelpers.callMethod(
                XposedHelpers.newInstance(iconColorState, color, Color.WHITE, 0x66ffffff, true),
                "getColorStateList", color));
    }
    private static void setTint(Object proxy, ColorStateList tint) {
        boolean previous = settingTint; settingTint = true;
        try { XposedHelpers.callMethod(proxy, "setTintList", tint); }
        finally { settingTint = previous; }
    }
    private static void bind(View tile) {
        if (cards.containsKey(tile)) return;
        Card card = new Card();
        cards.put(tile, card);
        tile.addOnLayoutChangeListener(card.listener);
        // Existing tiles survive a module hot reload without running init again.
        // Reuse both native callbacks, including the secondary target's click gate.
        View indicator = (View) XposedHelpers.getObjectField(tile, "mIndicatorContainer");
        View.OnClickListener primary = clickListener(tile);
        View.OnClickListener secondary = indicator == null ? null : clickListener(indicator);
        if (primary != null && secondary != null) {
            card.primary = new WeakReference<>(primary);
            card.secondary = new WeakReference<>(secondary);
            tile.setOnClickListener(routedClick(tile, card, primary, secondary));
        }
    }
    private static View.OnClickListener clickListener(View view) {
        Object listeners = XposedHelpers.getObjectField(view, "mListenerInfo");
        return listeners == null ? null
                : (View.OnClickListener) XposedHelpers.getObjectField(listeners, "mOnClickListener");
    }
    private static void updateCard(View tile) {
        Card card = cards.get(tile);
        if (card == null) return;
        ViewGroup frame = (ViewGroup) XposedHelpers.getObjectField(tile, "mIconFrame");
        View circle = card.circle == null ? null : card.circle.get();
        View icon = (View) XposedHelpers.callMethod(tile, "getIcon");
        View label = (View) XposedHelpers.getObjectField(tile, "mLabelContainer");
        View indicator = (View) XposedHelpers.getObjectField(tile, "mIndicatorContainer");
        View arrow = (View) XposedHelpers.getObjectField(tile, "mExpandIndicator");
        if (card.nativeTranslationX == null) card.nativeTranslationX = icon.getTranslationX();
        if (card.indicatorVisibility == null && indicator != null) card.indicatorVisibility = indicator.getVisibility();
        float scale = MergedCardRatioHooks.ratioEnabled(tile) ? MergedCardRatioHooks.contentScale(tile) : 1f;
        float unit = tile.getResources().getDisplayMetrics().density * scale;
        updateSubtitle(tile, card, scale);
        ViewGroup.MarginLayoutParams labelParams = label != null && label.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params ? params : null;
        if (!circleEnabled(tile)) {
            if (circle != null) frame.removeView(circle);
            card.circle = null;
            icon.setTranslationX(card.nativeTranslationX);
            if (indicator != null && card.indicatorVisibility != null) indicator.setVisibility(card.indicatorVisibility);
            if (arrow != null) {
                Object state = XposedHelpers.getObjectField(tile, "mTempState");
                if (state != null) arrow.setVisibility(XposedHelpers.getBooleanField(state, "dualTarget") ? View.VISIBLE : View.GONE);
            }
            if (labelParams != null) {
                int start = Math.round(((Number) XposedHelpers.callMethod(tile, "getTitleMarginStart")).floatValue() * scale);
                if (labelParams.getMarginStart() != start || labelParams.getMarginEnd() != 0) {
                    labelParams.setMarginStart(start); labelParams.setMarginEnd(0); label.setLayoutParams(labelParams);
                }
            }
        } else {
            if (circle == null) {
                circle = new View(tile.getContext());
                card.circle = new WeakReference<>(circle);
                circle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                card.shape = new GradientDrawable(); card.shape.setShape(GradientDrawable.OVAL);
                circle.setBackground(card.shape);
                frame.addView(circle, Math.min(1, frame.getChildCount()), new FrameLayout.LayoutParams(1, 1));
            }
            int state = XposedHelpers.getIntField(tile, "mTileIconState");
            int activeColor = enabled(tile) ? activeWhite(tile)
                    : (Integer) XposedHelpers.callMethod(XposedHelpers.callStaticMethod(qsHelper, "getInstance"),
                            "getActiveColorWithDarkMode", tile.getContext());
            card.shape.setColor(state == 2 ? activeColor : 0x1affffff);
            // Flyme's circle_qs_icon_size is 38dp. Leave 12dp on each side of
            // the circle instead of anchoring a 42dp disk against the card edge.
            int size = Math.max(1, Math.round(38f * unit));
            Drawable leafBackground = backgroundLeaf(tile);
            boolean mix = leafBackground != null && leafBackground.getClass().getSimpleName().equals("MixColorTileDrawable");
            Drawable fill = card.shape;
            if (state == 2 && mix && enabled(tile)) {
                boolean night = tile.getResources().getConfiguration().isNightModeActive();
                Drawable activeShape = card.activeShape == null ? null : card.activeShape.get();
                if (activeShape == null || card.activeNight != night) {
                    activeShape = (Drawable) XposedHelpers.callMethod(
                            XposedHelpers.getStaticObjectField(seekBarBlurManager, "INSTANCE"), "getSeekBarActiveDrawable", circle);
                    card.activeShape = new WeakReference<>(activeShape);
                    card.activeNight = night;
                    card.activeSize = 0;
                }
                if (card.activeSize != size) {
                    Object proxy = XposedHelpers.callMethod(activeShape, "getViewBlurProxy");
                    Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
                    XposedHelpers.callMethod(config, "setCornerRadius", size / 2f);
                    // Seek bars clip the active drawable externally. This standalone
                    // circle needs the native static-blur corner clipping enabled.
                    XposedHelpers.callMethod(config, "setEnableStaticBlurCorner", true);
                    XposedHelpers.callMethod(proxy, "applyBlurConfig");
                    card.activeSize = size;
                }
                fill = activeShape;
            }
            if (circle.getBackground() != fill) circle.setBackground(fill);
            int inset = Math.round(12f * unit);
            int left = frame.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? frame.getWidth() - inset - size : inset;
            int top = (frame.getHeight() - size) / 2;
            circle.layout(left, top, left + size, top + size);
            icon.setTranslationX(left + size / 2f - icon.getLeft() - icon.getWidth() / 2f);
            if (indicator != null && indicator.getVisibility() != View.GONE) indicator.setVisibility(View.GONE);
            if (arrow != null && arrow.getVisibility() != View.GONE) arrow.setVisibility(View.GONE);
            if (labelParams != null) {
                int start = size + inset + Math.round(8f * unit);
                if (labelParams.getMarginStart() != start || labelParams.getMarginEnd() != inset) {
                    labelParams.setMarginStart(start); labelParams.setMarginEnd(inset); label.setLayoutParams(labelParams);
                }
            }
        }
        refreshBackground(tile);
        tile.invalidate();
    }
    private static Drawable backgroundLeaf(View tile) {
        Drawable leaf = (Drawable) XposedHelpers.getObjectField(tile, "mBgDrawable");
        while (leaf instanceof DrawableWrapper parent && parent.getDrawable() != null) {
            if (leaf.getClass().getSimpleName().equals("MixColorTileDrawable")
                    || leaf.getClass().getSimpleName().equals("GradientTileDrawable")) break;
            leaf = parent.getDrawable();
        }
        return leaf;
    }
    private static void updateSubtitle(View tile, Card card, float scale) {
        LinearLayout group = (LinearLayout) XposedHelpers.getObjectField(tile, "mLabelGroup");
        TextSwitcher title = (TextSwitcher) XposedHelpers.getObjectField(tile, "mLabel");
        if (group == null || title == null) return;
        TextSwitcher subtitle = card.subtitle == null ? null : card.subtitle.get();
        if (!circleEnabled(tile)) {
            if (subtitle != null) group.removeView(subtitle);
            card.subtitle = null;
            if (card.nativeLabelOrientation != null) group.setOrientation(card.nativeLabelOrientation);
            if (card.nativeTitleHeight != null && title.getLayoutParams().height != card.nativeTitleHeight) {
                ViewGroup.LayoutParams params = title.getLayoutParams();
                params.height = card.nativeTitleHeight; title.setLayoutParams(params);
            }
            return;
        }
        if (subtitle == null) {
            int layoutId = resource(tile, "oplus_separate_highlight_qs_tile_label", "layout");
            ViewGroup nativeLabels = (ViewGroup) LayoutInflater.from(tile.getContext()).inflate(layoutId, group, false);
            subtitle = nativeLabels.findViewById(resource(tile, "tile_label_desc", "id"));
            nativeLabels.removeView(subtitle);
            card.nativeLabelOrientation = group.getOrientation();
            card.nativeTitleHeight = title.getLayoutParams().height;
            group.setOrientation(LinearLayout.VERTICAL);
            ViewGroup.LayoutParams params = title.getLayoutParams();
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT; title.setLayoutParams(params);
            group.addView(subtitle, Math.min(1, group.getChildCount()), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.subtitle = new WeakReference<>(subtitle);
        }
        Object state = XposedHelpers.getObjectField(tile, "mTempState");
        CharSequence text = "";
        if (state != null && XposedHelpers.getIntField(state, "state") == 2) {
            String spec = (String) XposedHelpers.getObjectField(state, "spec");
            boolean network = "wifi".equals(spec) || "bt".equals(spec);
            CharSequence label = (CharSequence) XposedHelpers.getObjectField(state, "label");
            boolean connected = network && !TextUtils.isEmpty(label) && !TextUtils.equals(label,
                    tile.getResources().getString(resource(tile,
                            "wifi".equals(spec) ? "quick_settings_wifi_label" : "quick_settings_bluetooth_label", "string")));
            text = tile.getResources().getString(resource(tile,
                    connected ? "quick_settings_connected" : "oplus_qs_status_subtitle_open", "string"));
        }
        int visibility = TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE;
        if (subtitle.getVisibility() != visibility) subtitle.setVisibility(visibility);
        TextView current = (TextView) subtitle.getCurrentView();
        if (!TextUtils.equals(current.getText(), text)) subtitle.setCurrentText(text);
        float size = tile.getResources().getDimension(resource(tile, "qs_highlight_tile_text_desc_size", "dimen")) * scale;
        int lineHeight = Math.round(tile.getResources().getDimension(resource(tile, "qs_highlight_tile_label_desc_line_height", "dimen")) * scale);
        ColorStateList color = tile.getContext().getColorStateList(resource(tile,
                "status_bar_qs_highlight_tile_label_second_color_active", "color"));
        TextView titleText = (TextView) title.getCurrentView();
        // Both switchers share a start edge; do not let the description's
        // full-width container inherit centered text alignment from its theme.
        if (subtitle.getPaddingStart() != title.getPaddingStart() || subtitle.getPaddingEnd() != title.getPaddingEnd())
            subtitle.setPaddingRelative(title.getPaddingStart(), subtitle.getPaddingTop(),
                    title.getPaddingEnd(), subtitle.getPaddingBottom());
        LinearLayout.LayoutParams subtitleParams = (LinearLayout.LayoutParams) subtitle.getLayoutParams();
        LinearLayout.LayoutParams titleParams = (LinearLayout.LayoutParams) title.getLayoutParams();
        if (subtitleParams.gravity != Gravity.START
                || subtitleParams.getMarginStart() != titleParams.getMarginStart()
                || subtitleParams.getMarginEnd() != titleParams.getMarginEnd()) {
            subtitleParams.gravity = Gravity.START;
            subtitleParams.setMarginStart(titleParams.getMarginStart());
            subtitleParams.setMarginEnd(titleParams.getMarginEnd());
            subtitle.setLayoutParams(subtitleParams);
        }
        for (int i = 0; i < subtitle.getChildCount(); i++) {
            TextView view = (TextView) subtitle.getChildAt(i);
            view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            if (view.getPaddingStart() != titleText.getPaddingStart() || view.getPaddingEnd() != titleText.getPaddingEnd())
                view.setPaddingRelative(titleText.getPaddingStart(), view.getPaddingTop(),
                        titleText.getPaddingEnd(), view.getPaddingBottom());
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            if (params.gravity != (Gravity.START | Gravity.CENTER_VERTICAL)) {
                params.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                view.setLayoutParams(params);
            }
            if (view.getTextSize() != size) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            if (view.getLineHeight() != lineHeight) view.setLineHeight(lineHeight);
            if (!view.getTextColors().equals(color)) view.setTextColor(color);
        }
        if (titleText.getWidth() > 0 && current.getWidth() > 0) {
            boolean rtl = group.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float titleStart = title.getX() + titleText.getX() + (rtl
                    ? titleText.getWidth() - titleText.getCompoundPaddingRight() : titleText.getCompoundPaddingLeft());
            float subtitleStart = subtitle.getLeft() + current.getX() + (rtl
                    ? current.getWidth() - current.getCompoundPaddingRight() : current.getCompoundPaddingLeft());
            if (titleText.getLayout() != null && current.getLayout() != null) {
                if (rtl) {
                    titleStart += titleText.getLayout().getLineRight(0)
                            - (titleText.getWidth() - titleText.getCompoundPaddingLeft() - titleText.getCompoundPaddingRight());
                    subtitleStart += current.getLayout().getLineRight(0)
                            - (current.getWidth() - current.getCompoundPaddingLeft() - current.getCompoundPaddingRight());
                } else {
                    titleStart += titleText.getLayout().getLineLeft(0);
                    subtitleStart += current.getLayout().getLineLeft(0);
                }
            }
            // Match the perceived start edge of the smaller description text.
            float offset = titleStart - subtitleStart
                    + (rtl ? 1f : -1f) * tile.getResources().getDisplayMetrics().density * scale;
            if (subtitle.getTranslationX() != offset) subtitle.setTranslationX(offset);
        }
    }
    private static int resource(View tile, String name, String type) {
        return tile.getResources().getIdentifier(name, type, "com.android.systemui");
    }
    private static void refreshBackground(View tile) {
        Drawable wrapper = (Drawable) XposedHelpers.getObjectField(tile, "mBgDrawable");
        Drawable leaf = backgroundLeaf(tile);
        if (leaf != null && (leaf.getClass().getSimpleName().equals("MixColorTileDrawable")
                || leaf.getClass().getSimpleName().equals("GradientTileDrawable"))
            ) XposedHelpers.callMethod(leaf, "onStateChange", (Object) wrapper.getState());
    }
    public static void refresh() {
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }
    private static void refreshNow() {
            for (View tile : new ArrayList<>(cards.keySet())) if (tile != null) updateCard(tile);
            for (View icon : new ArrayList<>(icons.keySet())) if (icon != null) {
                Object proxy = icons.get(icon).get();
                if (proxy == null) continue;
                ColorStateList color = enabled(icon) || circleEnabled(icon) ? tintForIcon(icon) : nativeTints.get(proxy);
                setTint(proxy, color); icon.invalidate();
                View tile = tileOwner(icon);
                if (tile != null) { refreshBackground(tile); tile.invalidate(); }
            }
    }
    public static Object captureHotReloadHosts() {
        ArrayList<Object> state = new ArrayList<>();
        for (View icon : new ArrayList<>(icons.keySet())) if (icon != null) {
            Object proxy = icons.get(icon).get();
            if (proxy != null) state.add(new Object[]{icon, proxy, nativeTints.get(proxy), icon.getRootView()});
        }
        for (View tile : new ArrayList<>(cards.keySet())) if (tile != null) {
            Card card = cards.get(tile);
            state.add(new Object[]{tile, card.primary == null ? null : card.primary.get(),
                    card.secondary == null ? null : card.secondary.get()});
        }
        return state;
    }
    public static void restoreHotReloadHosts(Object state) {
        java.util.HashSet<View> roots = new java.util.HashSet<>();
        if (state instanceof Iterable<?> views) for (Object item : views) {
            if (item instanceof Object[] record && record.length == 4 && record[0] instanceof View icon) {
                icons.put(icon, new WeakReference<>(record[1]));
                nativeTints.put(record[1], (ColorStateList) record[2]);
                if (record[3] instanceof View root) roots.add(root);
            } else if (item instanceof Object[] record && record.length == 3 && record[0] instanceof View tile) {
                bind(tile);
                Card card = cards.get(tile);
                View.OnClickListener primary = (View.OnClickListener) record[1], secondary = (View.OnClickListener) record[2];
                if (primary != null) {
                    card.primary = new WeakReference<>(primary); card.secondary = new WeakReference<>(secondary);
                    tile.setOnClickListener(routedClick(tile, card, primary, secondary));
                }
                roots.add(tile.getRootView());
            } else if (item instanceof View view) {
                // Compatibility with the previous generation's icon-only snapshot.
                roots.add(view.getRootView());
            }
        }
        for (View root : roots) restoreTree(root);
        refreshNow();
    }
    public static void restoreInRoots(Object state) {
        java.util.HashSet<View> roots = new java.util.HashSet<>();
        if (state instanceof Iterable<?> views) for (Object item : views) {
            View view = item instanceof View v ? v
                    : item instanceof Object[] record && record.length > 0 && record[0] instanceof View v ? v : null;
            if (view != null) roots.add(view.getRootView());
        }
        for (View root : roots) restoreTree(root);
        refreshNow();
    }
    private static void restoreTree(View view) {
        if (merged(view) && (highlightClass.isInstance(view) || normalClass.isInstance(view))) {
            if (highlightClass.isInstance(view)) bind(view);
            View icon = (View) XposedHelpers.callMethod(view, "getIcon");
            if (!icons.containsKey(icon)) {
                Object proxy = XposedHelpers.getObjectField(icon, "iconViewProxy");
                icons.put(icon, new WeakReference<>(proxy));
                nativeTints.put(proxy, (ColorStateList) XposedHelpers.getObjectField(proxy, "tintList"));
            }
        }
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) restoreTree(group.getChildAt(i));
    }
    public static void cleanupForHotReload() {
        handler.removeCallbacksAndMessages(null);
        restoring = true;
        try {
            for (View tile : new ArrayList<>(cards.keySet())) if (tile != null) {
                Card card = cards.get(tile);
                updateCard(tile); tile.removeOnLayoutChangeListener(card.listener);
                if (card.primary != null) tile.setOnClickListener(card.primary.get());
            }
            for (View icon : new ArrayList<>(icons.keySet())) if (icon != null) {
                View tile = tileOwner(icon);
                if (tile != null) refreshBackground(tile);
            }
            for (Object proxy : new ArrayList<>(nativeTints.keySet())) if (proxy != null) setTint(proxy, nativeTints.get(proxy));
        } finally { cards.clear(); icons.clear(); nativeTints.clear(); tints.clear(); restoring = false; }
    }
    private static final class Card {
        WeakReference<View> circle;
        GradientDrawable shape;
        WeakReference<Drawable> activeShape;
        int activeSize;
        boolean activeNight;
        Float nativeTranslationX;
        Integer indicatorVisibility;
        boolean circleClick;
        WeakReference<TextSwitcher> subtitle;
        Integer nativeTitleHeight, nativeLabelOrientation;
        WeakReference<View.OnClickListener> primary, secondary;
        final View.OnLayoutChangeListener listener = (view, l, t, r, b, ol, ot, or, ob) -> updateCard(view);
    }
}
