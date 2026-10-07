package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Paint;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.ArrayList;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** 合并版普通磁贴和收起状态单行磁贴的正方形网格。 */
final class MergedTileGridHooks {
    private static Class<?> tileClass, headerClass, quickControllerClass;
    private static final WeakHashMap<ViewGroup, Boolean> pagedPages = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Boolean> columnHosts = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> quickControllers = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> restoringColumns = new ThreadLocal<>();
    private static Boolean lastFourColumns, lastRatio;
    private static final WeakHashMap<Object, Runnable> pendingAnimations = new WeakHashMap<>();
    private static final WeakHashMap<Object, Long> animatorGeometry = new WeakHashMap<>();
    private static final WeakHashMap<Object, Integer> fixedAnimators = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Original> hosts = new WeakHashMap<>();
    private static final String[] FIELDS = {"mCellWidth", "mCellHeight", "mResourceCellHeight", "mEstimatedCellHeight", "mCellMarginHorizontal", "mCellMarginVertical", "mSidePadding"};

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            tileClass = XposedHelpers.findClass("com.android.systemui.qs.TileLayout", pkg.classLoader);
            headerClass = XposedHelpers.findClass("com.oplus.systemui.qs.widget.OplusHeaderTileLayout", pkg.classLoader);
            quickControllerClass = XposedHelpers.findClass("com.android.systemui.qs.QuickQSPanelController", pkg.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(quickControllerClass, "onInit", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { quickControllers.put(p.thisObject, true); }
            });
            XposedHelpers.findAndHookDeclaredMethod("com.android.systemui.qs.QuickQSPanel", pkg.classLoader, "getNumQuickTiles", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (fourColumns()) p.setResult(4);

                }
            });
            XposedHelpers.findAndHookDeclaredMethod(tileClass, "updateColumns", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { p.setObjectExtra("oldColumns", integer(p.thisObject, "mColumns")); }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (!isGrid(root)) return;
                    columnHosts.put(root, true);
                    if (!fourColumns() || headerClass.isInstance(root)) return;
                    XposedHelpers.setIntField(root, "mColumns", 4);
                    p.setResult((Integer) p.getObjectExtra("oldColumns") != 4);
                }
            });
            // 区域顶部间距同时用于容器高度计算与展开位置，不仅修改最终视觉坐标。
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.OplusQSPanelContainer", pkg.classLoader,
                    "getTopGap", float.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!readBool(KEY_QS_MERGED_CARD_RATIO, false)) return;
                            Float gap = MergedCardRatioHooks.regionGap((View) p.thisObject);
                            if (gap != null) p.setResult(gap);
                        }
                    });
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.OplusQSPanelContainer", pkg.classLoader,
                    "updateViewState", float.class, float.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (readBool(KEY_QS_MERGED_CARD_RATIO, false)) p.args[1] = 1f;
                        }
                    });
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.OplusQSAnimator", pkg.classLoader,
                    "updateAnimators$1", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            fixedAnimators.put(XposedHelpers.getObjectField(p.thisObject, "mQQSTileScaleAnimator"), 1);
                            if (readBool(KEY_QS_MERGED_CARD_RATIO, false)) animatorGeometry.put(p.thisObject, animationGeometry(p.thisObject));
                        }
                    });
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.OplusQSAnimator", pkg.classLoader,
                    "onLayoutChange", View.class, int.class, int.class, int.class, int.class,
                    int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!readBool(KEY_QS_MERGED_CARD_RATIO, false)) return;
                            Object animator = p.thisObject;
                            p.setResult(null);
                            if (pendingAnimations.containsKey(animator)) return;
                            Long previous = animatorGeometry.get(animator);
                            if (!XposedHelpers.getBooleanField(animator, "mNeedsAnimatorUpdate")
                                    && previous != null && previous == animationGeometry(animator)) return;
                            WeakReference<Object> owner = new WeakReference<>(animator);
                            Runnable update = () -> {
                                Object current = owner.get();
                                if (current == null) return;
                                pendingAnimations.remove(current);
                                Long built = animatorGeometry.get(current);
                                boolean enabled = readBool(KEY_QS_MERGED_CARD_RATIO, false);
                                if (!enabled || XposedHelpers.getBooleanField(current, "mNeedsAnimatorUpdate")
                                        || built == null || built != animationGeometry(current))
                                    ((Runnable) XposedHelpers.getObjectField(current, "mUpdateAnimators")).run();
                            };
                            pendingAnimations.put(animator, update);
                            ((View) XposedHelpers.getObjectField(animator, "mQsRootView")).postOnAnimation(update);
                        }
                    });
            XposedHelpers.findAndHookDeclaredMethod("com.android.systemui.qs.TouchAnimator", pkg.classLoader,
                    "setPosition", float.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!readBool(KEY_QS_MERGED_CARD_RATIO, false)) return;
                            Integer kind = fixedAnimators.get(p.thisObject);
                            if (kind == null) return;
                            Object[] targets = (Object[]) XposedHelpers.getObjectField(p.thisObject, "mTargets");
                            for (Object target : targets) if (target instanceof View view) {
                                if (kind == 0) view.setTranslationX(0f);
                                else { view.setScaleX(1f); view.setScaleY(1f); }
                            }
                            p.setResult(null);
                        }
                    });
            // 展开动画的 squish 路径直接使用 getColumnStart，必须与正常布局使用同一栏间距。
            XC_MethodHook columnStart = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (!enabled(root)) return;
                    int col = (Integer) p.args[0];
                    p.setResult(columnLeft(root, col));
                }
            };
            XposedHelpers.findAndHookDeclaredMethod(tileClass, "getColumnStart", int.class, columnStart);
            XposedHelpers.findAndHookDeclaredMethod(headerClass, "getColumnStart", int.class, columnStart);
            XC_MethodHook measure = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled((View) p.thisObject)) return;
                    ViewGroup root = (ViewGroup) p.thisObject;
                    int width = View.MeasureSpec.getSize((Integer) p.args[0]);
                    configure(root, width);
                    int columns = columns(root);
                    int rows = headerClass.isInstance(root) ? 1 : integer(root, "mRows");
                    if (!headerClass.isInstance(root) && !pagedPages.containsKey(root)
                            && View.MeasureSpec.getMode((Integer) p.args[1]) == View.MeasureSpec.UNSPECIFIED) {
                        rows = (root.getChildCount() + columns - 1) / columns;
                        XposedHelpers.setIntField(root, "mRows", rows);
                    }
                    int size = integer(root, "mCellWidth");
                    int spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
                    boolean header = headerClass.isInstance(root);
                    int rowHeight = header ? size : integer(root, "mCellHeight");
                    // 行距只依据名称第一行；磁贴自身仍可容纳两行内容，避免裁切文字。
                    int tileHeight = header ? size : size + labelHeight(root, 2);
                    int heightSpec = View.MeasureSpec.makeMeasureSpec(tileHeight, View.MeasureSpec.EXACTLY);
                    Original original = hosts.get(root);
                    boolean reorder = original.accessibilityHash != original.contentHash;
                    View previous = root;
                    for (int i = 0; i < root.getChildCount(); i++) {
                        View tile = root.getChildAt(i);
                        if (tile.getVisibility() == View.GONE) continue;
                        if (!header) widenLabel(root, tile, size);
                        tile.measure(spec, heightSpec);
                        if (reorder) previous = (View) XposedHelpers.callMethod(tile, "updateAccessibilityOrder", previous);
                    }
                    original.accessibilityHash = original.contentHash;
                    int gap = integer(root, "mCellMarginVertical");
                    int height = rows > 0 ? (rows - 1) * (rowHeight + gap) + tileHeight : 0;
                    XposedHelpers.callMethod(root, "setMeasuredDimension", new Class<?>[]{int.class, int.class}, width, height);
                    p.setResult(null);
                }
            };
            XposedHelpers.findAndHookDeclaredMethod(tileClass, "onMeasure", int.class, int.class, measure);
            XposedHelpers.findAndHookDeclaredMethod(headerClass, "onMeasure", int.class, int.class, measure);
            XposedHelpers.findAndHookDeclaredMethod(tileClass, "getCellHeight", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (!enabled(root)) return;
                    // 分页器测量前已经按本轮宽度配置行高；不得再用上一轮 measuredWidth 回写。
                    if (hosts.containsKey(root)) {
                        p.setResult(integer(root, "mCellHeight"));
                        return;
                    }
                    int width = root.getMeasuredWidth();
                    if (width <= 0 && root.getParent() instanceof View parent) width = parent.getMeasuredWidth();
                    if (width <= 0) return;
                    configure(root, width);
                    p.setResult(integer(root, "mCellHeight"));
                }
            });
            // 分页器在测量磁贴前决定每页行数，先更新高度，避免使用上一帧的高度上限。
            Class<?> paged = XposedHelpers.findClass("com.android.systemui.qs.PagedTileLayout", pkg.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(paged, "onMeasure", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!readBool(KEY_QS_MERGED_CARD_RATIO, false) && !fourColumns()) return;
                    Object pages = XposedHelpers.getObjectField(p.thisObject, "mPages");
                    if (pages instanceof Iterable<?> list) for (Object page : list)
                        if (page instanceof ViewGroup root && isGrid(root)) {
                            columnHosts.put(root, true);
                            pagedPages.put(root, true);
                            if (fourColumns()) XposedHelpers.setIntField(root, "mColumns", 4);
                            if (enabled(root)) {
                                View pager = (View) p.thisObject;
                                int width = View.MeasureSpec.getSize((Integer) p.args[0]) - pager.getPaddingLeft() - pager.getPaddingRight();
                                configure(root, width);
                            }
                        }
                }
            });
            XC_MethodHook layout = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (!enabled(root)) return;
                    // 接管普通网格布局，避免系统布局后再移动一次所有磁贴。
                    layoutGrid(root);
                    p.setResult(null);
                }
            };
            XposedHelpers.findAndHookDeclaredMethod(tileClass, "onLayout", boolean.class, int.class, int.class, int.class, int.class, layout);
            XposedHelpers.findAndHookDeclaredMethod(headerClass, "onLayout", boolean.class, int.class, int.class, int.class, int.class, layout);
            XC_MethodHook resources = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { restore((ViewGroup) p.thisObject); }
            };
            XposedHelpers.findAndHookDeclaredMethod(tileClass, "updateResources", resources);
            XposedHelpers.findAndHookDeclaredMethod(headerClass, "updateResources", resources);
        } catch (Throwable t) { log("merged tile grid hook failed: " + t); }
    }

    private static long viewGeometry(long hash, View view) {
        if (view == null) return hash * 31;
        hash = hash * 31 + System.identityHashCode(view);
        hash = hash * 31 + System.identityHashCode(view.getParent());
        boolean tile = view.getParent() instanceof View parent && isGrid(parent);
        hash = hash * 31 + view.getLeft();
        // squish 动画会改变磁贴的行位置，这属于动画进度，不是动画端点改变。
        hash = hash * 31 + (tile ? 0 : view.getTop());
        hash = hash * 31 + view.getMeasuredWidth(); hash = hash * 31 + view.getMeasuredHeight();
        return hash * 31 + view.getVisibility();
    }
    private static long animationGeometry(Object animator) {
        View root = (View) XposedHelpers.getObjectField(animator, "mQsRootView");
        long hash = root.getMeasuredWidth();
        hash = hash * 31 + root.getResources().getConfiguration().densityDpi;
        hash = hash * 31 + Float.floatToIntBits(root.getResources().getConfiguration().fontScale);
        hash = viewGeometry(hash, (View) XposedHelpers.getObjectField(animator, "mQuickQsPanel"));
        hash = viewGeometry(hash, (View) XposedHelpers.getObjectField(animator, "mQsPanel"));
        ViewGroup pager = (ViewGroup) XposedHelpers.getObjectField(animator, "mPagedLayout");
        if (pager != null) {
            Object pages = XposedHelpers.getObjectField(pager, "mPages");
            if (pages instanceof Iterable<?> list) for (Object page : list) if (page instanceof ViewGroup grid) {
                hash = viewGeometry(hash, grid);
                hash = hash * 31 + integer(grid, "mRows"); hash = hash * 31 + integer(grid, "mColumns");
                hash = hash * 31 + integer(grid, "mCellHeight"); hash = hash * 31 + integer(grid, "mCellMarginVertical");
                for (int i = 0; i < grid.getChildCount(); i++) hash = viewGeometry(hash, grid.getChildAt(i));
            }
        }
        Object views = XposedHelpers.getObjectField(animator, "mAllViews");
        if (views instanceof Iterable<?> list) for (Object value : list)
            if (value instanceof View view) hash = viewGeometry(hash, view);
        return hash;
    }

    private static void layoutGrid(ViewGroup root) {
        configure(root, root.getMeasuredWidth());
        int columns = columns(root), size = integer(root, "mCellWidth");
        int gap = integer(root, "mCellMarginHorizontal");
        boolean rtl = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float squish = headerClass.isInstance(root) ? 1f : XposedHelpers.getFloatField(root, "mSquishinessFraction") * .9f + .1f;
        int count = Math.min(root.getChildCount(), headerClass.isInstance(root) ? columns : integer(root, "mRows") * columns);
        for (int i = 0; i < count; i++) {
            View tile = root.getChildAt(i);
            int col = i % columns;
            if (rtl) col = columns - 1 - col;
            int left = columnLeft(root, col);
            int top = Math.round((i / columns) * (integer(root, "mCellHeight") * squish + gap));
            tile.layout(left, top, left + size, top + tile.getMeasuredHeight());
            XposedHelpers.callMethod(tile, "setPosition", i);
        }
        MergedCardRatioHooks.refreshPaths(root);
    }

    private static boolean enabled(View view) {
        return readBool(KEY_QS_MERGED_CARD_RATIO, false) && isGrid(view);
    }
    private static boolean isGrid(View view) {
        if (tileClass == null || !tileClass.isInstance(view)) return false;
        String name = view.getClass().getName();
        return name.equals("com.android.systemui.qs.TileLayout") || name.equals("com.android.systemui.qs.SideLabelTileLayout")
                || (headerClass != null && headerClass.isInstance(view));
    }
    static ViewGroup root(View view) {
        View ancestor = view;
        while (ancestor != null) {
            if (isGrid(ancestor)) return (ViewGroup) ancestor;
            ancestor = ancestor.getParent() instanceof View parent ? parent : null;
        }
        return null;
    }
    static Float surfaceScale(View view) {
        ViewGroup root = root(view);
        Original original = hosts.get(root);
        if (original == null) return null;
        int resource = root.getResources().getIdentifier(headerClass.isInstance(root)
                ? "qs_quick_qs_tile_size" : "qs_quick_tile_size", "dimen", "com.android.systemui");
        // 每次从当前配置读取固定直径，避免 DPI 改变后仍使用缓存的旧像素值。
        float reference = resource != 0 ? root.getResources().getDimensionPixelSize(resource)
                : original.reference * root.getResources().getDisplayMetrics().density / original.density;
        int width = integer(root, "mCellWidth");
        return reference > 0 && width > 0 ? width / reference : null;
    }
    private static int integer(Object view, String field) { return XposedHelpers.getIntField(view, field); }
    private static boolean fourColumns() {
        return !Boolean.TRUE.equals(restoringColumns.get()) && readBool(KEY_QS_MERGED_FOUR_COLUMNS, false);
    }
    private static int columns(ViewGroup root) {
        return fourColumns() ? 4 : Math.max(1, integer(root, "mColumns"));
    }
    // 与系统收起布局相同，按固定直径决定可见数量；展开列数不参与磁贴直径计算。
    private static int collapsedColumns(ViewGroup root, int available) {
        if (fourColumns()) return 4;
        android.content.res.Resources resources = root.getResources();
        int sizeId = resources.getIdentifier("qs_quick_qs_tile_size", "dimen", "com.android.systemui");
        int paddingId = resources.getIdentifier("op_qs_quick_tile_padding", "dimen", "com.android.systemui");
        int maxId = resources.getIdentifier("quick_qs_panel_max_tiles", "integer", "com.android.systemui");
        int diameter = sizeId == 0 ? 0 : resources.getDimensionPixelSize(sizeId);
        int padding = paddingId == 0 ? 0 : resources.getDimensionPixelSize(paddingId);
        int records = headerClass.isInstance(root) ? root.getChildCount()
                : (maxId == 0 ? 5 : resources.getInteger(maxId)) + 2;
        return Math.max(1, Math.min(records, diameter <= 0 ? records : Math.max(1, available - 2 * padding) / diameter));
    }
    private static int columnLeft(ViewGroup root, int col) {
        Original original = hosts.get(root);
        if (original == null || headerClass.isInstance(root))
            return root.getPaddingStart() + col * (integer(root, "mCellWidth") + integer(root, "mCellMarginHorizontal"));
        // 左右边界各外扩半个栏间距，再按栏位均分并居中；直径保持收起态尺寸。
        int count = columns(root);
        float gap = integer(root, "mCellMarginHorizontal");
        float pitch = (original.contentWidth + gap) / count;
        return root.getPaddingStart() + Math.round(-gap / 2f + (col + .5f) * pitch
                - integer(root, "mCellWidth") / 2f);
    }
    private static void widenLabel(ViewGroup root, View tile, int diameter) {
        Object value = XposedHelpers.callMethod(tile, "getLabel");
        if (!(value instanceof TextView text)) return;
        View container = (View) XposedHelpers.getObjectField(tile, "mLabelContainer");
        View indicator = (View) XposedHelpers.getObjectField(tile, "mExpandIndicator");
        View group = (View) XposedHelpers.getObjectField(tile, "mLabelGroup");
        float density = root.getResources().getDisplayMetrics().density;
        float scale = MergedCardRatioHooks.contentScale(root);
        int arrow = indicator.getLayoutParams().width;
        int arrowMargin = indicator.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins
                ? margins.getMarginStart() + margins.getMarginEnd() : 0;
        int required = (int) Math.ceil(text.getPaint().measureText("移动网络"))
                + Math.max(0, arrow) + arrowMargin + group.getPaddingLeft() + group.getPaddingRight()
                + text.getPaddingLeft() + text.getPaddingRight();
        int count = columns(root);
        Original original = hosts.get(root);
        int pitch = Math.round((original.contentWidth + integer(root, "mCellMarginHorizontal")) / (float) count);
        int width = Math.min(Math.max(diameter + Math.round(4f * density), required),
                Math.max(diameter, pitch - Math.round(2f * density * scale)));
        ViewGroup.LayoutParams lp = container.getLayoutParams();
        if (lp.width != width) { lp.width = width; container.setLayoutParams(lp); }
    }

    private static void configure(ViewGroup root, int width) {
        if (width <= 0) return;
        if (!hosts.containsKey(root)) hosts.put(root, new Original(root));
        if (!headerClass.isInstance(root)) {
            // QQSPanel 原生有侧边距，展开 QSPanel 没有；在分页内部补上同样的边界。
            int id = root.getResources().getIdentifier("qs_header_panel_side_padding", "dimen", "com.android.systemui");
            if (id != 0) {
                int side = root.getResources().getDimensionPixelSize(id);
                View ancestor = root.getParent() instanceof View parent ? parent : null;
                while (ancestor != null) {
                    if (ancestor.getClass().getName().equals("com.android.systemui.qs.QSPanel")) {
                        side = Math.max(0, side - ancestor.getPaddingLeft()); break;
                    }
                    ancestor = ancestor.getParent() instanceof View parent ? parent : null;
                }
                if (root.getPaddingLeft() != side || root.getPaddingRight() != side)
                    root.setPadding(side, root.getPaddingTop(), side, root.getPaddingBottom());
            }
        }
        int available = Math.max(1, width - root.getPaddingLeft() - root.getPaddingRight());
        Original original = hosts.get(root);
        original.contentWidth = available;
        int gap = Math.round(available * MergedCardRatioHooks.gapFraction());
        int diameterWidth = available;
        int diameterColumns = collapsedColumns(root, available);
        // QQS 与 QS 的父容器宽度可能不同。以同一面板的收起网格为尺寸基准，
        // 仅改变展开栏位的分布，不重新计算圆形磁贴直径。
        if (!headerClass.isInstance(root)) {
            for (ViewGroup quick : hosts.keySet()) {
                if (quick != null && headerClass.isInstance(quick) && quick.getRootView() == root.getRootView()) {
                    Original baseline = hosts.get(quick);
                    if (baseline != null && baseline.configuredWidth > 0
                            && baseline.configuredDensity == root.getResources().getDisplayMetrics().density) {
                        diameterWidth = baseline.contentWidth;
                        diameterColumns = columns(quick);
                        break;
                    }
                }
            }
        }
        if (headerClass.isInstance(root)) {
            XposedHelpers.setIntField(root, "mColumns", diameterColumns);
            XposedHelpers.setIntField(root, "mPaddingAdjust", 0);
            for (int i = 0; i < root.getChildCount(); i++)
                root.getChildAt(i).setVisibility(i < diameterColumns ? View.VISIBLE : View.GONE);
        }
        int count = columns(root);
        int diameterGap = Math.round(diameterWidth * MergedCardRatioHooks.gapFraction());
        int size = Math.max(1, (diameterWidth - (diameterColumns - 1) * diameterGap) / diameterColumns);
        float scale = MergedCardRatioHooks.contentScale(root);
        float density = root.getResources().getDisplayMetrics().density;
        float fontScale = root.getResources().getConfiguration().fontScale;
        long content = 1;
        for (int i = 0; i < root.getChildCount(); i++) {
            View tile = root.getChildAt(i);
            content = content * 31 + System.identityHashCode(tile);
            content = content * 31 + tile.getVisibility();
        }
        original.contentHash = content;
        if (original.configuredWidth == available && original.configuredColumns == count
                && original.configuredGap == gap && original.configuredScale == scale
                && original.configuredDensity == density && original.configuredFontScale == fontScale
                && original.configuredContent == content && integer(root, "mCellWidth") == size
                && integer(root, "mCellHeight") == original.rowHeight
                && integer(root, "mCellMarginHorizontal") == gap && integer(root, "mCellMarginVertical") == gap) return;
        XposedHelpers.setIntField(root, "mCellWidth", size);
        MergedCardRatioHooks.applyGridGeometry(root);
        int rowHeight = size + (headerClass.isInstance(root) ? 0 : labelHeight(root, 1));
        XposedHelpers.setIntField(root, "mCellHeight", rowHeight);
        XposedHelpers.setIntField(root, "mResourceCellHeight", rowHeight);
        XposedHelpers.setIntField(root, "mEstimatedCellHeight", rowHeight);
        XposedHelpers.setIntField(root, "mCellMarginHorizontal", gap);
        XposedHelpers.setIntField(root, "mCellMarginVertical", gap);
        XposedHelpers.setIntField(root, "mSidePadding", 0);
        original.configuredWidth = available; original.configuredColumns = count; original.configuredGap = gap;
        original.configuredScale = scale; original.configuredDensity = density; original.configuredFontScale = fontScale;
        original.configuredContent = content; original.rowHeight = rowHeight;
    }

    private static int labelHeight(ViewGroup root, int lines) {
        float scale = MergedCardRatioHooks.contentScale(root);
        float density = root.getResources().getDisplayMetrics().density;
        int lineHeight = 0;
        // 行距使用第一行，文字测量保留两行；均由字体指标决定，不随文本内容反馈变化。
        for (int i = 0; i < root.getChildCount(); i++) {
            Object label = XposedHelpers.callMethod(root.getChildAt(i), "getLabel");
            if (label instanceof TextView text) { lineHeight = text.getLineHeight(); break; }
        }
        if (lineHeight <= 0) {
            int id = root.getResources().getIdentifier("oplus_qs_tile_text_size", "dimen", "com.android.systemui");
            Paint paint = new Paint();
            paint.setTextSize((id != 0 ? root.getResources().getDimension(id) : 10f * density) * scale);
            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            lineHeight = metrics.descent - metrics.ascent;
        }
        return Math.max(1, lineHeight) * lines + Math.max(0, Math.round(2f * density * scale)) + Math.round(8f * density);
    }
    private static void restore(ViewGroup root) {
        Original original = hosts.remove(root);
        if (original == null) return;
        MergedCardRatioHooks.restoreGridGeometry(root);
        for (int i = 0; i < FIELDS.length; i++) XposedHelpers.setIntField(root, FIELDS[i], original.values[i]);
        root.setPaddingRelative(original.paddingStart, root.getPaddingTop(), original.paddingEnd, root.getPaddingBottom());
        root.requestLayout();
    }
    static void refresh() {
        boolean four = fourColumns(), ratio = readBool(KEY_QS_MERGED_CARD_RATIO, false);
        if (lastFourColumns == null || lastFourColumns != four || lastRatio == null || lastRatio != ratio) {
            lastFourColumns = four; lastRatio = ratio;
            for (Object controller : new ArrayList<>(quickControllers.keySet())) if (controller != null)
                XposedHelpers.callMethod(controller, "setTiles");
            for (ViewGroup root : new ArrayList<>(columnHosts.keySet())) if (root != null) {
                XposedHelpers.callMethod(root, "updateResources");
                if (root.getParent() instanceof View parent) {
                    if (parent.getClass().getName().equals("com.android.systemui.qs.PagedTileLayout"))
                        XposedHelpers.setBooleanField(parent, "mDistributeTiles", true);
                    parent.requestLayout();
                }
                root.requestLayout();
            }
        }
        for (ViewGroup root : new ArrayList<>(hosts.keySet())) if (root != null) {
            if (!readBool(KEY_QS_MERGED_CARD_RATIO, false)) restore(root);
            root.requestLayout();
        }
    }
    static ArrayList<Object> capture() {
        ArrayList<Object> result = new ArrayList<>(hosts.keySet());
        result.addAll(columnHosts.keySet()); result.addAll(quickControllers.keySet());
        return result;
    }
    static void restoreHosts(Iterable<?> views) {
        for (Object view : views) {
            if (quickControllerClass.isInstance(view)) quickControllers.put(view, true);
            if (!(view instanceof ViewGroup root) || !isGrid(root)) continue;
            columnHosts.put(root, true);
            if (!hosts.containsKey(root)) hosts.put(root, new Original(root));
            root.requestLayout();
        }
    }
    static void cleanup() {
        for (Object animator : new ArrayList<>(pendingAnimations.keySet())) {
            Runnable callback = pendingAnimations.get(animator);
            if (animator != null && callback != null)
                ((View) XposedHelpers.getObjectField(animator, "mQsRootView")).removeCallbacks(callback);
        }
        pendingAnimations.clear();
        for (ViewGroup root : new ArrayList<>(hosts.keySet())) if (root != null) restore(root);
        restoringColumns.set(true);
        try {
            for (ViewGroup root : new ArrayList<>(columnHosts.keySet())) if (root != null) XposedHelpers.callMethod(root, "updateResources");
            for (Object controller : new ArrayList<>(quickControllers.keySet())) if (controller != null) XposedHelpers.callMethod(controller, "setTiles");
        } finally { restoringColumns.remove(); }
        hosts.clear(); pagedPages.clear(); columnHosts.clear(); quickControllers.clear(); fixedAnimators.clear(); animatorGeometry.clear(); lastFourColumns = null; lastRatio = null;
    }
    private static final class Original {
        final int[] values = new int[FIELDS.length];
        final int reference, paddingStart, paddingEnd;
        final float density;
        int contentWidth, configuredWidth = -1, configuredColumns, configuredGap, rowHeight;
        float configuredScale, configuredDensity, configuredFontScale;
        long configuredContent, contentHash, accessibilityHash = Long.MIN_VALUE;
        Original(ViewGroup root) {
            density = root.getResources().getDisplayMetrics().density;
            paddingStart = root.getPaddingStart(); paddingEnd = root.getPaddingEnd();
            for (int i = 0; i < FIELDS.length; i++) values[i] = integer(root, FIELDS[i]);
            int resource = root.getResources().getIdentifier(headerClass.isInstance(root) ? "qs_quick_qs_tile_size" : "qs_quick_tile_size", "dimen", "com.android.systemui");
            reference = resource != 0 ? root.getResources().getDimensionPixelSize(resource) : Math.max(values[2], values[3]);
        }
    }
}
