package ink.hoka.odyssey.coreshaders;

import java.util.UUID;
import net.minestom.server.entity.Entity;

/**
 * Один эффект на карте. Источник правды — этот объект (хранится в module/coreshaders/effects/&lt;карта&gt;.json);
 * сущности (ItemDisplay и Interaction-хитбокс) — только его вид, они создаются, пока загружен чанк, и не сохраняются.
 */
final class Effect {
    final UUID id;
    EffectData data;
    /** Команды на эффекте; null — нет. */
    Actions.Data actions;
    Entity display;
    Entity hitbox;

    Effect(UUID id, EffectData data, Actions.Data actions) {
        this.id = id;
        this.data = data;
        this.actions = actions;
    }

    boolean hasActions() {
        return actions != null && !actions.actions().isEmpty();
    }

    long blockKey() {
        return EffectWorld.key(data.bx(), data.by(), data.bz());
    }

    long chunkKey() {
        return EffectWorld.chunkKey(data.bx() >> 4, data.bz() >> 4);
    }
}
