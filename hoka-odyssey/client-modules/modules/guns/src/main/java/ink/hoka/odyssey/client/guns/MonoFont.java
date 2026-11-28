package ink.hoka.odyssey.client.guns;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Моноширинный шрифт HUD (копия MonoFont модуля starmap): DejaVu Sans Mono (свободная лицензия, файл и лицензия — в jar модуля,
 * {@code assets/guns/font/}), буквы растеризуются stb_truetype (есть в LWJGL игры) прямо в нужном размере
 * в настоящих пикселях экрана — гладкие, без «пиксельного» масштабирования — и складываются в атлас-текстуру.
 * У каждой буквы — лёгкий ореол (свечение люминофора).
 * <p>
 * Почему не {@code Style.withFont}: шрифты игры грузятся только перезагрузкой ресурсов, а мод Hoka Odyssey её
 * нарочно не делает и не даёт модулям регистрировать шрифты. Нет шрифта или stb — тихо рисуется шрифтом игры
 * {@code minecraft:uniform}, растянутым до нужного размера.
 * <p>
 * Координаты — в настоящих пикселях (HUD патронов рисуется в них, см. {@link GunHud}).
 */
final class MonoFont {
    private static final String TTF = "/assets/guns/font/DejaVuSansMono.ttf";
    private static final int AW = 1024, AH = 1024;
    private static final int PAD = 3;
    private static final float HALO = 0.16f;
    private static final Style UNIFORM = Style.EMPTY.withFont(new FontDescription.Resource(Minecraft.UNIFORM_FONT));

    private record Glyph(int u, int v, int w, int h, int xoff, int yoff) {}

    private ByteBuffer ttf;
    private STBTTFontinfo info;
    private boolean ok;
    private int ascent, descent;
    private final Map<Long, Glyph> glyphs = new HashMap<>();
    private final GlTex atlas = new GlTex("font");
    private int shelfX = 1, shelfY = 1, shelfH;
    private boolean dirty;

    /** enabled = false — сразу шрифт игры (настройка mono_digits = false). */
    MonoFont(Logger log, boolean enabled) {
        if (!enabled) return;
        try (InputStream in = MonoFont.class.getResourceAsStream(TTF)) {
            if (in == null) throw new IllegalStateException("нет " + TTF);
            byte[] bytes = in.readAllBytes();
            ttf = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
            info = STBTTFontinfo.malloc();
            if (!STBTruetype.stbtt_InitFont(info, ttf)) throw new IllegalStateException("stbtt_InitFont");
            try (MemoryStack st = MemoryStack.stackPush()) {
                IntBuffer a = st.mallocInt(1), d = st.mallocInt(1), g = st.mallocInt(1);
                STBTruetype.stbtt_GetFontVMetrics(info, a, d, g);
                ascent = a.get(0);
                descent = d.get(0);
            }
            ok = true;
        } catch (Throwable e) {                                    // нет файла, нет stb — шрифт игры
            log.warn("guns: свой шрифт не загрузился ({}) — цифры будут шрифтом игры", e.toString());
            free();
        }
    }

    boolean ok() {
        return ok;
    }

    private float scale(int px) {
        return STBTruetype.stbtt_ScaleForMappingEmToPixels(info, px);
    }

