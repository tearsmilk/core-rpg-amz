package ink.hoka.odyssey.client.guns;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Вид в оптику (AWP): весь экран чёрный, кроме круга прицела по центру — в нём мир (приближение делает модуль через
 * угол обзора) и сетка прицела TACZ ({@link Reticle}: маска-круг, штрихи, точка — из модели прицела). Рисуется в
 * настоящих пикселях экрана (масштаб GUI снят), поверх мира и под остальным HUD. cover 0..1 — плавное появление в
 * конце сведения оружия к глазу.
 */
final class ScopeOverlay {
    private ScopeOverlay() {
    }

    /** reticle null — модели прицела нет (или ещё не пришла): просто чёрное вокруг круга. size — доля высоты экрана. */
    static void render(GuiGraphics g, Reticle reticle, float cover, float size) {
        Window win = Minecraft.getInstance().getWindow();
        int gs = Math.max(1, win.getGuiScale());
        int fbW = win.getWidth(), fbH = win.getHeight();
        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(1f / gs, 1f / gs);
        try {
            int d = Math.max(16, Math.round(fbH * size));
            int x0 = fbW / 2 - d / 2, y0 = fbH / 2 - d / 2;
            int black = GunHud.a(0xFF000000, cover);
            g.fill(0, 0, fbW, Math.max(0, y0), black);
            g.fill(0, y0 + d, fbW, fbH, black);
            g.fill(0, y0, Math.max(0, x0), y0 + d, black);
            g.fill(x0 + d, y0, fbW, y0 + d, black);
            if (reticle != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, reticle.texture(d), x0, y0, 0f, 0f, d, d, d, d, GunHud.a(0xFFFFFFFF, cover));
            } else {
                circleMask(g, fbW / 2, fbH / 2, d / 2, y0, d, black);
            }
        } finally {
            pose.popMatrix();
        }
    }

    /** Без сетки: чёрное внутри квадрата, но вне круга радиуса r (полосами по строкам). */
    private static void circleMask(GuiGraphics g, int cx, int cy, int r, int y0, int d, int color) {
        for (int y = y0; y < y0 + d; y++) {
            double dy = y + 0.5 - cy;
            int half = dy * dy >= (double) r * r ? r : (int) Math.round(Math.sqrt((double) r * r - dy * dy));
            g.fill(cx - r, y, cx - half, y + 1, color);
            g.fill(cx + half, y, cx + r, y + 1, color);
        }
    }
}
