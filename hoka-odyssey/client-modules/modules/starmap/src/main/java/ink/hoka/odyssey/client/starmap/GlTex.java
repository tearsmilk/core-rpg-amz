package ink.hoka.odyssey.client.starmap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Текстура радара, которую модуль рисует сам (кольца, развёртка, блоки, буквы шрифта): картинка в памяти +
 * текстура на видеокарте со сглаживанием (LINEAR, края — clamp), чтобы поворот и масштаб были мягкими, без «лесенки».
 * Пересоздаётся при смене размера ({@link #ensure}); {@link #close} отдаёт память.
 */
final class GlTex {
    private static final class Tex extends DynamicTexture {
        Tex(String name, int w, int h) {
            super(() -> name, w, h, true);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

    final Identifier id;
    private final String name;
    private Tex tex;
    int w, h;

    GlTex(String path) {
        id = Identifier.fromNamespaceAndPath("hoka_starmap", path);
        name = "hoka starmap " + path;
    }

    /** Текстура нужного размера (новая — прозрачная). true — пересоздана. */
    boolean ensure(int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        if (tex != null && this.w == w && this.h == h) return false;
        close();
        tex = new Tex(name, w, h);
        Minecraft.getInstance().getTextureManager().register(id, tex);
        this.w = w;
        this.h = h;
        return true;
    }

    boolean ready() {
        return tex != null;
    }

    NativeImage img() {
        return tex.getPixels();
    }

    void upload() {
        if (tex != null) tex.upload();
    }

    void close() {
        if (tex != null) {
            Minecraft.getInstance().getTextureManager().release(id);   // закрывает и текстуру, и картинку
            tex = null;
            w = h = 0;
        }
    }
}
