package core.amazake.systems.coremobs.region;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coremobs.util.YamlFiles;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import org.bukkit.Bukkit;

/**
 * Регионы в памяти (неизменяемый снимок, подменяется целиком) и в regions.yml.
 *
 * <p>Запись — в асинхронном потоке, под одним замком и всегда из текущего снимка: поздняя запись не может
 * затереть файл старыми данными.</p>
 */
public final class RegionStore {

    private final SystemContext context;
    private final Path file;
    private final AtomicReference<Map<String, Region>> regions = new AtomicReference<>(Map.of());
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Object ioLock = new Object();

    public RegionStore(final SystemContext context) {
        this.context = context;
        this.file = context.dataFolder().resolve("regions.yml");
    }

    /** Перечитать файл (enable, reload). Поток вызывающего; файл маленький. */
    public void load() {
        synchronized (this.ioLock) {
            try {
                final Map<String, Region> read = RegionCodec.read(YamlFiles.read(this.file),
                    w -> this.context.logger().warning("regions.yml: " + w));
                this.regions.set(Collections.unmodifiableMap(read));
            } catch (final Exception e) {
                this.context.logger().log(Level.WARNING, "regions.yml не прочитан, регионы оставлены прежними", e);
            }
        }
    }

    public Region get(final String name) {
        return name == null ? null : this.regions.get().get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Region> all() {
        return this.regions.get().values();
    }

    public Map<String, Region> snapshot() {
        return this.regions.get();
    }

    /** Добавить или заменить регион и сохранить файл. */
    public void put(final Region region) {
        this.mutate(map -> {
            map.put(region.name(), region);
            return map;
        });
    }

    /** Изменить регион, если он есть; null — региона нет. */
    public Region update(final String name, final UnaryOperator<Region> change) {
        final Region[] result = new Region[1];
        this.mutate(map -> {
            final Region old = map.get(name);
            if (old != null) {
                result[0] = change.apply(old);
                map.put(name, result[0]);
            }
            return map;
        });
        return result[0];
    }

    public boolean remove(final String name) {
        final boolean[] removed = new boolean[1];
        this.mutate(map -> {
            removed[0] = map.remove(name) != null;
            return map;
        });
        return removed[0];
    }

    private void mutate(final UnaryOperator<Map<String, Region>> change) {
        this.regions.updateAndGet(old -> Collections.unmodifiableMap(change.apply(new LinkedHashMap<>(old))));
        this.dirty.set(true);
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), task -> this.saveNow());
    }

    /** Записать файл, если есть несохранённые изменения. Любой поток; в disable — синхронно. */
    public void saveNow() {
        synchronized (this.ioLock) {
            if (!this.dirty.getAndSet(false)) {
                return;
            }
            try {
                YamlFiles.write(this.file, RegionCodec.write(this.regions.get().values()));
            } catch (final Exception e) {
                this.dirty.set(true);
                this.context.logger().log(Level.WARNING, "Не удалось сохранить regions.yml", e);
            }
        }
    }
}
