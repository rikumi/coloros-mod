package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Rect;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** 分离控制中心的显示列表投影，编辑和拖拽时恢复原生列表。 */
public final class FoldIdleMediaHooks {
    private static final String LAYOUT = "com.oplus.systemui.qs.base.widget.recyclerview.StaggeredPagerLayoutManager";
    private static final String ADAPTER = "com.oplus.systemui.plugins.qs.customize.view.EditTileAdapter";
    private static final String MEDIA_VIEW = "com.oplus.systemui.qs.media.OplusQsBaseMediaPanelView";
    private static final String MEDIA_MODEL = "com.oplus.systemui.plugins.qs.customize.view.viewholder.MediaViewModel";
    private static final WeakHashMap<ViewGroup, Host> hosts = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> compactHolders = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> mediaPlaying = new WeakHashMap<>();
    private static final View.OnAttachStateChangeListener attachListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { refresh(); }
        @Override public void onViewDetachedFromWindow(View view) { }
    };
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Class<?> locClass;
    private static boolean changing, queued, polling;
    private static final Runnable poll = () -> {
        polling = false;
        refresh();
    };
    private static final class Host {
        final WeakReference<ViewGroup> root;
        final WeakReference<Object> manager, adapter;
        List<?> data, locs;
        List<?> displayedData, displayedLocs;
        int displayedRows = -1;
        List<Integer> halves = new ArrayList<>();
        Set<Object> compactData = new HashSet<>();
        Host(ViewGroup root, Object manager, Object adapter) {
            this.root = new WeakReference<>(root); this.manager = new WeakReference<>(manager);
            this.adapter = new WeakReference<>(adapter);
            data = copy(XposedHelpers.callMethod(adapter, "getDataList"));
            locs = copy(XposedHelpers.callMethod(config(), "getSpecifyLoc"));
        }
        Object config() { return XposedHelpers.callMethod(manager.get(), "getLayoutConfig"); }
    }
    public static void hookFoldIdleMedia(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            locClass = XposedHelpers.findClass("com.oplusos.systemui.common.model.Loc", pkg.classLoader);
            Class<?> layout = XposedHelpers.findClass(LAYOUT, pkg.classLoader);
            Class<?> recycler = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView$Recycler", pkg.classLoader);
            Class<?> state = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView$State", pkg.classLoader);
            Class<?> recyclerView = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", pkg.classLoader);
            XposedHelpers.findAndHookMethod(layout, "onAttachedToWindow", recyclerView, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) { register(p.thisObject); refresh(); }
                }
            });
            XposedHelpers.findAndHookMethod(layout, "fillItems", recycler, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) { register(p.thisObject); refresh(); }
                }
            });
            XposedHelpers.findAndHookMethod(layout, "onMeasure", recycler, state, int.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    ViewGroup root = (ViewGroup) XposedHelpers.callMethod(p.thisObject, "getRecyclerView");
                    Host host = hosts.get(root);
                    if (host == null || host.displayedRows < 0 || !is(root, "personal_tiles_container")) return;
                    Object config = host.config();
                    int removed = number(config, "getRowCount") - host.displayedRows;
                    int step = number(config, "getCellHeight") + number(config, "getVerticalSpace");
                    // 下方区域约束在上方区域底部，补位时收紧末行后的空白。
                    int fillSpacing = host.compactData.isEmpty() ? 0
                            : Math.round(6f * root.getResources().getDisplayMetrics().density);
                    XposedHelpers.callMethod(p.thisObject, "setMeasuredDimension", new Class<?>[]{int.class, int.class},
                            root.getMeasuredWidth(), Math.max(0, root.getMeasuredHeight() - removed * step - fillSpacing));
                }
            });
            Class<?> span = XposedHelpers.findClass("com.oplusos.systemui.common.model.SpanSize", pkg.classLoader);
            XposedHelpers.findAndHookMethod(layout, "getItemRect", locClass, span, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) XposedHelpers.callMethod(p.thisObject, "getRecyclerView");
                    Host host = hosts.get(root);
                    if (host == null || host.displayedLocs == null || !(p.getResult() instanceof Rect rect)) return;
                    // 两个磁贴可共享原生格坐标，按坐标对象身份分别取得左右半格。
                    for (int i = 0; i < host.displayedLocs.size() && i < host.halves.size(); i++) {
                        if (host.displayedLocs.get(i) != p.args[0]) continue;
                        int half = host.halves.get(i);
                        if (half < 0) return;
                        if (root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) half = 1 - half;
                        int middle = rect.left + rect.width() / 2;
                        p.setResult(new Rect(half == 0 ? rect.left : middle, rect.top,
                                half == 0 ? middle : rect.right, rect.bottom));
                        return;
                    }
                }
            });
            Class<?> adapter = XposedHelpers.findClass(ADAPTER, pkg.classLoader);
            Class<?> holder = XposedHelpers.findClass("com.oplus.systemui.plugins.qs.customize.view.viewholder.BaseEditableViewHolder", pkg.classLoader);
            for (String method : new String[]{"setPersonalArea", "setEnableTileName"}) {
                XposedHelpers.findAndHookMethod(holder, method, boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (compactHolders.containsKey(p.thisObject)) p.args[0] = false;
                    }
                });
            }
            XposedHelpers.findAndHookMethod(adapter, "onBindViewHolder", holder, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Object itemHolder = p.args[0];
                    compactHolders.remove(itemHolder);
                    ViewGroup root = (ViewGroup) XposedHelpers.getObjectField(p.thisObject, "attachedRecyclerView");
                    Host host = hosts.get(root);
                    List<?> data = (List<?>) XposedHelpers.callMethod(p.thisObject, "getDataList");
                    int position = (Integer) p.args[1];
                    if (host != null && position >= 0 && position < data.size() && host.compactData.contains(data.get(position))) {
                        compactHolders.put(itemHolder, true);
                        XposedHelpers.callMethod(itemHolder, "setPersonalArea", false);
                        XposedHelpers.callMethod(itemHolder, "setEnableTileName", false);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(adapter, "onAttachedToRecyclerView", recyclerView, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.args[0];
                    Object manager = XposedHelpers.callMethod(root, "getLayoutManager");
                    if (manager != null && LAYOUT.equals(manager.getClass().getName())) register(manager, root);
                    refresh();
                }
            });
            Class<?> baseAdapter = XposedHelpers.findClass("com.oplusos.systemui.common.adapter.BaseRecyclerAdapter", pkg.classLoader);
            XposedHelpers.findAndHookMethod(baseAdapter, "setDataList", List.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (changing || !ADAPTER.equals(p.thisObject.getClass().getName())) return;
                    ViewGroup root = (ViewGroup) XposedHelpers.getObjectField(p.thisObject, "attachedRecyclerView");
                    Host host = hosts.get(root);
                    if (host != null) {
                        host.data = copy(p.args[0]);
                        host.locs = copy(XposedHelpers.callMethod(host.config(), "getSpecifyLoc"));
                        host.displayedData = null; host.displayedLocs = null; host.displayedRows = -1;
                    }
                    refresh();
                }
            });
            XposedHelpers.findAndHookMethod(adapter, "submitList", List.class, List.class, List.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (changing) return;
                    ViewGroup root = (ViewGroup) XposedHelpers.getObjectField(p.thisObject, "attachedRecyclerView");
                    if (root == null) return;
                    Object manager = XposedHelpers.callMethod(root, "getLayoutManager");
                    if (manager == null || !LAYOUT.equals(manager.getClass().getName())) return;
                    register(manager);
                    Host host = hosts.get(root);
                    if (host == null) return;
                    List<?> incomingLocs = copy(XposedHelpers.callMethod(host.config(), "getSpecifyLoc"));
                    restoreAll();
                    host.data = copy(p.args[0]); host.locs = incomingLocs;
                    XposedHelpers.callMethod(manager, "setLoc", incomingLocs);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { if (!changing) refresh(); }
            });
            for (String method : new String[]{"updateEditMode$1", "setDragMode"}) {
                XposedHelpers.findAndHookMethod(adapter, method, boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (Boolean.TRUE.equals(p.args[0])) restoreAll();
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) { refresh(); }
                });
            }
            Class<?> media = XposedHelpers.findClass(MEDIA_VIEW, pkg.classLoader);
            Class<?> data = XposedHelpers.findClass("com.android.systemui.media.controls.shared.model.MediaData", pkg.classLoader);
            XposedHelpers.findAndHookMethod(media, "bindMediaDataInner", data, String.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    mediaPlaying.put((View) p.thisObject, p.args[0] != null && Boolean.TRUE.equals(XposedHelpers.callMethod(p.args[0], "isPlaying")));
                    refresh();
                }
            });
        } catch (Throwable t) { log("HOOK FAIL control center reflow: " + t); }
    }
    private static void register(Object manager) {
        ViewGroup root = (ViewGroup) XposedHelpers.callMethod(manager, "getRecyclerView");
        register(manager, root);
    }
    private static void register(Object manager, ViewGroup root) {
        if (root == null || !(is(root, "personal_tiles_container") || is(root, "other_tiles_container"))) return;
        Object adapter = XposedHelpers.callMethod(root, "getAdapter");
        if (adapter == null || !ADAPTER.equals(adapter.getClass().getName())) return;
        Host host = hosts.get(root);
        if (host == null || host.manager.get() != manager || host.adapter.get() != adapter) {
            hosts.put(root, new Host(root, manager, adapter));
            root.removeOnAttachStateChangeListener(attachListener);
            root.addOnAttachStateChangeListener(attachListener);
        } else {
            syncNative(host);
        }
    }
    private static void syncNative(Host host) {
        // 未投影时原生列表和坐标仍可能在首次挂载之后到达，不能保留初始空快照。
        // 投影期间不回读，避免把补位后的显示列表当成用户原始排列。
        if (host.displayedData != null || host.adapter.get() == null || host.manager.get() == null) return;
        host.data = copy(XposedHelpers.callMethod(host.adapter.get(), "getDataList"));
        host.locs = copy(XposedHelpers.callMethod(host.config(), "getSpecifyLoc"));
    }
    private static List<?> copy(Object list) { return new ArrayList<>((List<?>) list); }
    private static boolean is(View view, String name) {
        int id = view.getResources().getIdentifier(name, "id", "com.android.systemui");
        return id != 0 && view.getId() == id;
    }
    private static int number(Object object, String method) { return (Integer) XposedHelpers.callMethod(object, method); }
    private static boolean editing(Host host) {
        Object adapter = host.adapter.get();
        return adapter == null || XposedHelpers.getBooleanField(adapter, "isEditMode")
                || XposedHelpers.getBooleanField(adapter, "isDragMode");
    }
    private static List<IdleMediaGeometry.Cell> cells(Host host) {
        List<IdleMediaGeometry.Cell> result = new ArrayList<>();
        if (host.data.size() != host.locs.size()) return result;
        for (int i = 0; i < host.data.size(); i++) {
            Object grid = host.data.get(i), size = XposedHelpers.callMethod(grid, "getSize"), loc = host.locs.get(i);
            Object model = XposedHelpers.callMethod(grid, "getData");
            result.add(new IdleMediaGeometry.Cell(i, number(loc, "getPageIndex"), number(loc, "getStartCol"),
                    number(loc, "getStartRow"), number(size, "getColSpan"), number(size, "getRowSpan"),
                    model != null && MEDIA_MODEL.equals(model.getClass().getName()) ? IdleMediaGeometry.MEDIA
                            : model != null && "com.oplus.systemui.plugins.qs.customize.view.viewholder.BrightnessVolumeViewModel".equals(model.getClass().getName())
                            ? IdleMediaGeometry.VERTICAL_ONLY : IdleMediaGeometry.CARD));
        }
        return result;
    }
    private static boolean active(int state) {
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING || state == PlaybackState.STATE_SKIPPING_TO_NEXT
                || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS || state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM;
    }
    private static boolean playing(View root) {
        try {
            MediaSessionManager sessions = root.getContext().getSystemService(MediaSessionManager.class);
            if (sessions != null) for (MediaController controller : sessions.getActiveSessions(null)) {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && active(state.getState())) return true;
            }
            return false;
        } catch (SecurityException ignored) {
            return mediaPlaying.containsValue(Boolean.TRUE);
        }
    }
    public static void refresh() {
        if (queued) return;
        queued = true;
        handler.post(() -> {
            queued = false;
            try { rebuild(); } catch (Throwable t) { log("control center reflow error: " + t); restoreAll(); }
            if (!polling && (readBoolCached(KEY_FOLD_IDLE_MEDIA, false) || readBoolCached(KEY_QS_FILL_EMPTY, false))) {
                for (ViewGroup root : hosts.keySet()) if (root.isAttachedToWindow() && root.isShown()) {
                    polling = true; handler.postDelayed(poll, 1000); break;
                }
            }
        });
    }
    private static void rebuild() {
        boolean hide = readBool(KEY_FOLD_IDLE_MEDIA, false), fill = readBool(KEY_QS_FILL_EMPTY, false);
        if (!hide && !fill) { restoreAll(); return; }
        for (ViewGroup root : new ArrayList<>(hosts.keySet())) {
            if (!is(root, "personal_tiles_container") || !root.isAttachedToWindow()) continue;
            Host upper = hosts.get(root);
            ViewGroup parent = root.getParent() instanceof ViewGroup ? (ViewGroup) root.getParent() : null;
            int id = root.getResources().getIdentifier("other_tiles_container", "id", "com.android.systemui");
            ViewGroup lowerRoot = parent == null ? null : parent.findViewById(id);
            if (lowerRoot == null) continue;
            Object lowerManager = XposedHelpers.callMethod(lowerRoot, "getLayoutManager");
            if (lowerManager == null || !LAYOUT.equals(lowerManager.getClass().getName())) continue;
            register(lowerManager);
            Host lower = hosts.get(lowerRoot);
            if (lower == null) continue;
            syncNative(upper);
            syncNative(lower);
            if (editing(upper) || editing(lower)) { restoreAll(); return; }
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(root, "isComputingLayout"))
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(lowerRoot, "isComputingLayout"))) { refresh(); return; }
            List<IdleMediaGeometry.Cell> top = cells(upper), bottom = cells(lower);
            if (top.size() != upper.data.size() || bottom.size() != lower.data.size()) continue;
            Set<Integer> hidden = new HashSet<>();
            if (hide && !playing(root)) for (IdleMediaGeometry.Cell cell : top)
                if (cell.kind == IdleMediaGeometry.MEDIA) hidden.add(cell.index);
            if (hidden.isEmpty() && !fill) { restore(upper); restore(lower); continue; }
            IdleMediaGeometry.Plan plan = IdleMediaGeometry.arrange(top, bottom, number(upper.config(), "getColCount"),
                    number(upper.config(), "getRowCount"), number(lower.config(), "getColCount"),
                    number(lower.config(), "getRowCount"), hidden, fill);
            if (plan == null) { restore(upper); restore(lower); continue; }
            List<Object> topData = new ArrayList<>(), topLocs = new ArrayList<>(), bottomData = new ArrayList<>(), bottomLocs = new ArrayList<>();
            int retained = top.size() - hidden.size();
            upper.halves = new ArrayList<>();
            upper.compactData.clear();
            for (int i = 0; i < plan.upper.size(); i++) {
                IdleMediaGeometry.Cell cell = plan.upper.get(i);
                Object item = (i < retained ? upper.data : lower.data).get(cell.index);
                topData.add(item); topLocs.add(loc(cell));
                upper.halves.add(cell.half);
                if (cell.half >= 0) upper.compactData.add(item);
            }
            for (IdleMediaGeometry.Cell cell : plan.lower) { bottomData.add(lower.data.get(cell.index)); bottomLocs.add(loc(cell)); }
            project(upper, topData, topLocs, plan.upperRows);
            project(lower, bottomData, bottomLocs, -1);
        }
    }
    private static Object loc(IdleMediaGeometry.Cell cell) {
        return XposedHelpers.newInstance(locClass, cell.page, cell.col, cell.row);
    }
    private static void project(Host host, List<?> data, List<?> locs, int rows) {
        if (data.equals(host.displayedData) && locs.equals(host.displayedLocs) && rows == host.displayedRows
                && data.equals(XposedHelpers.callMethod(host.adapter.get(), "getDataList"))
                && locs.equals(XposedHelpers.callMethod(host.config(), "getSpecifyLoc"))) return;
        boolean previous = changing; changing = true;
        try {
            XposedHelpers.callMethod(host.adapter.get(), "setDataList", data);
            host.displayedData = new ArrayList<>(data); host.displayedLocs = new ArrayList<>(locs); host.displayedRows = rows;
            XposedHelpers.callMethod(host.manager.get(), "setLoc", locs);
            host.displayedLocs = copy(XposedHelpers.callMethod(host.config(), "getSpecifyLoc"));
            Object layoutState = XposedHelpers.getObjectField(host.manager.get(), "layoutState");
            XposedHelpers.callMethod(XposedHelpers.callMethod(layoutState, "getItemLayoutRects"), "clear");
            XposedHelpers.callMethod(host.adapter.get(), "notifyDataSetChanged");
            ViewGroup root = host.root.get(); if (root != null) root.requestLayout();
        } finally { changing = previous; }
    }
    private static void restore(Host host) {
        if (host.displayedData == null) return;
        host.halves.clear(); host.compactData.clear();
        project(host, host.data, host.locs, -1);
        host.displayedData = null; host.displayedLocs = null;
    }
    private static void restoreAll() { for (Host host : new ArrayList<>(hosts.values())) restore(host); }
    public static Object captureHotReloadHosts() { return new ArrayList<>(hosts.keySet()); }
    public static void restoreHotReloadHosts(Object state) {
        if (state instanceof List<?> list) for (Object item : list) if (item instanceof ViewGroup root) {
            Object manager = XposedHelpers.callMethod(root, "getLayoutManager"); if (manager != null) register(manager);
        }
        refresh();
    }
    public static void cleanupForHotReload() {
        handler.removeCallbacksAndMessages(null); queued = false; polling = false;
        for (ViewGroup root : new ArrayList<>(hosts.keySet())) root.removeOnAttachStateChangeListener(attachListener);
        restoreAll(); hosts.clear(); mediaPlaying.clear(); compactHolders.clear();
    }
}
