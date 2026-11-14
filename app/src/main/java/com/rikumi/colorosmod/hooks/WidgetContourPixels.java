package com.rikumi.colorosmod.hooks;

/** Flyme's background-distance mask, including translucent widget surfaces. */
final class WidgetContourPixels {
    static int[] mask(int[] pixels, int w, int h, float stroke) {
        float saturatedDistance = stroke + .5f;
        float[] distance = new float[pixels.length];
        for (int y=0; y<h; y++) for (int x=0; x<w; x++) {
            int i=y*w+x;
            distance[i] = (pixels[i]>>>24)<8 ? 0 : Math.min(saturatedDistance, Math.min(Math.min(x+1,w-x),Math.min(y+1,h-y)));
        }
        for (int y=0; y<h; y++) for (int x=0; x<w; x++) {
            int i=y*w+x; float d=distance[i];
            if (x>0) d=Math.min(d,distance[i-1]+1);
            if (y>0) {
                d=Math.min(d,distance[i-w]+1);
                if (x>0) d=Math.min(d,distance[i-w-1]+1.41421356f);
                if (x+1<w) d=Math.min(d,distance[i-w+1]+1.41421356f);
            }
            distance[i]=d;
        }
        for (int y=h-1; y>=0; y--) for (int x=w-1; x>=0; x--) {
            int i=y*w+x; float d=distance[i];
            if (x+1<w) d=Math.min(d,distance[i+1]+1);
            if (y+1<h) {
                d=Math.min(d,distance[i+w]+1);
                if (x>0) d=Math.min(d,distance[i+w-1]+1.41421356f);
                if (x+1<w) d=Math.min(d,distance[i+w+1]+1.41421356f);
            }
            distance[i]=d;
        }
        int[] mask = new int[pixels.length];
        for (int i=0; i<mask.length; i++) if ((pixels[i]>>>24)>=8) {
            int red = Math.round(255f*Math.min(1f,Math.max(0f,distance[i]-.5f)/stroke));
            mask[i] = 0xFF000000 | (red<<16);
        }
        return mask;
    }
    private WidgetContourPixels() {}
}
