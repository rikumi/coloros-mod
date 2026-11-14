package com.rikumi.colorosmod.hooks;

final class IconInnerShadow {
    private IconInnerShadow() {}
    static int[] pixels(int[] silhouette, int width, int height, float depth) {
        int[] shadow = new int[silhouette.length];
        if (depth <= 0f) return shadow;
        for (int x=0; x<width; x++) {
            int bottom=height-1;
            while (bottom>=0 && (silhouette[bottom*width+x]>>>24)==0) bottom--;
            // Shade inward from the exterior bottom; holes and transparent pixels stay clear.
            int top=Math.max(0, (int)Math.ceil(bottom+.5f-depth));
            for (int y=top; y<=bottom; y++) {
                int coverage=silhouette[y*width+x]>>>24;
                if (coverage==0) continue;
                float distance=(bottom-y+.5f)/depth;
                int alpha=Math.round(18f*(coverage/255f)*(float)Math.exp(-2f*distance*distance));
                shadow[y*width+x]=(alpha<<24)|0x666666;
            }
        }
        return shadow;
    }
}
