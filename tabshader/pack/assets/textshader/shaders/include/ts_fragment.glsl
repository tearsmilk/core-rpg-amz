#version 330

#moj_import <textshader:ts_effects.glsl>

flat in int tsEffect;
flat in int tsPalette;
flat in int tsLetter;
flat in int tsShade;
in vec2 tsScreen;
in vec4 tsCornerA;
in vec4 tsCornerC;
in float tsWeightA;
in float tsWeightC;

vec4 ts_fragment(vec4 color, vec4 vertexColor, vec4 modulator) {
    if (tsEffect < 0) {
        return color;
    }
    tsAtlas = vec2(textureSize(Sampler0, 0));
    tsT = GameTime;
    tsSec = GameTime * 1200.0;
    tsIsShadow = tsShade == 1;
    tsIndex = float(tsLetter);
    tsFill = vertexColor;
    tsBack = vec4(0.0);
    tsTop = vec4(0.0);
    tsLookup = true;
    vec4 a = tsCornerA / max(tsWeightA, 1e-6);
    vec4 c = tsCornerC / max(tsWeightC, 1e-6);
    vec2 lo = min(a.xy, c.xy);
    vec2 hi = max(a.xy, c.xy);
    vec2 size = max(hi - lo, vec2(1e-6));
    tsUvMin = min(a.zw, c.zw);
    tsUvMax = max(a.zw, c.zw);
    tsUvMid = 0.5 * (tsUvMin + tsUvMax);
    tsSize = (tsUvMax - tsUvMin) * tsAtlas;
    tsLocal = vec2((tsScreen.x - lo.x) / size.x, (hi.y - tsScreen.y) / size.y);
    tsUv = tsUvMin + tsLocal * (tsUvMax - tsUvMin);
    vec2 scale = tsSize / size;
    tsPos = vec2(tsScreen.x, -tsScreen.y) * scale;
    tsChar = vec2(0.5 * (lo.x + hi.x), -0.5 * (lo.y + hi.y)) * scale;
    if (tsIsShadow) {
        tsPos -= vec2(1.0);
        tsChar -= vec2(1.0);
    }
    vec3 tone = TS_PALETTE[tsPalette];
    ts_apply(tsEffect, tone);
    if (tsPalette > 0 && TS_OWN[tsEffect]) {
        ts_tint(tone);
    }
    vec4 glyph = tsLookup && !ts_outside(tsUv) ? texture(Sampler0, tsUv) : vec4(0.0);
    vec4 result = mix(vec4(tsBack.rgb, tsBack.a * tsFill.a), glyph * tsFill, glyph.a);
    result.rgb = mix(result.rgb, tsTop.rgb, tsTop.a);
    result.a = max(result.a, tsTop.a * tsFill.a);
    return result * modulator;
}
