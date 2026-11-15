package core.amazake.systems.corenpc;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corenpc.dialog.Action;
import core.amazake.systems.corenpc.dialog.DialogManager;
import core.amazake.systems.corenpc.hook.ModelHook;
import core.amazake.systems.corenpc.npc.NpcDef;
import core.amazake.systems.corenpc.npc.NpcManager;
import core.amazake.systems.corenpc.util.Args;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/** /npc … — право corenpc.admin. Алиасы /cnpc, /corenpc (если /npc занят Citizens). */
final class NpcCommand extends Command {

    static final String PERMISSION = "corenpc.admin";
    /** Добавлять NPC консольные команды: это доступ к консоли сервера, поэтому отдельное право (у OP есть). */
    static final String CONSOLE_PERMISSION = "corenpc.admin.console";
    private static final List<String> SUBS = List.of("create", "remove", "list", "info", "tp", "tphere", "rename", "skin",
        "type", "model", "dialog", "command", "set", "look", "talk", "open", "close", "reload");
    private static final List<String> SET_KEYS = List.of("look-at", "look-radius", "gravity", "collidable", "permission",
        "cooldown", "left-click", "hologram", "lines", "scale");

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final NpcManager npcs;
    private final DialogManager dialogs;
    private final ModelHook models;
    private final Consumer<Consumer<String>> reload;

    NpcCommand(final SystemContext context, final Supplier<Settings> settings, final NpcManager npcs,
               final DialogManager dialogs, final ModelHook models, final Consumer<Consumer<String>> reload) {
        super("npc", "NPC core-npc: создание, скины, модели, диалоги, команды",
            "/npc create|remove|list|info|tp|tphere|rename|skin|type|model|dialog|command|set|talk|open|close|reload",
            List.of("cnpc", "corenpc"));
        this.setPermission(PERMISSION);
        this.context = context;
        this.settings = settings;
        this.npcs = npcs;
        this.dialogs = dialogs;
        this.models = models;
        this.reload = reload;
    }

    private void msg(final CommandSender to, final String key, final TagResolver... r) {
        Text.send(to, this.settings.get(), key, r);
    }

