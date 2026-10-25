package com.rikumi.colorosmod.hooks;

import android.graphics.Rect;
import android.animation.ValueAnimator;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class FluidCloudLyricGeometry {
    private static final Set<Class<?>> sHooked = new HashSet<>();
    private static View sRoot, sCapsule, sContent, sHostContent, sSpace, sRight;
    private static ViewGroup sLeft, sContainer, sOuter;
    private static View sText, sLeftMoon, sRightMoon;
    private static ViewGroup sIcons;
    private static int sNativeIconLimit;
    private static boolean sWritingIconLimit;
    private static int sNativeRightSectionWidth, sNativeHole;
    private static int sLyricOffset;
    private static Plan sPlan;
    private static Plan sTargetPlan;
    private static ValueAnimator sWidthAnimator;
    private static boolean sRestoring;
    private static int sCapsuleRightInset;
    private static boolean sPaddingHooksInstalled;
    private static final float sStableLeftMoonTranslationX = 0f;
    private static final float sStableRightMoonTranslationX = 0f;
    private static float sTextAlpha;
    private static ViewTreeObserver sDrawObserver;
    private static final Map<View, NativeBounds> sNativeBounds = new WeakHashMap<>();
    private static final Map<View, Integer> sNativeWidths = new WeakHashMap<>();
    private static final Map<View, int[]> sOriginalPadding = new WeakHashMap<>();
    private static int[] sStableContentPadding, sStableLeftPadding, sStableSpacePadding,
            sStableRightPadding, sStableContainerPadding, sStableOuterPadding,
            sStableLeftMoonPadding, sStableRightMoonPadding;
    private static final ViewTreeObserver.OnPreDrawListener sPositionBeforeDraw = () -> {
        if (sPlan != null) {
            boolean keepRightEdge = geometryAttached();
            View anchor = sRestoring ? currentCapsule() : sCapsule;
            if (keepRightEdge && managedCapsule(anchor)) {

                int right = sContainer.getWidth() - sCapsuleRightInset;
                int left = right - sPlan.width;
                anchor.layout(left, anchor.getTop(), right,
                        anchor.getTop() + anchor.getMeasuredHeight());
                setDrawableBounds(anchor);
            }
            if (keepRightEdge) applyStablePadding();
            if (keepRightEdge) lockMoonTranslations();
            if (keepRightEdge) {
                positionContainer(anchor);
            } else if (sIcons != null
                    && XposedHelpers.getIntField(sIcons, "mMaxIcons") != sNativeIconLimit) {
                writeIconLimit(sNativeIconLimit);
            }
            FluidCloudLyricHooks.updateStatusIconOccupancy(statusIconHalfWidth());
            if (!sRestoring && keepRightEdge && anchor == sCapsule) {
                NativeBounds bounds = sNativeBounds.get(sCapsule);
                int nativeWidth = Math.round(bounds.width * sRoot.getResources().getDisplayMetrics().density
                        / bounds.density);
                int visibleWidth = sCapsule.getWidth();
                sText.setAlpha(sPlan.width > nativeWidth && visibleWidth > nativeWidth ? sTextAlpha : 0f);
            }
            if (sRestoring && keepRightEdge && managedCapsule(anchor) && sWidthAnimator == null
                    && !Boolean.TRUE.equals(XposedHelpers.callMethod(anchor, "getWidthChanging"))) {
                boolean restored = managedCapsule(anchor) ? anchor.getWidth() == sPlan.width
                        : anchor.getWidth() == anchor.getMeasuredWidth();
                if (restored) finishClear();
            }
        }
        return true;
    };

    private static final class NativeBounds {
        final int hole, rightExtent, rightSectionWidth, width, iconRightExtent;
        final float density;
        NativeBounds(int hole, int rightExtent, int rightSectionWidth, int width,
                int iconRightExtent, float density) {
            this.hole = hole; this.rightExtent = rightExtent; this.width = width;
            this.rightSectionWidth = rightSectionWidth;
            this.iconRightExtent = iconRightExtent; this.density = density;
        }
    }

    static final class Plan {
        final int start, left, lyric, holeWidth, right, paddingLeft, paddingRight, width;
        Plan(int start, int left, int lyric, int holeWidth, int right, int pl, int pr) {
            this.start = start; this.left = left; this.lyric = lyric;
            this.holeWidth = holeWidth; this.right = right;
            paddingLeft = pl; paddingRight = pr;
            width = pl + left + holeWidth + right + pr;
        }
        int holeCenter() { return start + paddingLeft + left + holeWidth / 2; }
        int end() { return start + width; }
    }

    static Plan plan(int holeCenter, int holeWidth, int screenStart, int notifications,
            int outerGap, int cover, int coverGap, int rightWidth, int pl, int pr) {
        if (holeWidth <= 0 || cover <= 0 || rightWidth < 0) return null;
        int start = screenStart + notifications + (notifications > 0 ? outerGap : 0);
        int left = holeCenter - holeWidth / 2 - start - pl;
        int lyric = left - cover - coverGap;
        if (lyric <= 0) return null;
        return new Plan(start, left, lyric, holeWidth, rightWidth, pl, pr);
    }

    static int iconSpace(int count, int slot, int padding) {
        return count <= 0 || slot <= 0 ? 0 : Math.min(count, 2) * slot + padding;
    }

    static void bind(View root, View capsule, View content, ViewGroup left,
            View space, View right, View text, float textAlpha) {
        if (!(capsule.getParent() instanceof ViewGroup)) throw new IllegalStateException("capsule parent");
        ViewGroup container = (ViewGroup) capsule.getParent();
        boolean reuse = sPlan != null && sRoot == root && sText == text
                && sContainer == container;
        NativeBounds previousBounds = reuse ? sNativeBounds.get(sCapsule) : null;
        if (reuse && sRestoring) {

            cancelWidthAnimator();
            sTargetPlan = null;
            sRestoring = false;
        } else if (!reuse) {
            finishClear();
        }
        if (!"com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleContainer".equals(
                container.getClass().getName()) || !(container.getParent() instanceof FrameLayout)) {
            throw new IllegalStateException("native capsule container");
        }
        sRoot = root; sCapsule = capsule; sContent = content; sLeft = left;
        sHostContent = content;
        while (sHostContent.getParent() != capsule) {
            if (!(sHostContent.getParent() instanceof View)) throw new IllegalStateException("native page wrapper");
            sHostContent = (View) sHostContent.getParent();
        }
        sSpace = space; sRight = right; sText = text;
        sTextAlpha = textAlpha;
        sContainer = container; sOuter = (ViewGroup) container.getParent();
        if (!reuse || sLeftMoon == null || sRightMoon == null) {
            sLeftMoon = (View) XposedHelpers.callMethod(container, "getLeftMoon");
            sRightMoon = (View) XposedHelpers.callMethod(container, "getRightMoon");
        }
        if (!(container.getLayoutParams() instanceof FrameLayout.LayoutParams)
                || (((FrameLayout.LayoutParams) container.getLayoutParams()).gravity
                & Gravity.HORIZONTAL_GRAVITY_MASK) != Gravity.CENTER_HORIZONTAL) {
            throw new IllegalStateException("native centered container layout");
        }
        // Capture the unmodified native space and capsule before the first lyric layout pass.
        NativeBounds nativeBounds = sNativeBounds.get(capsule);
        if (nativeBounds == null && previousBounds != null) {
            nativeBounds = previousBounds;
            sNativeBounds.put(capsule, nativeBounds);
        }
        float density = root.getResources().getDisplayMetrics().density;
        if (nativeBounds == null) {
            int hole = leftWithin(root, space) + space.getWidth() / 2;
            int extent = leftWithin(root, capsule) + capsule.getWidth() - hole;
            if (space.getWidth() <= 0 || extent <= 0) throw new IllegalStateException("unmeasured native capsule");
            int iconExtent = leftWithin(root, container) + container.getWidth() - hole;
            int rightSectionWidth = right.getMeasuredWidth();
            if (rightSectionWidth <= 0) {
                rightSectionWidth = Math.max(0, extent - (space.getWidth() - space.getWidth() / 2)
                        - content.getPaddingRight());
            }
            nativeBounds = new NativeBounds(hole, extent, rightSectionWidth,
                    capsule.getWidth(), iconExtent, density);
            sNativeBounds.put(capsule, nativeBounds);
        }
        sNativeWidths.putIfAbsent(capsule, Math.round(nativeBounds.width * density / nativeBounds.density));
        sCapsuleRightInset = container.getWidth() - capsule.getLeft() - capsule.getWidth();
        captureStablePadding(content, left, space, right, container, sOuter, reuse);
        installPaddingHooks();

        sNativeHole = nativeBounds.hole;
        sNativeRightSectionWidth = Math.round(nativeBounds.rightSectionWidth
                * density / nativeBounds.density);
        installHooks(capsule.getClass(), content.getClass(), container.getClass());
        if (!reuse) {
            sDrawObserver = root.getViewTreeObserver();
            sDrawObserver.addOnPreDrawListener(sPositionBeforeDraw);
        }
        View icons = systemView(root, "notificationIcons");
        if (icons instanceof ViewGroup) {
            boolean sameIcons = reuse && sIcons == icons;
            sIcons = (ViewGroup) icons;
            installIconHooks(icons.getClass());
            if (!sameIcons) sNativeIconLimit = XposedHelpers.getIntField(icons, "mMaxIcons");
            writeIconLimit(Math.min(sNativeIconLimit, 2));
        }
        if (reuse) applyPlan(sPlan);
    }

    static boolean refresh() {
        if (!active()) return false;
        int hole = holeCenter();
        int blank = sSpace.getLayoutParams().width;
        if (blank <= 0) blank = ((Number) XposedHelpers.callMethod(sContainer, "getBlankWidth")).intValue();
        int coverId = sLeft.getResources().getIdentifier("img_primary", "id", "com.oplus.systemui.plugins");
        View artwork = coverId == 0 ? null : sLeft.findViewById(coverId);

        int cover = artwork == null ? 0 : leftWithin(sLeft, artwork)
                + Math.max(artwork.getMeasuredWidth(), artwork.getLayoutParams().width);
        int screenStart = sRoot.getPaddingLeft();
        View startSide = systemView(sRoot, "status_bar_start_side_content");
        if (startSide != null) screenStart = Math.max(screenStart,
                leftWithin(sRoot, startSide) + startSide.getPaddingLeft());
        int paddingId = sRoot.getResources().getIdentifier("status_bar_padding_start", "dimen",
                "com.android.systemui");
        if (paddingId != 0) screenStart = Math.max(screenStart,
                sRoot.getResources().getDimensionPixelSize(paddingId));
        int slot = sIcons != null && sIcons.getChildCount() > 0
                ? sIcons.getChildAt(0).getMeasuredWidth() : 0;
        if (slot <= 0) {
            int iconSize = sRoot.getResources().getIdentifier("status_bar_icon_size", "dimen",
                    "com.android.systemui");
            if (iconSize != 0) slot = sRoot.getResources().getDimensionPixelSize(iconSize);
        }
        int notifications = sIcons != null && sIcons.isShown()
                ? iconSpace(sIcons.getChildCount(), slot, sIcons.getPaddingLeft() + sIcons.getPaddingRight()) : 0;
        View badge = systemView(sRoot, "notification_num_view");
        if (badge != null && badge.isShown()) notifications = Math.max(notifications,
                Math.min(badge.getMeasuredWidth(), iconSpace(2, slot, 0)));
        int gap = Math.round(sRoot.getResources().getDisplayMetrics().density * 4f);
        int notificationGap = Math.round(sRoot.getResources().getDisplayMetrics().density * 8f);
        sLyricOffset = cover + gap;
        Plan next = plan(hole, blank, screenStart, notifications, notificationGap, cover, gap,
                sNativeRightSectionWidth, sContent.getPaddingLeft(), sContent.getPaddingRight());
        if (next == null) return false;
        if (sTargetPlan == null || next.start != sTargetPlan.start || next.width != sTargetPlan.width
                || next.lyric != sTargetPlan.lyric || next.holeWidth != sTargetPlan.holeWidth) {
            animateTo(next);
        }
        return true;
    }

    static void setPanelExpanded(boolean expanded) {
        if (sText != null && sText.getParent() == sLeft) {
            sText.setAlpha(expanded ? 0f : sTextAlpha);
        }
    }

    private static void applyPlan(Plan next) {
        sPlan = next;
        if (sRestoring) {
            sContainer.requestLayout();
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sText.getLayoutParams();
        if (lp.width != next.lyric || lp.leftMargin != sLyricOffset) {
            lp.width = next.lyric; lp.leftMargin = sLyricOffset;
            sText.setLayoutParams(lp);
        }
        // CapsuleView deliberately blocks requestLayout during native width transitions.
        sContainer.requestLayout();
    }

    private static void animateTo(Plan next) {
        cancelWidthAnimator();
        sTargetPlan = next;
        int nativeWidth = sPlan == null ? nativeWidth(sCapsule) : 0;
        if (sPlan == null && nativeWidth > 0) {
            int leftWidth = Math.max(0, nativeWidth - next.paddingLeft - next.holeWidth
                    - next.right - next.paddingRight);
            int lyricWidth = Math.max(0, leftWidth - (next.left - next.lyric));
            int initialWidth = next.paddingLeft + leftWidth + next.holeWidth
                    + next.right + next.paddingRight;
            int initialStart = next.end() - initialWidth;
            Plan initial = new Plan(initialStart, leftWidth, lyricWidth, next.holeWidth,
                    next.right, next.paddingLeft, next.paddingRight);
            applyPlan(initial);
            applyStablePadding();
            lockMoonTranslations();
            positionContainer(sCapsule);
        }
        final int fromStart = sPlan == null
                ? next.end() - sCapsule.getWidth() : sPlan.start;
        if (fromStart == next.start) { applyPlan(next); return; }
        ValueAnimator animator = ValueAnimator.ofInt(fromStart, next.start);
        sWidthAnimator = animator;
        animator.setDuration(200L);
        animator.addUpdateListener(value -> {
            if (sWidthAnimator != value) return;
            int start = (Integer) value.getAnimatedValue();
            int delta = next.start - start;
            applyPlan(new Plan(start, next.left + delta, Math.max(0, next.lyric + delta),
                    next.holeWidth, next.right, next.paddingLeft, next.paddingRight));
            if (value.getAnimatedFraction() >= 1f) sWidthAnimator = null;
        });
        animator.start();
    }

    private static void cancelWidthAnimator() {
        ValueAnimator animator = sWidthAnimator;
        sWidthAnimator = null;
        if (animator != null) animator.cancel();
    }

    private static boolean active() {
        return sCapsule != null && (sRestoring || (sText != null
                && sText.getParent() == sLeft)) && sCapsule.isShown()
                && XposedHelpers.callMethod(sContainer, "g") == sCapsule;
    }

    private static void installHooks(Class<?> capsule, Class<?> content, Class<?> container) {
        if (!sHooked.contains(capsule)) {
            XposedHelpers.findAndHookMethod(capsule, "setCapsuleDrawableWidth", int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            View view = (View) param.thisObject;
                            if (!geometryAttached() || !managedCapsule(view)) return;
                            setDrawableBounds(view);
                        }
                    });
            XposedHelpers.findAndHookMethod(capsule, "onMeasure", int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            View view = (View) param.thisObject;
                            if (!geometryAttached() || !managedCapsule(view)) return;
                            int height = view.getMeasuredHeight();
                            if (view == sCapsule) {
                                sHostContent.measure(exact(sPlan.width), exact(height));
                                if (sHostContent != sContent) sContent.measure(exact(sPlan.width), exact(height));
                                sLeft.measure(exact(sPlan.left), exact(height));
                                sRight.measure(exact(sPlan.right), exact(height));
                            }
                            XposedHelpers.callMethod(view, "setMeasuredDimension", sPlan.width, height);
                            setDrawableBounds(view);
                        }
                    });
            sHooked.add(capsule);
        }
        if (!sHooked.contains(content)) { hookLayout(content, true); sHooked.add(content); }
        if (!sHooked.contains(container)) {
            XposedHelpers.findAndHookMethod(container, "onLayout", boolean.class, int.class, int.class,
                    int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject == sContainer && sPlan != null) {
                                lockMoonTranslations();
                                positionContainer(sRestoring ? currentCapsule() : sCapsule);
                            }
                        }
                    });
            sHooked.add(container);
        }
    }

    private static void hookLayout(Class<?> type, boolean content) {
        XposedHelpers.findAndHookMethod(type, "onLayout", boolean.class, int.class, int.class,
                int.class, int.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (sPlan == null) return;
                        if (content && param.thisObject == sContent && geometryAttached()) {
                            int height = sContent.getHeight();
                            int left = sPlan.paddingLeft;
                            sLeft.layout(left, 0, left + sPlan.left, height);
                            left += sPlan.left;
                            sSpace.layout(left, 0, left + sPlan.holeWidth, height);
                            left += sPlan.holeWidth;
                            sRight.layout(left, 0, left + sPlan.right, height);
                        }
                    }
                });
    }

    private static int nativeContainerLeft() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sContainer.getLayoutParams();
        return sOuter.getPaddingLeft() + (sOuter.getWidth() - sOuter.getPaddingLeft()
                - sOuter.getPaddingRight() - sContainer.getWidth()) / 2 + lp.leftMargin - lp.rightMargin;
    }

    private static void setDrawableBounds() {
        int right = sPlan.width, height = sCapsule.getHeight();
        if (sCapsule.getBackground() != null) sCapsule.getBackground().setBounds(0, 0, right, height);
        if (sCapsule.getForeground() != null) sCapsule.getForeground().setBounds(0, 0, right, height);
    }

    static int statusIconHalfWidth() {
        if (sPlan == null || !geometryAttached()) return -1;
        NativeBounds bounds = sNativeBounds.get(sCapsule);
        int extent = Math.round(bounds.iconRightExtent * sRoot.getResources().getDisplayMetrics().density
                / bounds.density);
        return Math.max(0, sPlan.holeCenter() + extent - sRoot.getWidth() / 2);
    }

    private static void positionContainer(View anchor) {
        if (!geometryAttached()) return;
        int targetRight = sPlan.end() + sCapsuleRightInset;
        if (managedCapsule(anchor)) targetRight -= Math.round(anchor.getTranslationX());
        int outerLeft = leftWithin(sRoot, sOuter);
        int target = targetRight - outerLeft - sContainer.getWidth()
                - Math.round(sContainer.getTranslationX());

        sContainer.offsetLeftAndRight(target - sContainer.getLeft());
    }

    private static boolean geometryAttached() {
        return sPlan != null && attached() && (sRestoring
                || (sText != null && sLeft != null && sText.getParent() == sLeft));
    }

    private static boolean managedCapsule(View view) {
        return view != null && (view == sCapsule || (sRestoring && isMusicCapsule(view)));
    }

    private static boolean isMusicCapsule(View view) {
        try {
            Object item = XposedHelpers.callMethod(view, "getViewItem");
            return item != null && FluidCloudLyricHooks.isMusicCapsuleKey(
                    (String) XposedHelpers.getObjectField(item, "a"));
        } catch (Throwable ignored) { return false; }
    }

    private static int nativeWidth(View view) {
        Integer width = sNativeWidths.get(view);
        if (width != null) return width;
        int measured = view == null ? 0 : view.getMeasuredWidth();
        if (measured <= 0 && view != null) measured = view.getWidth();
        if (view != null && measured > 0) sNativeWidths.put(view, measured);
        NativeBounds original = sNativeBounds.get(sCapsule);
        if (measured <= 0 && original != null) {
            measured = Math.round(original.width * sRoot.getResources().getDisplayMetrics().density
                    / original.density);
        }
        return measured;
    }

    private static boolean attached() {
        return sRoot != null && sOuter != null && sContainer != null
                && sContainer.getParent() == sOuter;
    }

    private static View currentCapsule() {
        if (sContainer == null) return sCapsule;
        try {
            Object current = XposedHelpers.callMethod(sContainer, "g");
            return current instanceof View ? (View) current : sCapsule;
        } catch (Throwable ignored) { return sCapsule; }
    }

    private static void setDrawableBounds(View capsule) {
        if (sPlan == null || capsule == null) return;
        int right = sPlan.width, height = capsule.getHeight();
        if (capsule.getBackground() != null) capsule.getBackground().setBounds(0, 0, right, height);
        if (capsule.getForeground() != null) capsule.getForeground().setBounds(0, 0, right, height);
    }

    private static void captureStablePadding(View content, View left, View space, View right,
            View container, View outer, boolean reuse) {
        captureNativePadding(content);
        captureNativePadding(left);
        captureNativePadding(space);
        captureNativePadding(right);
        captureNativePadding(container);
        captureNativePadding(outer);
        captureNativePadding(sLeftMoon);
        captureNativePadding(sRightMoon);
        if (!reuse || sStableContentPadding == null) {
            sStableContentPadding = horizontalPadding(content);
            sStableLeftPadding = horizontalPadding(left);
            sStableSpacePadding = horizontalPadding(space);
            sStableRightPadding = horizontalPadding(right);
            sStableContainerPadding = horizontalPadding(container);
            sStableOuterPadding = horizontalPadding(outer);
            sStableLeftMoonPadding = horizontalPadding(sLeftMoon);
            sStableRightMoonPadding = horizontalPadding(sRightMoon);
        }
        applyStablePadding();
    }

    private static void captureNativePadding(View view) {
        if (!sOriginalPadding.containsKey(view)) {
            sOriginalPadding.put(view, new int[] { view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), view.getPaddingBottom(), view.getPaddingStart(),
                    view.getPaddingEnd() });
        }
    }

    private static int[] horizontalPadding(View view) {
        return new int[] { view.getPaddingLeft(), view.getPaddingRight(),
                view.getPaddingStart(), view.getPaddingEnd() };
    }

    private static void applyStablePadding() {
        lockPadding(sContent, sStableContentPadding);
        lockPadding(sLeft, sStableLeftPadding);
        lockPadding(sSpace, sStableSpacePadding);
        lockPadding(sRight, sStableRightPadding);
        lockPadding(sContainer, sStableContainerPadding);
        lockPadding(sOuter, sStableOuterPadding);
        lockPadding(sLeftMoon, sStableLeftMoonPadding);
        lockPadding(sRightMoon, sStableRightMoonPadding);
    }

    private static void lockPadding(View view, int[] stable) {
        if (view == null || stable == null) return;
        if (view.getPaddingLeft() != stable[0] || view.getPaddingRight() != stable[1]
                || view.getPaddingStart() != stable[2] || view.getPaddingEnd() != stable[3]) {
            view.setPaddingRelative(stable[2], view.getPaddingTop(), stable[3], view.getPaddingBottom());
        }
    }

    private static int[] stablePaddingFor(View view) {
        if (view == sContent) return sStableContentPadding;
        if (view == sLeft) return sStableLeftPadding;
        if (view == sSpace) return sStableSpacePadding;
        if (view == sRight) return sStableRightPadding;
        if (view == sContainer) return sStableContainerPadding;
        if (view == sOuter) return sStableOuterPadding;
        if (view == sLeftMoon) return sStableLeftMoonPadding;
        if (view == sRightMoon) return sStableRightMoonPadding;
        return null;
    }

    private static void installPaddingHooks() {
        if (sPaddingHooksInstalled) return;
        XposedHelpers.findAndHookMethod(View.class, "setPadding", int.class, int.class,
                int.class, int.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (sPlan == null) return;
                        int[] stable = stablePaddingFor((View) param.thisObject);
                        if (stable == null) return;
                        param.args[0] = stable[0];
                        param.args[2] = stable[1];
                    }
                });
        XposedHelpers.findAndHookMethod(View.class, "setPaddingRelative", int.class, int.class,
                int.class, int.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (sPlan == null) return;
                        int[] stable = stablePaddingFor((View) param.thisObject);
                        if (stable == null) return;
                        param.args[0] = stable[2];
                        param.args[2] = stable[3];
                    }
                });
        XposedHelpers.findAndHookMethod(View.class, "setTranslationX", float.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        View view = (View) param.thisObject;
                        if (geometryAttached() && (view == sLeftMoon || view == sRightMoon)) {
                            param.args[0] = 0f;
                        }
                    }
                });
        sPaddingHooksInstalled = true;
    }

    private static void lockMoonTranslations() {
        if (sContainer == null || sLeftMoon == null || sRightMoon == null) return;
        int height = sContainer.getHeight();
        int left = sContainer.getPaddingLeft();
        int right = sContainer.getWidth() - sContainer.getPaddingRight();
        if (sLeftMoon.getWidth() > 0 && sLeftMoon.getHeight() > 0) {
            int top = (height - sLeftMoon.getHeight()) / 2;
            sLeftMoon.layout(left, top, left + sLeftMoon.getWidth(), top + sLeftMoon.getHeight());
        }
        if (sRightMoon.getWidth() > 0 && sRightMoon.getHeight() > 0) {
            int top = (height - sRightMoon.getHeight()) / 2;
            sRightMoon.layout(right - sRightMoon.getWidth(), top, right, top + sRightMoon.getHeight());
        }
        sLeftMoon.setTranslationX(sStableLeftMoonTranslationX);
        sRightMoon.setTranslationX(sStableRightMoonTranslationX);
    }

    private static void restoreNativePadding() {
        for (Map.Entry<View, int[]> entry : sOriginalPadding.entrySet()) {
            View view = entry.getKey();
            int[] padding = entry.getValue();
            if (view.getPaddingLeft() != padding[0] || view.getPaddingTop() != padding[1]
                    || view.getPaddingRight() != padding[2] || view.getPaddingBottom() != padding[3]) {
                view.setPadding(padding[0], padding[1], padding[2], padding[3]);
            }
            if (view.getPaddingStart() != padding[4] || view.getPaddingEnd() != padding[5]) {
                view.setPaddingRelative(padding[4], padding[1], padding[5], padding[3]);
            }
        }
        sOriginalPadding.clear();
        sStableContentPadding = sStableLeftPadding = sStableSpacePadding = sStableRightPadding = null;
        sStableContainerPadding = sStableOuterPadding = null;
        sStableLeftMoonPadding = sStableRightMoonPadding = null;
    }

    private static void installIconHooks(Class<?> type) {
        if (sHooked.contains(type)) return;
        XposedHelpers.findAndHookMethod(type, "setMaxIconsAmount", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject != sIcons || sWritingIconLimit) return;
                sNativeIconLimit = (Integer) param.args[0];
                if (active()) param.args[0] = Math.min(sNativeIconLimit, 2);
            }
        });
        XposedHelpers.findAndHookMethod(type, "onMeasure", int.class, int.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject != sIcons || !active()) return;
                int slot = sIcons.getChildCount() > 0 ? sIcons.getChildAt(0).getMeasuredWidth() : 0;
                int max = iconSpace(sIcons.getChildCount(), slot,
                        sIcons.getPaddingLeft() + sIcons.getPaddingRight());
                XposedHelpers.callMethod(sIcons, "setMeasuredDimension",
                        Math.min(sIcons.getMeasuredWidth(), max), sIcons.getMeasuredHeight());
            }
        });
        sHooked.add(type);
    }

    private static void writeIconLimit(int limit) {
        if (sIcons == null) return;
        sWritingIconLimit = true;
        try { XposedHelpers.callMethod(sIcons, "setMaxIconsAmount", limit); }
        finally { sWritingIconLimit = false; }
        sIcons.requestLayout();
    }

    private static int holeCenter() {
        WindowInsets insets = sRoot.getRootWindowInsets();
        DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
        if (cutout != null) {
            int[] root = new int[2]; sRoot.getLocationOnScreen(root);
            for (Rect rect : cutout.getBoundingRects()) {
                if (rect.width() > 0 && rect.top < root[1] + sRoot.getHeight()
                        && rect.bottom > root[1]) return rect.centerX() - root[0];
            }
        }
        View cutoutSpace = systemView(sRoot, "cutout_space_view");
        return cutoutSpace != null && cutoutSpace.getWidth() > 0
                ? leftWithin(sRoot, cutoutSpace) + cutoutSpace.getWidth() / 2 : sNativeHole;
    }

    private static int leftWithin(View root, View view) {
        int x = 0;
        while (view != root) {
            x += view.getLeft();
            if (!(view.getParent() instanceof View)) throw new IllegalStateException("detached capsule layout");
            view = (View) view.getParent();
            x -= view.getScrollX();
        }
        return x;
    }

    private static View systemView(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", "com.android.systemui");
        return id == 0 ? null : root.findViewById(id);
    }

    private static int exact(int size) { return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY); }

    static void clear() {
        if (sRestoring) return;
        if (sPlan != null && attached()) {
            View restoreAnchor = currentCapsule();
            int width = managedCapsule(restoreAnchor) ? nativeWidth(restoreAnchor)
                    : nativeWidth(sCapsule);
            if (width <= 0) { finishClear(); return; }
            int start = sPlan.end() - width;
            int left = sPlan.left + sPlan.start - start;
            sRestoring = true;
            writeIconLimit(sNativeIconLimit);
            animateTo(new Plan(start, left, 0, sPlan.holeWidth, sPlan.right,
                    sPlan.paddingLeft, sPlan.paddingRight));
            return;
        }
        finishClear();
    }

    private static void finishClear() {
        cancelWidthAnimator();
        lockMoonTranslations();
        sPlan = null;
        sTargetPlan = null;
        sRestoring = false;
        restoreNativePadding();
        if (sDrawObserver != null && sDrawObserver.isAlive()) {
            sDrawObserver.removeOnPreDrawListener(sPositionBeforeDraw);
        }
        sDrawObserver = null;
        if (sContainer != null && sOuter != null && sContainer.getParent() == sOuter) {

            sContainer.requestLayout();
        }
        writeIconLimit(sNativeIconLimit);
        sRoot = null; sCapsule = null; sContent = null; sHostContent = null; sSpace = null; sRight = null;
        sLeft = null; sContainer = null; sOuter = null; sText = null; sIcons = null;
        sLeftMoon = null; sRightMoon = null;
        FluidCloudLyricHooks.updateStatusIconOccupancy(-1);
    }
}
