package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.content.Context;
import android.content.res.ColorStateList;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.util.TypedValue;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyCallback;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.lang.reflect.Field;

/** ColorOS 16 mobile pipeline；复用 Flyme 的实时 Drawable 双排绘制。 */
public final class SignalHooks {
    private static final String MOBILE = "com.oplus.systemui.statusbar.phone.signal.widget.OplusModernStatusBarMobileView";
    private static final String MODERN = "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView";
    private static final String CONTAINER = "com.android.systemui.statusbar.phone.StatusIconContainer";
    // 以下视图状态只在主线程访问；onViewRemoved 释放含有宿主视图引用的 Mobile。
    private static final WeakHashMap<View, Mobile> mobiles = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, View.OnAttachStateChangeListener> containers = new WeakHashMap<>();
    private static final ThreadLocal<ViewGroup> layoutContainer = new ThreadLocal<>();
    private static ConnectivityManager connectivity;
    private static ConnectivityManager.NetworkCallback networkCallback;
    private static TelephonyManager telephony;
    private static TelephonyCallback dataCallback;
    private static boolean wifiDefault, changingTypeVisibility;

    public static void hookDualSignal(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            Class<?> container = XposedHelpers.findClass(CONTAINER, pkg.classLoader);
            Class<?> modern = XposedHelpers.findClass(MODERN, pkg.classLoader);
            XposedHelpers.findClass(MOBILE, pkg.classLoader);
            XposedHelpers.findAndHookMethod(container, "onMeasure", int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            ViewGroup root = (ViewGroup) p.thisObject;
                            prepare(root);
                            // 被合并的根视图仍须正常测量，保留信号图的 bounds 和 image matrix。
                            for (int i = 0; i < root.getChildCount(); i++) {
                                View child = root.getChildAt(i);
                                Mobile mobile = mobiles.get(child);
                                if (mobile != null && mobile.primary != null) {
                                    XposedHelpers.callMethod(root, "measureChild",
                                            new Class<?>[]{View.class, int.class, int.class}, child,
                                            View.MeasureSpec.makeMeasureSpec(
                                                    View.MeasureSpec.getSize((Integer) p.args[0]),
                                                    View.MeasureSpec.UNSPECIFIED), p.args[1]);
                                }
                            }
                            enterLayout(p, root);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) { leaveLayout(p); }
                    });
            XposedHelpers.findAndHookMethod(container, "updateStates", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    prepare(root);
                    enterLayout(p, root);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { leaveLayout(p); }
            });
            // ColorOS 直接累加子视图宽度，没有 Flyme 的 getViewTotalWidth/MeasuredWidth。
            // 仅在容器的测量及排布期间排除第二张卡，外部仍读取原生订阅可见性。
            XposedHelpers.findAndHookMethod(modern, "isIconVisible", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Mobile mobile = mobiles.get(p.thisObject);
                    if (mobile != null && mobile.primary != null
                            && layoutContainer.get() == mobile.root.getParent()) p.setResult(false);
                }
            });
            XposedHelpers.findAndHookMethod(modern, "setVisibleState", int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Mobile mobile = mobiles.get(p.thisObject);
                            if (mobile != null && mobile.primary != null
                                    && layoutContainer.get() == mobile.root.getParent()) {
                                // 保持下排 Drawable 的原生绑定、尺寸及更新；仅将内容设为透明。
                                p.args[0] = 0;
                                p.args[1] = false;
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(container, "onViewRemoved", View.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Mobile removed = mobiles.remove(p.args[0]);
                    if (removed != null) restoreMobile(removed);
                    for (Mobile mobile : mobiles.values()) {
                        if (mobile.primary == removed || mobile.second == removed) mobile.reset();
                    }
                }
            });
            XposedHelpers.findAndHookMethod(ImageView.class, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Mobile mobile = findSignal(p.thisObject);
                    if (mobile == null || mobile.second == null) return;
                    Canvas canvas = (Canvas) p.args[0];
                    // 分别保存和恢复两排的画布状态，保证上排裁剪不影响下排。
                    drawUpperSignal(canvas, mobile.signal);
                    drawLowerSignal(canvas, mobile.signal, mobile.second.signal);
                    p.setResult(null);
                }
            });
            Class<?> mobileView = XposedHelpers.findClass(MOBILE, pkg.classLoader);
            XposedHelpers.findAndHookMethod(mobileView, "dispatchDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Mobile mobile = mobiles.get(p.thisObject);
                    if (!p.hasThrowable() && mobile != null && mobile.second != null
                            && mobile.group.getVisibility() == View.VISIBLE)
                        drawLowerActivity((Canvas) p.args[0], mobile);
                }
            });
            XposedHelpers.findAndHookMethod(ImageView.class, "invalidateDrawable", Drawable.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            invalidatePrimary(p.thisObject);
                            invalidateMergedActivity(p.thisObject);
                        }
                    });
            // ColorOS 信号资源和颜色均由这两个 Binder 方法更新。
            Class<?> binder = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.pipeline.mobile.ui.view.OplusStatusBarMobileViewBinder", pkg.classLoader);
            XposedHelpers.findAndHookMethod(binder, "bindCustEx",
                    XposedHelpers.findClass("com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel", pkg.classLoader),
                    ViewGroup.class, XposedHelpers.findClass("kotlinx.coroutines.flow.Flow", pkg.classLoader),
                    XposedHelpers.findClass("kotlinx.coroutines.flow.StateFlow", pkg.classLoader),
                    XposedHelpers.findClass("com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger", pkg.classLoader),
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) { initialize((View) p.args[1]); }
                    });
            XposedHelpers.findAndHookMethod(ImageView.class, "setImageResource", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    invalidateMergedActivity(p.thisObject);
                    for (Mobile mobile : mobiles.values()) if (mobile.type == p.thisObject) {
                        mobile.rat = ratName(mobile.root.getContext(), (Integer) p.args[0]);
                        requestUpdate();
                        break;
                    }
                }
            });
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    invalidateMergedActivity(p.thisObject);
                    if (!changingTypeVisibility) for (Mobile mobile : mobiles.values())
                        if (mobile.roaming == p.thisObject || mobile.roamingSpace == p.thisObject) {
                            requestUpdate();
                            break;
                        }
                }
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (changingTypeVisibility) return;
                    for (Mobile mobile : mobiles.values()) {
                        if (mobile.roaming == p.thisObject || mobile.roamingSpace == p.thisObject) {
                            if (mobile.roaming == p.thisObject) mobile.roamingVisibility = (Integer) p.args[0];
                            else mobile.roamingSpaceVisibility = (Integer) p.args[0];
                            if (readBoolCached(KEY_HIDE_ROAMING_ICON, false)
                                    || mobile.primary != null || mobile.second != null) p.args[0] = View.GONE;
                            return;
                        }
                    }
                    for (Mobile mobile : mobiles.values()) if (mobile.type == p.thisObject) {
                        mobile.typeVisibility = (Integer) p.args[0];
                        if (networkTypeMode(true) != 0) p.args[0] = View.GONE;
                        break;
                    }
                }
            });
            XposedHelpers.findAndHookMethod(container, "onLayout", boolean.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            ViewGroup root = (ViewGroup) p.thisObject;
                            for (int i = 0; i < root.getChildCount(); i++) {
                                Mobile mobile = mobiles.get(root.getChildAt(i));
                                if (mobile != null) alignNetworkLabel(mobile);
                            }
                        }
                    });
            Class<?> model = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel", pkg.classLoader);
            XposedHelpers.findAndHookMethod(binder, "bindCustEx$updateSignalIcon", ImageView.class, model,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            invalidatePrimary(p.args[0]);
                            if (findSignal(p.args[0]) != null) requestUpdate();
                        }
                    });
            XposedHelpers.findAndHookMethod(binder, "bindCustEx$updateTint", ImageView.class, ImageView.class,
                    ImageView.class, ImageView.class, ImageView.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) { invalidatePrimary(p.args[3]); }
                    });
            log("HOOK OK StatusIconContainer / OplusStatusBarMobileViewBinder (dual signal)");
        } catch (Throwable t) {
            log("HOOK FAIL dual signal: " + t);
        }
    }

    private static void enterLayout(XC_MethodHook.MethodHookParam p, ViewGroup root) {
        p.setObjectExtra("previousSignalContainer", layoutContainer.get());
        p.setObjectExtra("signalLayoutEntered", true);
        layoutContainer.set(root);
    }

    private static void leaveLayout(XC_MethodHook.MethodHookParam p) {
        if (!Boolean.TRUE.equals(p.getObjectExtra("signalLayoutEntered"))) return;
        ViewGroup previous = (ViewGroup) p.getObjectExtra("previousSignalContainer");
        if (previous == null) layoutContainer.remove();
        else layoutContainer.set(previous);
    }

    private static void prepare(ViewGroup container) {
        if (!containers.containsKey(container)) {
            View.OnAttachStateChangeListener listener = new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { view.requestLayout(); }
                @Override public void onViewDetachedFromWindow(View view) {
                    ViewGroup root = (ViewGroup) view;
                    for (int i = 0; i < root.getChildCount(); i++) {
                        Mobile mobile = mobiles.remove(root.getChildAt(i));
                        if (mobile != null) restoreMobile(mobile);
                    }
                }
            };
            containers.put(container, listener);
            container.addOnAttachStateChangeListener(listener);
        }
        boolean enabled = readBool(KEY_MERGE_DUAL_SIGNAL, false);
        boolean hideRoaming = readBool(KEY_HIDE_ROAMING_ICON, false);
        int networkMode = networkTypeMode(false);
        boolean network = networkMode == 1;
        if (network) observeNetwork(container.getContext());
        List<Mobile> visible = new ArrayList<>();
        List<?> ignored = (List<?>) XposedHelpers.getObjectField(container, "mIgnoredSlots");
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (!MOBILE.equals(child.getClass().getName())) continue;
            Mobile mobile = mobiles.get(child);
            if (mobile == null) mobile = initialize(child);
            if (mobile == null) continue;
            mobile.reset();
            mobile.setSignalEndSpacing(true);
            if (network) refreshRat(mobile);
            if ((enabled || network) && child.getVisibility() == View.VISIBLE
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(child, "isIconVisible"))
                    && !Boolean.TRUE.equals(XposedHelpers.callMethod(child, "isIconBlocked"))
                    && !ignored.contains(XposedHelpers.callMethod(child, "getSlot"))) visible.add(mobile);
        }
        if (enabled && visible.size() == 2) {
            Mobile first = visible.get(0), second = visible.get(1);
            first.second = second;
            second.primary = first;
            second.group.setAlpha(0f);
            int comboWidth = Math.max(first.requiredComboWidth(), second.requiredComboWidth());
            first.setComboWidth(comboWidth);
            second.setComboWidth(comboWidth);
        }
        for (int i = 0; i < container.getChildCount(); i++) {
            Mobile mobile = mobiles.get(container.getChildAt(i));
            if (mobile == null) continue;
            boolean merged = mobile.primary != null || mobile.second != null;
            if (!merged) mobile.restoreComboWidth();
            setRoamingVisibility(mobile, hideRoaming || merged);
            updateRoamingLabel(mobile, hideRoaming);
        }
        Mobile data = null;
        int active = activeSubscription();
        for (Mobile mobile : visible) if ((Integer) XposedHelpers.callMethod(mobile.root, "getSubId") == active) data = mobile;
        for (int i = 0; i < container.getChildCount(); i++) {
            Mobile mobile = mobiles.get(container.getChildAt(i));
            if (mobile == null || mobile.type == null) continue;
            if (network && mobile.group instanceof LinearLayout group) {
                if (mobile.label == null) {
                    mobile.label = new StatusBarFontHooks.StatusBarTextView(mobile.root.getContext());
                    mobile.label.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                    int size = mobile.root.getResources().getIdentifier("stat_clock_size", "dimen", "com.android.systemui");
                    mobile.label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.9f * (size != 0
                            ? mobile.root.getResources().getDimension(size) : 13 * density(mobile.root)));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    // 放在其它状态图标之后、信号组合之前，左侧间距参与实际测量。
                    params.setMarginStart(Math.round(density(mobile.root)));
                    View combo = mobile.root.findViewById(id(mobile.root, "mobile_combo_real"));
                    int position = combo == null ? group.getChildCount() : group.indexOfChild(combo);
                    group.addView(mobile.label, position, params);
                    StatusBarFontHooks.applyStatusBarFont(mobile.label);
                }
                StatusBarFontHooks.applyStatusBarFont(mobile.label);
                Mobile source = mobile.second != null ? data : mobile == data ? mobile : null;
                mobile.dataOnUpper = mobile.second != null && source == mobile;
                LinearLayout.LayoutParams labelParams = (LinearLayout.LayoutParams) mobile.label.getLayoutParams();
                int startMargin = Math.round(density(mobile.root));
                if (labelParams.getMarginStart() != startMargin) {
                    labelParams.setMarginStart(startMargin);
                    mobile.label.setLayoutParams(labelParams);
                }
                String text = source == null || wifiDefault || mobile.primary != null ? "" : source.rat;
                if (!android.text.TextUtils.equals(text, mobile.label.getText())) mobile.label.setText(text);
                mobile.label.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
                updateTint(mobile);
                setTypeVisibility(mobile, View.GONE);
            } else {
                if (mobile.label != null) {
                    ((ViewGroup) mobile.group).removeView(mobile.label);
                    mobile.label = null;
                }
                setTypeVisibility(mobile, networkMode == 2 ? View.GONE : mobile.typeVisibility);
            }
            if (mobile.inout != null) {
                mobile.inout.setTranslationX(mobile.inoutTranslationX
                        + (networkMode != 0 ? 3 * density(mobile.root) : 0));
            }
        }
    }

    private static String roamingName(Mobile mobile, int fallback) {
        if (mobile.roamingVisibility != View.VISIBLE) return "";
        int slot = SubscriptionManager.getSlotIndex((Integer) XposedHelpers.callMethod(mobile.root, "getSubId"));
        return "R" + (slot >= 0 ? slot + 1 : fallback);
    }

    private static void updateRoamingLabel(Mobile mobile, boolean hide) {
        String text = "";
        if (!hide && mobile.second != null) {
            String first = roamingName(mobile, 1), second = roamingName(mobile.second, 2);
            text = first.isEmpty() ? second : second.isEmpty() ? first
                    : first.compareTo(second) <= 0 ? first + "\n" + second : second + "\n" + first;
        }
        if (mobile.second == null) {
            if (mobile.roamingLabel != null) {
                ((ViewGroup) mobile.group).removeView(mobile.roamingLabel);
                mobile.roamingLabel = null;
            }
            return;
        }
        if (mobile.roamingLabel == null && !text.isEmpty() && mobile.group instanceof LinearLayout group) {
            mobile.roamingLabel = new StatusBarFontHooks.StatusBarTextView(mobile.root.getContext());
            mobile.roamingLabel.setGravity(Gravity.CENTER_VERTICAL);
            mobile.roamingLabel.setIncludeFontPadding(false);
            mobile.roamingLabel.setMaxLines(2);
            mobile.roamingLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            params.setMarginEnd(Math.round(2 * density(mobile.root)));
            View next = mobile.label == null ? mobile.combo : mobile.label;
            int index = next == null ? group.getChildCount() : group.indexOfChild(next);
            group.addView(mobile.roamingLabel, index, params);
        }
        if (mobile.roamingLabel != null) {
            mobile.roamingLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, text.contains("\n") ? 9 : 10);
            if (!android.text.TextUtils.equals(text, mobile.roamingLabel.getText())) mobile.roamingLabel.setText(text);
            mobile.roamingLabel.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            StatusBarFontHooks.applyStatusBarFont(mobile.roamingLabel);
            updateTint(mobile);
        }
    }

    private static void setRoamingVisibility(Mobile mobile, boolean hide) {
        boolean previous = changingTypeVisibility;
        changingTypeVisibility = true;
        try {
            if (mobile.roaming != null) mobile.roaming.setVisibility(hide ? View.GONE : mobile.roamingVisibility);
            if (mobile.roamingSpace != null) mobile.roamingSpace.setVisibility(hide ? View.GONE : mobile.roamingSpaceVisibility);
        } finally { changingTypeVisibility = previous; }
    }

    private static float drawableLeft(ImageView image) {
        Drawable drawable = image.getDrawable();
        RectF bounds = drawable == null ? new RectF() : new RectF(drawable.getBounds());
        image.getImageMatrix().mapRect(bounds);
        return image.getPaddingLeft() + bounds.left;
    }

    private static float signalLeft(Mobile mobile) {
        int[] location = new int[2];
        mobile.signal.getLocationInWindow(location);
        float left = drawableLeft(mobile.signal);
        if (mobile.second != null) {
            ImageView lower = mobile.second.signal;
            if (lower.getDrawable() != null && !lower.getDrawable().getBounds().isEmpty()
                    && lower.getWidth() > 0 && mobile.signal.getWidth() > 0) {
                // 下排绘制在上排 ImageView 内，按 drawLowerSignal 的水平缩放换算。
                float lowerLeft = drawableLeft(lower) * mobile.signal.getWidth() / lower.getWidth();
                left = Math.min(left, lowerLeft);
            }
        }
        return location[0] + left;
    }

    private static void alignNetworkLabel(Mobile mobile) {
        if (mobile.label == null || mobile.label.getVisibility() != View.VISIBLE || mobile.label.getLayout() == null) return;
        // 合并时卡 1 制式相对上一版向左移动 1dp，两张上网卡均保留 5dp 间距。
        float gap = (mobile.second != null ? 5 : 2) * density(mobile.root);
        int[] location = new int[2]; mobile.label.getLocationInWindow(location);
        float textRight = location[0] + mobile.label.getPaddingLeft() + mobile.label.getLayout().getLineRight(0);
        // 以上下两排信号的整体左缘为基准，并应用上网卡位置补偿。
        mobile.label.setTranslationX(mobile.label.getTranslationX() + signalLeft(mobile) - gap - textRight);
    }

    private static int networkTypeMode(boolean cached) {
        int legacy = (cached ? readBoolCached(KEY_SEPARATE_NETWORK_TYPE, false)
                : readBool(KEY_SEPARATE_NETWORK_TYPE, false)) ? 1 : 0;
        int mode = cached ? readIntCached(KEY_SIGNAL_NETWORK_TYPE_MODE, legacy)
                : readInt(KEY_SIGNAL_NETWORK_TYPE_MODE, legacy);
        return mode >= 0 && mode <= 2 ? mode : 0;
    }

    private static Mobile initialize(View child) {
        Mobile mobile = mobiles.get(child);
        if (mobile != null) return mobile;
        if (!MOBILE.equals(child.getClass().getName())) return null;
        ImageView signal = child.findViewById(id(child, "mobile_signal"));
        View group = child.findViewById(id(child, "mobile_group"));
        if (signal == null || group == null) return null;
        mobile = new Mobile(child, signal, group);
        mobiles.put(child, mobile);
        return mobile;
    }

    private static float density(View view) { return view.getResources().getDisplayMetrics().density; }

    private static void refreshRat(Mobile mobile) {
        // 原生 ModernStatusBarViewBinding 持有 Oplus binding，后者捕获对应订阅的 VM。
        // 按源码中捕获字段的类型查找，不依赖编译器生成的字段名；支持已有视图 hot reload。
        try {
            Object binding = XposedHelpers.getObjectField(mobile.root, "binding");
            Object oplus = captured(binding,
                    "com.android.systemui.statusbar.pipeline.OplusMobileSignalEx$OplusMobileViewBinding");
            Object model = captured(oplus,
                    "com.oplus.systemui.statusbar.pipeline.mobile.ui.viewmodel.OplusMobileIconViewModel");
            if (model == null) return;
            Object flow = XposedHelpers.callMethod(model, "getNetworkTypeIcon");
            Object icon = XposedHelpers.callMethod(flow, "getValue");
            mobile.rat = icon == null ? "" : ratName(mobile.root.getContext(),
                    (Integer) XposedHelpers.callMethod(icon, "getRes"));
        } catch (ReflectiveOperationException ignored) { }
    }

    private static Object captured(Object owner, String type) throws IllegalAccessException {
        if (owner == null) return null;
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (field.getType().getName().equals(type)) {
                field.setAccessible(true);
                return field.get(owner);
            }
        }
        return null;
    }

    private static void setTypeVisibility(Mobile mobile, int visibility) {
        changingTypeVisibility = true;
        try { mobile.type.setVisibility(visibility); }
        finally { changingTypeVisibility = false; }
    }

    private static void updateTint(Mobile mobile) {
        ColorStateList tint = mobile.signal.getImageTintList();
        if (tint != null) {
            if (mobile.label != null) mobile.label.setTextColor(tint);
            if (mobile.roamingLabel != null) mobile.roamingLabel.setTextColor(tint);
        }
    }

    private static String ratName(Context context, int resource) {
        if (resource == 0) return "";
        String name = context.getResources().getResourceEntryName(resource).toLowerCase(java.util.Locale.ROOT);
        if (name.contains("5g")) return name.contains("plus") ? "5G+" : "5G";
        if (name.contains("4g") || name.contains("lte")) return name.contains("plus") ? "4G+" : "4G";
        if (name.contains("3g")) return "3G";
        if (name.contains("h_plus")) return "H+";
        if (name.contains("_h")) return "H";
        if (name.contains("_1x")) return "1X";
        if (name.contains("_e")) return "E";
        if (name.contains("_g")) return "G";
        return "";
    }

    private static int activeSubscription() {
        try {
            int active = (Integer) SubscriptionManager.class.getMethod("getActiveDataSubscriptionId").invoke(null);
            if (SubscriptionManager.isValidSubscriptionId(active)) return active;
        } catch (ReflectiveOperationException ignored) { }
        return SubscriptionManager.getDefaultDataSubscriptionId();
    }

    private static void observeNetwork(Context context) {
        if (connectivity != null) return;
        connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return;
        try {
            NetworkCapabilities initial = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            wifiDefault = initial != null && initial.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    wifiDefault = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    requestUpdate();
                }
                @Override public void onLost(Network network) { wifiDefault = false; requestUpdate(); }
            };
            connectivity.registerDefaultNetworkCallback(networkCallback, new Handler(Looper.getMainLooper()));
            telephony = context.getSystemService(TelephonyManager.class);
            if (telephony != null) {
                dataCallback = new DataSubscriptionCallback();
                telephony.registerTelephonyCallback(context.getMainExecutor(), dataCallback);
            }
        } catch (RuntimeException t) { log("signal network observer error: " + t); }
    }

    private static final class DataSubscriptionCallback extends TelephonyCallback
            implements TelephonyCallback.ActiveDataSubscriptionIdListener {
        @Override public void onActiveDataSubscriptionIdChanged(int subId) { requestUpdate(); }
    }

    private static void requestUpdate() {
        for (ViewGroup root : new ArrayList<>(containers.keySet())) root.requestLayout();
    }

    private static Mobile findSignal(Object image) {
        if (!(image instanceof View view) || view.getParent() == null) return null;
        for (Mobile mobile : mobiles.values()) if (mobile.signal == image) return mobile;
        return null;
    }

    private static void invalidatePrimary(Object image) {
        Mobile mobile = findSignal(image);
        if (mobile != null) updateTint(mobile);
        if (mobile != null && mobile.primary != null) mobile.primary.signal.invalidate();
    }

    private static void invalidateMergedActivity(Object image) {
        for (Mobile mobile : mobiles.values()) {
            if (mobile.inout == image && mobile.primary != null) {
                mobile.primary.root.invalidate();
                break;
            }
        }
    }

    private static void drawLowerActivity(Canvas canvas, Mobile mobile) {
        ImageView activity = mobile.second.inout;
        if (activity == null || activity.getVisibility() != View.VISIBLE || activity.getDrawable() == null
                || activity.getWidth() <= 0 || activity.getHeight() <= 0) return;
        View combo = mobile.root.findViewById(id(mobile.root, "mobile_combo_real"));
        if (combo == null) return;
        int[] comboLocation = new int[2], rootLocation = new int[2];
        combo.getLocationInWindow(comboLocation);
        mobile.root.getLocationInWindow(rootLocation);
        float activityLeft = activity.getLeft() + activity.getTranslationX();
        if (networkTypeMode(true) != 0 && mobile.inout != null) {
            // 独立或隐藏制式时，以卡 1 为锚点；相对上一版再向右移动 1dp。
            activityLeft = mobile.inout.getLeft() + mobile.inoutTranslationX + 3 * density(mobile.root);
        }
        int save = canvas.save();
        try {
            // 在根视图上叠加，避免箭头左移后被信号 ImageView 的边界裁掉。
            float coordination = ((Number) XposedHelpers.callMethod(mobile.root, "getTransXForCoord")).floatValue();
            canvas.translate(comboLocation[0] - rootLocation[0] + activityLeft + coordination,
                    comboLocation[1] - rootLocation[1] + (combo.getHeight() - activity.getHeight()) / 2f
                            + activity.getTranslationY());
            activity.draw(canvas);
        } finally { canvas.restoreToCount(save); }
    }

    private static int id(View view, String name) {
        return view.getResources().getIdentifier(name, "id", "com.android.systemui");
    }

    private static void drawUpperSignal(Canvas canvas, ImageView upper) {
        Drawable drawable = upper.getDrawable();
        int width = upper.getWidth(), height = upper.getHeight();
        if (drawable == null || width <= 0 || height <= 0) return;
        int save = canvas.save();
        try {
            // 保留原图上段，只裁剪，不额外平移或缩放。
            canvas.clipRect(0, 0, width, height * 0.65f + density(upper));
            canvas.translate(upper.getPaddingLeft(), upper.getPaddingTop());
            canvas.concat(upper.getImageMatrix());
            drawable.draw(canvas);
        } finally { canvas.restoreToCount(save); }
    }

    private static void drawLowerSignal(Canvas canvas, ImageView upper, ImageView lower) {
        Drawable drawable = lower.getDrawable();
        int width = upper.getWidth(), height = upper.getHeight();
        if (drawable == null || drawable.getBounds().isEmpty() || width <= 0 || height <= 0
                || lower.getWidth() <= 0 || lower.getHeight() <= 0) return;
        int save = canvas.save();
        try {
            // 整体下移 1dp，再将裁剪上缘向上扩展 1dp；底部随整体下移。
            canvas.translate(0, height * 0.395f + density(upper));
            canvas.scale(1f, 0.5f);
            canvas.clipRect(0, height * 0.65f, width, height);
            canvas.scale((float) width / lower.getWidth(), (float) height / lower.getHeight());
            canvas.translate(lower.getPaddingLeft(), lower.getPaddingTop());
            canvas.concat(lower.getImageMatrix());
            drawable.draw(canvas);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    public static void onSettingsSnapshotPublished() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (ViewGroup container : new ArrayList<>(containers.keySet())) container.requestLayout();
        });
    }

    public static Object captureHotReloadHosts() { return new ArrayList<>(containers.keySet()); }

    public static void restoreHotReloadHosts(Object state) {
        if (!(state instanceof List<?> hosts)) return;
        for (Object host : hosts) if (host instanceof ViewGroup container) {
            prepare(container);
            container.requestLayout();
        }
    }

    public static void cleanupForHotReload() {
        for (Mobile mobile : mobiles.values()) restoreMobile(mobile);
        for (ViewGroup root : containers.keySet()) root.removeOnAttachStateChangeListener(containers.get(root));
        try {
            if (connectivity != null && networkCallback != null) connectivity.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException t) { log("signal network cleanup error: " + t); }
        try {
            if (telephony != null && dataCallback != null) telephony.unregisterTelephonyCallback(dataCallback);
        } catch (RuntimeException t) { log("signal subscription cleanup error: " + t); }
        connectivity = null; networkCallback = null; telephony = null; dataCallback = null;
        wifiDefault = false;
        mobiles.clear();
        containers.clear();
        layoutContainer.remove();
    }

    private static void restoreMobile(Mobile mobile) {
        mobile.reset();
        mobile.setSignalEndSpacing(false);
        mobile.restoreComboWidth();
        if (mobile.inout != null) mobile.inout.setTranslationX(mobile.inoutTranslationX);
        if (mobile.label != null) {
            ((ViewGroup) mobile.group).removeView(mobile.label);
            mobile.label = null;
        }
        if (mobile.roamingLabel != null) {
            ((ViewGroup) mobile.group).removeView(mobile.roamingLabel);
            mobile.roamingLabel = null;
        }
        if (mobile.type != null) setTypeVisibility(mobile, mobile.typeVisibility);
        setRoamingVisibility(mobile, false);
        mobile.root.removeOnLayoutChangeListener(mobile.layoutListener);
    }

    private static final class Mobile {
        final View root, group, roaming, roamingSpace;
        final ViewGroup combo;
        final int originalComboMinWidth;
        int reservedComboWidth;
        final View.OnLayoutChangeListener layoutListener;
        int roamingVisibility, roamingSpaceVisibility;
        final ImageView signal, type, inout;
        final float inoutTranslationX;
        TextView label, roamingLabel;
        int typeVisibility;
        String rat = "";
        final float groupAlpha;
        final int groupMarginEnd;
        Mobile primary, second;
        boolean dataOnUpper;

        Mobile(View root, ImageView signal, View group) {
            this.root = root;
            this.signal = signal;
            this.group = group;
            combo = (ViewGroup) root.findViewById(id(root, "mobile_combo_real"));
            originalComboMinWidth = combo == null ? 0 : combo.getMinimumWidth();
            groupMarginEnd = group.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params
                    ? params.getMarginEnd() : 0;
            roaming = root.findViewById(id(root, "mobile_roaming"));
            roamingSpace = root.findViewById(id(root, "mobile_roaming_space"));
            roamingVisibility = roaming == null ? View.GONE : roaming.getVisibility();
            roamingSpaceVisibility = roamingSpace == null ? View.GONE : roamingSpace.getVisibility();
            layoutListener = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> alignNetworkLabel(this);
            root.addOnLayoutChangeListener(layoutListener);
            type = root.findViewById(id(root, "mobile_type"));
            inout = root.findViewById(id(root, "data_inout"));
            inoutTranslationX = inout == null ? 0f : inout.getTranslationX();
            typeVisibility = type == null ? View.GONE : type.getVisibility();
            groupAlpha = group.getAlpha();
        }

        int requiredComboWidth() {
            if (combo == null) return 0;
            int width = originalComboMinWidth;
            // FrameLayout 取子项最大宽度；GONE 子项也按其原生资源尺寸保留占位。
            for (int i = 0; i < combo.getChildCount(); i++) {
                View child = combo.getChildAt(i);
                ViewGroup.LayoutParams params = child.getLayoutParams();
                int childWidth = Math.max(child.getMeasuredWidth(), child.getMinimumWidth());
                if (params.width >= 0) childWidth = params.width;
                else if (child instanceof ImageView image && image.getDrawable() != null) {
                    childWidth = Math.max(childWidth, Math.max(0, image.getDrawable().getIntrinsicWidth())
                            + child.getPaddingLeft() + child.getPaddingRight());
                }
                if (params instanceof ViewGroup.MarginLayoutParams margins)
                    childWidth += margins.leftMargin + margins.rightMargin;
                width = Math.max(width, childWidth + combo.getPaddingLeft() + combo.getPaddingRight());
            }
            reservedComboWidth = Math.max(reservedComboWidth, width);
            return reservedComboWidth;
        }

        void setComboWidth(int width) {
            if (combo == null) return;
            reservedComboWidth = Math.max(reservedComboWidth, width);
            if (combo.getMinimumWidth() != reservedComboWidth) combo.setMinimumWidth(reservedComboWidth);
        }

        void restoreComboWidth() {
            if (combo != null && combo.getMinimumWidth() != originalComboMinWidth)
                combo.setMinimumWidth(originalComboMinWidth);
            reservedComboWidth = 0;
        }

        void setSignalEndSpacing(boolean enabled) {
            if (!(group.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params)) return;
            // 在整个移动信号组右侧增加占位，保持内部制式、上下两排信号的距离。
            int end = groupMarginEnd + (enabled ? Math.round(density(root)) : 0);
            if (params.getMarginEnd() != end) {
                params.setMarginEnd(end);
                group.setLayoutParams(params);
            }
        }

        void reset() {
            if (primary != null) group.setAlpha(groupAlpha);
            if (second != null) signal.invalidate();
            primary = null;
            second = null;
            dataOnUpper = false;
        }
    }
}
