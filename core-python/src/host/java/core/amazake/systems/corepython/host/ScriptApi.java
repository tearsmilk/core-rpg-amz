package core.amazake.systems.corepython.host;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.graalvm.polyglot.Value;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Объект хоста, который видит модуль amazake (amazake.py) как {@code polyglot.import_value("amazake_host")}.
 * Скрипты обычно зовут не его, а функции amazake.py — там же документация.
 */
public final class ScriptApi {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Gson GSON = new Gson();
    /** Пакеты, в которых ищутся события по короткому имени: on("PlayerJoinEvent"). */
    private static final List<String> EVENT_PACKAGES = List.of(
        "org.bukkit.event.player.", "org.bukkit.event.entity.", "org.bukkit.event.block.", "org.bukkit.event.inventory.",
        "org.bukkit.event.server.", "org.bukkit.event.world.", "org.bukkit.event.weather.", "org.bukkit.event.vehicle.",
        "org.bukkit.event.hanging.", "org.bukkit.event.enchantment.", "org.bukkit.event.raid.",
        "com.destroystokyo.paper.event.player.", "com.destroystokyo.paper.event.entity.", "com.destroystokyo.paper.event.block.",
        "com.destroystokyo.paper.event.server.", "io.papermc.paper.event.player.", "io.papermc.paper.event.entity.",
        "io.papermc.paper.event.block.", "io.papermc.paper.event.server.", "io.papermc.paper.event.world.",
        "io.papermc.paper.event.packet.");

    private final PyScript script;
    private final String namespace;

    ScriptApi(final PyScript script) {
        this.script = script;
        this.namespace = "py_" + script.name.replace('-', '_');
    }

    // ------------------------------------------------------------------------------------------
    // Сведения
    // ------------------------------------------------------------------------------------------

    public String name() {
        return this.script.name;
    }

    public String version() {
        return this.script.manifest.version();
    }

    public String scriptFolder() {
        return this.script.dir.toAbsolutePath().toString();
    }

