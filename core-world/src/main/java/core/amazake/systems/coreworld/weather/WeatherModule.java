package core.amazake.systems.coreworld.weather;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreworld.module.WorldModule;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Модуль {@code weather}: дождя (и снегопада) и грозы нет. Начало дождя и грозы отменяется; уже идущий дождь
 * снимается при включении, reload и загрузке мира. Погода мира — глобальное состояние: трогаем её в глобальном потоке.
 * Команда /weather тоже не включит дождь (событие отменяется) — чтобы включить, выключите модуль в config.yml.
 */
public final class WeatherModule implements WorldModule, Listener {

    private final AtomicReference<WeatherSettings> settings = new AtomicReference<>(WeatherSettings.DEFAULTS);
    private volatile SystemContext context;

    @Override
    public String id() {
        return "weather";
    }

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        context.registerListener(this);
    }

    @Override
    public void configure(final ConfigurationSection config, final Consumer<String> warn) {
        this.settings.set(WeatherSettings.from(config, warn));
        Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), this::clearAll);
    }

    @Override
    public void disable() {
        // Дождь не возвращаем: погода дальше идёт сама по ванильным правилам.
    }

    @Override
    public boolean active() {
        return this.settings.get().rainDisabled();
    }

    @Override
    public List<String> status() {
        final WeatherSettings s = this.settings.get();
        return List.of(s.rainDisabled()
            ? "<gray>weather:</gray> <green>дождя нет</green> <gray>(" + s.worlds().describe() + ")</gray>"
            : "<gray>weather:</gray> <yellow>выключен, погода ванильная</yellow>");
    }

    private void clearAll() {
        for (final World world : Bukkit.getWorlds()) {
            this.clear(world);
        }
    }

    private void clear(final World world) {
        if (world.getEnvironment() != World.Environment.NORMAL || !this.settings.get().affects(world.getName())) {
            return;
        }
        if (world.hasStorm()) {
            world.setStorm(false);
        }
        if (world.isThundering()) {
            world.setThundering(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWeather(final WeatherChangeEvent event) {
        if (event.toWeatherState() && this.settings.get().affects(event.getWorld().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onThunder(final ThunderChangeEvent event) {
        if (event.toThunderState() && this.settings.get().affects(event.getWorld().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onWorldLoad(final WorldLoadEvent event) {
        Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> this.clear(event.getWorld()));
    }
}
