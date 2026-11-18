package core.amazake.systems.coremechanics.statue;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coremechanics.Settings;
import core.amazake.systems.coremechanics.doll.Doll;
import core.amazake.systems.coremechanics.doll.DollRenderer;
import core.amazake.systems.coremechanics.doll.Picking;
import core.amazake.systems.coremechanics.doll.Piece;
import core.amazake.systems.coremechanics.doll.Pose;
import core.amazake.systems.coremechanics.doll.PoseMath;
import core.amazake.systems.coremechanics.doll.RagdollManager;
import core.amazake.systems.coremechanics.physics.RagdollSpec;
import core.amazake.systems.coremechanics.skin.BodyLooks;
import core.amazake.systems.coremechanics.skin.MojangProfiles;
import core.amazake.systems.coremechanics.skin.SkinService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Куклы-статуи ({@code /rd statue|pose|anim|frame|tool…}, перенесено из MagicRuruka DollStudio): кукла со скином
 * игрока стоит в позе, играет анимацию или свой мультик из записанных кадров. «Кукловод» (палочка) крутит части по 15°,
 * F — записать кадр, Shift+F — проиграть. Ударишь или взорвёшь рядом — статуя обмякает настоящим рэгдоллом (Jolt) и
 * через statues.topple-seconds встаёт обратно.
 *
 * <p>Потоки: тик статуи — задача региона её чанка (глобальный таймер раз в тик ставит её, не больше одной в очереди);
 * манекен рисуется там же. Команды и кукловод — в потоке игрока: меняют только volatile-описание статуи, а действия с
 * сущностями (обмякнуть, встать, перенести, убрать) кладут в очередь статуи.</p>
 */
public final class StatueManager implements Listener {

    private static final String[] PART_NAMES = {"голова", "тело", "правая рука", "левая рука", "правая нога", "левая нога"};
    private static final String[] AXES = {"X (вперёд/назад)", "Y (кручение)", "Z (в сторону)", "высота"};
    /** Тег корня в экспорте /summon. */
    static final String SUMMON_TAG = "cm_statue";
    public static final List<String> SUBS = List.of("statue", "summon", "pose", "anim", "frame", "tool", "topple", "up", "turn", "move",
        "remove", "removeall", "list", "poses");

    /** Выбор кукловода: статуя, часть, ось. */
    private record Sel(UUID statue, int part, int axis) {
    }

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final RagdollManager ragdolls;
    private final DollRenderer renderer;
    private final SkinService skins;
    private final NamespacedKey toolKey = new NamespacedKey("coremechanics", "doll_tool");
    private final Path file;
    private final Map<UUID, Statue> statues = new ConcurrentHashMap<>();
    private final Map<UUID, Sel> sel = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    /** Статуи из миров, которых сейчас нет: хранятся как есть, чтобы не потерять при сохранении. */
    private final Map<String, Map<String, Object>> orphans = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile boolean closed;
    private volatile ScheduledTask tickTask;
    private volatile ScheduledTask saveTask;

    public StatueManager(final SystemContext context, final Supplier<Settings> settings, final RagdollManager ragdolls,
                         final DollRenderer renderer, final SkinService skins) {
        this.context = context;
        this.settings = settings;
        this.ragdolls = ragdolls;
        this.renderer = renderer;
        this.skins = skins;
        this.file = context.dataFolder().resolve("statues.yml");
    }

    /** Глобальный поток (enable): прочитать statues.yml, запустить тики и автосохранение. */
    public void start() {
        this.load();
        this.tickTask = this.context.runGlobalTimer(t -> this.tickAll(), Duration.ofMillis(50));
        this.saveTask = this.context.runGlobalTimer(t -> this.flushIfDirty(), Duration.ofSeconds(2));
        if (!this.statues.isEmpty()) {
            // команды /summon статуй — в statue_summon/ (когда куклы встанут и скин тела подгрузится)
            Bukkit.getAsyncScheduler().runDelayed(this.context.plugin(), t -> {
                if (!this.closed) {
                    this.exportAll(null);
                }
            }, 60, TimeUnit.SECONDS);
        }
    }

    public int count() {
        return (int) this.statues.values().stream().filter(s -> !s.removed).count();
    }

    // ================================================================== команды (/rd …)

