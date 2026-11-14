package com.rikumi.colorosmod.hooks;

final class IconContourPixels {
    static int[] mask(int[] pixels,int width,int height,float stroke) {
        stroke=Math.max(.5f,stroke);
        float cap=stroke+.5f;
        float[] distance=new float[pixels.length];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int i=y*width+x;
            if((pixels[i]>>>24)<64)continue;
            float d=Math.min(cap,Math.min(Math.min(x+1,width-x),Math.min(y+1,height-y)));
            distance[i]=d;
        }
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int i=y*width+x;float d=distance[i];
            if(x>0)d=Math.min(d,distance[i-1]+1);
            if(y>0) {
                d=Math.min(d,distance[i-width]+1);
                if(x>0)d=Math.min(d,distance[i-width-1]+1.41421356f);
                if(x+1<width)d=Math.min(d,distance[i-width+1]+1.41421356f);
            }
            distance[i]=d;
        }
        for(int y=height-1;y>=0;y--)for(int x=width-1;x>=0;x--) {
            int i=y*width+x;float d=distance[i];
            if(x+1<width)d=Math.min(d,distance[i+1]+1);
            if(y+1<height) {
                d=Math.min(d,distance[i+width]+1);
                if(x>0)d=Math.min(d,distance[i+width-1]+1.41421356f);
                if(x+1<width)d=Math.min(d,distance[i+width+1]+1.41421356f);
            }
            distance[i]=d;
        }
        int[] result=new int[pixels.length];
        for(int i=0;i<result.length;i++)if((pixels[i]>>>24)>=64) {
            int red=Math.round(255*Math.min(1,Math.max(0,distance[i]-.5f)/stroke));
            result[i]=0xff000000|(red<<16);
        }
        return result;
    }

}