    /** Ответ из любого потока: игроку — в его потоке, консоли — в глобальном. */
    private void reply(final CommandSender to, final String key, final TagResolver... r) {
        final Component c = Text.message(this.settings.get(), key, r);
        if (c == null) {
            return;
        }
        if (to instanceof Player p) {
            this.context.runOnceForPlayer(p, () -> p.sendMessage(c));
        } else {
            Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> to.sendMessage(c));
        }
    }

    private static TagResolver id(final String id) {
        return Placeholder.unparsed("id", id);
    }

    private NpcDef npc(final CommandSender sender, final String[] args, final int at) {
        if (args.length <= at) {
            this.msg(sender, "usage");
            return null;
        }
        final NpcDef d = this.npcs.get(args[at]);
        if (d == null) {
            this.msg(sender, "no-npc", id(args[at]));
        }
        return d;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        final String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> this.create(sender, args);
            case "remove", "delete" -> {
                final NpcDef d = this.npc(sender, args, 1);
                if (d != null && this.npcs.remove(d.id())) {
                    this.msg(sender, "removed", id(d.id()));
                }
            }
            case "list" -> this.list(sender);
            case "info" -> this.info(sender, args);
            case "tp" -> {
                final NpcDef d = this.npc(sender, args, 1);
                if (d != null && sender instanceof Player p) {
                    final World w = Bukkit.getWorld(d.world());
                    if (w != null) {
                        p.teleportAsync(new Location(w, d.x(), d.y(), d.z(), d.yaw(), d.pitch()));
                        this.msg(sender, "teleported", id(d.id()));
                    }
                } else if (d != null) {
                    this.msg(sender, "no-player-sender");
                }
            }
            case "tphere", "move" -> {
                final NpcDef d = this.npc(sender, args, 1);
                if (d != null && sender instanceof Player p) {
                    final Location l = p.getLocation();
                    this.npcs.update(d.id(), x -> x.withLocation(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                        l.getYaw(), l.getPitch()));
                    this.msg(sender, "moved", id(d.id()));
                } else if (d != null) {
                    this.msg(sender, "no-player-sender");
                }
            }
            case "rename", "name" -> {
                final NpcDef d = this.npc(sender, args, 1);
                if (d != null) {
                    final String name = Args.join(args, 2);
                    final String v = name.equals("-") ? "" : name;
                    this.npcs.update(d.id(), x -> x.withName(v));
                    this.msg(sender, "renamed", id(d.id()), Placeholder.parsed("name", v.isEmpty() ? "<gray>—</gray>" : v));
                }
            }
            case "skin" -> this.skin(sender, args);
            case "type" -> {
                final NpcDef d = this.npc(sender, args, 1);
                if (d != null && args.length >= 3) {
                    final EntityType t = NpcManager.parseType(args[2]);
                    if (t == null) {
                        this.msg(sender, "bad-type", Placeholder.unparsed("type", args[2]));
                    } else {
                        final String key = t.getKey().getKey();
                        this.npcs.update(d.id(), x -> x.withType(key));
                        this.msg(sender, "type-set", id(d.id()), Placeholder.unparsed("type", key));
                    }
                } else if (d != null) {
                    this.msg(sender, "usage");
                }
            }
            case "model" -> this.model(sender, args);
            case "dialog" -> {
                final NpcDef d = this.npc(sender, args, 1);
                if (d == null) {
                    return true;
                }
                if (args.length < 3 || args[2].equalsIgnoreCase("none") || args[2].equals("-")) {
                    this.npcs.update(d.id(), x -> x.withDialog(""));
                    this.msg(sender, "dialog-cleared", id(d.id()));
                } else if (this.dialogs.registry().get(args[2]) == null) {
                    this.msg(sender, "no-dialog", Placeholder.unparsed("dialog", args[2]));
                } else {
                    this.npcs.update(d.id(), x -> x.withDialog(args[2]));
                    this.msg(sender, "dialog-set", id(d.id()), Placeholder.unparsed("dialog", args[2]));
                }
            }
            case "command", "cmd" -> this.command(sender, args);
            case "set" -> this.set(sender, args);
            case "look" -> this.look(sender, args);
            case "talk" -> this.talk(sender, args);
            case "open" -> this.open(sender, args);
            case "close" -> {
                final Player t = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (t != null) {
                    this.context.runOnceForPlayer(t, () -> this.dialogs.close(t));
                }
            }
            case "reload" -> this.reload.accept(err -> {
                // файлы читаются вне потоков тика; ответ — когда применено (или ничего не изменилось)
                if (err == null) {
                    this.reply(sender, "reloaded", Placeholder.unparsed("npcs", Integer.toString(this.npcs.all().size())),
                        Placeholder.unparsed("dialogs", Integer.toString(this.dialogs.registry().size())));
                } else {
                    this.reply(sender, "reload-failed", Placeholder.unparsed("reason", err));
                }
            });
            default -> this.msg(sender, "usage");
        }
        return true;
    }

    private void create(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            this.msg(sender, "no-player-sender");
            return;
        }
        if (args.length < 2) {
            this.msg(sender, "usage");
            return;
        }
        if (!Args.validId(args[1])) {
            this.msg(sender, "bad-id");
            return;
        }
        final String type = args.length >= 3 ? args[2] : "mannequin";
        final EntityType t = NpcManager.parseType(type);
        if (t == null) {
            this.msg(sender, "bad-type", Placeholder.unparsed("type", type));
            return;
        }
        final Location l = p.getLocation();
        final NpcDef d = NpcDef.create(args[1], t.getKey().getKey(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
            l.getYaw(), l.getPitch());
        if (!this.npcs.add(d)) {
            this.msg(sender, "exists", id(args[1]));
            return;
        }
        this.msg(sender, "created", id(d.id()), Placeholder.unparsed("type", d.type()));
    }

    private void list(final CommandSender sender) {
        final List<NpcDef> all = this.npcs.all();
        if (all.isEmpty()) {
            this.msg(sender, "list-empty");
            return;
        }
        final List<String> parts = new ArrayList<>();
        for (final NpcDef d : all) {
            final String c = this.npcs.isLive(d.id()) ? "green" : "gray";
            parts.add("<" + c + ">" + d.id() + "</" + c + ">");
        }
        this.msg(sender, "list", Placeholder.unparsed("count", Integer.toString(all.size())),
            Placeholder.parsed("list", String.join("<dark_gray>, </dark_gray>", parts)));
    }

    private void info(final CommandSender sender, final String[] args) {
        final NpcDef d = this.npc(sender, args, 1);
        if (d == null) {
            return;
        }
        final String s = "<gold>" + d.id() + "</gold> <gray>(" + d.type() + (this.npcs.isLive(d.id()) ? ", в мире" : ", не стоит")
            + ")</gray>\n<gray>Где:</gray> " + d.world() + " " + Math.round(d.x() * 10) / 10.0 + " " + Math.round(d.y() * 10) / 10.0
            + " " + Math.round(d.z() * 10) / 10.0
            + "\n<gray>Имя:</gray> <reset>" + (d.name().isBlank() ? "—" : d.name()) + "<reset> <gray>(" + d.hologram() + ")</gray>"
            + "\n<gray>Скин:</gray> " + (d.skin() == null ? "—" : Text.MM.escapeTags(d.skin().name().isBlank() ? "текстура" : d.skin().name()))
            + "\n<gray>Модель:</gray> " + (d.model() == null ? "—" : Text.MM.escapeTags(d.model().id() + " ×" + d.model().scale()
            + " idle=" + d.model().idle() + " interact=" + d.model().interact()))
            + "\n<gray>Диалог:</gray> " + (d.dialog().isBlank() ? "—" : Text.MM.escapeTags(d.dialog()))
            + "\n<gray>Команд:</gray> " + d.commands().size() + " <gray>право:</gray> "
            + (d.permission().isBlank() ? "—" : Text.MM.escapeTags(d.permission())) + " <gray>кулдаун:</gray> "
            + (d.cooldown() < 0 ? "по умолчанию" : d.cooldown() + " с") + " <gray>ЛКМ:</gray> " + d.leftClick()
            + "\n<gray>Смотрит на игрока:</gray> " + d.lookAt() + " <gray>гравитация:</gray> " + d.gravity()
            + " <gray>масштаб:</gray> " + d.scale();
        sender.sendMessage(Text.parse(s));
    }

    private void skin(final CommandSender sender, final String[] args) {
        final NpcDef d = this.npc(sender, args, 1);
        if (d == null) {
            return;
        }
        if (args.length < 3) {
            this.msg(sender, "usage");
            return;
        }
        if (!d.mannequin()) {
            this.msg(sender, "skin-not-mannequin", Placeholder.unparsed("type", d.type()));
        }
        if (args[2].equalsIgnoreCase("clear") || args[2].equals("-")) {
            this.npcs.update(d.id(), x -> x.withSkin(null));
            this.msg(sender, "skin-cleared", id(d.id()));
            return;
        }
        if (args[2].equalsIgnoreCase("value") || args[2].equalsIgnoreCase("texture")) {
            if (args.length < 4) {
                this.msg(sender, "usage");
                return;
            }
            final String sig = args.length >= 5 ? args[4] : "";
            this.npcs.update(d.id(), x -> x.withSkin(new NpcDef.Skin("", args[3], sig)));
            this.msg(sender, "skin-set", id(d.id()), Placeholder.unparsed("name", "текстура"));
            return;
        }
        final String name = args[2];
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            this.msg(sender, "skin-failed", Placeholder.unparsed("name", name), Placeholder.unparsed("reason", "не похоже на ник"));
            return;
        }
        this.msg(sender, "skin-loading", Placeholder.unparsed("name", name));
        // Профиль ищется вне потоков тика (сеть), результат кладётся в npcs.yml и NPC ставится заново в его регионе.
        final PlayerProfile profile = Bukkit.createProfile(name);
        profile.update().whenComplete((pp, err) -> {
            if (this.npcs.isClosed()) {
                return;   // система выключена или перезапущена: не трогать npcs.yml новой копии
            }
            ProfileProperty tex = null;
            if (err == null && pp != null) {
                for (final ProfileProperty pr : pp.getProperties()) {
                    if (pr.getName().equals("textures")) {
                        tex = pr;
                    }
                }
            }
            if (tex == null) {
                this.reply(sender, "skin-failed", Placeholder.unparsed("name", name),
                    Placeholder.unparsed("reason", err != null ? String.valueOf(err.getMessage()) : "нет текстуры (нет такого игрока или Mojang недоступен)"));
                return;
            }
            final String real = pp.getName() != null ? pp.getName() : name;
            final NpcDef.Skin skin = new NpcDef.Skin(real, tex.getValue(), tex.getSignature() == null ? "" : tex.getSignature());
            if (this.npcs.update(d.id(), x -> x.withSkin(skin)) != null) {
                this.reply(sender, "skin-set", id(d.id()), Placeholder.unparsed("name", real));
            }
        });
    }

    private void model(final CommandSender sender, final String[] args) {
        final NpcDef d = this.npc(sender, args, 1);
        if (d == null) {
            return;
        }
        if (args.length < 3) {
            this.msg(sender, "usage");
            return;
        }
        if (args[2].equalsIgnoreCase("remove") || args[2].equalsIgnoreCase("none") || args[2].equals("-")) {
            this.npcs.update(d.id(), x -> x.withModel(null));
            this.msg(sender, "model-removed", id(d.id()));
            return;
        }
        if (args[2].equalsIgnoreCase("anim") || args[2].equalsIgnoreCase("animation")) {
            if (d.model() == null || args.length < 5) {
                this.msg(sender, "usage");
                return;
            }
            final String which = args[3].toLowerCase(Locale.ROOT);
            final String anim = args[4].equals("-") ? "" : args[4];
            final NpcDef.ModelSpec m = d.model();
            final NpcDef.ModelSpec nm = switch (which) {
                case "idle" -> new NpcDef.ModelSpec(m.id(), m.scale(), anim, m.interact());
                case "interact", "click" -> new NpcDef.ModelSpec(m.id(), m.scale(), m.idle(), anim);
                default -> null;
            };
            if (nm == null) {
                this.msg(sender, "usage");
                return;
            }
            this.npcs.update(d.id(), x -> x.withModel(nm));
            this.msg(sender, "set", id(d.id()), Placeholder.unparsed("key", "model." + which), Placeholder.unparsed("value", anim));
            return;
        }
        double scale = 1.0;
        if (args.length >= 4) {
            final Double v = Args.number(args[3]);
            if (v == null || v <= 0 || v > 64) {
                this.msg(sender, "bad-value", Placeholder.unparsed("value", args[3]), Placeholder.unparsed("key", "scale"));
                return;
            }
            scale = v;
        }
        final String modelId = args[2];
        final NpcDef.ModelSpec old = d.model();
        final NpcDef.ModelSpec spec = new NpcDef.ModelSpec(modelId, scale, old == null ? "" : old.idle(),
            old == null ? "" : old.interact());
        this.npcs.update(d.id(), x -> x.withModel(spec));
        this.msg(sender, "model-set", id(d.id()), Placeholder.unparsed("model", modelId),
            Placeholder.unparsed("scale", Double.toString(scale)));
        if (!this.models.available()) {
            this.msg(sender, "no-modelengine");
        } else if (!this.models.hasModel(modelId)) {
            this.msg(sender, "model-unknown", Placeholder.unparsed("model", modelId));
        }
    }

    /** /npc command add &lt;id&gt; &lt;player|console|message|sound&gt; &lt;текст&gt; | list &lt;id&gt; | remove &lt;id&gt; &lt;номер&gt;. */
    private void command(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            this.msg(sender, "usage");
            return;
        }
        final String op = args[1].toLowerCase(Locale.ROOT);
        final NpcDef d = this.npc(sender, args, 2);
        if (d == null) {
            return;
        }
        switch (op) {
            case "add" -> {
                if (args.length < 5) {
                    this.msg(sender, "usage");
                    return;
                }
                final Action a = Action.parse(args[3] + ": " + Args.join(args, 4));
                if (a == null || a.value().isBlank() || a.type() == Action.Type.GOTO || a.type() == Action.Type.CLOSE) {
                    this.msg(sender, "usage");
                    return;
                }
                if (a.type() == Action.Type.CONSOLE && !sender.hasPermission(CONSOLE_PERMISSION)) {
                    this.msg(sender, "no-console-permission");
                    return;
                }
                final List<Action> list = new ArrayList<>(d.commands());
                list.add(a);
                this.npcs.update(d.id(), x -> x.withCommands(list));
                this.msg(sender, "command-added", id(d.id()), Placeholder.unparsed("index", Integer.toString(list.size())),
                    Placeholder.unparsed("command", a.serialize()));
            }
            case "list" -> {
                if (d.commands().isEmpty()) {
                    this.msg(sender, "commands-empty", id(d.id()));
                    return;
                }
                for (int i = 0; i < d.commands().size(); i++) {
                    this.msg(sender, "command-line", Placeholder.unparsed("index", Integer.toString(i + 1)),
                        Placeholder.unparsed("command", d.commands().get(i).serialize()));
                }
            }
            case "remove", "delete" -> {
                final Double n = args.length >= 4 ? Args.number(args[3]) : null;
                final int idx = n == null ? -1 : n.intValue() - 1;
                if (idx < 0 || idx >= d.commands().size()) {
                    this.msg(sender, "command-bad-index", Placeholder.unparsed("index", args.length >= 4 ? args[3] : "?"));
                    return;
                }
                final List<Action> list = new ArrayList<>(d.commands());
                list.remove(idx);
                this.npcs.update(d.id(), x -> x.withCommands(list));
                this.msg(sender, "command-removed", id(d.id()), Placeholder.unparsed("index", Integer.toString(idx + 1)));
            }
            default -> this.msg(sender, "usage");
        }
    }

    private void set(final CommandSender sender, final String[] args) {
        final NpcDef d = this.npc(sender, args, 1);
        if (d == null) {
            return;
        }
        if (args.length < 4) {
            this.msg(sender, "bad-key", Placeholder.unparsed("key", args.length >= 3 ? args[2] : "?"),
                Placeholder.unparsed("keys", String.join(", ", SET_KEYS)));
            return;
        }
        final String key = args[2].toLowerCase(Locale.ROOT);
        final String raw = Args.join(args, 3);
        final Object value = switch (key) {
            case "look-at", "gravity", "collidable", "left-click" -> Args.bool(raw);
            case "look-radius" -> {
                final Double v = Args.number(raw);
                yield v == null || v > 64 ? null : v;
            }
            case "scale" -> {
                final Double v = Args.number(raw);
                yield v == null || v <= 0 || v > 16 ? null : v;
            }
            case "cooldown" -> raw.equals("-") || raw.equalsIgnoreCase("default") ? Double.valueOf(-1) : Args.seconds(raw);
            case "permission" -> raw.equals("-") ? "" : raw;
            case "hologram" -> List.of("name", "display", "none").contains(raw.toLowerCase(Locale.ROOT)) ? raw.toLowerCase(Locale.ROOT) : null;
            case "lines" -> raw.equals("-") ? List.<String>of() : Arrays.asList(raw.split("\\s*\\|\\s*"));
            default -> {
                this.msg(sender, "bad-key", Placeholder.unparsed("key", key), Placeholder.unparsed("keys", String.join(", ", SET_KEYS)));
                yield Boolean.FALSE;
            }
        };
        if (Boolean.FALSE.equals(value) && !SET_KEYS.contains(key)) {
            return;
        }
        if (value == null) {
            this.msg(sender, "bad-value", Placeholder.unparsed("value", raw), Placeholder.unparsed("key", key));
            return;
        }
        this.npcs.update(d.id(), x -> x.withOption(key, value));
        this.msg(sender, "set", id(d.id()), Placeholder.unparsed("key", key), Placeholder.unparsed("value", raw));
    }

    /**
     * /npc look &lt;id&gt; [on|off|me] — следить за игроком или стоять статично. Без аргумента — переключить.
     * off — стоит, как поставили (поворот из create/tphere); me — статично и лицом к вам.
     */
    private void look(final CommandSender sender, final String[] args) {
        final NpcDef d = this.npc(sender, args, 1);
        if (d == null) {
            return;
        }
        final String mode = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : (d.lookAt() ? "off" : "on");
        switch (mode) {
            case "on", "true", "вкл" -> {
                this.npcs.update(d.id(), x -> x.withOption("look-at", Boolean.TRUE));
                this.msg(sender, "look-on", id(d.id()));
            }
            case "off", "false", "выкл" -> {
                this.npcs.update(d.id(), x -> x.withOption("look-at", Boolean.FALSE));
                this.msg(sender, "look-off", id(d.id()));
            }
            case "me", "here" -> {
                if (!(sender instanceof Player p)) {
                    this.msg(sender, "no-player-sender");
                    return;
                }
                if (!p.getWorld().getName().equals(d.world())) {
                    this.msg(sender, "look-other-world", id(d.id()));
                    return;
                }
                final Location e = p.getEyeLocation();
                final double dx = e.getX() - d.x();
                final double dz = e.getZ() - d.z();
                final float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                this.npcs.update(d.id(), x -> x.withOption("look-at", Boolean.FALSE)
                    .withLocation(d.world(), d.x(), d.y(), d.z(), yaw, 0f));
                this.msg(sender, "look-me", id(d.id()));
            }
            default -> this.msg(sender, "bad-value", Placeholder.unparsed("value", mode), Placeholder.unparsed("key", "look"));
        }
    }

    /** /npc talk &lt;id&gt; [игрок] — открыть диалог NPC игроку (как будто он кликнул, без команд и кулдауна). */
    private void talk(final CommandSender sender, final String[] args) {
        final NpcDef d = this.npc(sender, args, 1);
        if (d == null) {
            return;
        }
        if (d.dialog().isBlank()) {
            this.msg(sender, "no-dialog", Placeholder.unparsed("dialog", "—"));
            return;
        }
        this.openFor(sender, args.length >= 3 ? args[2] : null, d.dialog(), d);
    }

    /** /npc open &lt;диалог&gt; &lt;игрок&gt; [npc] — открыть любой диалог (для других плагинов и систем). */
    private void open(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            this.msg(sender, "usage");
            return;
        }
        if (this.dialogs.registry().get(args[1]) == null) {
            this.msg(sender, "no-dialog", Placeholder.unparsed("dialog", args[1]));
            return;
        }
        final NpcDef npc = args.length >= 4 ? this.npcs.get(args[3]) : null;
        this.openFor(sender, args.length >= 3 ? args[2] : null, args[1], npc);
    }

    private void openFor(final CommandSender sender, final String playerName, final String dialog, final NpcDef npc) {
        final Player target = playerName != null ? Bukkit.getPlayerExact(playerName) : sender instanceof Player p ? p : null;
        if (target == null) {
            this.msg(sender, playerName == null ? "no-player-sender" : "no-target", Placeholder.unparsed("player", String.valueOf(playerName)));
            return;
        }
        this.context.runOnceForPlayer(target, () -> this.dialogs.open(target, dialog, npc));
        this.msg(sender, "talk-started", Placeholder.unparsed("dialog", dialog), Placeholder.unparsed("player", target.getName()));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        final String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        final List<String> ids = new ArrayList<>();
        for (final NpcDef d : this.npcs.all()) {
            ids.add(d.id());
        }
        if (args.length == 1) {
            out.addAll(SUBS);
        } else if (args.length == 2) {
            switch (sub) {
                case "create" -> out.add("<id>");
                case "command", "cmd" -> out.addAll(List.of("add", "list", "remove"));
                case "open" -> out.addAll(this.dialogs.registry().ids());
                case "close" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                case "list", "reload" -> {
                }
                default -> out.addAll(ids);
            }
        } else if (args.length == 3) {
            switch (sub) {
                case "create", "type" -> {
                    for (final EntityType t : Registry.ENTITY_TYPE) {
                        if (NpcManager.parseType(t.getKey().getKey()) != null) {
                            out.add(t.getKey().getKey());
                        }
                    }
                }
                case "skin" -> {
                    out.addAll(List.of("value", "clear"));
                    Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                }
                case "model" -> {
                    out.addAll(List.of("remove", "anim"));
                    out.addAll(this.models.models());
                }
                case "dialog" -> {
                    out.add("none");
                    out.addAll(this.dialogs.registry().ids());
                }
                case "command", "cmd" -> out.addAll(ids);
                case "set" -> out.addAll(SET_KEYS);
                case "look" -> out.addAll(List.of("on", "off", "me"));
                case "talk", "open" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                case "rename" -> out.add("<имя>");
                default -> {
                }
            }
        } else if (args.length == 4) {
            switch (sub) {
                case "command", "cmd" -> {
                    if (args[1].equalsIgnoreCase("add")) {
                        out.addAll(List.of("player", "console", "message", "sound"));
                    } else if (args[1].equalsIgnoreCase("remove")) {
                        final NpcDef d = this.npcs.get(args[2]);
                        for (int i = 1; d != null && i <= d.commands().size(); i++) {
                            out.add(Integer.toString(i));
                        }
                    }
                }
                case "set" -> {
                    switch (args[2].toLowerCase(Locale.ROOT)) {
                        case "look-at", "gravity", "collidable", "left-click" -> out.addAll(List.of("true", "false"));
                        case "hologram" -> out.addAll(List.of("name", "display", "none"));
                        case "cooldown" -> out.addAll(List.of("0", "1", "3s", "1m", "default"));
                        default -> {
                        }
                    }
                }
                case "model" -> {
                    if (args[2].equalsIgnoreCase("anim")) {
                        out.addAll(List.of("idle", "interact"));
                    } else if (!args[2].equalsIgnoreCase("remove")) {
                        out.addAll(List.of("1", "0.5", "1.5", "2"));
                    }
                }
                case "open" -> out.addAll(ids);
                default -> {
                }
            }
        }
        final String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
