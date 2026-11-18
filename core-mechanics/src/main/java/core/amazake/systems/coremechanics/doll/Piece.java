package core.amazake.systems.coremechanics.doll;

import org.bukkit.Material;
import org.joml.Vector3f;

/**
 * Кусок отрисовки куклы: к какой части прикреплён, смещение центра в осях части, размер. Голова-предмет (head=true):
 * player_head с текстурой (value/signature); иначе block_display блока {@code block}. Неизменяемый (векторы не трогать).
 */
public record Piece(int part, Vector3f off, Vector3f size, boolean head, String texture, String signature, Material block) {

    public static Piece head(final int part, final Vector3f off, final Vector3f scale, final String texture, final String signature) {
        return new Piece(part, off, scale, true, texture, signature, null);
    }

    public static Piece block(final int part, final Vector3f off, final Vector3f size, final Material block) {
        return new Piece(part, off, size, false, null, null, block);
    }

    /** Для /summon: «head:&lt;текстура&gt;» или «block:&lt;ключ&gt;». */
    public String look() {
        return this.head ? "head:" + (this.texture == null ? "" : this.texture) : "block:" + this.block.getKey();
    }
}
