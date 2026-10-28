package com.rikumi.colorosmod.hooks;

/** Center all visible card content, including update status, inside a shared height. */
final class AboutCardGeometry {
    record Layout(int height, float contentTop, float scale) {}

    static Layout layoutContent(int cardHeight, int contentTop, int contentBottom) {
        if (cardHeight <= 0 || contentTop < 0 || contentBottom <= contentTop) return null;
        float center = (contentTop + contentBottom) / 2f;
        float scale = Math.min(1f, cardHeight / (float) (contentBottom - contentTop));
        return new Layout(cardHeight, cardHeight / 2f - center, scale);
    }

    private AboutCardGeometry() {}
}
