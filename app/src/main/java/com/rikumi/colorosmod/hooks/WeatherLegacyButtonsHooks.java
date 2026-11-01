package com.rikumi.colorosmod.hooks;

import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Weather 17: keep native button instances and listeners, but restore their title-bar placement. */
final class WeatherLegacyButtonsHooks {
    private static final String STYLE = "main.home.quality.feel.HomeBottomButtonMaterialStyleHelper";
    private static final String ACTIVITY = "main.view.WeatherMainActivity";
    private static final WeakHashMap<Object, State> states = new WeakHashMap<>();
    private static boolean restoring;

    private WeatherLegacyButtonsHooks() {}

    private static boolean enabled() { return !restoring && WeatherHooks.enabled("legacy_buttons"); }

    static void install(ClassLoader cl) {
        if (XposedHelpers.findClassIfExists(WeatherHooks.BASE + STYLE, cl) == null) return;
        WeatherHooks.hook(cl, ACTIVITY, "onCreate", false, p -> update(binding(p.thisObject)));
        WeatherHooks.hook(cl, ACTIVITY, "onConfigurationChanged", false, p -> update(binding(p.thisObject)));
        WeatherHooks.hook(cl, ACTIVITY, "applyBottomLayoutWindowInset", false, p -> update(binding(p.thisObject)));
        WeatherHooks.hook(cl, ACTIVITY, "onDestroy", true, p -> restore(binding(p.thisObject), false));
        WeatherHooks.hook(cl, STYLE, "refresh", false, p -> update(p.args[1]));
        WeatherHooks.hook(cl, STYLE, "setBottomButtonsVisible", false, p -> update(p.args[0]));
        WeatherHooks.hook(cl, ACTIVITY, "refreshPrecipitationForecast", false, p -> update(binding(p.thisObject)));
        // This preparation posts callbacks which can show the glass background again.
        WeatherHooks.hook(cl, STYLE, "prepareTitleBarButtonsMaterialStyle", true, p -> {
            if (enabled()) p.setResult(null);
        });
        WeatherHooks.hook(cl, STYLE, "applyTargetMaterialStyle", true, p -> {
            if (!enabled() || !Boolean.TRUE.equals(XposedHelpers.callMethod(p.args[0], "getCircular"))) return;
            plainButton(p.args[0], cl);
            p.setResult(null);
        });
        WeatherHooks.hook(cl, STYLE, "applyBottomBarGradient", true, p -> {
            if (!enabled()) return;
            clearBottom((View) p.args[0]);
            p.setResult(null);
        });
        WeatherHooks.hook(cl, ACTIVITY, "applyBottomMaskGradientBlur", true, p -> {
            if (!enabled()) return;
            clearBottom(view(binding(p.thisObject), "llBottomLayout"));
            p.setResult(null);
        });
    }

    private static void plainButton(Object target, ClassLoader cl) {
        Object applier = singleton(cl, "main.home.quality.feel.HomeButtonDefaultStyleApplier");
        // Native cleanup removes material delegates, blur, outer shadows and press followers.
        XposedHelpers.callMethod(applier, "clearPreviousStyleEffects", target);
        XposedHelpers.callMethod(applier, "prepareTransparentContent", target);
        for (String getter : new String[]{"getBlurLayer", "getGlassBg"}) {
            View layer = (View) XposedHelpers.callMethod(target, getter);
            layer.setBackground(null);
            layer.setVisibility(View.GONE);
        }
        ((View) XposedHelpers.callMethod(target, "getContainer")).setBackground(null);
    }

    private static void clearBottom(View bottom) {
        if (bottom == null) return;
        XposedHelpers.callStaticMethod(XposedHelpers.findClass(WeatherHooks.BASE + "main.utils.BackgroundBlurUtils",
                bottom.getContext().getClassLoader()), "clearBackgroundBlur", bottom);
        bottom.setBackground(null);
    }

    private static Object singleton(ClassLoader cl, String name) {
        return XposedHelpers.getStaticObjectField(XposedHelpers.findClass(WeatherHooks.BASE + name, cl), "INSTANCE");
    }

    private static Object binding(Object activity) { return XposedHelpers.callMethod(activity, "getBinding"); }
    private static View view(Object binding, String field) { return (View) XposedHelpers.getObjectField(binding, field); }

    private static void update(Object binding) {
        if (binding == null) return;
        hideUnsupportedButtons(binding);
        if (!enabled()) {
            restore(binding, true);
            return;
        }
        State state = states.get(binding);
        if (state == null) {
            Object title = XposedHelpers.getObjectField(binding, "includeTitleBar");
            View map = view(binding, "precipitationCloudMapContainer"), city = view(binding, "cityManagerContainer");
            View bottom = view(binding, "llBottomLayout");
            View toolbar = view(title, "linMore");
            if (!(toolbar instanceof LinearLayout) || map.getParent() != bottom || city.getParent() != bottom) return;
            state = new State(binding, (LinearLayout) toolbar, view(title, "moreFrame"), map, city);
            states.put(binding, state);
        }
        state.apply();
    }

