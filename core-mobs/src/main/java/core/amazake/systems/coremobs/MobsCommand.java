package core.amazake.systems.coremobs;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coremobs.drop.DropEntry;
import core.amazake.systems.coremobs.drop.DropStore;
import core.amazake.systems.coremobs.geo.BlockPos;
import core.amazake.systems.coremobs.mob.MobCatalog;
import core.amazake.systems.coremobs.mob.MobDef;
import core.amazake.systems.coremobs.mob.MobService;
import core.amazake.systems.coremobs.region.ModelMobs;
import core.amazake.systems.coremobs.region.Region;
import core.amazake.systems.coremobs.region.RegionSpawner;
import core.amazake.systems.coremobs.region.RegionStore;
import core.amazake.systems.coremobs.region.Selections;
import core.amazake.systems.coremobs.region.SpawnSettings;
import core.amazake.systems.coremobs.region.WandListener;
import core.amazake.systems.coremobs.util.IntRange;
import core.amazake.systems.coremobs.util.Keys;
import core.amazake.systems.coremobs.util.Text;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * {@code /mobs} — всё админское: мобы, лут, яйца, топорик, регионы, перезагрузка. Право {@code core.mobs.admin}
 * (по умолчанию у операторов). Команда игрока выполняется в его потоке, из консоли — в глобальном; работа с миром
 * уходит в поток нужного региона, с другим игроком — в его поток.
 */
final class MobsCommand extends Command {

    private static final List<String> SUBS = List.of("help", "list", "spawn", "egg", "drops", "wand", "sel", "region", "world", "reload");
    private static final List<String> DROP_SUBS = List.of("save", "add", "list", "give", "remove", "clear");
    private static final List<String> SEL_SUBS = List.of("info", "undo", "clear");
    private static final List<String> REGION_SUBS = List.of("create", "redefine", "delete", "list", "info", "show", "tp",
        "expand", "set", "addmob", "removemob");
    private static final List<String> SET_KEYS = List.of("spawn", "interval", "max-alive", "amount", "player-radius",
        "min-distance", "despawn-radius");

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final Supplier<MobCatalog> catalog;
    private final MobService mobs;
    private final DropStore drops;
    private final RegionStore regions;
    private final Selections selections;
    private final RegionSpawner spawner;
    private final core.amazake.systems.coremobs.region.RegionDisplays displays;
    private final core.amazake.systems.coremobs.world.WorldRules worlds;
    private final Runnable reload;