    public String dataFolder() {
        try {
            Files.createDirectories(this.script.dataDir);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return this.script.dataDir.toAbsolutePath().toString();
    }

    /** Свойства команды из manifest.yml (или null): [permission, aliases, description, usage]. */
    public Object[] manifestCommand(final String name) {
        final Manifest.CommandSpec c = this.script.manifest.commands().get(name);
        return c == null ? null : new Object[]{c.permission(), c.aliases().toArray(String[]::new), c.description(), c.usage()};
    }

    public void log(final String level, final String message) {
        final Level l = switch (level.toLowerCase(Locale.ROOT)) {
            case "warn", "warning" -> Level.WARNING;
            case "error", "severe" -> Level.SEVERE;
            case "debug", "fine" -> Level.FINE;
            default -> Level.INFO;
        };
        this.script.platform.logger().log(l, "[py:" + this.script.name + "] " + message);
    }

    // ------------------------------------------------------------------------------------------
    // События и команды
    // ------------------------------------------------------------------------------------------

    /** Класс по имени (сервер, потом плагины) или по значению java.type(...). */
    public Class<?> resolveClass(final Value type) throws ClassNotFoundException {
        if (type.isHostObject() && type.asHostObject() instanceof Class<?> c) {
            return c;
        }
        if (type.isMetaObject()) {
            return this.script.platform.findClass(type.getMetaQualifiedName());
        }
        if (type.isString()) {
            return this.script.platform.findClass(type.asString());
        }
        throw new IllegalArgumentException("ожидался класс Java или его имя, получено " + type);
    }

    @SuppressWarnings("unchecked")
    public void listen(final Value type, final String priority, final boolean ignoreCancelled, final Value fn) throws ClassNotFoundException {
        Class<?> cls = null;
        if (type.isString() && !type.asString().contains(".")) {
            for (final String pkg : EVENT_PACKAGES) {
                try {
                    cls = this.script.platform.findClass(pkg + type.asString());
                    break;
                } catch (final ClassNotFoundException ignored) {
                    // следующий пакет
                }
            }
            if (cls == null) {
                throw new ClassNotFoundException("событие " + type.asString() + " не найдено — укажите полное имя класса");
            }
        } else {
            cls = this.resolveClass(type);
        }
        if (!Event.class.isAssignableFrom(cls)) {
            throw new IllegalArgumentException(cls.getName() + " — не событие Bukkit");
        }
        final Class<? extends Event> eventType = (Class<? extends Event>) cls;
        final EventPriority p = EventPriority.valueOf(priority.toUpperCase(Locale.ROOT));
        final String where = "обработчике " + eventType.getSimpleName();
        this.script.whenActive(() -> {
            this.script.platform.registerEvent(eventType, this.script.owner, p, (listener, event) -> {
                if (eventType.isInstance(event)) {
                    this.script.call(where, fn, event);
                }
            }, ignoreCancelled);
            this.script.listeners.incrementAndGet();
        });
    }

    public PyCommand command(final String name, final String permission, final Value aliases, final String description,
                             final String usage, final Value fn) {
        final List<String> al = new ArrayList<>();
        if (aliases != null && aliases.hasArrayElements()) {
            for (long i = 0; i < aliases.getArraySize(); i++) {
                al.add(aliases.getArrayElement(i).asString());
            }
        }
        final PyCommand c = new PyCommand(this.script, name, description, usage, al, permission, fn);
        synchronized (this.script) {
            switch (this.script.state) {
                case LOADING -> this.script.commands.add(c);
                case ACTIVE -> {
                    // Объявлена после загрузки (из on_load или позже): регистрируем сразу.
                    this.script.commands.add(c);
                    this.script.platform.registerCommands(this.script.prefix(), List.of(c));
                }
                default -> throw new IllegalStateException("система " + this.script.name + " выгружается");
            }
        }
        return c;
    }

    public void onLoad(final Value fn) {
        this.script.loadHooks.add(fn);
    }

    public void onUnload(final Value fn) {
        this.script.unloadHooks.add(fn);
    }

    // ------------------------------------------------------------------------------------------
    // Потоки
    // ------------------------------------------------------------------------------------------

    private Runnable body(final String where, final Value fn, final Tasks.Owned handle, final boolean repeating) {
        return () -> {
            if (handle.isCancelled()) {
                handle.cancel();
                return;
            }
            final Value r = this.script.call(where, fn);
            if (!repeating) {
                handle.finished();
            } else if (r != null && r.isBoolean() && !r.asBoolean()) {
                handle.cancel();
            }
        };
    }

    public Task runForPlayer(final Player player, final Value fn, final long periodTicks) {
        final Tasks.Owned h = new Tasks.Owned(this.script);
        this.script.track(h);
        h.bind(this.script.platform.runForPlayer(player, this.body("задаче игрока", fn, h, true), periodTicks));
        return h;
    }

    public Task runOnceForPlayer(final Player player, final Value fn, final long delayTicks) {
        final Tasks.Owned h = new Tasks.Owned(this.script);
        this.script.track(h);
        final Runnable once = this.body("задаче игрока", fn, h, false);
        if (delayTicks <= 1) {
            if (!this.script.platform.runOnceForPlayer(player, once)) {
                h.finished();
            }
        } else {
            // Задержка: глобальный таймер, потом поток игрока (entity-планировщик напрямую не используем).
            h.bind(this.script.platform.runGlobal(() -> {
                if (!h.isCancelled() && !this.script.platform.runOnceForPlayer(player, once)) {
                    h.finished();
                }
            }, delayTicks - 1, 0));
        }
        return h;
    }

    public Task runInRegion(final Object where, final Value fn, final long delayTicks, final long periodTicks) {
        final Location loc = switch (where) {
            case Location l -> l;
            case Entity e -> e.getLocation();
            case Block b -> b.getLocation();
            default -> throw new IllegalArgumentException("run_in_region: нужен Location, Entity или Block");
        };
        final Tasks.Owned h = new Tasks.Owned(this.script);
        this.script.track(h);
        h.bind(this.script.platform.runInRegion(loc, this.body("задаче региона", fn, h, periodTicks > 0), delayTicks, periodTicks));
        return h;
    }

    public Task runGlobal(final Value fn, final long delayTicks, final long periodTicks) {
        final Tasks.Owned h = new Tasks.Owned(this.script);
        this.script.track(h);
        h.bind(this.script.platform.runGlobal(this.body("глобальной задаче", fn, h, periodTicks > 0), delayTicks, periodTicks));
        return h;
    }

    public Task runAsync(final Value fn, final long delayMillis, final long periodMillis) {
        final Tasks.Owned h = new Tasks.Owned(this.script);
        this.script.track(h);
        h.bind(this.script.platform.runAsync(this.body("асинхронной задаче", fn, h, periodMillis > 0), delayMillis, periodMillis));
        return h;
    }

    public boolean owns(final Object thing) {
        return this.script.platform.owns(thing);
    }

    private void requireOwner(final Player p, final String what) {
        if (!this.script.platform.owns(p)) {
            throw new IllegalStateException(what + ": вызывайте из потока игрока " + p.getName()
                + " (обработчик его события, run_for_player или run_once_for_player)");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Ключи, PDC, атрибуты, эффекты
    // ------------------------------------------------------------------------------------------

    /** «имя» → py_&lt;система&gt;:имя; «ns:имя» → как есть. */
    public NamespacedKey key(final String key) {
        if (key.indexOf(':') >= 0) {
            final NamespacedKey k = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
            if (k == null) {
                throw new IllegalArgumentException("плохой ключ «" + key + "»");
            }
            return k;
        }
        return new NamespacedKey(this.namespace, key.toLowerCase(Locale.ROOT));
    }

    private static io.papermc.paper.persistence.PersistentDataContainerView view(final Object holder) {
        return switch (holder) {
            case ItemStack i -> i.getPersistentDataContainer();
            case PersistentDataHolder h -> h.getPersistentDataContainer();
            default -> throw new IllegalArgumentException("нет PDC у " + holder);
        };
    }

    /** Строка из PDC (или null). Для предметов — из копии, без изменения. */
    public String pdcGet(final Object holder, final String key) {
        return view(holder).get(this.key(key), PersistentDataType.STRING);
    }

    public boolean pdcHas(final Object holder, final String key) {
        return view(holder).has(this.key(key));
    }

    public void pdcSet(final Object holder, final String key, final String value) {
        final NamespacedKey k = this.key(key);
        switch (holder) {
            case ItemStack i -> i.editPersistentDataContainer(pdc -> pdc.set(k, PersistentDataType.STRING, value));
            case PersistentDataHolder h -> h.getPersistentDataContainer().set(k, PersistentDataType.STRING, value);
            default -> throw new IllegalArgumentException("нет PDC у " + holder);
        }
    }

    public void pdcRemove(final Object holder, final String key) {
        final NamespacedKey k = this.key(key);
        switch (holder) {
            case ItemStack i -> i.editPersistentDataContainer(pdc -> pdc.remove(k));
            case PersistentDataHolder h -> h.getPersistentDataContainer().remove(k);
            default -> throw new IllegalArgumentException("нет PDC у " + holder);
        }
    }

    /** Атрибут по имени: armor, max_health, minecraft:movement_speed … */
    public Attribute attribute(final String name) {
        final String n = name.toLowerCase(Locale.ROOT).replace("generic.", "").replace("player.", "");
        final Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.fromString(n.contains(":") ? n : "minecraft:" + n));
        if (a == null) {
            throw new IllegalArgumentException("нет атрибута «" + name + "»");
        }
        return a;
    }

    /** Модификатор: operation = add | multiply_base | multiply (= ADD_NUMBER | ADD_SCALAR | MULTIPLY_SCALAR_1). */
    public AttributeModifier modifier(final String key, final double amount, final String operation, final String slotGroup) {
        final AttributeModifier.Operation op = switch (operation.toLowerCase(Locale.ROOT)) {
            case "add", "add_number", "add_value" -> AttributeModifier.Operation.ADD_NUMBER;
            case "multiply_base", "add_scalar", "add_multiplied_base" -> AttributeModifier.Operation.ADD_SCALAR;
            case "multiply", "multiply_scalar_1", "add_multiplied_total" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
            default -> throw new IllegalArgumentException("операция «" + operation + "»: add | multiply_base | multiply");
        };
        final EquipmentSlotGroup group = slotGroup == null || slotGroup.isBlank() ? EquipmentSlotGroup.ANY : EquipmentSlotGroup.getByName(slotGroup.toLowerCase(Locale.ROOT));
        if (group == null) {
            throw new IllegalArgumentException("слот «" + slotGroup + "»: any, head, chest, legs, feet, armor, mainhand, offhand, hand");
        }
        return new AttributeModifier(this.key(key), amount, op, group);
    }

    /** Временный модификатор атрибута игрока; снимается сам при выгрузке скрипта. Только в потоке игрока. */
    public void addPlayerModifier(final Player p, final String attribute, final String key, final double amount, final String operation) {
        this.requireOwner(p, "add_modifier");
        this.script.tracker.addModifier(p, this.attribute(attribute), this.modifier(key, amount, operation, "any"));
    }

    public boolean removePlayerModifier(final Player p, final String attribute, final String key) {
        this.requireOwner(p, "remove_modifier");
        return this.script.tracker.removeModifier(p, this.attribute(attribute), this.key(key));
    }

    public PotionEffectType effectType(final String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        final PotionEffectType t = Registry.EFFECT.get(NamespacedKey.fromString(n.contains(":") ? n : "minecraft:" + n));
        if (t == null) {
            throw new IllegalArgumentException("нет эффекта «" + name + "»");
        }
        return t;
    }

    /** Эффект от скрипта; снимается сам при выгрузке (если на игроке всё ещё он). Только в потоке игрока. */
    public void addPlayerEffect(final Player p, final String type, final int durationTicks, final int amplifier, final boolean ambient,
                                final boolean particles, final boolean icon) {
        this.requireOwner(p, "add_effect");
        this.script.tracker.addEffect(p, new PotionEffect(this.effectType(type), durationTicks, amplifier, ambient, particles, icon));
    }

    public boolean removePlayerEffect(final Player p, final String type) {
        this.requireOwner(p, "remove_effect");
        return this.script.tracker.removeEffect(p, this.effectType(type));
    }

    /** Игрок вышел — забыть его временные изменения (они и так пропадают). */
    public void forgetPlayer(final Player p) {
        this.script.tracker.forget(p.getUniqueId());
    }

    // ------------------------------------------------------------------------------------------
    // Текст
    // ------------------------------------------------------------------------------------------

    public Component mm(final String text, final Value placeholders) {
        final List<TagResolver> rs = new ArrayList<>();
        if (placeholders != null && placeholders.hasHashEntries()) {
            final Value it = placeholders.getHashKeysIterator();
            while (it.hasIteratorNextElement()) {
                final Value k = it.getIteratorNextElement();
                final Value v = placeholders.getHashValue(k);
                final String name = k.asString();
                if (v.isHostObject() && v.asHostObject() instanceof Component c) {
                    rs.add(Placeholder.component(name, c));
                } else {
                    rs.add(Placeholder.unparsed(name, v.isString() ? v.asString() : v.toString()));
                }
            }
        }
        return MM.deserialize(text, TagResolver.resolver(rs));
    }

    /** Текст для названий/описаний предметов: без курсива по умолчанию. */
    public Component itemText(final String text, final Value placeholders) {
        return this.mm(text, placeholders).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ------------------------------------------------------------------------------------------
    // Файлы
    // ------------------------------------------------------------------------------------------

    private Path inside(final Path base, final String rel) {
        final Path p = base.resolve(rel).normalize();
        if (!p.startsWith(base.normalize())) {
            throw new IllegalArgumentException("путь «" + rel + "» вне папки системы");
        }
        return p;
    }

    /** YAML из папки скрипта → JSON (amazake.config делает из него dict). null, если файла нет. */
    public String readConfig(final String rel) throws IOException {
        final Path f = this.inside(this.script.dir, rel);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        final Object data = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(f, StandardCharsets.UTF_8));
        return GSON.toJson(data == null ? Map.of() : data);
    }

    /** Текстовый файл из папки данных (systems/core-python/data/&lt;система&gt;/), или null. */
    public String readText(final String rel) throws IOException {
        final Path f = this.inside(this.script.dataDir, rel);
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /** Атомарная запись (временный файл + rename) в папку данных. Вне тиков: run_async. */
    public void writeText(final String rel, final String content) throws IOException {
        final Path f = this.inside(this.script.dataDir, rel);
        Files.createDirectories(f.getParent());
        final Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Java-классы
    // ------------------------------------------------------------------------------------------

    /** Класс сервера или плагина по полному имени. */
    public Class<?> findClass(final String name) throws ClassNotFoundException {
        return this.script.platform.findClass(name);
    }

    /** Класс из конкретного плагина (например, API Nexo), или null, если плагина нет / он выключен. */
    public Class<?> pluginClass(final String plugin, final String name) {
        final ClassLoader l = this.script.platform.pluginLoader(plugin);
        if (l == null) {
            return null;
        }
        try {
            return Class.forName(name, true, l);
        } catch (final ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
