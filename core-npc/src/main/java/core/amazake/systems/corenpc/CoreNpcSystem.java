package core.amazake.systems.corenpc;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corenpc.dialog.Actions;
import core.amazake.systems.corenpc.dialog.Dialog;
import core.amazake.systems.corenpc.dialog.DialogManager;
import core.amazake.systems.corenpc.dialog.DialogRegistry;
import core.amazake.systems.corenpc.hook.ModelHook;
import core.amazake.systems.corenpc.hook.Papi;
import core.amazake.systems.corenpc.hud.FontWidths;
import core.amazake.systems.corenpc.hud.HudLayout;
import core.amazake.systems.corenpc.hud.HudRenderer;
import core.amazake.systems.corenpc.npc.NpcDef;
import core.amazake.systems.corenpc.npc.NpcManager;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * core-npc — свои NPC без Citizens: манекены со скином или любые мобы, модели ModelEngine (по желанию), диалоги
 * в окне над хотбаром (пакет ресурсов) или в обычном окне диалога клиента, команды по клику.
 */
public final class CoreNpcSystem implements AmazakeSystem, Listener {

    /** Как часто проверять, что NPC загруженных чанков стоят (их могли убрать другие плагины). */
    private static final Duration HEAL_PERIOD = Duration.ofSeconds(5);

    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.DEFAULTS);
    private volatile SystemContext context;
    private volatile NpcManager npcs;
    private volatile DialogManager dialogs;
    private volatile Actions actions;
    private volatile ModelHook models;
    private volatile ScheduledTask lookTask;
    private volatile ScheduledTask healTask;
    private volatile boolean closed;
    private volatile java.util.function.ToIntFunction<String> measure;

    @Override
    public void enable(final SystemContext context) throws IOException {
        this.context = context;
        this.closed = false;
        this.loadSettings();
        final Path dialogDir = context.dataFolder().resolve("dialogs");
        if (!Files.isDirectory(dialogDir)) {
            context.saveDefaultResource("dialogs/example.yml");
        }
        final FontWidths widths;
        try (InputStream in = CoreNpcSystem.class.getClassLoader().getResourceAsStream("font-widths.txt")) {
            if (in == null) {
                throw new IOException("нет font-widths.txt в .azs");
            }
            widths = FontWidths.load(in);
        }
        final Papi papi = new Papi(context.logger());
        this.models = new ModelHook(context.logger());
        this.actions = new Actions(context, papi);
        final DialogRegistry registry = new DialogRegistry();
        final HudRenderer hud = new HudRenderer(widths);
        this.measure = line -> hud.width(Text.parse(line));
        registry.load(dialogDir, context.logger(), this.measure, HudLayout.TEXT_WIDTH);
        this.dialogs = new DialogManager(context, this.settings::get, registry, this.actions, papi, hud);
        this.npcs = new NpcManager(context, this.settings::get, this.models);
        this.npcs.onClick(this::click);
        this.npcs.load();
        context.registerListener(this.npcs);
        context.registerListener(this.dialogs);
        context.registerListener(this);
        context.registerCommand(new NpcCommand(context, this.settings::get, this.npcs, this.dialogs, this.models, this::reloadAsync));
        this.dialogs.clearFlags(Bukkit.getOnlinePlayers());
        this.startLook();
        this.npcs.spawnAll();
        this.logModelEngine();
        context.logger().info("core-npc включён: " + this.npcs.all().size() + " NPC, " + registry.size() + " диалогов, режим "
            + this.settings.get().dialog().mode().name().toLowerCase() + ".");
    }

    @Override
    public void disable(final SystemContext context) {
        this.closed = true;
        for (final ScheduledTask t : new ScheduledTask[] {this.lookTask, this.healTask}) {
            if (t != null) {
                t.cancel();
            }
        }
        this.lookTask = null;
        this.healTask = null;
        final DialogManager d = this.dialogs;
        if (d != null) {
            d.shutdown();
        }
        final NpcManager n = this.npcs;
        if (n != null) {
            n.shutdown();
        }
    }

    /** amazake system reload (глобальный поток): перечитать здесь же; ошибка — в лог, всё остаётся как было. */
    @Override
    public void reload(final SystemContext context) {
        try {
            this.apply(this.readAll());
        } catch (final IOException | RuntimeException e) {
            context.logger().warning("core-npc: не перечитано, всё осталось как было: " + reason(e));
        }
    }

    /** Всё, что перечитывает reload: сначала прочитать, менять — только если прочиталось целиком. */
    private record Reloaded(Settings settings, Map<String, Dialog> dialogs, Map<String, NpcDef> npcs) {
    }

    /** Файлы: config.yml, dialogs/, npcs.yml. Ничего не меняет; любой поток. */
    private Reloaded readAll() throws IOException {
        final Settings s = Settings.from(this.context.loadConfig(), w -> this.context.logger().warning("config.yml: " + w));
        final Map<String, Dialog> d = DialogRegistry.read(this.context.dataFolder().resolve("dialogs"), this.context.logger(),
            this.measure, HudLayout.TEXT_WIDTH);
        final Map<String, NpcDef> n = this.npcs.read();
        return new Reloaded(s, d, n);
    }

    /** Применить прочитанное (глобальный поток). */
    private void apply(final Reloaded r) {
        this.settings.set(r.settings());
        this.dialogs.registry().set(r.dialogs());
        this.npcs.apply(r.npcs());
        this.startLook();
        this.context.logger().info("core-npc: перечитано (" + this.npcs.all().size() + " NPC, "
            + this.dialogs.registry().size() + " диалогов).");
    }

    /**
     * /npc reload: файлы читаются вне потоков тика, применяется в глобальном потоке; done получает null (успех) или
     * причину ошибки — тогда ничего не изменилось.
     */
    private void reloadAsync(final Consumer<String> done) {
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), t -> {
            final Reloaded r;
            try {
                r = this.readAll();
            } catch (final IOException | RuntimeException e) {
                this.context.logger().warning("core-npc: не перечитано, всё осталось как было: " + reason(e));
                done.accept(reason(e));
                return;
            }
            Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> {
                if (this.closed) {
                    return;
                }
                this.apply(r);
                done.accept(null);
            });
        });
    }

    private static String reason(final Throwable e) {
        final Throwable c = e instanceof UncheckedIOException && e.getCause() != null ? e.getCause() : e;
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }

    private void loadSettings() {
        this.settings.set(Settings.from(this.context.loadConfig(), w -> this.context.logger().warning("config.yml: " + w)));
    }

    private void startLook() {
        final ScheduledTask old = this.lookTask;
        if (old != null) {
            old.cancel();
        }
        final NpcManager n = this.npcs;
        this.lookTask = this.context.runGlobalTimer(t -> n.lookTick(),
            Duration.ofMillis(50L * this.settings.get().npc().lookIntervalTicks()));
        if (this.healTask == null) {
            this.healTask = this.context.runGlobalTimer(t -> n.healTick(), HEAL_PERIOD);
        }
    }

    private void logModelEngine() {
        if (!this.settings.get().modelEngine()) {
            return;
        }
        if (this.models.available()) {
            this.context.logger().info("ModelEngine " + this.models.version() + " найден: модели на NPC включены.");
        } else {
            this.context.logger().warning("ModelEngine не найден: NPC работают без моделей (модели из npcs.yml появятся, когда он включится).");
        }
    }

    /** ModelEngine включился позже системы — поставить NPC заново, чтобы повесить модели. */
    @EventHandler
    public void onPluginEnable(final PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("ModelEngine") && this.npcs != null) {
            this.logModelEngine();
            for (final NpcDef d : this.npcs.all()) {
                if (d.model() != null) {
                    this.npcs.update(d.id(), x -> x);
                }
            }
        }
    }

    /** Клик по NPC (поток игрока): уже идёт разговор с ним — «дальше»; иначе право, кулдаун, команды, диалог. */
    private void click(final Player p, final NpcDef d, final Entity entity, final boolean left) {
        final Settings s = this.settings.get();
        final String open = this.dialogs.openWith(p);
        if (open != null && open.equals(d.id())) {
            if (!left || s.dialog().leftClickConfirms()) {
                this.dialogs.confirm(p);
            }
            return;
        }
        if (left && !d.leftClick()) {
            return;
        }
        if (!d.permission().isBlank() && !p.hasPermission(d.permission())) {
            Text.send(p, s, "no-permission", Placeholder.unparsed("id", d.id()));
            return;
        }
        if (!this.npcs.tryCooldown(p, d)) {
            Text.send(p, s, "cooldown", Placeholder.unparsed("id", d.id()));
            return;
        }
        this.npcs.playInteract(d, entity);
        final Map<String, String> vars = DialogManager.vars(p, d, d.dialog().isBlank() ? null : d.dialog());
        this.actions.run(p, d.commands(), vars);
        if (!d.dialog().isBlank() && !this.dialogs.open(p, d.dialog(), d)) {
            this.context.logger().warning("NPC " + d.id() + ": нет диалога «" + d.dialog() + "»");
        }
    }
}
