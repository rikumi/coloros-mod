package com.rikumi.colorosmod.hooks;

final class ContourLightGeometry {
    private ContourLightGeometry() {}
    static float fadeDistance(float width, float height, float corner, boolean circular) {
        float radius = Math.max(0f, Math.min(corner, Math.min(width,height)/2f));
        if (circular || (Math.abs(width-height)<1f && radius>=Math.min(width,height)/2f-1f)) {

            return (width+height)*.625f;
        }
        // Top center to the end of a corner: horizontal half-edge plus corner height.
        return (width/2f+radius)/.4f;
    }
}
