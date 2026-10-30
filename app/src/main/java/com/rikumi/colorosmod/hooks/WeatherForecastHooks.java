package com.rikumi.colorosmod.hooks;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.List;

/** The horizontal seven-day layout from android-app-mods, using native day bindings. */
final class WeatherForecastHooks {
    private static final String CARD = "colorosmod.weather.horizontalCard";
    private static final String DAY = "colorosmod.weather.horizontalDay";
    private static final String ITEM = "main.view.itemview.FutureDayWeatherItem";

    static void install(ClassLoader cl) {
        Class<?> forecast = XposedHelpers.findClassIfExists(WeatherHooks.BASE + ITEM, cl);
        Class<?> bindingType = XposedHelpers.findClassIfExists(
                WeatherHooks.BASE + "databinding.ItemFutureDayWeatherBinding", cl);
        if (forecast == null || bindingType == null) return;
        // Do not change the native column property. Only our horizontal presentation spans a row.
        WeatherHooks.hook(cl, "main.recycler.GridBindingItem", "getItemSpanSize", true, p -> {
            if (enabled() && forecast.isInstance(p.thisObject)) p.setResult(p.args[0]);
        });
        // The native tablet grid reserves the other 2/4 columns beside its vertical
        // forecast for an EmbedBindingItem. A full-width forecast has no such side area.
        // Returning zero prevents its creation; its weather cards stay in the main grid.
        WeatherHooks.hook(cl, "main.recycler.BindingItemHelper", "getEmbedSpanSize", true, p -> {
            if (enabled()) p.setResult(0);
        });
        WeatherHooks.hook(cl, ITEM, "onBindViewHolder", true, p -> {
            Object binding = p.args[0];
            if (!bindingType.isInstance(binding)) return;
            ViewGroup root = (ViewGroup) XposedHelpers.callMethod(binding, "getRoot");
            Card old = (Card) XposedHelpers.getAdditionalInstanceField(root, CARD);
            if (!enabled()) {
                if (old != null) old.restore();
                return;
            }
            List<?> days = (List<?>) XposedHelpers.callMethod(p.thisObject, "getChildList");
            if (days.isEmpty()) {
                if (old != null) old.restore();
                return;
            }
            // Build off-tree first: incompatible bindings leave the original card usable.
            LinearLayout row = createRow(root.getContext(), cl, days);
            Class<?> adapter = XposedHelpers.findClass(WeatherHooks.BASE + "bindingAdapter.ViewAdapter", cl);
            XposedHelpers.callStaticMethod(adapter, "setCardBackgroundColor", root, 1, p.thisObject, false);
            if (old != null) old.restore();
            Card card = new Card(root, row);
            XposedHelpers.setAdditionalInstanceField(root, CARD, card);
            card.apply();
            p.setResult(null);
        });
        // The custom row owns no pager or fragment observer; native lifecycle expects those
        // to have been initialized by the native onBindViewHolder implementation.
        for (String method : new String[]{"onViewAttachedToWindow", "onViewDetachedFromWindow"}) {
            WeatherHooks.hook(cl, ITEM, method, true, p -> {
                View root = (View) XposedHelpers.callMethod(p.args[0], "getRoot");
                if (XposedHelpers.getAdditionalInstanceField(root, CARD) != null) p.setResult(null);
            });
        }
        WeatherHooks.hook(cl, "databinding.ItemFutureDayWeatherBindingImpl", "executeBindings", false, p -> {
            View root = (View) XposedHelpers.callMethod(p.thisObject, "getRoot");
            Card card = (Card) XposedHelpers.getAdditionalInstanceField(root, CARD);
            if (card != null) card.apply();
        });
        WeatherHooks.hook(cl, "databinding.ItemFutureDayBindingImpl", "executeBindings", false, p -> {
            View root = (View) XposedHelpers.callMethod(p.thisObject, "getRoot");
            Day day = (Day) XposedHelpers.getAdditionalInstanceField(root, DAY);
            if (day != null) day.style();
        });
    }

