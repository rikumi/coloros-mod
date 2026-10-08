package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.util.TypedValue;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.util.ArrayList;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** 合并版卡片通过约束求解器以宽度决定高度，保留原有水平约束及区域排布。 */
public final class MergedCardRatioHooks {
    private static Class<?> containerClass, constraintParamsClass, highlightClass, mediaClass, roundRectClass, iconClass, normalTileClass;
    private static final WeakHashMap<ViewGroup, Boolean> hosts = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> nativeLabelPadding = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> layoutListeners = new WeakHashMap<>();
    private static final View.OnLayoutChangeListener layoutListener = (view, l, t, r, b, ol, ot, or, ob) -> {
        if (!ratioEnabled(view)) return;
        if (containerClass.isInstance(view)) refreshPaths((ViewGroup) view);
        else adjustLayout(view);
    };
    private static final WeakHashMap<ViewGroup, Float> cardScales = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, WeakReference<ViewGroup>> gridCards = new WeakHashMap<>();
    private static final WeakHashMap<View, Original> originals = new WeakHashMap<>();
    private static final WeakHashMap<View, IconTransform> iconTransforms = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> iconSizes = new WeakHashMap<>();
    private static final WeakHashMap<View, Geometry> geometry = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, GeometryPass> geometryPasses = new WeakHashMap<>();
    private static final WeakHashMap<View, RadiusState> radii = new WeakHashMap<>();
    private static final WeakHashMap<View, Float> sliderRadii = new WeakHashMap<>();
    private static final ThreadLocal<View> outlineOwner = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> restoringRadius = new ThreadLocal<>();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final ThreadLocal<Boolean> remeasuring = new ThreadLocal<>();
    static boolean ratioEnabled(View view) {
        return view.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT
                && readBool(KEY_QS_MERGED_CARD_RATIO, false);
    }

    private static float iconFactor(View view) {
        if (!ratioEnabled(view)) return 1f;
        if (MergedTileGridHooks.root(view) != null)
            return readBool(KEY_QS_MERGED_FOUR_COLUMNS, false) ? 1f : .92f;
        return .95f;
    }

    static float gapFraction() {
        if (!readBool(KEY_QS_MERGED_GAP_ENABLED, false)) return 0.05f;
        int value = Math.max(40, Math.min(60, readInt(KEY_QS_MERGED_GAP_PERCENT_TENTHS, 50)));
        value = 40 + Math.round((value - 40) / 5f) * 5;
        return value / 1000f;
    }

    static Float regionGap(View panel) {
        int width = 0;
        for (ViewGroup cards : hosts.keySet()) {
            if (cards != null && cards.getRootView() == panel.getRootView() && cards.getMeasuredWidth() > 0) {
                width = cards.getMeasuredWidth() - cards.getPaddingLeft() - cards.getPaddingRight();
                break;
            }
        }
        if (width <= 0 && panel.getMeasuredWidth() > 0) {
            int id = panel.getResources().getIdentifier("qs_footer_side_padding", "dimen", "com.android.systemui");
            int side = id != 0 ? panel.getResources().getDimensionPixelSize(id) : 0;
            width = panel.getMeasuredWidth() - 2 * side;
        }
        return width > 0 ? (float) Math.round(width * gapFraction()) : null;
    }