    /** Поток игрока. true — команда наша (иначе /rd ник — обычный рэгдолл). */
    public boolean command(final Player p, final String[] a) {
        final String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "statue" -> {
                if (a.length < 2) {
                    msg(p, "/rd statue <ник> [поза|анимация]", NamedTextColor.YELLOW);
                    return true;
                }
                this.spawnStatue(p, a[1], a.length >= 3 ? a[2] : null);
                return true;
            }
            case "pose" -> {
                final Statue s = this.target(p);
                if (s == null || a.length < 2) {
                    msg(p, s == null ? "Посмотри на статую (или выбери кукловодом)." : "Позы: " + String.join(", ", DollPoses.POSES.keySet()),
                        NamedTextColor.RED);
                    return true;
                }
                final String id = a[1].toLowerCase(Locale.ROOT);
                final float[] ps = DollPoses.pose(id);
                if (ps == null) {
                    msg(p, "Нет позы. Позы: " + String.join(", ", DollPoses.POSES.keySet()), NamedTextColor.RED);
                    return true;
                }
                s.setPose(ps);
                this.save();
                msg(p, "Поза: " + DollPoses.POSE_TITLES.get(id), NamedTextColor.GREEN);
                return true;
            }
            case "summon" -> {
                this.summon(p, this.target(p));
                return true;
            }
            case "anim" -> {
                final Statue s = this.target(p);
                if (s == null || a.length < 2) {
                    msg(p, s == null ? "Посмотри на статую." : "Анимации: " + String.join(", ", DollPoses.ANIMS.keySet()) + ", stop",
                        NamedTextColor.RED);
                    return true;
                }
                final String id = a[1].toLowerCase(Locale.ROOT);
                if (id.equals("stop")) {
                    s.anim = null;
                    s.playing = false;
                } else if (DollPoses.ANIMS.containsKey(id)) {
                    s.playing = false;
                    s.anim = id;
                } else {
                    msg(p, "Нет анимации. Есть: " + String.join(", ", DollPoses.ANIMS.keySet()), NamedTextColor.RED);
                    return true;
                }
                this.save();
                msg(p, id.equals("stop") ? "Анимация остановлена" : "Анимация: " + DollPoses.ANIMS.get(id).title(), NamedTextColor.GREEN);
                return true;
            }
            case "frame" -> {
                final Statue s = this.target(p);
                if (s == null) {
                    msg(p, "Посмотри на статую.", NamedTextColor.RED);
                    return true;
                }
                final String op = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "";
                switch (op) {
                    case "add" -> this.addFrame(p, s);
                    case "clear" -> {
                        s.frames.clear();
                        s.playing = false;
                        msg(p, "Кадры стёрты", NamedTextColor.YELLOW);
                    }
                    case "play" -> this.togglePlay(p, s, true);
                    case "stop" -> this.togglePlay(p, s, false);
                    case "speed" -> {
                        final Integer n = a.length >= 3 ? parseInt(a[2]) : null;
                        if (n == null) {
                            msg(p, "/rd frame speed <тиков на кадр>", NamedTextColor.RED);
                        } else {
                            s.frameTicks = Math.max(1, Math.min(200, n));
                            msg(p, "Кадр длится " + s.frameTicks + " тик.", NamedTextColor.GREEN);
                        }
                    }
                    default -> msg(p, "/rd frame add|clear|play|stop|speed <тики> (кадров: " + s.frames.size() + ")", NamedTextColor.YELLOW);
                }
                this.save();
                return true;
            }
            case "tool" -> {
                p.getInventory().addItem(this.tool());
                msg(p, "Кукловод: ПКМ по части статуи — выбрать, ЛКМ +15° / Shift+ЛКМ −15°, Shift+ПКМ — ось, F — кадр, Shift+F — проиграть.",
                    NamedTextColor.GREEN);
                return true;
            }
            case "topple" -> {
                final Statue s = this.target(p);
                if (s != null) {
                    final Vector d = p.getEyeLocation().getDirection();
                    this.topple(s, d.getX() * 0.5, 0.25, d.getZ() * 0.5);
                }
                return true;
            }
            case "up" -> {
                final Statue s = this.target(p);
                if (s != null) {
                    s.ops.add(st -> {
                        if (st.doll != null) {
                            st.dollTimer = 1;
                        }
                    });
                }
                return true;
            }
            case "turn" -> {
                final Statue s = this.target(p);
                if (s != null) {
                    s.yaw = p.getLocation().getYaw() + 180f;
                    this.save();
                }
                return true;
            }
            case "move" -> {
                final Statue s = this.target(p);
                if (s != null) {
                    final Location l = p.getLocation();
                    final String wn = l.getWorld().getName();
                    final double nx = l.getX(), ny = l.getY(), nz = l.getZ();
                    s.ops.add(st -> {
                        this.despawnPuppet(st);
                        st.worldName = wn;
                        st.x = nx;
                        st.y = ny;
                        st.z = nz;
                        st.walk = 0;
                        st.lastYaw = Float.NaN;
                        this.save();
                    });
                    msg(p, "Статуя перенесена к тебе", NamedTextColor.GREEN);
                }
                return true;
            }
            case "remove" -> {
                final Statue s = this.target(p);
                if (s == null) {
                    msg(p, "Посмотри на статую.", NamedTextColor.RED);
                    return true;
                }
                this.removeStatue(s);
                this.save();
                msg(p, "Статуя убрана", NamedTextColor.YELLOW);
                return true;
            }
            case "removeall" -> {
                int n = 0;
                for (final Statue s : this.statues.values()) {
                    if (!s.removed) {
                        this.removeStatue(s);
                        n++;
                    }
                }
                this.save();
                msg(p, "Статуй убрано: " + n, NamedTextColor.YELLOW);
                return true;
            }
            case "list" -> {
                final List<Statue> all = this.statues.values().stream().filter(s -> !s.removed).toList();
                if (all.isEmpty()) {
                    msg(p, "Статуй нет.", NamedTextColor.GRAY);
                }
                for (final Statue s : all) {
                    msg(p, s.name + " — " + s.worldName + " " + (int) s.x + " " + (int) s.y + " " + (int) s.z
                        + (s.anim != null ? ", анимация " + s.anim : "") + (s.frames.isEmpty() ? "" : ", кадров " + s.frames.size()),
                        NamedTextColor.YELLOW);
                }
                return true;
            }
            case "poses" -> {
                msg(p, "Позы: " + String.join(", ", DollPoses.POSES.keySet()), NamedTextColor.YELLOW);
                msg(p, "Анимации: " + String.join(", ", DollPoses.ANIMS.keySet()), NamedTextColor.YELLOW);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static Integer parseInt(final String s) {
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static void msg(final CommandSender p, final String t, final NamedTextColor c) {
        p.sendMessage(Component.text(t, c));
    }

    // ================================================================== /summon

    /**
     * /rd summon — статуя в текущей позе как ванильная команда /summon: командный блок с ней — в инвентарь, текст — в
     * systems/core-mechanics/statue_summon/ и в чат (клик — скопировать). ~ ~ ~ — ноги статуи.
     */
    private void summon(final Player p, final Statue s) {
        if (s == null || s.doll != null) {
            msg(p, s == null ? "Посмотри на статую (/rd statue <ник> [поза])." : "Статуя сейчас лежит — подожди, пока встанет.",
                NamedTextColor.RED);
            return;
        }
        final String cmd = this.summonOf(s, s.pose);
        final String fileName = fileBase(s) + "_" + s.id.toString().substring(0, 8) + ".mcfunction";
        this.writeAsync(List.<String[]>of(new String[] {fileName, cmd}));
        final ItemStack cb = new ItemStack(Material.COMMAND_BLOCK);
        cb.editMeta(m -> {
            if (m instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof org.bukkit.block.CommandBlock st) {
                st.setCommand(cmd);
                bsm.setBlockState(st);
            }
            m.displayName(Component.text("Статуя " + s.name + " (summon)", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(Component.text("Поставь и подай сигнал — статуя встанет на блок выше", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        });
        p.getInventory().addItem(cb);
        final boolean cubes = !cmd.contains("minecraft:block_display\",block_state");
        p.sendMessage(Component.text("Команда статуи " + s.name + " (" + cmd.length() + " симв.) — командный блок в инвентаре, файл "
                + fileName + ". ", NamedTextColor.GREEN)
            .append(Component.text("[скопировать]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.copyToClipboard("/" + cmd))
                .hoverEvent(HoverEvent.showText(Component.text("В чат не влезет (256) — только командный блок / функция"))))
            .append(Component.text(cubes ? "" : "\nСкин тела ещё генерируется — тело пока из цветных блоков; повтори позже.", NamedTextColor.GRAY))
            .append(Component.text("\nУбрать: /kill @e[tag=" + SUMMON_TAG + ",distance=..3] и /kill @e[type=item_display,distance=..3]",
                NamedTextColor.DARK_GRAY)));
    }

    /** Команда /summon статуи в позе pose (стоит на месте статуи). Чистая функция — любой поток. */
    String summonOf(final Statue s, final float[] pose) {
        final Pose ps = PoseMath.full(s.x, s.y, s.z, s.yaw, pose);
        final List<Piece> pieces = new ArrayList<>();
        pieces.add(BodyLooks.head(s.tex, s.sig));
        pieces.addAll(this.skins.body(s.owner));
        return SummonExport.command(ps, pieces, s.x, s.y, s.z, SUMMON_TAG);
    }

    private static String fileBase(final Statue s) {
        return s.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }

    /** Консоль: /rd summon — команды всех статуй в statue_summon/ (to == null — в лог). Файлы пишутся вне тиков. */
    public void exportAll(final CommandSender to) {
        final List<String[]> files = new ArrayList<>();
        for (final Statue s : this.statues.values()) {
            if (s.removed) {
                continue;
            }
            files.add(new String[] {fileBase(s) + "_" + s.id.toString().substring(0, 8) + ".mcfunction", this.summonOf(s, s.pose)});
            files.add(new String[] {fileBase(s) + "_point.mcfunction", this.summonOf(s, DollPoses.pose("point"))});
        }
        final int n = files.size() / 2;
        this.writeAsync(files);
        final String text = "Статуй выгружено в statue_summon/: " + n;
        if (to != null) {
            to.sendMessage(text);
        } else {
            this.context.logger().info(text);
        }
    }

    private void writeAsync(final List<String[]> files) {
        final Path dir = this.context.dataFolder().resolve("statue_summon");
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), t -> {
            try {
                Files.createDirectories(dir);
                for (final String[] f : files) {
                    final Path tmp = dir.resolve(f[0] + ".tmp");
                    Files.writeString(tmp, f[1] + "\n", StandardCharsets.UTF_8);
                    Files.move(tmp, dir.resolve(f[0]), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (final IOException e) {
                this.context.logger().warning("не записать statue_summon/: " + e.getMessage());
            }
        });
    }

    // ================================================================== статуи

    /** Поток админа: статуя в 2,5 блока перед ним (свободное место ищется в потоке региона той точки). */
    private void spawnStatue(final Player admin, final String name, final String poseOrAnim) {
        final Location l = admin.getLocation();
        Vector f = l.getDirection().setY(0);
        if (f.lengthSquared() < 1e-4) {
            f = new Vector(0, 0, 1);
        }
        f.normalize().multiply(2.5);
        final Location at = l.clone().add(f);
        final float yaw = l.getYaw() + 180f;
        final Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            this.place(admin, online.getPlayerProfile(), at, yaw, poseOrAnim);
            return;
        }
        msg(admin, "Ищу скин " + name + " у Mojang…", NamedTextColor.GRAY);
        final boolean lookup = this.settings.get().mojangLookup();
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), task -> {
            final PlayerProfile prof = lookup ? MojangProfiles.lookup(name) : null;
            final PlayerProfile pp = prof != null ? prof : MojangProfiles.offline(name);
            if (!this.closed) {
                this.place(admin, pp, at, yaw, poseOrAnim);
            }
        });
    }

    /** Любой поток: найти свободное место над точкой (в её регионе) и поставить статую. */
    private void place(final Player admin, final PlayerProfile prof, final Location at, final float yaw, final String poseOrAnim) {
        Bukkit.getRegionScheduler().execute(this.context.plugin(), at, () -> {
            Block b = at.getBlock();
            for (int i = 0; i < 4 && !b.isPassable(); i++) {
                at.add(0, 1, 0);
                b = at.getBlock();
            }
            at.setY(Math.floor(at.getY()));
            final Statue s = this.create(prof, at, yaw, poseOrAnim);
            this.context.runOnceForPlayer(admin, () -> admin.sendMessage(Component.text("Статуя " + s.name + " поставлена. ", NamedTextColor.GREEN)
                .append(Component.text("/rd pose|anim … — поза/анимация, /rd tool — кукловод, ударь — упадёт.", NamedTextColor.GRAY))));
        });
    }

    private Statue create(final PlayerProfile prof, final Location at, final float yaw, final String poseOrAnim) {
        final Statue s = new Statue(UUID.randomUUID());
        s.name = prof.getName() == null ? "?" : prof.getName();
        s.owner = prof.getId() == null ? UUID.randomUUID() : prof.getId();
        for (final ProfileProperty pr : prof.getProperties()) {
            if (pr.getName().equals("textures")) {
                s.tex = pr.getValue();
                s.sig = pr.getSignature();
            }
        }
        s.worldName = at.getWorld().getName();
        s.x = at.getX();
        s.y = at.getY();
        s.z = at.getZ();
        s.yaw = yaw;
        if (poseOrAnim != null) {
            final String k = poseOrAnim.toLowerCase(Locale.ROOT);
            if (DollPoses.POSES.containsKey(k)) {
                s.pose = DollPoses.pose(k);
            } else if (DollPoses.ANIMS.containsKey(k)) {
                s.anim = k;
            }
        }
        this.statues.put(s.id, s);
        this.save();
        return s;
    }

    private PlayerProfile profile(final Statue s) {
        final PlayerProfile pp = Bukkit.createProfile(s.owner, s.name);
        if (s.tex != null) {
            pp.setProperty(new ProfileProperty("textures", s.tex, s.sig));
        }
        return pp;
    }

    /** Любой поток: убрать статую (сущности — в её тике). */
    private void removeStatue(final Statue s) {
        s.removed = true;
        if (s.world() == null) {
            this.statues.remove(s.id);
        }
    }

    /** Поток MineSkin: кубики скина готовы — манекены этого скина переодеть. */
    public void upgrade(final String hash, final String[][] tex) {
        for (final Statue s : this.statues.values()) {
            final Doll d = s.puppet;
            if (d != null && !d.cubed() && hash.equals(this.skins.hash(s.owner))) {
                d.setBody(BodyLooks.cubes(tex), true);
            }
        }
    }

    // ================================================================== тик

    /** Глобальный поток, каждый тик: тик каждой статуи — задачей региона её чанка. */
    private void tickAll() {
        if (this.closed) {
            return;
        }
        for (final Statue s : this.statues.values()) {
            final World w = s.world();
            if (w == null || !s.queued.compareAndSet(false, true)) {
                continue;
            }
            Bukkit.getRegionScheduler().execute(this.context.plugin(), w, s.cx(), s.cz(), () -> {
                s.queued.set(false);
                if (!this.closed) {
                    this.tick(s, w);
                }
            });
        }
    }

    /** Поток региона статуи. */
    private void tick(final Statue s, final World w) {
        for (java.util.function.Consumer<Statue> op; (op = s.ops.poll()) != null; ) {
            op.accept(s);
        }
        if (s.removed) {
            this.despawnPuppet(s);
            if (s.doll != null) {
                this.ragdolls.remove(s.doll);
                s.doll = null;
            }
            this.statues.remove(s.id);
            return;
        }
        if (!w.getName().equals(s.worldName) || !w.isChunkLoaded(s.cx(), s.cz())) {
            return;   // перенесена в другой мир (следующий тик — там) или чанк не загружен
        }
        if (s.doll != null) {
            if (--s.dollTimer > 0 && this.ragdolls.alive(s.doll)) {
                return;
            }
            this.ragdolls.remove(s.doll);
            s.doll = null;
            s.cur = DollPoses.pose("lie");   // встаёт из лёжа
            w.playSound(new Location(w, s.x, s.y, s.z), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.6f, 0.7f);
        }
        Doll pup = s.puppet;
        if (pup == null || pup.removed.get()) {
            pup = this.spawnPuppet(s, w);
        }
        float[] target;
        double walkSpeed = 0;
        float k = 0.35f;
        s.t++;
        final String animId = s.anim;
        final DollPoses.Anim an = animId == null ? null : DollPoses.ANIMS.get(animId);
        final List<float[]> frames = List.copyOf(s.frames);
        if (s.playing && frames.size() >= 2) {
            target = PoseInterp.interp(frames, s.frameTicks, s.t);
            k = 0.7f;
        } else if (an != null) {
            target = PoseInterp.interp(an.frames(), an.ticks(), s.t);
            walkSpeed = an.walk();
            k = 0.6f;
            if (an.sound() != null && s.t % (an.ticks() * an.frames().size()) == an.ticks()) {
                w.playSound(new Location(w, s.x, s.y + 1, s.z), an.sound(), SoundCategory.PLAYERS, 0.5f, 1.4f);
            }
        } else {
            target = s.pose;
        }
        boolean changed = s.shown == null;
        if (s.cur == null) {
            s.cur = target.clone();
            changed = true;
        }
        changed |= PoseInterp.approach(s.cur, target, k);
        float yaw = s.yaw;
        if (walkSpeed > 0) {
            s.walk += walkSpeed * s.walkDir;
            if (Math.abs(s.walk) > 3) {
                s.walk = Math.signum(s.walk) * 3;
                s.walkDir = -s.walkDir;
            }
            if (s.walkDir < 0) {
                yaw += 180f;
            }
            changed = true;
        } else if (s.walk != 0) {
            s.walk *= 0.9;
            if (Math.abs(s.walk) < 0.01) {
                s.walk = 0;
            }
            changed = true;
        }
        if (yaw != s.lastYaw) {
            changed = true;
        }
        if (changed) {
            final double r = Math.toRadians(s.yaw);
            final double fx = -Math.sin(r) * s.walk, fz = Math.cos(r) * s.walk;
            final Pose ps = PoseMath.full(s.x + fx, s.y, s.z + fz, yaw, s.cur);
            pup.pose = ps;
            s.shown = ps;
            s.lastYaw = yaw;
        }
        this.renderer.render(pup);
    }

    /** Поток региона статуи. */
    private Doll spawnPuppet(final Statue s, final World w) {
        final List<Piece> body = this.skins.body(s.owner);
        final Pose start = PoseMath.full(s.x, s.y, s.z, s.yaw, s.cur != null ? s.cur : s.pose);
        final Doll d = new Doll(false, w, s.name, s.owner, BodyLooks.head(s.tex, s.sig), body, start, s.x, s.y + 1, s.z);
        d.setBody(body, this.skins.cubed(s.owner));
        s.puppet = d;
        s.shown = null;
        s.lastYaw = Float.NaN;
        if (!this.skins.cubed(s.owner)) {
            this.skins.fetch(this.profile(s), false);
        }
        return d;
    }

    /** Поток региона статуи. */
    private void despawnPuppet(final Statue s) {
        final Doll d = s.puppet;
        if (d != null) {
            d.removed.set(true);
            this.renderer.despawn(d, true);
            s.puppet = null;
            s.shown = null;
        }
    }

    // ================================================================== обмякнуть

    /** Любой поток: статуя обмякает рэгдоллом (в её тике). */
    private void topple(final Statue s, final double vx, final double vy, final double vz) {
        s.ops.add(st -> {
            if (st.doll != null || st.puppet == null || !this.ragdolls.ready()) {
                return;
            }
            final World w = st.world();
            final double r = Math.toRadians(st.yaw);
            final Location feet = new Location(w, st.x - Math.sin(r) * st.walk, st.y + 0.05, st.z + Math.cos(r) * st.walk);
            final int ticks = this.settings.get().statueToppleTicks();
            final Doll d = this.ragdolls.spawn(this.profile(st), feet, Float.isNaN(st.lastYaw) ? st.yaw : st.lastYaw, vx, vy, vz, ticks + 40);
            if (d == null) {
                return;
            }
            this.despawnPuppet(st);
            st.doll = d;
            st.dollTimer = ticks;
            st.cur = null;
            w.playSound(feet, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 0.8f, 1f);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(final EntityExplodeEvent e) {
        this.blast(e.getLocation(), e.getYield() * 3 + 3);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent e) {
        this.blast(e.getBlock().getLocation().add(0.5, 0.5, 0.5), 6);
    }

    /** Поток региона взрыва: статуи в радиусе обмякают (сами — в своих регионах). */
    private void blast(final Location c, final double radius) {
        if (!this.settings.get().statueExplosionTopple() || c.getWorld() == null) {
            return;
        }
        final String wn = c.getWorld().getName();
        for (final Statue s : this.statues.values()) {
            if (s.removed || !wn.equals(s.worldName)) {
                continue;
            }
            final double dx = s.x - c.getX(), dy = s.y + 1 - c.getY(), dz = s.z - c.getZ();
            final double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < radius) {
                final double k = 1.2 * (1 - d / radius) / Math.max(0.5, d);
                this.topple(s, dx * k, 0.4 + 0.6 * (1 - d / radius), dz * k);
            }
        }
    }

    // ================================================================== выбор частей лучом

    private record Hit(Statue statue, int part, double t) {
    }

    /** Поток игрока: [статуя, часть, дистанция] под прицелом; null — нет. */
    private Hit pick(final Player p, final double reach) {
        final Location eye = p.getEyeLocation();
        final Vector dir = eye.getDirection();
        final String wn = p.getWorld().getName();
        Hit best = null;
        double bestT = reach;
        for (final Statue s : this.statues.values()) {
            final Pose shown = s.shown;
            if (shown == null || s.removed || !wn.equals(s.worldName)) {
                continue;
            }
            final Picking.Hit h = Picking.pick(shown, eye.getX(), eye.getY(), eye.getZ(), dir.getX(), dir.getY(), dir.getZ(), bestT, 0.06);
            if (h != null && h.t() < bestT) {
                bestT = h.t();
                best = new Hit(s, h.part(), h.t());
            }
        }
        return best;
    }

    /** Статуя для команды: выбранная кукловодом, под прицелом или ближайшая в 8 блоках. */
    private Statue target(final Player p) {
        final Sel se = this.sel.get(p.getUniqueId());
        if (se != null) {
            final Statue s = this.statues.get(se.statue());
            if (s != null && !s.removed) {
                return s;
            }
        }
        final Hit h = this.pick(p, 12);
        if (h != null) {
            return h.statue();
        }
        Statue best = null;
        double bd = 64;
        final Location l = p.getLocation();
        final String wn = p.getWorld().getName();
        for (final Statue s : this.statues.values()) {
            if (!s.removed && wn.equals(s.worldName)) {
                final double dx = l.getX() - s.x, dy = l.getY() - s.y, dz = l.getZ() - s.z;
                final double d = dx * dx + dy * dy + dz * dz;
                if (d < bd) {
                    bd = d;
                    best = s;
                }
            }
        }
        return best;
    }

    // ================================================================== кукловод

    public ItemStack tool() {
        final ItemStack it = new ItemStack(Material.STICK);
        final ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("🎭 Кукловод", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        final List<Component> lore = new ArrayList<>();
        for (final String s : new String[] {"ПКМ по части статуи — выбрать (ПКМ мимо — снять выбор)", "ЛКМ +15°, Shift+ЛКМ −15°",
            "Shift+ПКМ — сменить ось (X/Y/Z, у тела ещё высота)", "F — записать кадр, Shift+F — проиграть/стоп"}) {
            lore.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        m.lore(lore);
        m.getPersistentDataContainer().set(this.toolKey, PersistentDataType.BYTE, (byte) 1);
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    private boolean isTool(final ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(this.toolKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(final PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Player p = e.getPlayer();
        final boolean left = e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK;
        final boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!this.isTool(e.getItem())) {
            // удар по статуе — обмякает
            if (left && !this.statues.isEmpty() && this.settings.get().statueHitTopple()) {
                final Hit h = this.pick(p, 4);
                if (h != null) {
                    final Vector d = p.getEyeLocation().getDirection();
                    this.topple(h.statue(), d.getX() * 0.6, 0.3, d.getZ() * 0.6);
                    e.setCancelled(true);
                }
            }
            return;
        }
        e.setCancelled(true);
        if (!p.hasPermission("coremechanics.admin")) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = this.lastClick.get(p.getUniqueId());
        if (last != null && now - last < 120) {
            return;
        }
        this.lastClick.put(p.getUniqueId(), now);
        final Sel se = this.sel.get(p.getUniqueId());
        if (right && p.isSneaking()) {
            if (se == null) {
                p.sendActionBar(Component.text("Сначала выбери часть (ПКМ)", NamedTextColor.RED));
                return;
            }
            final Sel n = new Sel(se.statue(), se.part(), (se.axis() + 1) % (se.part() == RagdollSpec.TORSO ? 4 : 3));
            this.sel.put(p.getUniqueId(), n);
            this.hint(p, n);
        } else if (right) {
            final Hit h = this.pick(p, 10);
            this.clearGlow(se);
            if (h == null) {
                this.sel.remove(p.getUniqueId());
                p.sendActionBar(Component.text("Выбор снят", NamedTextColor.GRAY));
                return;
            }
            final Sel n = new Sel(h.statue().id, h.part(), se != null && se.statue().equals(h.statue().id) ? Math.min(se.axis(), 2) : 0);
            this.sel.put(p.getUniqueId(), n);
            final Doll pup = h.statue().puppet;
            if (pup != null) {
                pup.glowPart = h.part();
            }
            this.hint(p, n);
        } else if (left) {
            final Statue s = se == null ? null : this.statues.get(se.statue());
            if (s == null || s.removed) {
                p.sendActionBar(Component.text("Сначала выбери часть статуи (ПКМ)", NamedTextColor.RED));
                return;
            }
            final float[] np = (s.anim != null || s.playing) && s.cur != null ? s.cur.clone() : s.pose.clone();
            // правка «замораживает» текущий кадр анимации в позу
            final float step = p.isSneaking() ? -1 : 1;
            if (se.axis() == 3) {
                np[0] = Math.max(0, Math.min(2.5f, np[0] + step * 0.05f));
            } else {
                final int i = PoseMath.PART_IDX[se.part()] + se.axis();
                np[i] = PoseMath.wrap(np[i] + step * 15f);
            }
            s.setPose(np);
            this.save();
            p.playSound(p.getLocation(), Sound.BLOCK_WOOD_HIT, 0.5f, step > 0 ? 1.4f : 1.0f);
            this.hint(p, se);
        }
    }

    private void hint(final Player p, final Sel se) {
        final Statue s = this.statues.get(se.statue());
        if (s == null) {
            return;
        }
        final String val = se.axis() == 3 ? String.format(Locale.ROOT, "%.2f", s.pose[0])
            : Math.round(s.pose[PoseMath.PART_IDX[se.part()] + se.axis()]) + "°";
        p.sendActionBar(Component.text("🎭 " + s.name + " · " + PART_NAMES[se.part()] + " · ось " + AXES[se.axis()] + " = " + val
            + " · кадров " + s.frames.size(), NamedTextColor.LIGHT_PURPLE));
    }

    private void clearGlow(final Sel se) {
        if (se == null) {
            return;
        }
        final Statue s = this.statues.get(se.statue());
        if (s != null && s.puppet != null) {
            s.puppet.glowPart = -1;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(final PlayerSwapHandItemsEvent e) {
        final Player p = e.getPlayer();
        if (!this.isTool(p.getInventory().getItemInMainHand())) {
            return;
        }
        e.setCancelled(true);
        if (!p.hasPermission("coremechanics.admin")) {
            return;
        }
        final Statue s = this.target(p);
        if (s == null) {
            p.sendActionBar(Component.text("Выбери статую кукловодом", NamedTextColor.RED));
            return;
        }
        if (p.isSneaking()) {
            this.togglePlay(p, s, !s.playing);
        } else {
            this.addFrame(p, s);
        }
        this.save();
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent e) {
        final Sel se = this.sel.remove(e.getPlayer().getUniqueId());
        this.clearGlow(se);
        this.lastClick.remove(e.getPlayer().getUniqueId());
    }

    private void addFrame(final Player p, final Statue s) {
        s.frames.add(s.pose.clone());
        s.playing = false;
        p.playSound(p.getLocation(), Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.7f, 1.3f);
        p.sendActionBar(Component.text("🎬 Кадр " + s.frames.size() + " записан (Shift+F — проиграть)", NamedTextColor.AQUA));
    }

    private void togglePlay(final Player p, final Statue s, final boolean play) {
        if (play && s.frames.size() < 2) {
            p.sendActionBar(Component.text("Нужно хотя бы 2 кадра (F)", NamedTextColor.RED));
            return;
        }
        s.anim = null;
        s.t = 0;
        s.playing = play;
        p.sendActionBar(Component.text(play ? "▶ Мультик: " + s.frames.size() + " кадров по " + s.frameTicks + " тик." : "■ Стоп — статуя в позе",
            NamedTextColor.AQUA));
    }

    // ================================================================== сохранение

    /** Любой поток: сохранить при ближайшем автосохранении (раз в 2 с, вне тиков). */
    public void save() {
        this.dirty.set(true);
    }

    private void flushIfDirty() {
        if (!this.dirty.getAndSet(false)) {
            return;
        }
        final String text = this.snapshot();
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), t -> this.write(text));
    }

    private String snapshot() {
        final YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Статуи core-mechanics (пишет система; правка руками — при выключенной системе)."));
        for (final Statue s : this.statues.values()) {
            if (!s.removed) {
                s.save(y.createSection("statues." + s.id));
            }
        }
        for (final Map.Entry<String, Map<String, Object>> o : this.orphans.entrySet()) {
            y.createSection("statues." + o.getKey(), o.getValue());
        }
        return y.saveToString();
    }

    private synchronized void write(final String text) {
        try {
            Files.createDirectories(this.file.getParent());
            final Path tmp = this.file.resolveSibling("statues.yml.tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            this.context.logger().warning("не сохранить statues.yml: " + e.getMessage());
        }
    }

    private void load() {
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        final YamlConfiguration y = YamlConfiguration.loadConfiguration(this.file.toFile());
        final ConfigurationSection root = y.getConfigurationSection("statues");
        if (root == null) {
            return;
        }
        int bad = 0;
        for (final String k : root.getKeys(false)) {
            final ConfigurationSection c = root.getConfigurationSection(k);
            if (c == null) {
                continue;
            }
            try {
                final Statue s = Statue.load(k, c);
                if (Bukkit.getWorld(s.worldName) == null) {
                    this.orphans.put(k, c.getValues(true));
                    continue;
                }
                this.statues.put(s.id, s);
            } catch (final RuntimeException e) {
                bad++;
            }
        }
        this.context.logger().info("статуй загружено: " + this.statues.size()
            + (this.orphans.isEmpty() ? "" : ", в несуществующих мирах (сохранены как есть): " + this.orphans.size())
            + (bad > 0 ? ", битых записей: " + bad : ""));
    }

    /**
     * Глобальный поток (disable): сохранить, остановить тики, убрать манекены, которые этот поток может убрать.
     *
     * @return сколько дисплеев осталось в чужих регионах
     */
    public int shutdown() {
        this.closed = true;
        for (final ScheduledTask t : new ScheduledTask[] {this.tickTask, this.saveTask}) {
            if (t != null) {
                t.cancel();
            }
        }
        this.dirty.set(false);
        this.write(this.snapshot());
        int left = 0;
        for (final Statue s : this.statues.values()) {
            final Doll d = s.puppet;
            if (d != null) {
                d.removed.set(true);
                left += this.renderer.despawn(d, false);
            }
        }
        this.statues.clear();
        return left;
    }
}
