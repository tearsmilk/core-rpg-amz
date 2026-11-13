package core.amazake.systems.corerpg.aspect;

import core.amazake.systems.corerpg.profile.PlayerProfile;
import org.bukkit.entity.Player;

/**
 * Источник аспектов: бонусы класса, печати на экипировке, заточка, формулы параметров. Вызывается в потоке игрока при
 * пересчёте ({@link AspectService#refresh}); вкладчики идут по возрастанию {@link #order()}.
 *
 * <p>Порядок: 0 — бонусы класса/профессии, печати, заточка (добавляют сырые аспекты, в том числе stat_*);
 * 100 — формулы параметров (превращают очки профиля + stat_* в урон, здоровье, крит…); 200 — закрытые аспекты
 * профессии (обнуление) и потолки.</p>
 */
public interface AspectContributor {

    void contribute(Player player, PlayerProfile profile, AspectSnapshot.Builder out);

    default int order() {
        return 0;
    }
}
