package ink.hoka.odyssey.client.guns;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Свои звуки оружия у стрелка — сразу, без сервера: {@code hoka:<id>} (их раздаёт hosounds; файлы ставит
 * /hogun install). Без затухания и «у уха» (relative) — стерео-выстрел так и звучит, как в TACZ.
 */
final class Sfx {
    private static final RandomSource RND = RandomSource.create();

    private Sfx() {
    }

    static void play(String id, float volume, float pitch) {
        if (id == null || id.isEmpty() || volume <= 0.001f) return;
        Identifier key;
        try {
            key = Identifier.fromNamespaceAndPath("hoka", id);
        } catch (RuntimeException e) {
            return;
        }
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(key, SoundSource.PLAYERS, volume, pitch,
                RND, false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
    }
}