    MobsCommand(final SystemContext context, final Supplier<Settings> settings, final Supplier<MobCatalog> catalog,
                final MobService mobs, final DropStore drops, final RegionStore regions, final Selections selections,
                final RegionSpawner spawner, final core.amazake.systems.coremobs.region.RegionDisplays displays,
                final core.amazake.systems.coremobs.world.WorldRules worlds, final Runnable reload) {
        super("mobs", "Мобы, лут и регионы спавна", "/mobs help", List.of("cmobs", "мобы"));
        setPermission(WandListener.PERMISSION);
        this.context = context;
        this.settings = settings;
        this.catalog = catalog;
        this.mobs = mobs;
        this.drops = drops;
        this.regions = regions;
        this.selections = selections;
        this.spawner = spawner;
        this.displays = displays;
        this.worlds = worlds;
        this.reload = reload;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!testPermission(sender)) {
            return true;
        }
        final String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        final String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "list", "список" -> this.list(sender);
            case "spawn", "призвать" -> this.spawn(sender, rest);
            case "egg", "яйцо" -> this.egg(sender, rest);
            case "drops", "drop", "лут" -> this.drops(sender, rest);
            case "wand", "топорик" -> this.wand(sender);
            case "sel", "selection" -> this.selection(sender, rest);
            case "region", "rg", "регион" -> this.region(sender, rest);
            case "world", "мир" -> this.world(sender, rest);
            case "reload" -> {
                Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> {
                    this.reload.run();
                    this.reply(sender, "<green>core-mobs перезагружен: мобов " + this.catalog.get().all().size()
                        + ", регионов " + this.regions.all().size() + ".</green>");
                });
            }
            default -> this.help(sender);
        }
        return true;
    }

    // --- справка и список ---

    private void help(final CommandSender sender) {
        send(sender, "<green>core-mobs</green> <gray>— команды:</gray>");
        line(sender, "/mobs list", "мобы из mobs.yml");
        line(sender, "/mobs spawn <моб> [кол-во] [мир x y z]", "призвать моба (без координат — куда смотрите)");
        line(sender, "/mobs egg <моб> [кол-во] [ник]", "яйцо призыва");
        line(sender, "/mobs drops save|add <моб>", "лут из вашего инвентаря: заменить или дополнить");
        line(sender, "/mobs drops list <моб>", "лут моба с шансами");
        line(sender, "/mobs drops give <моб> <номер|all> [ник]", "выдать предмет из лута");
        line(sender, "/mobs drops remove <моб> <номер> · clear <моб>", "убрать предмет или весь лут");
        line(sender, "/mobs wand", "топорик региона (ЛКМ — точка, ПКМ — убрать, Shift+ПКМ — сброс)");
        line(sender, "/mobs sel info|undo|clear", "выделение");
        line(sender, "/mobs region create|redefine <имя>", "регион из выделения / заменить его точки");
        line(sender, "/mobs region list · info|tp|delete <имя>", "регионы");
        line(sender, "/mobs region addmob <имя> <моб> [вес] · removemob <имя> <моб>", "кто спавнится");
        line(sender, "/mobs region addmob <имя> all-models [вес] · removemob <имя> all-models", "все мобы с моделью");
        line(sender, "/mobs region set <имя> <параметр> <значение>", String.join(", ", SET_KEYS));
        line(sender, "/mobs region expand <имя> up|down <блоков>", "поднять потолок / опустить пол");
        line(sender, "/mobs region show <имя>", "показать контур региона всем / убрать у всех");
        line(sender, "/mobs world disable|enable <мир>", "выключить / включить ванильных мобов в мире");
        line(sender, "/mobs world list", "миры без ванильных мобов");
        line(sender, "/mobs reload", "перечитать конфиги, регионы и лут");
    }

    private static void line(final CommandSender sender, final String cmd, final String what) {
        sender.sendMessage(Text.mm("<yellow><cmd></yellow> <gray>— <what></gray>",
            Placeholder.unparsed("cmd", cmd), Placeholder.unparsed("what", what)));
    }

    private void list(final CommandSender sender) {
        final Map<String, MobDef> all = this.catalog.get().all();
        if (all.isEmpty()) {
            send(sender, "<gray>В mobs.yml нет мобов.</gray>");
            return;
        }
        send(sender, "<green>Мобы (" + all.size() + "):</green>");
        for (final MobDef def : all.values()) {
            final Component row = Text.mm("<gray>•</gray> <yellow><id></yellow> <gray>(<type>)</gray> ",
                    Placeholder.unparsed("id", def.id()), Placeholder.unparsed("type", def.type().getKey().getKey()))
                .append(Text.mm(def.name()))
                .append(Text.mm(" <gray>❤ <hp>, урон <dmg>, лут: <drops></gray>",
                    Placeholder.unparsed("hp", Text.num(def.health())),
                    Placeholder.unparsed("dmg", def.damage() == null ? "ванильный" : Text.num(def.damage())),
                    Placeholder.unparsed("drops", Integer.toString(this.drops.get(def.id()).size()))))
                .clickEvent(ClickEvent.suggestCommand("/mobs spawn " + def.id()))
                .hoverEvent(HoverEvent.showText(Text.mm("<gray>Нажмите — команда призыва</gray>")));
            sender.sendMessage(row);
        }
    }

    // --- спавн и яйца ---

    private void spawn(final CommandSender sender, final String[] a) {
        if (a.length < 1) {
            send(sender, "<red>/mobs spawn <моб> [кол-во] [мир x y z]</red>");
            return;
        }
        final MobDef def = this.mob(sender, a[0]);
        if (def == null) {
            return;
        }
        final int amount = a.length >= 2 ? this.number(sender, a[1], 1, 100) : 1;
        if (amount < 0) {
            return;
        }
        final Location at;
        if (a.length >= 6) {
            final World world = Bukkit.getWorld(a[2]);
            if (world == null) {
                send(sender, "<red>Мир <white>" + Text.escape(a[2]) + "</white> не найден.</red>");
                return;
            }
            try {
                at = new Location(world, Double.parseDouble(a[3]), Double.parseDouble(a[4]), Double.parseDouble(a[5]));
            } catch (final NumberFormatException e) {
                send(sender, "<red>Координаты должны быть числами.</red>");
                return;
            }
        } else if (sender instanceof final Player player) {
            final Block target = player.getTargetBlockExact(32);
            at = target == null ? player.getLocation() : target.getLocation().add(0.5, 1, 0.5);
        } else {
            send(sender, "<red>Из консоли: /mobs spawn <моб> <кол-во> <мир> <x> <y> <z></red>");
            return;
        }
        Bukkit.getRegionScheduler().execute(this.context.plugin(), at, () -> {
            for (int i = 0; i < amount; i++) {
                this.mobs.spawn(def, at, null);
            }
            this.reply(sender, "<green>Призвано: <white>" + amount + "</white> × " + def.name() + "<green>.</green>");
        });
    }

    private void egg(final CommandSender sender, final String[] a) {
        if (a.length < 1) {
            send(sender, "<red>/mobs egg <моб> [кол-во] [ник]</red>");
            return;
        }
        final MobDef def = this.mob(sender, a[0]);
        if (def == null) {
            return;
        }
        final int amount = a.length >= 2 ? this.number(sender, a[1], 1, 64) : 1;
        if (amount < 0) {
            return;
        }
        final Player target = this.target(sender, a.length >= 3 ? a[2] : null);
        if (target != null) {
            this.give(sender, target, List.of(MobService.egg(def, amount)), "яйцо призыва " + def.id() + " ×" + amount);
        }
    }

    // --- лут ---

    private void drops(final CommandSender sender, final String[] a) {
        if (a.length < 2) {
            send(sender, "<red>/mobs drops save|add|list|give|remove|clear <моб> ...</red>");
            return;
        }
        final String sub = a[0].toLowerCase(Locale.ROOT);
        final MobDef def = this.mob(sender, a[1]);
        if (def == null) {
            return;
        }
        switch (sub) {
            case "save", "add" -> {
                if (!(sender instanceof final Player player)) {
                    send(sender, "<red>Только для игрока: лут берётся из инвентаря.</red>");
                    return;
                }
                // Топорик и яйца призыва в лут не попадают.
                final List<DropEntry> items = DropStore.entriesFrom(Arrays.stream(player.getInventory().getStorageContents())
                    .filter(i -> !core.amazake.systems.coremobs.region.RegionView.isWand(i) && MobService.eggId(i) == null).toList());
                if (items.isEmpty()) {
                    send(sender, "<red>Инвентарь пуст: положите в него предметы, которые должны падать.</red>");
                    return;
                }
                final boolean replace = sub.equals("save");
                this.drops.change(def.id(), list -> {
                    if (replace) {
                        list.clear();
                    }
                    list.addAll(items);
                    return list;
                });
                send(sender, "<green>" + (replace ? "Лут заменён" : "В лут добавлено") + ": <white>" + items.size()
                    + "</white> предм. Всего у моба: <white>" + this.drops.get(def.id()).size() + "</white>.</green>");
                send(sender, "<gray>Шансы и количество — в systems/core-mobs/drops/" + def.id() + ".yml, потом /mobs reload.</gray>");
            }
            case "list" -> this.dropList(sender, def);
            case "give" -> {
                if (a.length < 3) {
                    send(sender, "<red>/mobs drops give <моб> <номер|all> [ник]</red>");
                    return;
                }
                final List<DropEntry> entries = this.drops.get(def.id());
                final List<ItemStack> items = new ArrayList<>();
                if (a[2].equalsIgnoreCase("all") || a[2].equalsIgnoreCase("все")) {
                    entries.forEach(e -> DropStore.addStacks(items, e.item(), Math.max(1, e.amount().max())));
                } else {
                    final int n = this.number(sender, a[2], 1, Math.max(1, entries.size()));
                    if (n < 0) {
                        return;
                    }
                    if (n > entries.size()) {
                        send(sender, "<red>У моба нет предмета №" + n + ".</red>");
                        return;
                    }
                    final DropEntry e = entries.get(n - 1);
                    DropStore.addStacks(items, e.item(), Math.max(1, e.amount().max()));
                }
                if (items.isEmpty()) {
                    send(sender, "<red>У моба " + Text.escape(def.id()) + " нет лута.</red>");
                    return;
                }
                final Player target = this.target(sender, a.length >= 4 ? a[3] : null);
                if (target != null) {
                    this.give(sender, target, items, "лут " + def.id() + " (" + items.size() + " стак.)");
                }
            }
            case "remove" -> {
                final List<DropEntry> entries = this.drops.get(def.id());
                if (a.length < 3 || entries.isEmpty()) {
                    send(sender, entries.isEmpty() ? "<red>У моба нет лута.</red>" : "<red>/mobs drops remove <моб> <номер></red>");
                    return;
                }
                final int n = this.number(sender, a[2], 1, entries.size());
                if (n < 0) {
                    return;
                }
                this.drops.change(def.id(), list -> {
                    if (n <= list.size()) {
                        list.remove(n - 1);
                    }
                    return list;
                });
                send(sender, "<green>Предмет №" + n + " убран из лута " + Text.escape(def.id()) + ".</green>");
            }
            case "clear" -> {
                this.drops.change(def.id(), list -> {
                    list.clear();
                    return list;
                });
                send(sender, "<green>Лут моба " + Text.escape(def.id()) + " очищен.</green>");
            }
            default -> send(sender, "<red>/mobs drops save|add|list|give|remove|clear <моб></red>");
        }
    }

    private void dropList(final CommandSender sender, final MobDef def) {
        final List<DropEntry> entries = this.drops.get(def.id());
        if (entries.isEmpty()) {
            send(sender, "<gray>У моба <white>" + Text.escape(def.id()) + "</white> нет своего лута. Положите предметы в инвентарь и /mobs drops save "
                + Text.escape(def.id()) + ".</gray>");
            return;
        }
        send(sender, "<green>Лут " + Text.escape(def.id()) + " (" + entries.size() + "):</green>");
        for (int i = 0; i < entries.size(); i++) {
            final DropEntry e = entries.get(i);
            final int n = i + 1;
            final Component row = Text.mm("<gray><n>.</gray> <white><item></white> <gray>— шанс <yellow><chance>%</yellow>, кол-во <yellow><amount></yellow></gray>",
                    Placeholder.unparsed("n", Integer.toString(n)), Placeholder.unparsed("item", e.preview()),
                    Placeholder.unparsed("chance", Text.num(e.chance())), Placeholder.unparsed("amount", e.amount().format()))
                .hoverEvent(e.item().asHoverEvent())
                .clickEvent(ClickEvent.runCommand("/mobs drops give " + def.id() + " " + n));
            sender.sendMessage(row);
        }
        send(sender, "<gray>Нажмите на строку — получить предмет.</gray>");
    }

    // --- топорик и выделение ---

    private void wand(final CommandSender sender) {
        if (!(sender instanceof final Player player)) {
            send(sender, "<red>Только для игрока.</red>");
            return;
        }
        final Settings s = this.settings.get();
        final ItemStack wand = new ItemStack(s.wandMaterial());
        wand.editMeta(meta -> {
            meta.displayName(Text.item(s.wandName()));
            meta.lore(Text.lore(s.wandLore()));
            meta.getPersistentDataContainer().set(Keys.WAND, PersistentDataType.BYTE, (byte) 1);
        });
        player.getInventory().addItem(wand).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        send(sender, "<green>Топорик выдан.</green> <gray>ЛКМ по блоку — точка, ПКМ — убрать последнюю, Shift+ПКМ — сброс.</gray>");
    }

    private void selection(final CommandSender sender, final String[] a) {
        if (!(sender instanceof final Player player)) {
            send(sender, "<red>Только для игрока.</red>");
            return;
        }
        final String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "undo" -> {
                final BlockPos removed = this.selections.undo(player.getUniqueId());
                send(sender, removed == null ? "<gray>Точек нет.</gray>" : "<yellow>Убрана точка " + removed.format() + ".</yellow>");
            }
            case "clear" -> {
                this.selections.clear(player.getUniqueId());
                send(sender, "<yellow>Выделение сброшено.</yellow>");
            }
            default -> {
                final Selections.Selection sel = this.selections.get(player.getUniqueId());
                if (sel == null) {
                    send(sender, "<gray>Выделения нет. Возьмите /mobs wand и кликайте ЛКМ по блокам.</gray>");
                    return;
                }
                send(sender, "<green>Выделение: <white>" + sel.points().size() + "</white> точек, мир <white>" + Text.escape(sel.world()) + "</white>.</green>");
                final StringBuilder b = new StringBuilder();
                for (final BlockPos p : sel.points()) {
                    b.append(b.isEmpty() ? "" : "  ").append(p.format());
                }
                send(sender, "<gray>" + Text.escape(b.toString()) + "</gray>");
            }
        }
    }

    // --- регионы ---

    private void region(final CommandSender sender, final String[] a) {
        final String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            this.regionList(sender);
            return;
        }
        if (a.length < 2) {
            send(sender, "<red>/mobs region " + Text.escape(sub) + " <имя> ...</red>");
            return;
        }
        final String name = a[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create", "redefine" -> this.regionFromSelection(sender, name, sub.equals("create"));
            case "delete", "remove" -> {
                final boolean removed = this.regions.remove(name);
                this.displays.remove(name);
                send(sender, removed ? "<green>Регион <white>" + name + "</white> удалён. Его мобы пропадут.</green>"
                    : "<red>Региона <white>" + Text.escape(name) + "</white> нет.</red>");
            }
            case "info" -> this.regionInfo(sender, name);
            case "show" -> {
                final Region r = this.regions.update(name, old -> old.withShow(!old.show()));
                if (r == null) {
                    send(sender, "<red>Региона <white>" + Text.escape(name) + "</white> нет. Список: /mobs region list.</red>");
                    return;
                }
                this.displays.rebuild(name);
                send(sender, r.show() ? "<green>Контур региона <white>" + name + "</white> показан всем игрокам.</green>"
                    : "<yellow>Контур региона <white>" + name + "</white> убран у всех.</yellow>");
            }
            case "tp" -> this.regionTp(sender, name);
            case "expand" -> {
                if (a.length < 4) {
                    send(sender, "<red>/mobs region expand <имя> up|down <блоков></red>");
                    return;
                }
                final int n;
                try {
                    n = Integer.parseInt(a[3]);
                } catch (final NumberFormatException e) {
                    send(sender, "<red>Нужно целое число блоков.</red>");
                    return;
                }
                final boolean up = a[2].equalsIgnoreCase("up") || a[2].equalsIgnoreCase("вверх");
                final Region r = this.regions.update(name, old -> up ? old.withHeight(old.minY(), old.maxY() + n)
                    : old.withHeight(old.minY() - n, old.maxY()));
                if (r != null) {
                    this.displays.rebuild(name);
                }
                send(sender, r == null ? "<red>Региона <white>" + Text.escape(name) + "</white> нет.</red>"
                    : "<green>Высота региона <white>" + name + "</white>: Y " + r.minY() + "…" + r.maxY() + ".</green>");
            }
            case "set" -> this.regionSet(sender, name, a);
            case "addmob" -> {
                if (a.length < 3) {
                    send(sender, "<red>/mobs region addmob <имя> <моб|all-models> [вес]</red>");
                    return;
                }
                if (a[2].equalsIgnoreCase(ModelMobs.ALL_MODELS)) {
                    this.addModelMobs(sender, name, a);
                    return;
                }
                final MobDef def = this.mob(sender, a[2]);
                if (def == null) {
                    return;
                }
                final int weight = a.length >= 4 ? this.number(sender, a[3], 1, 1_000_000) : 1;
                if (weight < 1) { // number() при ошибке возвращает min - 1 = 0
                    return;
                }
                final Region r = this.regions.update(name, old -> {
                    final Map<String, Integer> m = new LinkedHashMap<>(old.spawn().mobs());
                    m.put(def.id(), weight);
                    return old.withSpawn(old.spawn().withMobs(m));
                });
                this.spawner.resetTimer(name);
                send(sender, r == null ? "<red>Региона <white>" + Text.escape(name) + "</white> нет.</red>"
                    : "<green>В регионе <white>" + name + "</white> спавнится <white>" + def.id() + "</white> (вес " + weight + ").</green>");
            }
            case "removemob" -> {
                if (a.length < 3) {
                    send(sender, "<red>/mobs region removemob <имя> <моб|all-models></red>");
                    return;
                }
                if (a[2].equalsIgnoreCase(ModelMobs.ALL_MODELS)) {
                    this.removeModelMobs(sender, name);
                    return;
                }
                final String mob = a[2].toLowerCase(Locale.ROOT);
                final Region r = this.regions.update(name, old -> {
                    final Map<String, Integer> m = new LinkedHashMap<>(old.spawn().mobs());
                    m.remove(mob);
                    return old.withSpawn(old.spawn().withMobs(m));
                });
                send(sender, r == null ? "<red>Региона <white>" + Text.escape(name) + "</white> нет.</red>"
                    : "<green>Моб <white>" + Text.escape(mob) + "</white> убран из спавна региона <white>" + name + "</white>.</green>");
            }
            default -> send(sender, "<red>/mobs region " + String.join("|", REGION_SUBS) + "</red>");
        }
    }

    /** {@code addmob <регион> all-models [вес]}: все мобы с моделью; кто уже есть — получает новый вес. */
    private void addModelMobs(final CommandSender sender, final String name, final String[] a) {
        // порядок проверок как у addmob для одного моба: вес, регион, потом — есть ли кого добавлять
        final int weight = a.length >= 4 ? this.number(sender, a[3], 1, 1_000_000) : 1;
        if (weight < 1) { // number() при ошибке возвращает min - 1 = 0
            return;
        }
        if (this.regions.get(name) == null) {
            send(sender, "<red>Региона <white>" + Text.escape(name) + "</white> нет.</red>");
            return;
        }
        final List<String> ids = ModelMobs.withModels(this.catalog.get().all());
        if (ids.isEmpty()) {
            send(sender, "<red>В mobs.yml нет мобов с model — добавлять нечего.</red>");
            return;
        }
        final ModelMobs.Change[] change = new ModelMobs.Change[1];
        final Region r = this.regions.update(name, old -> {
            change[0] = ModelMobs.add(old.spawn().mobs(), ids, weight);
            return old.withSpawn(old.spawn().withMobs(change[0].mobs()));
        });
        this.spawner.resetTimer(name);
        if (r == null) {
            send(sender, "<red>Региона <white>" + Text.escape(name) + "</white> нет.</red>");
            return;
        }
        final ModelMobs.Change c = change[0];
        send(sender, "<green>В регионе <white>" + name + "</white> спавнятся все мобы с моделью (вес " + weight + "): <white>"
            + ids.size() + "</white>.</green>");
        if (!c.added().isEmpty()) {
            send(sender, "<gray>Добавлены (" + c.added().size() + "): <white>" + Text.escape(String.join(", ", c.added())) + "</white></gray>");
        }
        if (!c.updated().isEmpty()) {
            send(sender, "<gray>Уже были, вес обновлён (" + c.updated().size() + "): <white>"
                + Text.escape(String.join(", ", c.updated())) + "</white></gray>");
        }
    }

    /** {@code removemob <регион> all-models}: убрать всех мобов с моделью, остальные мобы региона остаются. */
    private void removeModelMobs(final CommandSender sender, final String name) {
        final List<String> ids = ModelMobs.withModelsAndLiteral(this.catalog.get().all());
        final ModelMobs.Change[] change = new ModelMobs.Change[1];
        final Region r = this.regions.update(name, old -> {
            change[0] = ModelMobs.remove(old.spawn().mobs(), ids);
            return old.withSpawn(old.spawn().withMobs(change[0].mobs()));
        });
        if (r == null) {
            send(sender, "<red>Региона <white>" + Text.escape(name) + "</white> нет.</red>");
            return;
        }
        final List<String> removed = change[0].removed();
        send(sender, removed.isEmpty() ? "<yellow>В регионе <white>" + name + "</white> не было мобов с моделью.</yellow>"
            : "<green>Из спавна региона <white>" + name + "</white> убраны мобы с моделью (" + removed.size() + "): <white>"
            + Text.escape(String.join(", ", removed)) + "</white>.</green>");
        send(sender, "<gray>Мобы, у которых модели уже нет в mobs.yml, так не убираются — <white>/mobs region removemob "
            + name + " <моб></white>.</gray>");
    }

    private void regionFromSelection(final CommandSender sender, final String name, final boolean create) {
        if (!(sender instanceof final Player player)) {
            send(sender, "<red>Только для игрока: регион строится по выделению топориком.</red>");
            return;
        }
        if (!Region.NAME.matcher(name).matches()) {
            send(sender, "<red>Имя региона — латиница в нижнем регистре, цифры, _ и - (до 48 символов).</red>");
            return;
        }
        final Selections.Selection sel = this.selections.get(player.getUniqueId());
        if (sel == null || sel.points().size() < 3) {
            send(sender, "<red>Нужно минимум 3 точки. Возьмите /mobs wand и кликайте ЛКМ по блокам по контуру.</red>");
            return;
        }
        final Settings s = this.settings.get();
        final Region existing = this.regions.get(name);
        if (create && existing != null) {
            send(sender, "<red>Регион <white>" + name + "</white> уже есть. Заменить точки: /mobs region redefine " + name + ".</red>");
            return;
        }
        if (!create && existing == null) {
            send(sender, "<red>Региона <white>" + name + "</white> нет. Создать: /mobs region create " + name + ".</red>");
            return;
        }
        final Region fresh = Region.fromPoints(name, sel.world(), sel.points(), s.defaultHeight(), s.defaultDespawnRadius());
        final Region region = existing == null ? fresh : new Region(name, sel.world(), fresh.polygon(), fresh.minY(), fresh.maxY(),
            existing.despawnRadius(), existing.spawn(), existing.show());
        this.regions.put(region);
        this.displays.rebuild(name);
        this.selections.clear(player.getUniqueId());
        send(sender, "<green>Регион <white>" + name + "</white> " + (create ? "создан" : "переопределён") + ": "
            + sel.points().size() + " точек, ~" + region.polygon().blockArea() + " блоков, Y " + region.minY() + "…" + region.maxY() + ".</green>");
        if (create) {
            send(sender, "<gray>Дальше: /mobs region addmob " + name + " <моб> [вес]. Показать контур всем: /mobs region show " + name + ".</gray>");
        }
    }

    private void regionList(final CommandSender sender) {
        if (this.regions.all().isEmpty()) {
            send(sender, "<gray>Регионов нет. /mobs wand → точки → /mobs region create <имя>.</gray>");
            return;
        }
        send(sender, "<green>Регионы (" + this.regions.all().size() + "):</green>");
        for (final Region r : this.regions.all()) {
            final Component row = Text.mm("<gray>•</gray> <yellow><name></yellow> <gray>— <world>, мобов <alive>/<max>, спавн <spawn></gray>",
                    Placeholder.unparsed("name", r.name()), Placeholder.unparsed("world", r.world()),
                    Placeholder.unparsed("alive", Integer.toString(this.mobs.alive(r.name()))),
                    Placeholder.unparsed("max", Integer.toString(r.spawn().maxAlive())),
                    Placeholder.parsed("spawn", r.spawn().enabled() ? "<green>вкл</green>" : "<red>выкл</red>"))
                .clickEvent(ClickEvent.runCommand("/mobs region info " + r.name()))
                .hoverEvent(HoverEvent.showText(Text.mm("<gray>Нажмите — подробнее</gray>")));
            sender.sendMessage(row);
        }
    }

    private void regionInfo(final CommandSender sender, final String name) {
        final Region r = this.existing(sender, name);
        if (r == null) {
            return;
        }
        final SpawnSettings s = r.spawn();
        send(sender, "<green>Регион <white>" + r.name() + "</white></green>");
        info(sender, "Мир", r.world());
        info(sender, "Точек", r.polygon().vertices().size() + ", площадь ~" + r.polygon().blockArea() + " блоков");
        info(sender, "Высота", "Y " + r.minY() + "…" + r.maxY());
        info(sender, "Радиус деспавна", r.despawnRadius() < 0 ? "выключен" : r.despawnRadius() + " бл. от границы");
        info(sender, "Спавн", (s.enabled() ? "включён" : "выключен") + ", каждые " + s.intervalSeconds() + " с, по "
            + s.amount().format() + ", максимум " + s.maxAlive() + " (сейчас " + this.mobs.alive(r.name()) + ")");
        info(sender, "Игрок рядом", "не дальше " + s.playerRadius() + " бл. от границы; моб не ближе " + s.minPlayerDistance() + " бл. к игроку");
        if (s.mobs().isEmpty()) {
            info(sender, "Мобы", "нет — /mobs region addmob " + r.name() + " <моб> [вес]");
        } else {
            final int total = s.mobs().values().stream().mapToInt(Integer::intValue).sum();
            final StringBuilder b = new StringBuilder();
            s.mobs().forEach((id, w) -> b.append(b.isEmpty() ? "" : ", ").append(id)
                .append(this.catalog.get().get(id) == null ? " (нет в mobs.yml!)" : "")
                .append(" ").append(Math.round(w * 100.0 / total)).append("%"));
            info(sender, "Мобы", b.toString());
        }
    }

    private static void info(final CommandSender sender, final String key, final String value) {
        sender.sendMessage(Text.mm("<gray><key>:</gray> <white><value></white>",
            Placeholder.unparsed("key", key), Placeholder.unparsed("value", value)));
    }

    private void regionSet(final CommandSender sender, final String name, final String[] a) {
        if (a.length < 4) {
            send(sender, "<red>/mobs region set <имя> <" + String.join("|", SET_KEYS) + "> <значение></red>");
            return;
        }
        if (this.existing(sender, name) == null) {
            return;
        }
        final String key = a[2].toLowerCase(Locale.ROOT);
        final String value = a[3];
        final Region r;
        switch (key) {
            case "spawn", "enabled" -> {
                final Boolean on = switch (value.toLowerCase(Locale.ROOT)) {
                    case "on", "true", "вкл", "да", "1" -> Boolean.TRUE;
                    case "off", "false", "выкл", "нет", "0" -> Boolean.FALSE;
                    default -> null;
                };
                if (on == null) {
                    send(sender, "<red>Значение: on или off.</red>");
                    return;
                }
                r = this.regions.update(name, old -> old.withSpawn(old.spawn().withEnabled(on)));
            }
            case "amount" -> {
                final IntRange range = IntRange.parse(value);
                if (range == null || range.min() < 1 || range.max() > 64) {
                    send(sender, "<red>amount — число или диапазон в пределах 1..64, например 1-3.</red>");
                    return;
                }
                r = this.regions.update(name, old -> old.withSpawn(old.spawn().withAmount(range)));
            }
            case "interval", "max-alive", "player-radius", "min-distance", "despawn-radius" -> {
                final int min = key.equals("interval") ? 1 : key.equals("despawn-radius") ? -1 : 0;
                final int max = key.equals("interval") ? 86400 : key.equals("max-alive") ? 1000 : key.equals("min-distance") ? 128 : 512;
                final int n = this.number(sender, value, min, max);
                if (n < min) {
                    return;
                }
                r = this.regions.update(name, old -> switch (key) {
                    case "interval" -> old.withSpawn(old.spawn().withInterval(n));
                    case "max-alive" -> old.withSpawn(old.spawn().withMaxAlive(n));
                    case "player-radius" -> old.withSpawn(old.spawn().withPlayerRadius(n));
                    case "min-distance" -> old.withSpawn(old.spawn().withMinPlayerDistance(n));
                    default -> old.withDespawnRadius(n);
                });
            }
            default -> {
                send(sender, "<red>Параметры: " + String.join(", ", SET_KEYS) + ".</red>");
                return;
            }
        }
        this.spawner.resetTimer(name);
        if (r != null) {
            send(sender, "<green>Регион <white>" + name + "</white>: " + Text.escape(key) + " = " + Text.escape(value) + ".</green>");
        }
    }

    private void regionTp(final CommandSender sender, final String name) {
        if (!(sender instanceof final Player player)) {
            send(sender, "<red>Только для игрока.</red>");
            return;
        }
        final Region r = this.existing(sender, name);
        if (r == null) {
            return;
        }
        final World world = Bukkit.getWorld(r.world());
        if (world == null) {
            send(sender, "<red>Мир региона не загружен.</red>");
            return;
        }
        // Высоту ищем в потоке того места, телепортируем — в потоке игрока.
        final BlockPos first = r.polygon().vertices().get(0);
        final int x = r.polygon().containsBlock(r.centerX(), r.centerZ()) ? r.centerX() : first.x();
        final int z = r.polygon().containsBlock(r.centerX(), r.centerZ()) ? r.centerZ() : first.z();
        Bukkit.getRegionScheduler().execute(this.context.plugin(), world, x >> 4, z >> 4, () -> {
            final int y = world.getHighestBlockYAt(x, z) + 1;
            final Location to = new Location(world, x + 0.5, y, z + 0.5);
            this.context.runOnceForPlayer(player, () -> player.teleportAsync(to));
        });
    }

    // --- миры ---

    private void world(final CommandSender sender, final String[] a) {
        final String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            final java.util.Set<String> off = this.worlds.disabled();
            send(sender, off.isEmpty() ? "<gray>Ванильные мобы включены во всех мирах.</gray>"
                : "<green>Ванильные мобы выключены в: <white>" + Text.escape(String.join(", ", new java.util.TreeSet<>(off))) + "</white>.</green>");
            return;
        }
        if (a.length < 2 || !(sub.equals("disable") || sub.equals("enable"))) {
            send(sender, "<red>/mobs world disable|enable <мир> · /mobs world list</red>");
            return;
        }
        final World world = Bukkit.getWorld(a[1]);
        if (world == null) {
            send(sender, "<red>Мир <white>" + Text.escape(a[1]) + "</white> не найден. Есть: "
                + Text.escape(String.join(", ", Bukkit.getWorlds().stream().map(World::getName).toList())) + ".</red>");
            return;
        }
        final boolean disable = sub.equals("disable");
        final boolean changed = this.worlds.set(world.getName(), disable);
        if (!disable) {
            send(sender, changed ? "<green>В мире <white>" + Text.escape(world.getName()) + "</white> ванильные мобы снова появляются.</green>"
                : "<gray>В мире " + Text.escape(world.getName()) + " ванильные мобы и так включены.</gray>");
            return;
        }
        send(sender, (changed ? "<green>В мире <white>" + Text.escape(world.getName()) + "</white> ванильные мобы выключены.</green>"
            : "<gray>В мире " + Text.escape(world.getName()) + " ванильные мобы уже выключены.</gray>")
            + " <gray>Мобы core-mobs появляются как обычно.</gray>");
        if (this.settings.get().worldRemoveExisting()) {
            this.sweep(sender, world);
        }
    }

    /** Убрать ванильных мобов вокруг игроков этого мира (каждый — в своём потоке); остальные уйдут при загрузке чанков. */
    private void sweep(final CommandSender sender, final World world) {
        final List<Player> players = world.getPlayers();
        if (players.isEmpty()) {
            send(sender, "<gray>Игроков в мире нет: уже живые мобы уберутся, когда загрузятся их чанки.</gray>");
            return;
        }
        final int radius = this.settings.get().adoptRadius();
        final java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(players.size());
        for (final Player p : players) {
            final Runnable done = () -> {
                if (left.decrementAndGet() == 0) {
                    this.reply(sender, "<green>Убрано ванильных мобов рядом с игроками: <white>" + removed.get() + "</white>.</green>");
                }
            };
            final boolean queued = this.context.runOnceForPlayer(p, () -> {
                try {
                    for (final org.bukkit.entity.Entity e : p.getNearbyEntities(radius, radius, radius)) {
                        if (core.amazake.systems.coremobs.world.WorldListener.removable(e) && Bukkit.isOwnedByCurrentRegion(e)) {
                            e.remove();
                            removed.incrementAndGet();
                        }
                    }
                } finally {
                    done.run();
                }
            });
            if (!queued) {
                done.run();
            }
        }
    }

    // --- общее ---

    private Region existing(final CommandSender sender, final String name) {
        final Region r = this.regions.get(name);
        if (r == null) {
            send(sender, "<red>Региона <white>" + Text.escape(name) + "</white> нет. Список: /mobs region list.</red>");
        }
        return r;
    }

    private MobDef mob(final CommandSender sender, final String id) {
        final MobDef def = this.catalog.get().get(id);
        if (def == null) {
            send(sender, "<red>Моба <white>" + Text.escape(id) + "</white> нет в mobs.yml. Есть: "
                + Text.escape(String.join(", ", this.catalog.get().ids())) + ".</red>");
        }
        return def;
    }

    /** Число в пределах; при ошибке — сообщение и {@code min - 1}. */
    private int number(final CommandSender sender, final String raw, final int min, final int max) {
        try {
            final int n = Integer.parseInt(raw);
            if (n >= min && n <= max) {
                return n;
            }
        } catch (final NumberFormatException ignored) {
            // ниже
        }
        send(sender, "<red>«" + Text.escape(raw) + "» — нужно целое число от " + min + " до " + max + ".</red>");
        return min - 1;
    }

    /** Игрок-получатель: указанный ник или сам отправитель. */
    private Player target(final CommandSender sender, final String nick) {
        if (nick == null) {
            if (sender instanceof final Player player) {
                return player;
            }
            send(sender, "<red>Из консоли укажите ник игрока.</red>");
            return null;
        }
        final Player player = Bukkit.getPlayerExact(nick);
        if (player == null) {
            send(sender, "<red>Игрок <white>" + Text.escape(nick) + "</white> не в сети.</red>");
        }
        return player;
    }

    /** Выдать предметы в потоке получателя; что не влезло — к его ногам. */
    private void give(final CommandSender sender, final Player target, final List<ItemStack> items, final String what) {
        final boolean queued = this.context.runOnceForPlayer(target, () -> {
            final Map<Integer, ItemStack> left = target.getInventory().addItem(items.stream().map(ItemStack::clone).toArray(ItemStack[]::new));
            left.values().forEach(stack -> target.getWorld().dropItemNaturally(target.getLocation(), stack));
            this.reply(sender, "<green>Выдано " + Text.escape(target.getName()) + ": " + Text.escape(what)
                + (left.isEmpty() ? "" : " (часть не влезла и упала рядом)") + ".</green>");
        });
        if (!queued) {
            send(sender, "<red>Игрок вышел.</red>");
        }
    }

    /** Ответ из чужого потока: игроку — в его потоке, консоли — сразу. */
    private void reply(final CommandSender sender, final String message) {
        if (sender instanceof final Player player) {
            this.context.runOnceForPlayer(player, () -> send(player, message));
        } else {
            send(sender, message);
        }
    }

    private static void send(final CommandSender sender, final String message) {
        sender.sendMessage(Text.mm(Text.prefix() + message));
    }

    // --- автодополнение ---

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (!sender.hasPermission(WandListener.PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBS, args[0]);
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        final List<String> mobIds = this.catalog.get().ids();
        final List<String> regionNames = List.copyOf(this.regions.snapshot().keySet());
        return switch (sub) {
            case "spawn" -> args.length == 2 ? filter(mobIds, args[1]) : args.length == 3 ? filter(List.of("1", "5", "10"), args[2])
                : args.length == 4 ? filter(Bukkit.getWorlds().stream().map(World::getName).toList(), args[3]) : List.of();
            case "egg" -> args.length == 2 ? filter(mobIds, args[1]) : args.length == 3 ? filter(List.of("1", "16", "64"), args[2])
                : args.length == 4 ? filter(players(), args[3]) : List.of();
            case "drops" -> args.length == 2 ? filter(DROP_SUBS, args[1]) : args.length == 3 ? filter(mobIds, args[2])
                : args.length == 4 && args[1].equalsIgnoreCase("give") ? filter(numbersAndAll(args[2]), args[3])
                : args.length == 5 && args[1].equalsIgnoreCase("give") ? filter(players(), args[4]) : List.of();
            case "sel" -> args.length == 2 ? filter(SEL_SUBS, args[1]) : List.of();
            case "region", "rg" -> this.regionTab(args, regionNames, mobIds);
            case "world" -> args.length == 2 ? filter(List.of("disable", "enable", "list"), args[1])
                : args.length == 3 ? filter(Bukkit.getWorlds().stream().map(World::getName).toList(), args[2]) : List.of();
            default -> List.of();
        };
    }

    private List<String> regionTab(final String[] args, final List<String> regionNames, final List<String> mobIds) {
        if (args.length == 2) {
            return filter(REGION_SUBS, args[1]);
        }
        final String sub = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3) {
            return sub.equals("create") ? List.of() : filter(regionNames, args[2]);
        }
        return switch (sub) {
            case "set" -> args.length == 4 ? filter(SET_KEYS, args[3])
                : args.length == 5 && args[3].equalsIgnoreCase("spawn") ? filter(List.of("on", "off"), args[4]) : List.of();
            case "expand" -> args.length == 4 ? filter(List.of("up", "down"), args[3]) : List.of();
            case "addmob" -> args.length == 4 ? filter(withAllModels(mobIds), args[3]) : args.length == 5 ? filter(List.of("1", "10", "50"), args[4]) : List.of();
            case "removemob" -> {
                final Region r = this.regions.get(args[2]);
                yield args.length == 4 && r != null ? filter(withAllModels(List.copyOf(r.spawn().mobs().keySet())), args[3]) : List.of();
            }
            default -> List.of();
        };
    }

    private static List<String> withAllModels(final List<String> ids) {
        return Stream.concat(ids.stream(), Stream.of(ModelMobs.ALL_MODELS)).toList();
    }

    private List<String> numbersAndAll(final String mob) {
        final int n = this.drops.get(mob.toLowerCase(Locale.ROOT)).size();
        return Stream.concat(Stream.of("all"), java.util.stream.IntStream.rangeClosed(1, n).mapToObj(Integer::toString)).toList();
    }

    private static List<String> players() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static List<String> filter(final List<String> options, final String prefix) {
        final String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
