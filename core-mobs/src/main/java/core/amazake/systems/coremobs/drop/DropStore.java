package core.amazake.systems.coremobs.drop;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coremobs.util.IntRange;
import core.amazake.systems.coremobs.util.Text;
import core.amazake.systems.coremobs.util.YamlFiles;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.random.RandomGenerator;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * Лут мобов: у каждого моба свой файл {@code drops/<моб>.yml}. Предметы сохраняются со всеми NBT, шанс и
 * количество правятся руками в файле, потом {@code /mobs reload}.
 */
public final class DropStore {

    static final List<String> HEADER = List.of(
        "Лут моба. Предметы сохраняет команда /mobs drops save|add <моб> из инвентаря, со всеми NBT.",
        "chance — шанс выпадения в процентах (0..100), можно дробный: 0.5",
        "amount — сколько выпадает: число (3) или диапазон (1-4)",
        "preview — подпись для вас, на предмет не влияет. item — сам предмет, его не меняйте.",
        "После правки: /mobs reload");

    private final SystemContext context;
    private final Path folder;
    private final AtomicReference<Map<String, List<DropEntry>>> drops = new AtomicReference<>(Map.of());
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final Object ioLock = new Object();

    public DropStore(final SystemContext context) {
        this.context = context;
        this.folder = context.dataFolder().resolve("drops");
    }

    public List<DropEntry> get(final String mob) {
        return this.drops.get().getOrDefault(mob, List.of());
    }

    /** Перечитать все файлы лута. Нужен сервер: предметы восстанавливаются из байтов. */
    public void load() {
        synchronized (this.ioLock) {
            final Map<String, List<DropEntry>> out = new HashMap<>();
            if (Files.isDirectory(this.folder)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(this.folder, "*.yml")) {
                    for (final Path file : files) {
                        final String name = file.getFileName().toString();
                        final String mob = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                        try {
                            out.put(mob, List.copyOf(this.read(YamlFiles.read(file), name)));
                        } catch (final Exception e) {
                            this.context.logger().log(Level.WARNING, "drops/" + name + " не прочитан", e);
                        }
                    }
                } catch (final IOException e) {
                    this.context.logger().log(Level.WARNING, "Папка drops не прочитана", e);
                }
            }
            this.drops.set(Collections.unmodifiableMap(out));
        }
    }

    private List<DropEntry> read(final YamlConfiguration yaml, final String file) {
        final List<DropEntry> out = new ArrayList<>();
        final ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items == null) {
            return out;
        }
        for (final String key : items.getKeys(false)) {
            final ConfigurationSection s = items.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            final String where = "drops/" + file + ", items." + key + ": ";
            final String data = s.getString("item");
            if (data == null || data.isBlank()) {
                this.context.logger().warning(where + "нет item");
                continue;
            }
            final ItemStack item;
            try {
                item = ItemStack.deserializeBytes(Base64.getDecoder().decode(data.trim()));
            } catch (final RuntimeException e) {
                this.context.logger().warning(where + "предмет не читается (" + e.getMessage() + ")");
                continue;
            }
            double chance = s.getDouble("chance", 100.0);
            if (!(chance >= 0 && chance <= 100)) {
                this.context.logger().warning(where + "chance " + chance + " вне 0..100, беру 100");
                chance = 100.0;
            }
            IntRange amount = IntRange.parse(s.getString("amount", Integer.toString(item.getAmount())));
            if (amount == null || amount.min() < 0 || amount.max() > 6400) {
                this.context.logger().warning(where + "amount «" + s.getString("amount") + "» — нужно число или диапазон 1-4");
                amount = IntRange.of(item.getAmount());
            }
            out.add(new DropEntry(item, data.trim(), s.getString("preview", preview(item)), chance, amount));
        }
        return out;
    }

    /** Новые записи лута из предметов: шанс 100%, количество — как в стаке. Поток игрока, чьи это предметы. */
    public static List<DropEntry> entriesFrom(final List<ItemStack> items) {
        final List<DropEntry> out = new ArrayList<>();
        for (final ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final ItemStack copy = stack.clone();
            final String data = Base64.getEncoder().encodeToString(copy.serializeAsBytes());
            out.add(new DropEntry(copy, data, preview(copy), 100.0, IntRange.of(copy.getAmount())));
        }
        return out;
    }

    /** Заменить или дополнить лут моба и записать его файл. */
    public void change(final String mob, final UnaryOperator<List<DropEntry>> change) {
        this.drops.updateAndGet(old -> {
            final Map<String, List<DropEntry>> copy = new HashMap<>(old);
            copy.put(mob, List.copyOf(change.apply(new ArrayList<>(old.getOrDefault(mob, List.of())))));
            return Collections.unmodifiableMap(copy);
        });
        this.dirty.add(mob);
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), task -> this.saveNow());
    }

    /** Записать изменённые файлы лута. Любой поток; в disable — синхронно. */
    public void saveNow() {
        synchronized (this.ioLock) {
            for (final String mob : List.copyOf(this.dirty)) {
                this.dirty.remove(mob);
                final Path file = this.folder.resolve(mob + ".yml");
                try {
                    YamlFiles.write(file, write(this.get(mob)));
                } catch (final IOException e) {
                    this.dirty.add(mob);
                    this.context.logger().log(Level.WARNING, "Не удалось сохранить drops/" + mob + ".yml", e);
                }
            }
        }
    }

    static YamlConfiguration write(final List<DropEntry> entries) {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(HEADER);
        yaml.createSection("items");
        for (int i = 0; i < entries.size(); i++) {
            final DropEntry e = entries.get(i);
            final String p = "items." + (i + 1) + ".";
            yaml.set(p + "preview", e.preview());
            yaml.set(p + "chance", e.chance());
            yaml.set(p + "amount", e.amount().format());
            yaml.set(p + "item", e.data());
        }
        return yaml;
    }

    /**
     * Что выпало с моба: каждая запись проверяет свой шанс отдельно, количество бьётся на стаки по размеру предмета.
     */
    public List<ItemStack> roll(final String mob, final RandomGenerator random) {
        final List<ItemStack> out = new ArrayList<>();
        for (final DropEntry e : this.get(mob)) {
            if (e.chance() < 100.0 && random.nextDouble() * 100.0 >= e.chance()) {
                continue;
            }
            addStacks(out, e.item(), e.amount().roll(random));
        }
        return out;
    }

    /** {@code amount} штук предмета, разбитых на стаки. */
    public static void addStacks(final List<ItemStack> out, final ItemStack template, final int amount) {
        final int max = Math.max(1, template.getMaxStackSize());
        int left = amount;
        while (left > 0) {
            final ItemStack stack = template.clone();
            final int n = Math.min(max, left);
            stack.setAmount(n);
            out.add(stack);
            left -= n;
        }
    }

    static String preview(final ItemStack item) {
        final String type = item.getType().getKey().getKey();
        final String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
            ? " «" + Text.plain(item.getItemMeta().displayName()) + "»" : "";
        return type + " x" + item.getAmount() + name;
    }
}
