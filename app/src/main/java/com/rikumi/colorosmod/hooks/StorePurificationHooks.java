package com.rikumi.colorosmod.hooks;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.app.Activity;
import android.view.View;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import static com.rikumi.colorosmod.XposedInit.*;

public final class StorePurificationHooks {
    private static final String DTO = "com.heytap.cdo.card.domain.dto.";
    private static final String PAGE = "com.heytap.cdo.client.cards.page.";
    private static final String MARKET = "com.heytap.market.";
    private static final String DETAIL = "com.heytap.cdo.client.detail.cn.app.tabcontent.";
    private static final String ADAPTER_KIND = "colorosmod.store.adapterKind";
    private static final String VISIBLE_TABS = "colorosmod.store.visibleTabs";
    private static final String SOFTWARE_MODE = "colorosmod.store.emptySoftware";
    private static final String SOFTWARE = PAGE + "main.app.AppGroupFragment";
    private static boolean attempted;

    private StorePurificationHooks() {}
    private static boolean enabled(String option) { return readBool("store_" + option, false); }

    public static void hookStore(XC_LoadPackage.LoadPackageParam pkg) {
        Context context = currentApplication();
        if (context != null) {
            installForVersion(context, pkg.classLoader);
        } else {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    installForVersion((Context) p.args[0], pkg.classLoader);
                }
            });
        }
    }

    private static synchronized void installForVersion(Context context, ClassLoader cl) {
        if (attempted || !"com.heytap.market".equals(context.getPackageName())) return;
        attempted = true;
        try {
            install(cl);
        } catch (Throwable t) { log("Store purification initialization: " + t); }
    }

    interface Action { void run(XC_MethodHook.MethodHookParam p) throws Throwable; }

    static void hook(ClassLoader cl, String type, String name, boolean before, Action action,
                             String... parameters) {
        try {
            Class<?>[] types = new Class<?>[parameters.length];
            for (int i = 0; i < types.length; i++) {
                types[i] = switch (parameters[i]) {
                    case "boolean" -> boolean.class;
                    case "int" -> int.class;
                    default -> Class.forName(parameters[i], false, cl);
                };
            }
            Method method = Class.forName(type, false, cl).getDeclaredMethod(name, types);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                private boolean reported;
                private void apply(MethodHookParam p) {
                    try { action.run(p); }
                    catch (Throwable t) {
                        if (!reported) { reported = true; log("Store " + type + "#" + name + ": " + t); }
                    }
                }
                @Override protected void beforeHookedMethod(MethodHookParam p) { if (before) apply(p); }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!before && !p.hasThrowable()) apply(p);
                }
            });
        } catch (Throwable t) { log("Store hook unavailable " + type + "#" + name + ": " + t); }
    }

    private static void install(ClassLoader cl) {
        installEmptySoftware(cl);
        installStyles(cl);
        // The native launch coordinator handles the no-splash branch, including slogan removal.
        hook(cl, "a.a.a.w3f", "isSupportSplash", true,
                p -> { if (enabled("block_splash")) p.setResult(false); });
        hook(cl, "a.a.a.w3f", "hasSplash", true,
                p -> { if (enabled("block_splash")) p.setResult(false); });

        hook(cl, PAGE + "struct.TabDataPresenter", "ރ", false, p -> {
            if (tabsFiltered()) p.setResult(filterTabs(p.getResult()));
        });

        // o9b is the actual tab navigation data manager. Filter every cache/network refresh here.
        hook(cl, "a.a.a.o9b", "m36690", true, p -> {
            if (tabsFiltered()) p.args[0] = visibleTabs(list(p.args[0]));
        }, "java.util.List");
        tagPresenter(cl, PAGE + "main.home.HomeChosenCardAdapterPresenter", "home");
        tagPresenter(cl, MARKET + "search.core.fragment.home.SearchHomeCardAdapterPresenter", "search");
        hook(cl, PAGE + "base.CardFragment", "ຑ", false, p -> {
            String type = p.thisObject.getClass().getName();
            if (type.equals(PAGE + "main.home.HomeChildCardFragment")) {
                Object args = XposedHelpers.callStaticMethod(Class.forName("a.a.a.an2", false, cl), "Ԩ",
                        call(p.thisObject, "getArguments"));
                // Category cards share HomeChildCardFragment: never erase the category page.
                String path = (String) call(args, "getRequestPath");
                tag(p.getResult(), categoryPath(path) ? "category" : "home");
            }
        });
        // These are concrete adapters; the API adapter's methods are abstract.
        for (String adapter : new String[]{"com.nearme.cards.adapter.RecyclerViewCardAdapter",
                "com.heytap.card.api.listener.CardApiRecyclerViewAdapterProxy"}) {
            for (String method : new String[]{"setDatas", "addDataAndNotifyChanged"}) {
                hook(cl, adapter, method, true, p -> {
                    Object kind = XposedHelpers.getAdditionalInstanceField(p.thisObject, ADAPTER_KIND);
                    if ("home".equals(kind) && enabled("empty_home")) p.args[0] = new ArrayList<>();
                    else if ("search".equals(kind) && enabled("clean_search")) p.args[0] = historyOnly(list(p.args[0]));
                }, "java.util.List");
            }
        }
        hook(cl, MARKET + "search.core.recommend.a", "getNextRecommendInfo", true,
                p -> { if (enabled("clean_search")) p.setResult(null); }, "int");
        hook(cl, MARKET + "search.core.recommend.a", "getRecommendWordSize", true,
                p -> { if (enabled("clean_search")) p.setResult(0); });

        // The final Mine render receives both online and cached results.
        hook(cl, MARKET + "mine.MineFragment", "ࢯ", true, p -> {
            if (!mineEnabled() || p.args[0] == null) return;
            Object original = p.args[0];
            List<?> cards = list(call(original, "getCards"));
            if (cards == null) return;
            Object copy = copyDto(original);
            call(copy, "setCards", filterMine(cards));
            p.args[0] = copy;
        }, DTO + "ViewLayerWrapDto", "boolean");

        // New inline recommendation result: skip before it is merged with the detail body.
        hook(cl, DETAIL + "detail.a", "ԭ", true,
                p -> { if (enabled("hide_detail_recommendations")) p.setResult(null); }, DETAIL + "detail.b");
        // Legacy separate recommendation tab: null uses its native empty/loading-complete branch.
        hook(cl, DETAIL + "recommend.TabRecommendContentView", "ޞ", true,
                p -> { if (enabled("hide_detail_recommendations")) p.args[0] = null; }, "a.a.a.sn2");

        String base = MARKET + "appmanage.core.upgrade.upgrademgr.BaseRecyclerViewDownloadFragment";
        for (String suffix : new String[]{"$e", "$f"}) {
            hook(cl, base + suffix, "Ԫ", true, p -> {
                Object fragment = owner(p.thisObject, base);
                String type = fragment.getClass().getName();
                if ((type.equals(MARKET + "appmanage.core.download.downloadmgr.DownloadManageFragment")
                        && enabled("hide_download_recommendations"))
                        || (type.equals(MARKET + "appmanage.core.upgrade.upgrademgr.recyclerview.AppUpdateFragment")
                        && enabled("hide_update_recommendations"))) p.setResult(null);
            }, "a.a.a.ik3");
        }
    }

    private static void installStyles(ClassLoader cl) {
        String resource = "com.heytap.cdo.common.domain.dto.ResourceDto";
        // z07 / BigImageMultiIconCard select iconUrl when gifIcon is absent.
        for (String type : new String[]{resource, "com.heytap.cdo.detail.domain.dto.detail.BaseDetailDto"}) {
            hook(cl, type, "getGifIconUrl", true,
                    p -> { if (enabled("disable_dynamic_icons")) p.setResult(null); });
        }
        // ax8 uses iconStyle=1 for app-icon entrance animation; zero is its native ordinary path.
        hook(cl, resource, "getIconStyle", true,
                p -> { if (enabled("disable_dynamic_icons")) p.setResult(0); });
        hook(cl, "com.heytap.cdo.common.domain.dto.supertheme.SuperThemeRightsDto", "getIconPendantDynamic", true,
                p -> { if (enabled("disable_dynamic_icons")) p.setResult(null); });
        // unf/gg7 clear decorations and bind ordinary app text/colors when this model is absent.
        hook(cl, resource, "getSuperThemeRights", true,
                p -> { if (enabled("disable_special_styles")) p.setResult(null); });
        hook(cl, resource, "getHighlight", true,
                p -> { if (enabled("disable_special_styles")) p.setResult(0); });
        // tyf and f14 select NORMAL only when both special and background are absent.
        hook(cl, resource, "getSpecial", true,
                p -> { if (enabled("disable_special_styles")) p.setResult(0); });
        hook(cl, resource, "getBg", true,
                p -> { if (enabled("disable_special_styles")) p.setResult(null); });
    }

    private static boolean categoryPath(String value) {
        if (value == null) return false;
        String path = Uri.parse(value).getPath();
        return "/card/store/v4/cat/app".equals(path) || "/card/store/v5/cat/app".equals(path)
                || "/card/store/v4/cat/game".equals(path) || "/card/store/v5/cat/game".equals(path);
    }

    private static boolean emptySoftware(Object fragment) {
        return SOFTWARE.equals(fragment.getClass().getName()) && enabled("empty_software");
    }

    private static void installEmptySoftware(ClassLoader cl) {
        String normal = PAGE + "main.normal.NormalGroupFragment";

        hook(cl, normal, "onViewCreated", true, p -> {
            if (!SOFTWARE.equals(p.thisObject.getClass().getName())) return;
            boolean empty = enabled("empty_software");
            Object previous = XposedHelpers.getAdditionalInstanceField(p.thisObject, SOFTWARE_MODE);
            XposedHelpers.setAdditionalInstanceField(p.thisObject, SOFTWARE_MODE, empty);
            if (!empty) {
                // A fragment may keep its instance while only its view is recreated.
                if (Boolean.TRUE.equals(previous)) {
                    Object args = call(p.thisObject, "getArguments");
                    XposedHelpers.setObjectField(p.thisObject, "ࢰ", args == null ? new Bundle() : args);
                }
                return;
            }

            XposedHelpers.setObjectField(p.thisObject, "ࢰ", new Bundle());
            Object manager = call(p.thisObject, "getChildFragmentManager");
            List<?> restored = new ArrayList<>((List<?>) call(manager, "getFragments"));
            if (!restored.isEmpty()) {
                Object transaction = call(manager, "beginTransaction");
                for (Object fragment : restored) call(transaction, "remove", fragment);
                call(transaction, "commitNowAllowingStateLoss");
            }
        }, "android.view.View", "android.os.Bundle");
        hook(cl, normal, "onViewCreated", false, p -> {
            if (!emptySoftware(p.thisObject)) return;

            for (String field : new String[]{"ࢫ", "ࢮ"}) {
                View content = (View) XposedHelpers.getObjectField(p.thisObject, field);
                content.setVisibility(View.GONE);
                if (content.getParent() instanceof ViewGroup parent) parent.removeView(content);
            }
        }, "android.view.View", "android.os.Bundle");
        hook(cl, normal, "getHeight", true, p -> {
            if (!emptySoftware(p.thisObject)) return;
            Object bar = XposedHelpers.getObjectField(p.thisObject, "ࢩ");

            p.setResult(XposedHelpers.callStaticMethod(Class.forName("a.a.a.bg", false, cl), "Ԭ", bar));
        });

        hook(cl, "com.nearme.module.ui.fragment.BaseFragment", "onResume", false, p -> {
            if (!SOFTWARE.equals(p.thisObject.getClass().getName())) return;
            Object old = XposedHelpers.getAdditionalInstanceField(p.thisObject, SOFTWARE_MODE);
            if (old instanceof Boolean previous && previous != enabled("empty_software")) {
                XposedHelpers.setAdditionalInstanceField(p.thisObject, SOFTWARE_MODE, enabled("empty_software"));
                ((Activity) call(p.thisObject, "requireActivity")).recreate();
            }
        });
    }

    private static void tagPresenter(ClassLoader cl, String name, String kind) {
        try {
            XposedBridge.hookAllConstructors(Class.forName(name, false, cl), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) tag(p.args[0], kind);
                }
            });
        } catch (Throwable t) { log("Store presenter unavailable " + name + ": " + t); }
    }

    private static void tag(Object adapter, String kind) {
        if (adapter != null) XposedHelpers.setAdditionalInstanceField(adapter, ADAPTER_KIND, kind);
    }

    private static final class TabSnapshot {
        final int mask;
        final List<?> source;
        final Object copy;
        TabSnapshot(int mask, List<?> source, Object copy) { this.mask = mask; this.source = source; this.copy = copy; }
    }

    private static List<?> visibleTabs(List<?> tabs) throws Throwable {
        if (tabs == null || tabs.isEmpty()) return tabs;
        ArrayList<Object> kept = new ArrayList<>();
        Object app = null;
        for (Object tab : tabs) {
            int id = (Integer) call(tab, "getId");
            // Main-tab mode keeps only Home (10) and Mine (50); remove Software (20), Games (30), and Rankings (40).
            if (enabled("empty_software")) {
                if (id == 10 || id == 50) kept.add(tab);
                continue;
            }
            if (id == 20) app = tab;
            if ((id == 10 && enabled("hide_featured")) || (id == 30 && enabled("hide_games"))
                    || (id == 40 && enabled("hide_rank"))) continue;
            kept.add(tab);
        }
        if (kept.size() == tabs.size()) return tabs;
        if (enabled("hide_featured") && !enabled("empty_software") && app != null) { kept.remove(app); kept.add(0, app); }
        return kept;
    }

    private static boolean tabsFiltered() {
        return enabled("hide_featured") || enabled("hide_games") || enabled("hide_rank")
                || enabled("empty_software");
    }

    private static Object filterTabs(Object original) throws Throwable {
        if (original == null) return null;
        List<?> tabs = list(call(original, "getTabDtoList"));
        int mask = (enabled("hide_featured") ? 1 : 0) | (enabled("hide_games") ? 2 : 0)
                | (enabled("hide_rank") ? 4 : 0) | (enabled("empty_software") ? 8 : 0);
        Object state = XposedHelpers.getAdditionalInstanceField(original, VISIBLE_TABS);
        // Fragment, navigation and pager must share one visible structure for deep-link selection.
        if (state instanceof TabSnapshot saved && saved.mask == mask && saved.source == tabs) return saved.copy;
        List<?> kept = visibleTabs(tabs);
        if (kept == tabs) return original;
        int selected = (Integer) call(original, "getDefaultSelected");
        Object selectedTab = selected >= 0 && selected < tabs.size() ? tabs.get(selected) : null;
        Object copy = copyDto(original);
        call(copy, "setTabDtoList", kept);
        call(copy, "setDefaultSelected", Math.max(0, kept.indexOf(selectedTab)));
        XposedHelpers.setAdditionalInstanceField(original, VISIBLE_TABS, new TabSnapshot(mask, tabs, copy));
        return copy;
    }

    private static List<Object> historyOnly(List<?> cards) throws Throwable {
        ArrayList<Object> kept = new ArrayList<>();
        if (cards == null) return kept;
        for (Object card : cards) {
            int code = (Integer) call(card, "getCode");
            // ml2 registers precisely these four search-history card renderers.
            if (code == 161 || code == 154 || code == 40117 || code == 100204) kept.add(card);
        }
        return kept;
    }

    private static boolean mineEnabled() {
        return enabled("hide_mine_recommendations") || enabled("hide_mine_booking")
                || enabled("hide_mine_welfare_quick") || enabled("hide_mine_clean");
    }

    private static List<Object> filterMine(List<?> cards) throws Throwable {
        ArrayList<Object> kept = new ArrayList<>();
        for (Object card : cards) {
            int code = (Integer) call(card, "getCode");
            if (enabled("hide_mine_clean") && (code == 40080 || code == 40087)) continue;
            if (enabled("hide_mine_welfare_quick") && (code == 40149 || code == 40156)) continue;
            // App-management, security and uninstall cards have their own non-recommendation DTOs.
            if (enabled("hide_mine_recommendations") && isRecommendation(card)) continue;
            if (instance(card, DTO + "NavCardDto") || instance(card, DTO + "BannerCardDto")) {
                List<?> banners = list(call(card, "getBanners"));
                if (banners != null) {
                    ArrayList<Object> filtered = new ArrayList<>();
                    for (Object banner : banners) {
                        if (banner == null || !hideEntry((String) call(banner, "getActionParam"))) filtered.add(banner);
                    }
                    if (filtered.size() != banners.size()) {
                        if (filtered.isEmpty()) continue;
                        card = copyDto(card);
                        call(card, "setBanners", filtered);
                    }
                }
            } else if (code == 100101 && instance(card, DTO + "ToolsCardDto") && enabled("hide_mine_clean")) {
                List<?> tools = list(call(card, "getToolItems"));
                if (tools != null) {
                    ArrayList<Object> filtered = new ArrayList<>();
                    for (Object tool : tools) if (tool == null || !"2".equals(call(tool, "getType"))) filtered.add(tool);
                    if (filtered.size() != tools.size()) {
                        if (filtered.isEmpty()) continue;
                        card = copyDto(card);
                        call(card, "setToolItems", filtered);
                    }
                }
            }
            kept.add(card);
        }
        return kept;
    }

    private static boolean hideEntry(String action) {
        if (action == null) return false;
        Uri uri = Uri.parse(action);
        if (!"oap".equals(uri.getScheme()) || !"mk".equals(uri.getHost())) return false;
        String path = uri.getPath();
        if (path == null) return false;
        if (enabled("hide_mine_booking") && (path.equals("/booked") || path.equals("/new_booked")
                || path.equals("/booked/event"))) return true;
        if (enabled("hide_mine_clean") && path.equals("/tshcln")) return true;
        // CardMainUriHandler also routes generic card pages to the Quick App feed.
        if (enabled("hide_mine_welfare_quick") && (path.equals("/cardstyle") || path.equals("/cardstyle/recmd"))) {
            String page = uri.getQueryParameter("p");
            if (page != null) {
                String target = Uri.parse(page).getPath();
                if ("/page/instant".equals(target) || "page/instant".equals(target)) return true;
            }
        }
        return enabled("hide_mine_welfare_quick") && (path.equals("/welfare")
                || path.equals("/welfare/home") || path.equals("/welfare/stimulation")
                || path.equals("/vip/welfare") || path.equals("/card/instant") || path.equals("/contentflow/instant"));
    }

    private static boolean isRecommendation(Object card) {
        for (String name : new String[]{"AppCardDto", "AppListCardDto", "ResourceListCardDto", "DailyRecommendListCardDto"}) {
            if (instance(card, DTO + name)) return true;
        }
        return false;
    }

    private static boolean instance(Object object, String type) {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) if (c.getName().equals(type)) return true;
        return false;
    }

    // Copy the exact DTO class and inherited metadata; never copy Android LayoutParams or views.
    private static Object copyDto(Object original) throws Throwable {
        Object copy = original.getClass().getDeclaredConstructor().newInstance();
        for (Class<?> c = original.getClass(); c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(copy, field.get(original));
            }
        }
        return copy;
    }

    private static Object owner(Object callback, String type) throws Throwable {
        for (Field field : callback.getClass().getDeclaredFields()) {
            if (field.getType().getName().equals(type)) { field.setAccessible(true); return field.get(callback); }
        }
        throw new NoSuchFieldException(type);
    }

    static Object call(Object target, String method, Object... args) { return XposedHelpers.callMethod(target, method, args); }
    private static List<?> list(Object value) { return value instanceof List<?> l ? l : null; }
}
