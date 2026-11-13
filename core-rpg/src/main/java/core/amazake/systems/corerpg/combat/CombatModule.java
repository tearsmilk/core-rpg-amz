package core.amazake.systems.corerpg.combat;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.CoreModule;
import core.amazake.systems.corerpg.CoreRpgSystem;

/** ЗАГЛУШКА (контракт). Модуль D заменяет тело, сохраняя сигнатуры публичных методов. */
public final class CombatModule implements CoreModule {

    private final CoreRpgSystem system;

    public CombatModule(final CoreRpgSystem system) {
        this.system = system;
    }

    @Override public void enable(final SystemContext context) { }
    @Override public void reload(final SystemContext context) { }
    @Override public void disable(final SystemContext context) { }
}
