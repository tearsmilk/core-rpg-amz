package core.amazake.systems.corerpg.sharpen;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.CoreModule;
import core.amazake.systems.corerpg.CoreRpgSystem;
import org.bukkit.inventory.ItemStack;

/** ЗАГЛУШКА (контракт). Модуль B заменяет тело, сохраняя сигнатуры публичных методов. */
public final class SharpenModule implements CoreModule {

    private final CoreRpgSystem system;

    public SharpenModule(final CoreRpgSystem system) {
        this.system = system;
    }

    @Override public void enable(final SystemContext context) { }
    @Override public void reload(final SystemContext context) { }
    @Override public void disable(final SystemContext context) { }

    /** Уровень заточки предмета (0, если нет; читает и старый ключ zatochka:sharpen_level). Любой поток. */
    public int level(final ItemStack item) { return 0; }

    /** Бонус заточки в процентах: к атаке для оружия, к защите для брони и щита. 0 для прочего. Любой поток. */
    public double bonusPercent(final ItemStack item) { return 0.0; }

    /** Максимальная заточка для предмета (по грейду), 0 если не точится. */
    public int maxLevel(final ItemStack item) { return 0; }
}
