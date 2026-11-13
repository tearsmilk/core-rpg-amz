package core.amazake.systems.corerpg.seals;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.CoreModule;
import core.amazake.systems.corerpg.CoreRpgSystem;
import org.bukkit.inventory.ItemStack;

/** ЗАГЛУШКА (контракт). Модуль C заменяет тело, сохраняя сигнатуры публичных методов. */
public final class SealsModule implements CoreModule {

    private final CoreRpgSystem system;

    public SealsModule(final CoreRpgSystem system) {
        this.system = system;
    }

    @Override public void enable(final SystemContext context) { }
    @Override public void reload(final SystemContext context) { }
    @Override public void disable(final SystemContext context) { }

    /** Название печати на предмете («Агрессии») или "" без печати. Любой поток. */
    public String sealName(final ItemStack item) { return ""; }

    /** Цвет печати по-русски («Фиолетовая») или "". Любой поток. */
    public String sealColorName(final ItemStack item) { return ""; }

    /** Число печатей в каталоге (включённых и выключенных). */
    public int catalogSize() { return 0; }
}
