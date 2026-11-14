package com.rikumi.colorosmod.hooks;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.view.View;
import java.nio.charset.StandardCharsets;

final class LauncherContourRenderer {
    private final RuntimeShader nativeCurve;
    private final UniformState nativeUniforms = new UniformState();
    private Bitmap inputMask;
    private int inputGeneration;
    private BitmapShader maskInput;
    private final Matrix inputScale = new Matrix();
    private float inputWidth, inputHeight;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap shadedMask;
    private int shadedGeneration;
    private float shadedWidth, shadedHeight;
    private final float[] near = {0, .4f, .2f, .2f, 0, 0};
    private final float[] far = {0, .2f, .2f, .2f, 0, 0};
    private Bitmap coverageMask;
    private float coverageHeight;
    private int coverageGeneration;

    LauncherContourRenderer(View view) throws Exception { this(view.getContext()); }
    LauncherContourRenderer(Context context) throws Exception {
        Context module = context.createPackageContext("com.rikumi.colorosmod", 0);
        nativeCurve = new RuntimeShader(source(module, "contour_icon_path"));
    }
    private static String source(Context context, String name) throws Exception {
        try (java.io.InputStream input = context.getAssets().open("launcher_contour/" + name + ".agsl")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    void drawIcon(View view, Canvas canvas, RectF bounds, float alpha, Bitmap mask) {
        drawIcon(view.getContext(), canvas, bounds, alpha, mask);
    }
    void drawIcon(Context context, Canvas canvas, RectF bounds, float alpha, Bitmap mask) {
        if (bounds.isEmpty() || alpha <= 0 || mask == null) return;
        if (shadedMask != mask || shadedGeneration != mask.getGenerationId()
                || shadedWidth != bounds.width() || shadedHeight != bounds.height()) {
            BitmapShader silhouette = new BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix scale = new Matrix();
            scale.setScale(bounds.width()/mask.getWidth(), bounds.height()/mask.getHeight());
            silhouette.setLocalMatrix(scale);
            Shader gradient = new LinearGradient(0, 0, 0, bounds.height(),
                    Color.TRANSPARENT, 0x1f000000, Shader.TileMode.CLAMP);
            // Mask the gradient to the icon, including transparent holes, before drawing its light.
            iconShadePaint.setShader(new ComposeShader(gradient, silhouette, BlendMode.DST_IN));
            shadedMask = mask; shadedGeneration = mask.getGenerationId();
            shadedWidth = bounds.width(); shadedHeight = bounds.height();
        }
        iconShadePaint.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, alpha))));
        int saved = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        try { canvas.drawRect(0, 0, bounds.width(), bounds.height(), iconShadePaint); }
        finally { canvas.restoreToCount(saved); }
        draw(context, canvas, bounds, 0f, alpha, mask, true);
    }
    void draw(View view, Canvas canvas, RectF bounds, float radius, float alpha, Bitmap mask) {
        draw(view.getContext(),canvas,bounds,radius,alpha,mask);
    }
    void draw(Context context, Canvas canvas, RectF bounds, float radius, float alpha, Bitmap mask) {
        draw(context, canvas, bounds, radius, alpha, mask, false);
    }
    private void draw(Context context, Canvas canvas, RectF bounds, float radius, float alpha, Bitmap mask, boolean iconStroke) {
        if (bounds.isEmpty() || alpha <= 0 || mask == null) return;
        RuntimeShader shader = nativeCurve;
        if (mask != null) {
            boolean changed=inputMask!=mask || inputGeneration!=mask.getGenerationId();
            if(changed) {
                inputMask=mask; inputGeneration=mask.getGenerationId();
                maskInput=new BitmapShader(mask,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
            }
            if(changed || inputWidth!=bounds.width() || inputHeight!=bounds.height()) {
                inputWidth=bounds.width(); inputHeight=bounds.height();
                inputScale.setScale(inputWidth/mask.getWidth(),inputHeight/mask.getHeight());
                maskInput.setLocalMatrix(inputScale); shader.setInputBuffer("uMask",maskInput);
            }
        }
        float width = bounds.width(), height = bounds.height();
        radius = Math.min(radius, Math.min(width, height)/2f);
        float coverageCorner = maskCoverageHeight(mask, width, height);
        float density=context.getResources().getDisplayMetrics().density;
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        UniformState state=nativeUniforms;
        if(!state.ready || state.width!=width || state.height!=height || state.radius!=radius
                || state.coverage!=coverageCorner || state.density!=density || state.night!=night || state.iconStroke!=iconStroke) {
            shader.setFloatUniform("u_size", width, height);
            shader.setFloatUniform("u_fadeDistance", ContourLightGeometry.fadeDistance(width, height, coverageCorner, false));
            shader.setFloatUniform("uRatio", .5f + width/(4f*(width+height)));
            shader.setFloatUniform("u_corner", radius); shader.setFloatUniform("u_weight", .2f);
            shader.setColorUniform("uColor", Color.WHITE);
            // Decode the existing 2dp mask independently of the visible stroke width.
            shader.setFloatUniform("uMaskDepth", 2f*density);
            near[0] = far[0] = (iconStroke ? 1.2f : 2f)*density;
            near[1] = (iconStroke ? .65f : .4f) * (night ? .4f : 1f);
            far[1] = (iconStroke ? .35f : .2f) * (night ? .4f : 1f);
            ContourInnerLight.configure(shader,density,night);
            shader.setFloatUniform("uNearLineParams",near); shader.setFloatUniform("uFarLineParams",far);
            state.width=width; state.height=height; state.radius=radius; state.coverage=coverageCorner;
            state.density=density; state.night=night; state.iconStroke=iconStroke; state.ready=true;
        }
        paint.setShader(shader); paint.setAlpha(Math.round(255f*Math.max(0f, Math.min(1f, alpha))));
        int save = canvas.save(); canvas.translate(bounds.left, bounds.top);
        try { canvas.drawRect(0, 0, width, height, paint); }
        finally { canvas.restoreToCount(save); }
    }
    private float maskCoverageHeight(Bitmap mask, float width, float height) {
        if (coverageMask != mask || coverageGeneration!=mask.getGenerationId()) {
            int w=mask.getWidth(), h=mask.getHeight(); int[] pixels=new int[w*h];
            mask.getPixels(pixels,0,w,0,0,w,h);
            int left=w, right=-1, top=h, bottom=-1;
            for(int y=0;y<h;y++) for(int x=0;x<w;x++) if((pixels[y*w+x]>>>24)>=128) {
                left=Math.min(left,x); right=Math.max(right,x); top=Math.min(top,y); bottom=Math.max(bottom,y);
            }
            int leftTop=top, rightTop=top;
            if(right>=left) {
                while(leftTop<=bottom && (pixels[leftTop*w+left]>>>24)<128) leftTop++;
                while(rightTop<=bottom && (pixels[rightTop*w+right]>>>24)<128) rightTop++;
            }
            coverageHeight=Math.max(0,Math.min(h/2f,Math.max(leftTop,rightTop)-top))/(float)h;
            coverageMask=mask;
            coverageGeneration=mask.getGenerationId();
        }
        return Math.min(width/2f,coverageHeight*height);
    }
    private static final class UniformState {
        boolean ready, night, iconStroke;
        float width,height,radius,coverage,density;
    }
}
