package com.rikumi.colorosmod;

import android.graphics.RuntimeShader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Retain a chosen fraction of the native background material's saturation increase. */
public final class TileBackgroundEnhancement {
    public static final String ENABLED = "qs_bg_enhancement_enabled";
    public static final String VALUE = "qs_bg_enhancement_percent";
    public static final String NOTIFICATION_ENABLED = "notification_bg_enhancement_enabled";
    public static final String NOTIFICATION_VALUE = "notification_bg_enhancement_percent";
    public static final int DEFAULT = 50;
    public static final String EFFECT = "colorosmodTileSaturationV1";
    private static final String UNIFORM = "u_colorosmodTileEnhancement";
    private static final String FUNCTIONS = """
            float3 cmTileRgbToHsv(float3 c) {
                float4 K = float4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
                float4 p = mix(float4(c.bg, K.wz), float4(c.gb, K.xy), step(c.b, c.g));
                float4 q = mix(float4(p.xyw, c.r), float4(c.r, p.yzx), step(p.x, c.r));
                float d = q.x - min(q.w, q.y);
                return float3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-10)),
                              d / (q.x + 1e-10), q.x);
            }
            float3 cmTileHsvToRgb(float3 c) {
                float4 K = float4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
                float3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
            }
            """;
    private static final String CORRECTION = """
            float3 cmTileSample = cmTileRgbToHsv(clamp(float3(uniBDFBitmap.eval(mapCoords).rgb), 0.0, 1.0));
            float3 cmTileMixed = cmTileRgbToHsv(clamp(float3(outputCol.rgb), 0.0, 1.0));
            cmTileMixed.y = mix(min(cmTileSample.y, cmTileMixed.y), cmTileMixed.y,
                                u_colorosmodTileEnhancement);
            outputCol.rgb = half3(cmTileHsvToRgb(cmTileMixed));
            """;

    public static int clamp(int percent) { return Math.max(0, Math.min(100, percent)); }

    public static Object createEffect(Class<?> effectInterface, int percent) {
        return Proxy.newProxyInstance(effectInterface.getClassLoader(), new Class<?>[]{effectInterface},
                new SaturationEffect(effectInterface, percent));
    }

    public static boolean updateEffect(Object effect, int percent) {
        if (!Proxy.isProxyClass(effect.getClass())) return false;
        InvocationHandler handler = Proxy.getInvocationHandler(effect);
        if (!(handler instanceof SaturationEffect saturation)) return false;
        saturation.percent = clamp(percent);
        return true;
    }

    private static final class SaturationEffect implements InvocationHandler {
        private final Class<?> effectInterface;
        private int percent;
        SaturationEffect(Class<?> effectInterface, int percent) {
            this.effectInterface = effectInterface;
            this.percent = clamp(percent);
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getEffectName", "getStructuralKey", "toString" -> EFFECT;
                // Correct the material before native inner shadows, optics and strokes.
                case "getOrder" -> 0;
                case "getUniformStateKey" -> percent;
                case "isEnabled" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "createCopy" -> createEffect(effectInterface, percent);
                case "copyFrom" -> {
                    InvocationHandler other = Proxy.getInvocationHandler(args[0]);
                    if (other instanceof SaturationEffect saturation) percent = saturation.percent;
                    yield null;
                }
                case "appendHeaderUniforms" -> {
                    ((StringBuilder) args[0]).append("uniform float " + UNIFORM + ";\n");
                    yield null;
                }
                case "appendMethodDefinitions" -> {
                    ((StringBuilder) args[0]).append(FUNCTIONS);
                    yield null;
                }
                case "appendMainAfterPublicMainMethod" -> {
                    ((StringBuilder) args[0]).append(CORRECTION);
                    yield null;
                }
                case "pushUniforms" -> {
                    ((RuntimeShader) args[0]).setFloatUniform(UNIFORM, percent / 100f);
                    yield null;
                }
                default -> null;
            };
        }
    }

    private TileBackgroundEnhancement() {}
}
