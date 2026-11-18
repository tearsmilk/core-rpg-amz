package core.amazake.systems.coreworld.regen;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreworld.module.WorldModule;
import io.papermc.paper.event.world.WorldGameRuleChangeEvent;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Модуль {@code regeneration}: выборочная блокировка пассивного восстановления HP игроков. Магическое лечение
 * (зелья, эффект «Регенерация», навыки, предметы) не трогается.
 *
 * <ul>
 *   <li>{@code regeneration.disable-natural} — нет ванильной естественной регенерации: во всех мирах выставляется
 *   правило {@code naturalRegeneration=false} (прежние значения запоминаются и возвращаются, когда ключ выключен или
 *   система выключается), включить правило обратно нельзя, пока ключ включён, а лечение игроков с причинами
 *   {@code SATIATED}/{@code REGEN} отменяется.</li>
 *   <li>{@code regeneration.disable-mmocore} — нет пассивной регенерации здоровья MMOCore. Обработчик события MMOCore
 *   подключается через reflection при включении системы, только если MMOCore загружен; если он нужен, а подключить
 *   его не удалось, система не включается (ошибка в консоли).</li>
 * </ul>
 */
// GameRule.NATURAL_REGENERATION в Paper 1.21.11 помечен на удаление, но замены в API пока нет.
@SuppressWarnings("removal")
public final class RegenModule implements WorldModule, Listener {

    /** Значение правила naturalRegeneration в каждом мире до того, как модуль его выключил: UID мира -> значение. */
    private final Map<UUID, Boolean> originalRules = new HashMap<>();
    private volatile boolean natural;
    private volatile boolean mmocore;
    /** Подключён ли обработчик события MMOCore. */
    private volatile boolean hooked;
    private SystemContext context;

    @Override
    public String id() {
        return "regeneration";
    }

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        context.registerListener(this);
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("MMOCore");
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        // MMOCore не в зависимостях сборки: событие и его методы находим через reflection.
        try {
            final Class<? extends Event> type = Class.forName("net.Indyuce.mmocore.api.event.PlayerResourceUpdateEvent",
                    true, plugin.getClass().getClassLoader())
                .asSubclass(Event.class);
            final Method resource = type.getMethod("getResource");
            final Method reason = type.getMethod("getReason");
            final Method difference = type.getMethod("getDifference");
            final Method cancel = type.getMethod("setCancelled", boolean.class);
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.HIGHEST, (listener, event) -> {
                if (!this.mmocore) {
                    return;
                }
                try {
                    if (RegenPolicy.passiveHealth(((Enum<?>) resource.invoke(event)).name(),
                        ((Enum<?>) reason.invoke(event)).name(), ((Number) difference.invoke(event)).doubleValue())) {
                        cancel.invoke(event, true);
                    }
                } catch (final ReflectiveOperationException e) {
                    throw new EventException(e);
                }
            }, context.plugin(), true);
            this.hooked = true;
            context.logger().info("MMOCore: подключена выборочная блокировка пассивной регенерации HP.");
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("MMOCore health-regeneration hook is incompatible", e);
        }
    }

    @Override
    public void configure(final ConfigurationSection config, final Consumer<String> warn) {
        final boolean next = config.getBoolean("regeneration.disable-natural", false);
        this.mmocore = config.getBoolean("regeneration.disable-mmocore", false);
        if (this.mmocore && !this.hooked) {
            // Блокировку MMOCore просили, а MMOCore нет (или он не включён): лучше не включиться, чем молча не работать.
            throw new IllegalStateException("MMOCore regeneration blocking requested but hook is unavailable");
        }
        this.natural = next;
        if (next) {
            for (final World world : Bukkit.getWorlds()) {
                this.apply(world);
            }
        } else {
            this.restore();
        }
    }

    /** Выключить naturalRegeneration в мире, запомнив прежнее значение (только первое). */
    private void apply(final World world) {
        if (!this.natural) {
            return;
        }
        this.originalRules.putIfAbsent(world.getUID(),
            Boolean.TRUE.equals(world.getGameRuleValue(GameRule.NATURAL_REGENERATION)));
        world.setGameRule(GameRule.NATURAL_REGENERATION, false);
    }

    /** Вернуть мирам запомненные значения naturalRegeneration. */
    private void restore() {
        for (final Map.Entry<UUID, Boolean> entry : this.originalRules.entrySet()) {
            final World world = Bukkit.getWorld(entry.getKey());
            if (world != null) {
                world.setGameRule(GameRule.NATURAL_REGENERATION, entry.getValue());
            }
        }
        this.originalRules.clear();
    }

    // --- события ---

    /** Ванильная регенерация игрока от сытости и мирного режима отменяется; остальное лечение проходит. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeal(final EntityRegainHealthEvent event) {
        if (this.natural && event.getEntity() instanceof Player
            && RegenPolicy.natural(event.getRegainReason().name())) {
            event.setCancelled(true);
        }
    }

    /** Пока ключ включён, /gamerule naturalRegeneration true не срабатывает. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRule(final WorldGameRuleChangeEvent event) {
        if (this.natural && event.getGameRule().equals(GameRule.NATURAL_REGENERATION)
            && Boolean.parseBoolean(event.getValue())) {
            event.setCancelled(true);
        }
    }

    /** Мир, загруженный позже, тоже получает naturalRegeneration=false. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(final WorldLoadEvent event) {
        this.apply(event.getWorld());
    }

    @Override
    public boolean active() {
        return this.natural || this.mmocore;
    }

    @Override
    public List<String> status() {
        return List.of("<gray>regeneration:</gray> естественная HP: " + (this.natural ? "выкл" : "разрешена")
            + "; пассивная MMOCore HP: " + (this.mmocore ? "выкл" : "разрешена")
            + "; hook: " + this.hooked);
    }

    @Override
    public void disable() {
        this.natural = false;
        this.mmocore = false;
        // Обработчик MMOCore зарегистрирован мимо ядра — снимаем сами.
        HandlerList.unregisterAll(this);
        this.restore();
    }
}