    private static boolean enabled() { return WeatherHooks.enabled("horizontal_forecast"); }
    private static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }

    private static LinearLayout createRow(Context context, ClassLoader cl, List<?> days) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Object adapter = XposedHelpers.newInstance(XposedHelpers.findClass(
                WeatherHooks.BASE + "main.recycler.MainChildItemBindingAdapter", cl));
        XposedHelpers.callMethod(adapter, "setChildItemType", 2);
        XposedHelpers.callMethod(adapter, "setData", days);
        int layout = context.getResources().getIdentifier("item_future_day", "layout", context.getPackageName());
        for (int i = 0; i < Math.min(7, days.size()); i++) {
            Object holder = XposedHelpers.callMethod(adapter, "onCreateViewHolder",
                    new Class<?>[]{ViewGroup.class, int.class}, row, layout);
            // RecyclerView also declares onBindViewHolder(ViewHolder, int). Our resolver
            // intentionally rejects ambiguous best matches, so select the concrete overload.
            XposedHelpers.callMethod(adapter, "onBindViewHolder",
                    new Class<?>[]{holder.getClass(), int.class}, holder, i);
            Object binding = XposedHelpers.callMethod(holder, "getBinding");
            FrameLayout root = (FrameLayout) XposedHelpers.callMethod(binding, "getRoot");
            Day day = new Day(root);
            XposedHelpers.setAdditionalInstanceField(root, DAY, day);
            day.style();
            row.addView(root, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        return row;
    }

    private static final class Card {
        final ViewGroup root;
        final LinearLayout row;
        final List<View> original = new ArrayList<>();
        final List<Integer> visibility = new ArrayList<>();
        final int height;

        Card(ViewGroup root, LinearLayout row) {
            this.root = root;
            this.row = row;
            height = root.getLayoutParams().height;
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                original.add(child);
                visibility.add(child.getVisibility());
            }
            root.addView(row, new ViewGroup.LayoutParams(-1, -1));
        }

        void apply() {
            for (View child : original) child.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = root.getLayoutParams();
            int wanted = dp(root.getContext(), 165);
            if (lp.height != wanted) { lp.height = wanted; root.setLayoutParams(lp); }
        }

        void restore() {
            root.removeView(row);
            for (int i = 0; i < original.size(); i++) original.get(i).setVisibility(visibility.get(i));
            ViewGroup.LayoutParams lp = root.getLayoutParams();
            lp.height = height;
            root.setLayoutParams(lp);
            XposedHelpers.removeAdditionalInstanceField(root, CARD);
        }
    }

    private static final class Day {
        final TextView week, date, temperature, rainfall;
        final View icon;

        Day(FrameLayout root) {
            week = find(root, "text_view_week");
            date = find(root, "text_view_date");
            temperature = find(root, "text_view_temperature");
            rainfall = find(root, "text_view_rainfall");
            View weather = find(root, "lin_weather");
            weather.setMinimumWidth(dp(root.getContext(), 28));
            icon = find(root, "image_view_weather_type_icon");
            LinearLayout column = new LinearLayout(root.getContext());
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER);
            for (int i = 0; i < root.getChildCount(); i++) root.getChildAt(i).setVisibility(View.GONE);
            add(column, week, 0);
            add(column, date, 4);
            add(column, weather, 8);
            add(column, temperature, 4);
            root.addView(column, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        }

        void style() {
            week.setTextSize(13);
            week.setTypeface(week.getTypeface(), Typeface.BOLD);
            date.setTextSize(9);
            date.setText(date.getText().toString());
            temperature.setTextSize(15);
            temperature.setMaxLines(3);
            temperature.setSingleLine(false);
            temperature.setLineSpacing(dp(temperature.getContext(), 2), 1);
            String text = temperature.getText().toString();
            if (!text.contains("\n")) {
                String[] range = text.split("\\s*/\\s*");
                if (range.length == 2) text = range[1] + "\n/ \n" + range[0];
            }
            temperature.setText(text);
            rainfall.setTextSize(10);
            ViewGroup.MarginLayoutParams rainParams = (ViewGroup.MarginLayoutParams) rainfall.getLayoutParams();
            rainParams.topMargin = dp(rainfall.getContext(), 2);
            rainfall.setLayoutParams(rainParams);
            ViewGroup.LayoutParams lp = icon.getLayoutParams();
            lp.width = lp.height = dp(icon.getContext(), 28);
            icon.setLayoutParams(lp);
            for (TextView view : new TextView[]{week, date, temperature, rainfall}) view.setGravity(Gravity.CENTER);
            week.setSingleLine(true);
            date.setSingleLine(true);
        }

        private static void add(LinearLayout column, View child, int margin) {
            ((ViewGroup) child.getParent()).removeView(child);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(column.getContext(), margin);
            column.addView(child, lp);
        }

        @SuppressWarnings("unchecked")
        private static <T extends View> T find(View root, String name) {
            int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
            View view = root.findViewById(id);
            if (view == null) throw new IllegalStateException("Missing forecast view: " + name);
            return (T) view;
        }
    }
}