    private static void hideUnsupportedButtons(Object binding) {
        if (!WeatherHooks.usingOverseasApi()) return;
        view(binding, "precipitationCloudMapContainer").setVisibility(View.GONE);
        Object title = XposedHelpers.getObjectField(binding, "includeTitleBar");
        view(title, "playVoiceContainer").setVisibility(View.GONE);
    }

    private static void restore(Object binding, boolean refresh) {
        State state = states.remove(binding);
        if (state == null) return;
        state.map.restore();
        state.city.restore();
        for (HeaderSpacing spacing : state.headerSpacing) spacing.restore();
        ViewGroup.MarginLayoutParams toolbarParams = (ViewGroup.MarginLayoutParams) state.toolbar.getLayoutParams();
        toolbarParams.setMarginEnd(state.toolbarMarginEnd);
        state.toolbar.setLayoutParams(toolbarParams);
        state.center.setLayoutParams(state.centerParams);
        state.bottom.setBackground(state.bottomBackground);
        if (refresh) {
            boolean previous = restoring;
            restoring = true;
            try {
                Object activity = state.bottom.getContext();
                ClassLoader cl = state.bottom.getContext().getClassLoader();
                XposedHelpers.callMethod(singleton(cl, STYLE), "refresh", activity, binding);
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(activity, "getIsSupportBlur")))
                    XposedHelpers.callMethod(activity, "applyBottomMaskGradientBlur");
            } finally { restoring = previous; }
        }
    }

    static void cleanupForHotReload() {
        for (Object binding : new ArrayList<>(states.keySet())) restore(binding, true);
        states.clear();
    }

    private static final class State {
        final LinearLayout toolbar;
        final View template, bottom, center;
        final Drawable bottomBackground;
        final ViewGroup.LayoutParams centerParams;
        final Move map, city;
        final ArrayList<HeaderSpacing> headerSpacing = new ArrayList<>();
        final int toolbarMarginEnd;

        State(Object binding, LinearLayout toolbar, View template, View map, View city) {
            this.toolbar = toolbar;
            toolbarMarginEnd = ((ViewGroup.MarginLayoutParams) toolbar.getLayoutParams()).getMarginEnd();
            for (int i = 0; i < toolbar.getChildCount(); i++)
                headerSpacing.add(new HeaderSpacing(toolbar.getChildAt(i)));
            this.template = template;
            this.map = new Move(map);
            this.city = new Move(city);
            bottom = view(binding, "llBottomLayout");
            bottomBackground = bottom.getBackground();
            center = view(binding, "centerView");
            ViewGroup.LayoutParams params = center.getLayoutParams();
            centerParams = (ViewGroup.LayoutParams) XposedHelpers.newInstance(params.getClass(), params);
        }

        void apply() {
            map.place(toolbar, 0, template);
            city.place(toolbar, 1, template);
            int width = Math.round(40 * toolbar.getResources().getDisplayMetrics().density);
            for (int i = 0; i < toolbar.getChildCount(); i++) {
                View child = toolbar.getChildAt(i);
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
                params.width = width;
                params.setMargins(0, 0, 0, 0);
                params.setMarginStart(0);
                params.setMarginEnd(0);
                child.setLayoutParams(params);
            }
            ViewGroup.MarginLayoutParams toolbarParams = (ViewGroup.MarginLayoutParams) toolbar.getLayoutParams();
            toolbarParams.setMarginEnd(Math.round(8 * toolbar.getResources().getDisplayMetrics().density));
            toolbar.setLayoutParams(toolbarParams);
            // The city indicator used to be anchored between the two bottom buttons.
            Object params = center.getLayoutParams();
            XposedHelpers.setIntField(params, "startToEnd", -1);
            XposedHelpers.setIntField(params, "endToStart", -1);
            XposedHelpers.setIntField(params, "startToStart", 0);
            XposedHelpers.setIntField(params, "endToEnd", 0);
            center.setLayoutParams((ViewGroup.LayoutParams) params);
            clearBottom(bottom);
        }
    }

    private static final class HeaderSpacing {
        final View view;
        final LinearLayout.LayoutParams original;

        HeaderSpacing(View view) {
            this.view = view;
            original = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) view.getLayoutParams());
        }

        void restore() { view.setLayoutParams(original); }
    }

    private static final class Move {
        final View view;
        final ViewGroup parent;
        final ViewGroup.LayoutParams original;
        final int index;

        Move(View view) {
            this.view = view;
            parent = (ViewGroup) view.getParent();
            original = view.getLayoutParams();
            index = parent.indexOfChild(view);
        }

        void place(LinearLayout toolbar, int index, View template) {
            ViewGroup.LayoutParams size = template.getLayoutParams();
            if (view.getParent() != toolbar || toolbar.indexOfChild(view) != index) {
                ((ViewGroup) view.getParent()).removeView(view);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size.width, size.height);
                params.gravity = Gravity.CENTER_VERTICAL;
                toolbar.addView(view, index, params);
            } else {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
                params.width = size.width;
                params.height = size.height;
                params.setMargins(0, 0, 0, 0);
                params.setMarginStart(0);
                params.setMarginEnd(0);
                view.setLayoutParams(params);
            }
        }

        void restore() {
            ((ViewGroup) view.getParent()).removeView(view);
            parent.addView(view, Math.min(index, parent.getChildCount()), original);
        }
    }
}