    /** Ширина одной буквы (шрифт моноширинный) размера px. */
    float advance(int px) {
        if (!ok) return Minecraft.getInstance().font.width(Component.literal("0").withStyle(UNIFORM)) * px / 9f;
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer adv = st.mallocInt(1), lsb = st.mallocInt(1);
            STBTruetype.stbtt_GetCodepointHMetrics(info, '0', adv, lsb);
            return adv.get(0) * scale(px);
        }
    }

    int width(String s, int px) {
        if (!ok) {
            return Math.round(Minecraft.getInstance().font.width(Component.literal(s).withStyle(UNIFORM)) * px / 9f);
        }
        return Math.round(s.codePointCount(0, s.length()) * advance(px));
    }

    /** Высота строки (от верха до низа букв с хвостиками). */
    int lineHeight(int px) {
        return ok ? Math.round((ascent - descent) * scale(px)) : Math.round(px * 1.15f);
    }

    /** Текст s, верхний левый угол строки — (x, y), цвет argb. Возвращает ширину. */
    int draw(GuiGraphics g, String s, int x, int y, int px, int argb) {
        if (((argb >>> 24) & 255) < 4 || s.isEmpty()) return width(s, px);
        if (!ok) {
            float k = px / 9f;
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(x, y + px * 0.1f);
            pose.scale(k, k);
            g.drawString(Minecraft.getInstance().font, Component.literal(s).withStyle(UNIFORM), 0, 0, argb, false);
            pose.popMatrix();
            return width(s, px);
        }
        float sc = scale(px);
        float adv = advance(px);
        int base = y + Math.round(ascent * sc);
        float pen = x;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp != ' ') {
                Glyph gl = glyph(cp, px, sc);
                if (gl != null && gl.w > 0) {
                    g.blit(RenderPipelines.GUI_TEXTURED, atlas.id, Math.round(pen) + gl.xoff, base + gl.yoff,
                            gl.u, gl.v, gl.w, gl.h, AW, AH, argb);
                }
            }
            pen += adv;
        }
        return Math.round(pen - x);
    }

    /** Отправить новые буквы на видеокарту (раз в кадр, до того как кадр нарисуется). */
    void flush() {
        if (dirty && atlas.ready()) {
            atlas.upload();
            dirty = false;
        }
    }

    private Glyph glyph(int cp, int px, float sc) {
        long key = ((long) px << 32) | cp;
        Glyph gl = glyphs.get(key);
        if (gl != null) return gl;
        if (STBTruetype.stbtt_FindGlyphIndex(info, cp) == 0 && cp != '?') {
            gl = glyph('?', px, sc);                                    // буквы нет в шрифте
            glyphs.put(key, gl);
            return gl;
        }
        atlas.ensure(AW, AH);
        int x0, y0, gw, gh;
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer a = st.mallocInt(1), b = st.mallocInt(1), c = st.mallocInt(1), d = st.mallocInt(1);
            STBTruetype.stbtt_GetCodepointBitmapBox(info, cp, sc, sc, a, b, c, d);
            x0 = a.get(0);
            y0 = b.get(0);
            gw = c.get(0) - x0;
            gh = d.get(0) - y0;
        }
        if (gw <= 0 || gh <= 0) {
            gl = new Glyph(0, 0, 0, 0, 0, 0);
            glyphs.put(key, gl);
            return gl;
        }
        int cw = gw + 2 * PAD, ch = gh + 2 * PAD;
        if (shelfX + cw + 1 > AW) {
            shelfX = 1;
            shelfY += shelfH + 1;
            shelfH = 0;
        }
        if (shelfY + ch + 1 > AH) {                                     // атлас полон — начать заново
            glyphs.clear();
            atlas.img().fillRect(0, 0, AW, AH, 0);
            shelfX = 1;
            shelfY = 1;
            shelfH = 0;
        }
        ByteBuffer bmp = MemoryUtil.memAlloc(gw * gh);
        try {
            STBTruetype.stbtt_MakeCodepointBitmap(info, bmp, gw, gh, gw, sc, sc, cp);
            float[] cov = new float[cw * ch];
            for (int yy = 0; yy < gh; yy++) {
                for (int xx = 0; xx < gw; xx++) cov[(yy + PAD) * cw + xx + PAD] = (bmp.get(yy * gw + xx) & 255) / 255f;
            }
            float[] halo = blur(blur(cov, cw, ch), cw, ch);
            NativeImage img = atlas.img();
            for (int yy = 0; yy < ch; yy++) {
                for (int xx = 0; xx < cw; xx++) {
                    int i = yy * cw + xx;
                    float al = Math.max(cov[i], Math.min(1f, halo[i] * 2.2f) * HALO);
                    int ai = Math.min(255, Math.round(al * 255));
                    img.setPixel(shelfX + xx, shelfY + yy, ai << 24 | 0xFFFFFF);
                }
            }
        } finally {
            MemoryUtil.memFree(bmp);
        }
        gl = new Glyph(shelfX, shelfY, cw, ch, x0 - PAD, y0 - PAD);
        shelfX += cw + 1;
        shelfH = Math.max(shelfH, ch);
        glyphs.put(key, gl);
        dirty = true;
        return gl;
    }

    /** Размытие окном 3×3 (для ореола). */
    private static float[] blur(float[] src, int w, int h) {
        float[] tmp = new float[src.length], out = new float[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int k = -1; k <= 1; k++) {
                    int xx = x + k;
                    if (xx >= 0 && xx < w) s += src[y * w + xx];
                }
                tmp[y * w + x] = s / 3f;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int k = -1; k <= 1; k++) {
                    int yy = y + k;
                    if (yy >= 0 && yy < h) s += tmp[yy * w + x];
                }
                out[y * w + x] = s / 3f;
            }
        }
        return out;
    }

    private void free() {
        ok = false;
        if (info != null) {
            info.free();
            info = null;
        }
        if (ttf != null) {
            MemoryUtil.memFree(ttf);
            ttf = null;
        }
    }

    void close() {
        atlas.close();
        glyphs.clear();
        shelfX = shelfY = 1;
        shelfH = 0;
        free();
    }
}
