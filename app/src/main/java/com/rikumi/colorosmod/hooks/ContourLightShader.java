package com.rikumi.colorosmod.hooks;

import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.HashMap;

/** 复用 ColorOS 原生轮廓光着色器，仅绘制描边。 */
final class ContourLightShader {
    private static Class<?> strokeStrings, cornerStrings;
    private static final HashMap<Object, String> sources = new HashMap<>();
    static void init(ClassLoader loader) {
        strokeStrings = XposedHelpers.findClass("com.oplus.posteffect.agsl.BlurDrawableShaderStrokeStringKt", loader);
        cornerStrings = XposedHelpers.findClass("com.oplus.posteffect.agsl.BlurDrawableShaderCornerStringKt", loader);
    }
    static String source(Object type) {
        String cached = sources.get(type);
        if (cached != null) return cached;
        StringBuilder source = new StringBuilder("uniform float2 u_size; uniform float2 u_origin; uniform float u_corner; uniform float u_weight;\n");
        source.append(XposedHelpers.getStaticObjectField(strokeStrings, "BLUR_DRAWABLE_SHADER_STRING_HEAD_UNIFORMS_GRADIENT_STROKE"));
        XposedHelpers.callStaticMethod(cornerStrings, "appendCornerAlgorithmMethodString", source, type);
        XposedHelpers.callStaticMethod(strokeStrings, "appendGradientStrokeMethodString", source, type, false);
        source.append("vec4 main(vec2 coords) { coords -= u_origin;\n");
        XposedHelpers.callStaticMethod(strokeStrings, "appendGradientStrokeMainMethodHeadString", source, false);
        source.append("float alpha = strokeResult.color.a * antiAliasing; return vec4(strokeResult.color.rgb * alpha, alpha); }");
        cached = source.toString();
        sources.put(type, cached);
        return cached;
    }
}