    public static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            containerClass = XposedHelpers.findClass("com.oplus.systemui.qs.OplusQSTileMediaContainer", pkg.classLoader);
            constraintParamsClass = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams", pkg.classLoader);
            highlightClass = XposedHelpers.findClass("com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView", pkg.classLoader);
            MergedTileGridHooks.hook(pkg);
            normalTileClass = XposedHelpers.findClass("com.oplus.systemui.qs.base.tile.OplusQSTileBaseView", pkg.classLoader);
            iconClass = XposedHelpers.findClass("com.android.systemui.plugins.qs.QSIconView", pkg.classLoader);
            mediaClass = XposedHelpers.findClass("com.oplus.systemui.qs.media.OplusQsBaseMediaPanelView", pkg.classLoader);
            roundRectClass = XposedHelpers.findClass("com.oplusos.systemui.common.outline.RoundRectOutlineProvider", pkg.classLoader);
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.tileimpl.OplusQSIconViewImpl", pkg.classLoader,
                    "onMeasure", int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            View icon = (View) p.thisObject;
                            if (!ratioEnabled(icon) || mergedRoot(icon) == null) return;
                            int current = XposedHelpers.getIntField(icon, "iconSizePx");
                            if (!iconSizes.containsKey(icon)) iconSizes.put(icon, current);
                            int id = icon.getResources().getIdentifier("qs_tile_icon_size", "dimen", "com.android.systemui");
                            int base = id != 0 ? icon.getResources().getDimensionPixelSize(id) : iconSizes.get(icon);
                            int size = Math.max(1, Math.round(areaScale(icon) * iconFactor(icon) * base));
                            XposedHelpers.setIntField(icon, "iconSizePx", size);
                            // 测量和图形统一尺寸，消除系统小空间模式另行施加的缩小倍率。
                            rememberIconTransform(icon);
                            icon.setScaleX(1f); icon.setScaleY(1f);
                            if (icon.getParent() instanceof View frame && frame.getParent() instanceof View tile
                                    && highlightClass.isInstance(tile)) {
                                // 图形本身放大，仍在原来的宽容器内居中，避免挤到卡片左边缘。
                                int nativeWidth = XposedHelpers.getIntField(tile, "mIconSize");
                                int width = Math.max(size, Math.round(nativeWidth * areaScale(icon)));
                                p.args[0] = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
                            }
                        }
                    });
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.tileimpl.OplusQSTileViewImpl", pkg.classLoader,
                    "updateLabelPadding", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            View tile = (View) p.thisObject;
                            if (!ratioEnabled(tile) || MergedTileGridHooks.root(tile) == null) return;
                            View group = field(tile, "mLabelGroup"), indicator = field(tile, "mExpandIndicator");
                            if (group != null) {
                                nativeLabelPadding.put(group, true);
                                int padding = indicator != null && indicator.getVisibility() == View.VISIBLE
                                        ? Math.round(XposedHelpers.getIntField(tile, "mTileLabelMargin") * areaScale(tile)) : 0;
                                if (group.getPaddingLeft() != padding || group.getPaddingRight() != padding
                                        || group.getPaddingTop() != 0 || group.getPaddingBottom() != 0)
                                    group.setPadding(padding, 0, padding, 0);
                            }
                            p.setResult(null);
                        }
                    });
            Class<?> cornerProvider = XposedHelpers.findClass("com.oplusos.systemui.common.outline.CornerOutlineProvider", pkg.classLoader);
            XC_MethodHook installOutline = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View owner = outlineOwner.get();
                    if (owner == null) return;
                    Object drawable = XposedHelpers.getObjectField(owner,
                            (highlightClass.isInstance(owner) || normalTileClass.isInstance(owner)) ? "mBgDrawable" : "transitionDrawable");
                    if (drawable != p.thisObject) return;
                    Object source = p.args[0];
                    RadiusState state = radii.get(owner);
                    if (state != null && state.drawable.get() == drawable && source == state.installed) return;
                    if (source == null || !roundRectClass.isInstance(source)) return;
                    float scaled = scaleRadius(owner, ((Number) XposedHelpers.callMethod(source, "getCornerRadius")).floatValue());
                    Object provider;
                    if (state != null && state.drawable.get() == drawable && source == state.source && state.radius == scaled) {
                        provider = state.installed;
                    } else {
                        provider = XposedHelpers.newInstance(roundRectClass, scaled, XposedHelpers.callMethod(source, "getCornerWeight"));
                        radii.put(owner, new RadiusState(drawable, source, provider, scaled));
                    }
                    // 原生路径更新直接消费最终半径，不先应用原始路径再更新一次模糊配置。
                    p.args[0] = provider;
                }
            };
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.base.res.drawable.TileDrawableWrapper", pkg.classLoader,
                    "setPathProvider", cornerProvider, installOutline);
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.base.res.drawable.TileLayerDrawable", pkg.classLoader,
                    "setPathProvider", cornerProvider, installOutline);
            XC_MethodHook outlineChanged = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra("previousOutlineOwner", outlineOwner.get());
                    View view = (View) p.thisObject;
                    if (ratioEnabled(view) && mergedRoot(view) != null) outlineOwner.set(view);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View previous = (View) p.getObjectExtra("previousOutlineOwner");
                    if (previous != null) outlineOwner.set(previous); else outlineOwner.remove();
                    View view = (View) p.thisObject;
                    if (!p.hasThrowable() && ratioEnabled(view) && mergedRoot(view) != null) applyOutline(view);
                }
            };
            XposedHelpers.findAndHookDeclaredMethod(highlightClass, "onOutlineUpdate", java.util.List.class, outlineChanged);
            XposedHelpers.findAndHookDeclaredMethod(mediaClass, "onOutlineUpdate", outlineChanged);
            XposedHelpers.findAndHookDeclaredMethod(normalTileClass, "onOutlineUpdate", java.util.List.class, outlineChanged);
            XposedHelpers.findAndHookDeclaredMethod(containerClass, "onFinishInflate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    hosts.put((ViewGroup) p.thisObject, true);
                }
            });
            XposedHelpers.findAndHookDeclaredMethod(containerClass, "updateResources$4", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    restore((ViewGroup) p.thisObject);
                }
            });
            Class<?> layout = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintLayout", pkg.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(layout, "onMeasure", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (containerClass.isInstance(p.thisObject) && !ratioEnabled((View) p.thisObject))
                        restore((ViewGroup) p.thisObject);
                    if (!enabled(p)) return;
                    ViewGroup root = (ViewGroup) p.thisObject;
                    hosts.put(root, true);
                    ensureLayoutListener(root);
                    int width = View.MeasureSpec.getSize((Integer) p.args[0]) - root.getPaddingLeft() - root.getPaddingRight();
                    int gap = Math.max(0, Math.round(width * gapFraction()));
                    for (int i = 0; i < root.getChildCount(); i++) margins(root.getChildAt(i), gap, true);
                    ViewGroup sliders = (ViewGroup) field(root, "mQsSeekBarContainer");
                    if (sliders != null) for (int i = 0; i < sliders.getChildCount(); i++)
                        margins(sliders.getChildAt(i), gap, false);
                    p.setObjectExtra("gridGap", gap);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!enabled(p) || p.getObjectExtra("gridGap") == null || p.hasThrowable()) return;
                    ViewGroup root = (ViewGroup) p.thisObject;
                    int gap = (Integer) p.getObjectExtra("gridGap");
                    boolean changed = highlight((ViewGroup) field(root, "mFirstTileContainer"), gap);
                    changed |= highlight((ViewGroup) field(root, "mSecondTileContainer"), gap);
                    View media = field(root, "mQsMediaPanelContainer");
                    if (media != null && media.getVisibility() != View.GONE && media.getMeasuredWidth() > 0)
                        changed |= height(media, media.getMeasuredWidth());
                    ViewGroup sliders = (ViewGroup) field(root, "mQsSeekBarContainer");
                    if (sliders != null && sliders.getVisibility() != View.GONE) {
                        int maxHeight = 0;
                        for (int i = 0; i < sliders.getChildCount(); i++) {
                            View slider = sliders.getChildAt(i);
                            if (slider.getVisibility() == View.GONE || slider.getMeasuredWidth() <= 0) continue;
                            int h = slider.getMeasuredWidth() * 2 + gap;
                            changed |= height(slider, h);
                            ViewGroup.LayoutParams lp = slider.getLayoutParams();
                            if (XposedHelpers.getIntField(lp, "topToTop") != 0) {
                                XposedHelpers.setIntField(lp, "topToTop", 0);
                                slider.setLayoutParams(lp);
                                changed = true;
                            }
                            maxHeight = Math.max(maxHeight, h);
                        }
                        if (maxHeight > 0) changed |= height(sliders, maxHeight + sliders.getPaddingTop() + sliders.getPaddingBottom());
                    }
                    cardScales.put(root, calculateCardScale(root));
                    changed |= applyGeometryIfNeeded(root);
                    if (changed) {
                        // 首轮取得约束求解后的实际宽度，再在同一测量周期内更新高度和区域布局。
                        remeasuring.set(true);
                        try {
                            XposedHelpers.callMethod(root, "onMeasure", new Class<?>[]{int.class, int.class}, p.args[0], p.args[1]);
                        } finally { remeasuring.remove(); }
                    }
                }
            });
        } catch (Throwable t) { log("merged card ratio hook failed: " + t); }
    }

    private static View field(Object host, String name) {
        return (View) XposedHelpers.getObjectField(host, name);
    }

    private static void remember(View view) {
        if (!originals.containsKey(view)) originals.put(view, new Original(view));
    }

    private static boolean enabled(XC_MethodHook.MethodHookParam p) {
        return !Boolean.TRUE.equals(remeasuring.get()) && containerClass.isInstance(p.thisObject)
                && ratioEnabled((View) p.thisObject);
    }

    private static boolean highlight(ViewGroup holder, int gap) {
        if (holder == null || holder.getVisibility() == View.GONE || holder.getMeasuredWidth() <= 0) return false;
        boolean changed = height(holder, Math.max(1, Math.round((holder.getMeasuredWidth() - gap) / 2f)));
        for (int i = 0; i < holder.getChildCount(); i++) {
            View tile = holder.getChildAt(i);
            if (!highlightClass.isInstance(tile)) continue;
            changed |= height(tile, ViewGroup.LayoutParams.MATCH_PARENT);
            changed |= height(field(tile, "mIconFrame"), ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return changed;
    }

    private static boolean height(View view, int height) {
        remember(view);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        boolean changed = lp.height != height;
        if (constraintParamsClass.isInstance(lp) && XposedHelpers.getObjectField(lp, "dimensionRatio") != null) {
            XposedHelpers.setObjectField(lp, "dimensionRatio", null);
            changed = true;
        }
        if (changed) {
            lp.height = height;
            view.setLayoutParams(lp);
        }
        return changed;
    }

    private static void margins(View view, int gap, boolean outer) {
        if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp)) return;
        remember(view);
        Original original = originals.get(view);
        int start = original.start > 0 ? (outer ? Math.round(gap / 2f) : gap) : original.start;
        int end = original.end > 0 ? (outer ? gap / 2 : gap) : original.end;
        int top = original.top > 0 ? gap : original.top;
        int bottom = original.bottom > 0 ? gap : original.bottom;
        if (lp.getMarginStart() != start || lp.getMarginEnd() != end || lp.topMargin != top || lp.bottomMargin != bottom) {
            lp.setMarginStart(start); lp.setMarginEnd(end); lp.topMargin = top; lp.bottomMargin = bottom;
            view.setLayoutParams(lp);
        }
    }

    private static ViewGroup mergedRoot(View view) {
        View ancestor = view;
        while (ancestor != null) {
            if (containerClass != null && containerClass.isInstance(ancestor)) return (ViewGroup) ancestor;
            ancestor = ancestor.getParent() instanceof View parent ? parent : null;
        }
        return MergedTileGridHooks.root(view);
    }

    /** 只缩放合并版卡片区域内的半径，外部磁贴保持系统行为。 */
    public static float scaleRadius(View view, float radius) {
        if (Boolean.TRUE.equals(restoringRadius.get()) || !ratioEnabled(view)) return radius;
        Float surfaceScale = MergedTileGridHooks.surfaceScale(view);
        return radius * (surfaceScale != null ? surfaceScale : areaScale(view));
    }

    private static IconTransform rememberIconTransform(View view) {
        IconTransform original = iconTransforms.get(view);
        if (original == null) { original = new IconTransform(view); iconTransforms.put(view, original); }
        return original;
    }

    private static final class IconTransform {
        final float scaleX, scaleY, translationY;
        final ImageView.ScaleType scaleType;
        IconTransform(View view) {
            scaleX = view.getScaleX(); scaleY = view.getScaleY(); translationY = view.getTranslationY();
            scaleType = view instanceof ImageView image ? image.getScaleType() : null;
        }
        void restore(View view) {
            view.setScaleX(scaleX); view.setScaleY(scaleY); view.setTranslationY(translationY);
            if (scaleType != null && view instanceof ImageView image) image.setScaleType(scaleType);
        }
    }

    static float contentScale(View view) { return areaScale(view); }

    private static float areaScale(View view) {
        ViewGroup grid = MergedTileGridHooks.root(view);
        ViewGroup root = grid == null ? mergedRoot(view) : null;
        if (grid != null) {
            WeakReference<ViewGroup> cached = gridCards.get(grid);
            root = cached != null ? cached.get() : null;
            if (root == null || root.getRootView() != grid.getRootView()) {
                root = null;
                for (ViewGroup candidate : hosts.keySet()) {
                    if (candidate != null && candidate.getRootView() == grid.getRootView()) {
                        root = candidate; gridCards.put(grid, new WeakReference<>(candidate)); break;
                    }
                }
            }
        }
        if (root == null) return 1f;
        Float scale = cardScales.get(root);
        return scale != null ? scale : calculateCardScale(root);
    }

    private static float calculateCardScale(ViewGroup root) {
        int width = root.getMeasuredWidth() - root.getPaddingLeft() - root.getPaddingRight();
        if (width <= 0) return 1f;
        int gap = Math.round(width * gapFraction());
        // 一个网格单位的栏宽，也是比例布局中 2×1 卡片的高度。
        ViewGroup holder = (ViewGroup) field(root, "mFirstTileContainer");
        float column = holder != null && holder.getMeasuredWidth() > 0
                ? (holder.getMeasuredWidth() - gap) / 2f : (width - 3f * gap) / 4f;
        int reference = 0;
        for (String name : new String[]{"mFirstTileContainer", "mSecondTileContainer"}) {
            ViewGroup tiles = (ViewGroup) field(root, name);
            if (tiles == null) continue;
            for (int i = 0; i < tiles.getChildCount(); i++) {
                View tile = tiles.getChildAt(i);
                if (highlightClass.isInstance(tile)) {
                    // setBgSize 根据当前系统配置读取固定高度；不使用被我们改写的布局高度。
                    reference = XposedHelpers.getIntField(tile, "mBgHeight");
                    if (reference > 0) break;
                }
            }
            if (reference > 0) break;
        }
        if (reference <= 0) {
            int id = root.getResources().getIdentifier("qs_footer_hl_tile_height_with_volume", "dimen", "com.android.systemui");
            if (id != 0) reference = root.getResources().getDimensionPixelSize(id);
        }
        return column > 0 && reference > 0 ? column / reference : 1f;
    }

    private static boolean isTileSurface(View view) {
        View ancestor = view.getParent() instanceof View parent ? parent : null;
        // Highlight cards also keep their full card background in mBg (an ImageView).
        // Exclude it from icon transforms just like the circular backgrounds of normal tiles.
        while (ancestor != null && !normalTileClass.isInstance(ancestor) && !highlightClass.isInstance(ancestor))
            ancestor = ancestor.getParent() instanceof View parent ? parent : null;
        return ancestor != null && (view == field(ancestor, "mIconFrame") || view == field(ancestor, "mBg"));
    }

    private static boolean sliderLayout(View view) {
        return view.getClass().getName().equals("com.oplus.systemui.qs.widget.OplusQsToggleSliderLayout");
    }

    private static void ensureLayoutListener(View view) {
        if (!layoutListeners.containsKey(view)) {
            layoutListeners.put(view, true);
            view.addOnLayoutChangeListener(layoutListener);
        }
    }

    private static void adjustLayout(View view) {
        if (sliderLayout(view)) {
            View toggle = field(view, "toggleView");
            if (toggle != null && toggle.getClass().getName().equals("com.oplus.systemui.qs.base.seek.ClipBrightnessView")) {
                IconTransform original = rememberIconTransform(toggle);
                float scale = areaScale(toggle) * iconFactor(toggle);
                toggle.setScaleX(original.scaleX * scale); toggle.setScaleY(original.scaleY * scale);
                toggle.setTranslationY(original.translationY - (scale - 1f) * toggle.getHeight() / 2f);
            } else if (toggle instanceof ImageView image) {
                rememberIconTransform(toggle);
                if (image.getScaleType() != ImageView.ScaleType.FIT_CENTER) image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }
        } else if (view.getParent() instanceof View tile && normalTileClass.isInstance(tile)
                && view == field(tile, "mIconFrame") && XposedHelpers.getBooleanField(tile, "mCollapsedView")) {
            View icon = field(tile, "mIcon"), background = field(tile, "mBg");
            int left = background.getLeft() + (background.getWidth() - icon.getMeasuredWidth()) / 2;
            int top = background.getTop() + (background.getHeight() - icon.getMeasuredHeight()) / 2;
            if (icon.getLeft() != left || icon.getTop() != top
                    || icon.getWidth() != icon.getMeasuredWidth() || icon.getHeight() != icon.getMeasuredHeight())
                icon.layout(left, top, left + icon.getMeasuredWidth(), top + icon.getMeasuredHeight());
        }
    }

    private static boolean applyGeometry(View view, ViewGroup root) {
        boolean changed = false;
        if (view instanceof ImageView && !isTileSurface(view)) {
            // 图片图标仅调整绘制大小，保留布局及触摸区域；QSIconView 自行调整 iconSizePx。
            View ancestor = view;
            boolean qsIcon = false;
            while (ancestor != root && ancestor != null) {
                if (iconClass.isInstance(ancestor)) { qsIcon = true; break; }
                ancestor = ancestor.getParent() instanceof View parent ? parent : null;
            }
            int cover = view.getResources().getIdentifier("oplus_media_cover_image", "id", "com.android.systemui");
            if (!qsIcon && (cover == 0 || view.getId() != cover)) {
                IconTransform original = rememberIconTransform(view);
                float factor = iconFactor(view);
                view.setScaleX(original.scaleX * factor); view.setScaleY(original.scaleY * factor);
            }
        }
        if (sliderLayout(view) || (view.getParent() instanceof View tile && normalTileClass.isInstance(tile)
                && view == field(tile, "mIconFrame"))) {
            ensureLayoutListener(view);
            adjustLayout(view);
        }
        // 区域外框与磁贴之间的间距由网格计算；这里只缩放卡片内部布局。
        if (view != root && view.getParent() != root
                && !(containerClass.isInstance(root) && view.getParent() instanceof View parent && parent == field(root, "mQsSeekBarContainer"))) {
            Geometry original = geometry.get(view);
            if (original == null) {
                boolean icon = view instanceof ImageView || iconClass.isInstance(view);
                if (view.getParent() instanceof View parent && normalTileClass.isInstance(parent))
                    icon |= view == field(parent, "mIconFrame");
                if (view.getParent() instanceof View parent
                        && parent.getClass().getName().equals("com.oplus.systemui.qs.widget.OplusQsToggleSliderLayout"))
                    icon |= view == field(parent, "toggleView");
                original = new Geometry(view, icon);
                geometry.put(view, original);
            }
            float scale = areaScale(view);
            float densityScale = view.getResources().getDisplayMetrics().density / original.density;
            changed = original.apply(view, scale * densityScale,
                    (isTileSurface(view) ? scaleRadius(view, 1f) : scale) * densityScale);

        }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++)
            changed |= applyGeometry(group.getChildAt(i), root);
        return changed;
    }

    private static final class Geometry {
        final int width, height, start, end, top, bottom, paddingStart, paddingEnd, paddingTop, paddingBottom;
        final boolean icon, margin, labelGroup, labelContainer;
        final boolean includeFontPadding;
        final float density, textSize, fontScale;
        Geometry(View view, boolean icon) {
            this.icon = icon;
            includeFontPadding = view instanceof TextView text && text.getIncludeFontPadding();
            labelContainer = MergedTileGridHooks.root(view) != null
                    && view.getClass().getName().equals("com.oplus.systemui.qs.base.tile.ButtonRelativeLayout")
                    && view.getParent() instanceof View tile && normalTileClass.isInstance(tile);
            labelGroup = MergedTileGridHooks.root(view) != null
                    && view.getId() == view.getResources().getIdentifier("label_group", "id", "com.android.systemui");
            density = view.getResources().getDisplayMetrics().density;
            fontScale = view.getResources().getConfiguration().fontScale;
            textSize = view instanceof TextView text && MergedTileGridHooks.root(view) != null ? text.getTextSize() : 0f;
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            width = lp.width; height = lp.height;
            margin = lp instanceof ViewGroup.MarginLayoutParams;
            ViewGroup.MarginLayoutParams m = margin ? (ViewGroup.MarginLayoutParams) lp : null;
            start = margin ? m.getMarginStart() : 0; end = margin ? m.getMarginEnd() : 0;
            top = margin ? m.topMargin : 0; bottom = margin ? m.bottomMargin : 0;
            paddingStart = view.getPaddingStart(); paddingEnd = view.getPaddingEnd();
            paddingTop = view.getPaddingTop(); paddingBottom = view.getPaddingBottom();
        }
        boolean applyText(TextView view, float scale) {
            if (textSize <= 0f) return false;
            float currentFontScale = view.getResources().getConfiguration().fontScale;
            float size = textSize * scale * (fontScale > 0 ? currentFontScale / fontScale : 1f);
            if (Math.abs(view.getTextSize() - size) < .01f) return false;
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            return true;
        }
        boolean apply(View view, float scale) { return apply(view, scale, scale, true); }
        boolean apply(View view, float scale, float sizeScale) { return apply(view, scale, sizeScale, false); }
        boolean apply(View view, float scale, float sizeScale, boolean restore) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            boolean changed = view instanceof TextView text && applyText(text, scale);
            if (textSize > 0f && view instanceof TextView text) {
                boolean fontPadding = restore && includeFontPadding;
                if (text.getIncludeFontPadding() != fontPadding) { text.setIncludeFontPadding(fontPadding); changed = true; }
            }
            if (labelContainer && restore && lp.width != width) { lp.width = width; changed = true; }
            if (icon) {
                int w = width > 0 ? Math.max(1, Math.round(width * sizeScale)) : width;
                int h = height > 0 ? Math.max(1, Math.round(height * sizeScale)) : height;
                if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; changed = true; }
            }
            if (margin && lp instanceof ViewGroup.MarginLayoutParams m) {
                // 名称组取消额外两侧留白，为四个汉字及展开箭头保留宽度。
                float inset = labelGroup && !restore ? 0f : Float.MAX_VALUE;
                int s = Math.round(Math.min(start * scale, inset)), e = Math.round(Math.min(end * scale, inset));
                int t = Math.round(top * scale), b = Math.round(bottom * scale);
                if (m.getMarginStart() != s || m.getMarginEnd() != e || m.topMargin != t || m.bottomMargin != b) {
                    m.setMarginStart(s); m.setMarginEnd(e); m.topMargin = t; m.bottomMargin = b; changed = true;
                }
            }
            if (changed) view.setLayoutParams(lp);
            int s = Math.round(paddingStart * scale), e = Math.round(paddingEnd * scale);
            int t = Math.round(paddingTop * scale), b = Math.round(paddingBottom * scale);
            if (labelContainer && !restore) {
                t = Math.min(t, Math.round(2f * view.getResources().getDisplayMetrics().density * areaScale(view)));
                IconTransform original = rememberIconTransform(view);
                float translation = original.translationY + 8f * view.getResources().getDisplayMetrics().density;
                if (view.getTranslationY() != translation) view.setTranslationY(translation);
            }
            // 名称双目标区域由原生 updateLabelPadding 的定向 hook 按实时状态管理。
            if ((!nativeLabelPadding.containsKey(view) || restore)
                    && (view.getPaddingStart() != s || view.getPaddingEnd() != e || view.getPaddingTop() != t || view.getPaddingBottom() != b)) {
                view.setPaddingRelative(s, t, e, b); changed = true;
            }
            return changed;
        }
    }

    private static void applyRadii(View view) {
        if (highlightClass.isInstance(view) || normalTileClass.isInstance(view) || mediaClass.isInstance(view)) applyOutline(view);
        if (view.getClass().getName().equals("com.oplus.systemui.qs.widget.OplusQsToggleSliderLayout")) {
            Float normal = readBool(KEY_QS_NORMAL_CORNER_RADIUS_ENABLED, false) ? QsHooks.resolveQsCornerRadiusPx(view) : null;
            float nativeRadius = normal != null ? normal : ((Number) XposedHelpers.callMethod(view, "getRadius")).floatValue();
            float scaled = scaleRadius(view, nativeRadius);
            Float previous = sliderRadii.get(view);
            if (previous == null || Float.compare(previous, scaled) != 0) {
                XposedHelpers.callMethod(view, "updateCornerRadius");
                sliderRadii.put(view, scaled);
            }
        }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) applyRadii(group.getChildAt(i));
    }

    private static void applyOutline(View view) {
        Object drawable = XposedHelpers.getObjectField(view, (highlightClass.isInstance(view) || normalTileClass.isInstance(view)) ? "mBgDrawable" : "transitionDrawable");
        Object current = XposedHelpers.callMethod(drawable, "getPathProvider");
        RadiusState state = radii.get(view);
        Object source = state != null && state.drawable.get() == drawable && current == state.installed ? state.source : current;
        if (source == null || !roundRectClass.isInstance(source)) return;
        float nativeRadius = ((Number) XposedHelpers.callMethod(source, "getCornerRadius")).floatValue();
        float scaled = scaleRadius(view, nativeRadius);
        if (state != null && state.drawable.get() == drawable && current == state.installed
                && source == state.source && Float.compare(state.radius, scaled) == 0) return;
        Object weight = XposedHelpers.callMethod(source, "getCornerWeight");
        Object provider = XposedHelpers.newInstance(roundRectClass, scaled, weight);
        XposedHelpers.callMethod(drawable, "setPathProvider", provider);
        radii.put(view, new RadiusState(drawable, source, provider, scaled));
        view.invalidate();
    }

    private static void restoreRadius(View view) {
        RadiusState state = radii.remove(view);
        if (state != null) {
            Object drawable = state.drawable.get();
            if (drawable != null && XposedHelpers.callMethod(drawable, "getPathProvider") == state.installed)
                XposedHelpers.callMethod(drawable, "setPathProvider", state.source);
            view.invalidate();
        }
        if (sliderRadii.remove(view) != null) {
            restoringRadius.set(true);
            try { XposedHelpers.callMethod(view, "updateCornerRadius"); }
            finally { restoringRadius.remove(); }
        }
    }

    private static final class RadiusState {
        final WeakReference<Object> drawable;
        final Object source, installed;
        final float radius;
        int left = Integer.MIN_VALUE, top, right, bottom;
        RadiusState(Object drawable, Object source, Object installed, float radius) {
            this.drawable = new WeakReference<>(drawable); this.source = source;
            this.installed = installed; this.radius = radius;
        }
    }

    private static final class GeometryPass {
        final int width, children;
        final float density, fontScale, scale, surfaceScale, iconFactor;
        final long tiles;
        GeometryPass(ViewGroup root) {
            width = root.getMeasuredWidth(); children = root.getChildCount();
            density = root.getResources().getDisplayMetrics().density;
            fontScale = root.getResources().getConfiguration().fontScale;
            scale = areaScale(root); iconFactor = iconFactor(root);
            Float surface = MergedTileGridHooks.surfaceScale(root);
            surfaceScale = surface != null ? surface : scale;
            long identity = 0;
            for (int i = 0; i < children; i++) {
                View child = root.getChildAt(i);
                identity = identity * 31 + System.identityHashCode(child);
                if (containerClass.isInstance(root) && child instanceof ViewGroup group)
                    for (int j = 0; j < group.getChildCount(); j++) identity = identity * 31 + System.identityHashCode(group.getChildAt(j));
            }
            tiles = identity;
        }
        boolean same(GeometryPass previous) {
            return previous != null && width == previous.width && children == previous.children && tiles == previous.tiles
                    && density == previous.density && fontScale == previous.fontScale && scale == previous.scale
                    && surfaceScale == previous.surfaceScale && iconFactor == previous.iconFactor;
        }
    }
    private static boolean applyGeometryIfNeeded(ViewGroup root) {
        GeometryPass current = new GeometryPass(root);
        if (current.same(geometryPasses.get(root))) return false;
        geometryPasses.put(root, current);
        boolean changed = applyGeometry(root, root);
        applyRadii(root);
        return changed;
    }
    static void applyGridGeometry(ViewGroup root) { applyGeometryIfNeeded(root); }
    static void refreshPaths(ViewGroup root) {
        refreshChangedPaths(root);
    }
    private static void refreshChangedPaths(View view) {
        RadiusState state = radii.get(view);
        Object drawable = state != null ? state.drawable.get() : null;
        if (drawable instanceof Drawable background) {
            Rect bounds = background.getBounds();
            // 模糊配置更新成本较高；仅新路径或真实边界改变时同步，避免展开动画每帧重建。
            if (bounds.width() > 0 && bounds.height() > 0 && (state.left != bounds.left
                    || state.top != bounds.top || state.right != bounds.right || state.bottom != bounds.bottom)) {
                state.left = bounds.left; state.top = bounds.top; state.right = bounds.right; state.bottom = bounds.bottom;
                XposedHelpers.callMethod(drawable, "invalidatePath");
                view.invalidateOutline(); view.invalidate();
            }
        }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++)
            refreshChangedPaths(group.getChildAt(i));
    }
    static void restoreGridGeometry(ViewGroup root) { restore(root); }

    private static void restore(ViewGroup root) {
        cardScales.remove(root);
        geometryPasses.remove(root);
        for (View view : new ArrayList<>(iconTransforms.keySet())) if (view != null && mergedRoot(view) == root) {
            IconTransform original = iconTransforms.remove(view);
            if (original != null) original.restore(view);
        }
        for (View view : new ArrayList<>(iconSizes.keySet())) if (view != null && mergedRoot(view) == root) {
            Integer original = iconSizes.remove(view);
            if (original != null) XposedHelpers.setIntField(view, "iconSizePx", original);
            view.requestLayout();
        }
        for (View view : new ArrayList<>(geometry.keySet())) if (view != null && mergedRoot(view) == root) {
            Geometry original = geometry.remove(view);
            if (original != null) original.apply(view, 1f);
        }
        ArrayList<View> radiusViews = new ArrayList<>(radii.keySet());
        radiusViews.addAll(sliderRadii.keySet());
        for (View view : radiusViews) if (view != null && mergedRoot(view) == root) restoreRadius(view);
        for (View view : new ArrayList<>(originals.keySet())) {
            if (view == null) continue;
            View ancestor = view;
            while (ancestor != root && ancestor.getParent() instanceof View parent) ancestor = parent;
            if (ancestor == root) {
                Original original = originals.remove(view);
                if (original != null) original.restore(view);
            }
        }
    }

    public static void refresh() {
        handler.post(() -> {
            MergedTileGridHooks.refresh();
            for (ViewGroup root : new ArrayList<>(hosts.keySet())) {
                if (root == null) continue;
                if (!ratioEnabled(root)) restore(root);
                cardScales.remove(root);
                geometryPasses.remove(root);
                root.requestLayout();
            }
        });
    }

    public static Object captureHotReloadHosts() {
        ArrayList<Object> views = new ArrayList<>(hosts.keySet());
        views.addAll(MergedTileGridHooks.capture());
        return views;
    }
    public static void restoreHotReloadHosts(Object state) {
        if (state instanceof Iterable<?> views) {
            MergedTileGridHooks.restoreHosts(views);
            for (Object view : views) if (view instanceof ViewGroup root && containerClass.isInstance(root)) hosts.put(root, true);
        }
        refresh();
    }
    public static void cleanupForHotReload() {
        handler.removeCallbacksAndMessages(null);
        for (View view : new ArrayList<>(layoutListeners.keySet())) if (view != null) view.removeOnLayoutChangeListener(layoutListener);
        layoutListeners.clear(); nativeLabelPadding.clear();
        MergedTileGridHooks.cleanup();
        for (ViewGroup root : new ArrayList<>(hosts.keySet())) if (root != null) restore(root);
        cardScales.clear(); gridCards.clear(); geometryPasses.clear();
        hosts.clear(); originals.clear(); radii.clear(); sliderRadii.clear(); geometry.clear(); iconSizes.clear(); iconTransforms.clear();
    }

    private static final class Original {
        final int height, topToTop, start, end, top, bottom;
        final String ratio;
        final boolean constraint;
        Original(View view) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            height = lp.height;
            ViewGroup.MarginLayoutParams margins = lp instanceof ViewGroup.MarginLayoutParams m ? m : null;
            start = margins != null ? margins.getMarginStart() : 0;
            end = margins != null ? margins.getMarginEnd() : 0;
            top = margins != null ? margins.topMargin : 0;
            bottom = margins != null ? margins.bottomMargin : 0;
            constraint = constraintParamsClass.isInstance(lp);
            topToTop = constraint ? XposedHelpers.getIntField(lp, "topToTop") : -1;
            ratio = constraint ? (String) XposedHelpers.getObjectField(lp, "dimensionRatio") : null;
        }
        void restore(View view) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            lp.height = height;
            if (lp instanceof ViewGroup.MarginLayoutParams margins) {
                margins.setMarginStart(start); margins.setMarginEnd(end);
                margins.topMargin = top; margins.bottomMargin = bottom;
            }
            if (constraint) {
                XposedHelpers.setIntField(lp, "topToTop", topToTop);
                XposedHelpers.setObjectField(lp, "dimensionRatio", ratio);
            }
            view.setLayoutParams(lp);
        }
    }
}
