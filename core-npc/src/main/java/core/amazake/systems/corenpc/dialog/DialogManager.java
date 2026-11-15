package core.amazake.systems.corenpc.dialog;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corenpc.Settings;
import core.amazake.systems.corenpc.Text;
import core.amazake.systems.corenpc.hook.Papi;
import core.amazake.systems.corenpc.hud.HudLayout;
import core.amazake.systems.corenpc.hud.HudRenderer;
import core.amazake.systems.corenpc.npc.NpcDef;
import core.amazake.systems.corenpc.util.Placeholders;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.entity.LookAnchor;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

/**
 * Открытые диалоги игроков. Сессия живёт в потоке своего игрока: задача runForPlayer (печать, окно, расстояние),
 * его события (колёсико, F, ЛКМ, Shift, движение) и его команды. Карта сессий — ConcurrentHashMap.
 */
public final class DialogManager implements Listener {

    private static final String KEY_NS = "corenpc";
    private static final int MAX_JUMPS = 16;
    private static final BinaryTagHolder EMPTY_TAG = BinaryTagHolder.binaryTagHolder("{}");
    /** Телепорт дальше этого (без NPC) закрывает диалог; с NPC — дальше dialog.max-distance от него. */
    private static final double TELEPORT_CLOSE_DISTANCE = 16;

    /** Состояние одного игрока; поля трогает только его поток. */
    private static final class Session {
        final Player player;
        final long seq;
        final NpcDef npc;
        final Settings.Mode mode;
        DialogFlow flow;
        ScheduledTask task;
        String lastFrame = "";
        int sinceFrame;
        int ticks;
        int typedForSound;
        boolean chatSent;
        boolean closed;

        Session(final Player player, final long seq, final NpcDef npc, final Settings.Mode mode) {
            this.player = player;
            this.seq = seq;
            this.npc = npc;
            this.mode = mode;
        }
    }

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final DialogRegistry registry;
    private final Actions actions;
    private final Papi papi;
    private final HudRenderer hud;
    private final NamespacedKey flagKey = new NamespacedKey("corenpc", "dialog");
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong seqs = new AtomicLong();

    public DialogManager(final SystemContext context, final Supplier<Settings> settings, final DialogRegistry registry,
                         final Actions actions, final Papi papi, final HudRenderer hud) {
        this.context = context;
        this.settings = settings;
        this.registry = registry;
        this.actions = actions;
        this.papi = papi;
        this.hud = hud;
    }

    public DialogRegistry registry() {
        return this.registry;
    }

    /** Открыт ли диалог (и с каким NPC: id или "" без NPC). null — не открыт. */
    public String openWith(final Player p) {
        final Session s = this.sessions.get(p.getUniqueId());
        return s == null ? null : s.npc == null ? "" : s.npc.id();
    }

    public int openCount() {
        return this.sessions.size();
    }

    /** Переменные для {подстановок}. */
    public static Map<String, String> vars(final Player p, final NpcDef npc, final String dialog) {
        final Map<String, String> m = new HashMap<>();
        m.put("player", p.getName());
        m.put("player_uuid", p.getUniqueId().toString());
        m.put("player_display", PlainTextComponentSerializer.plainText().serialize(p.displayName()));
        m.put("world", p.getWorld().getName());
        if (npc != null) {
            m.put("npc", npc.id());
            m.put("npc_name", PlainTextComponentSerializer.plainText().serialize(Text.parse(npc.name().isBlank() ? npc.id() : npc.name())));
        }
        if (dialog != null) {
            m.put("dialog", dialog);
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------- открыть / закрыть

    /** Открыть диалог (поток игрока). false — нет такого диалога. */
    public boolean open(final Player p, final String dialogId, final NpcDef npc) {
        final Dialog d = this.registry.get(dialogId);
        if (d == null) {
            return false;
        }
        final Session old = this.sessions.get(p.getUniqueId());
        if (old != null) {
            this.close(old, false);
        }
        final Settings s = this.settings.get();
        Settings.Mode mode = s.dialog().mode();
        if (d.mode().equals("native")) {
            mode = Settings.Mode.NATIVE;
        } else if (d.mode().equals("hud")) {
            mode = Settings.Mode.HUD;
        }
        final Session ses = new Session(p, this.seqs.incrementAndGet(), npc, mode);
        ses.flow = new DialogFlow(d, s.dialog().charsPerTick(), s.dialog().preventSkip());
        this.sessions.put(p.getUniqueId(), ses);
        if (old == null && s.dialog().clearChatLines() > 0) {
            // «чистый» чат на время разговора: старые сообщения уезжают вверх
            p.sendMessage(Component.text("\n".repeat(s.dialog().clearChatLines() - 1)));
        }
        if (s.dialog().flag()) {
            p.getPersistentDataContainer().set(this.flagKey, PersistentDataType.BYTE, (byte) 1);
        }
        if (npc != null && s.dialog().faceNpc() && p.getWorld().getName().equals(npc.world())) {
            p.lookAt(npc.x(), npc.y() + 1.5 * (npc.scale() > 0 ? npc.scale() : 1), npc.z(), LookAnchor.EYES);
        }
        ses.task = this.context.runForPlayer(p, t -> this.tick(ses), 1L);
        this.show(ses, d.start(), 0);
        return true;
    }

    /** Закрыть диалог игрока (поток игрока); completed — дошёл до конца (выполнить on-end). */
    private void close(final Session ses, final boolean completed) {
        if (ses.closed) {
            return;
        }
        ses.closed = true;
        this.sessions.remove(ses.player.getUniqueId(), ses);
        if (ses.task != null) {
            ses.task.cancel();
        }
        final Player p = ses.player;
        if (p.isOnline()) {
            if (ses.mode == Settings.Mode.HUD) {
                p.sendActionBar(Component.empty());
            } else {
                p.closeDialog();
            }
            p.getPersistentDataContainer().remove(this.flagKey);
        }
        if (completed && p.isOnline()) {
            final Dialog d = ses.flow.dialog();
            this.actions.run(p, d.onEnd(), vars(p, ses.npc, d.id()));
        }
    }

    /** Закрыть диалог игрока, если открыт (поток игрока). */
    public void close(final Player p) {
        final Session s = this.sessions.get(p.getUniqueId());
        if (s != null) {
            this.close(s, false);
        }
    }

    /** Выключение системы: каждому — в его потоке. */
    public void shutdown() {
        for (final Session s : new ArrayList<>(this.sessions.values())) {
            this.context.runOnceForPlayer(s.player, () -> this.close(s, false));
        }
        this.sessions.clear();
    }

    // ------------------------------------------------------------------------------------------- страницы

    private void show(final Session ses, final String rawTarget, final int depth) {
        if (ses.closed) {
            return;
        }
        final Player p = ses.player;
        final String target = DialogParser.normalizeTarget(rawTarget);
        if (target == null) {
            this.close(ses, true);
            return;
        }
        if (depth > MAX_JUMPS) {
            this.broken(ses, "больше " + MAX_JUMPS + " переходов подряд (петля goto?)");
            return;
        }
        Dialog d = ses.flow.dialog();
        String pageId = target;
        if (DialogParser.external(target)) {
            final int c = target.indexOf(':');
            final Dialog other = this.registry.get(target.substring(0, c));
            if (other == null) {
                this.broken(ses, "нет диалога «" + target.substring(0, c) + "»");
                return;
            }
            pageId = target.substring(c + 1).isEmpty() ? other.start() : target.substring(c + 1);
            if (other != d) {
                final Settings s = this.settings.get();
                d = other;
                ses.flow = new DialogFlow(d, s.dialog().charsPerTick(), s.dialog().preventSkip());
            }
        }
        final Page page = d.page(pageId);
        if (page == null) {
            this.broken(ses, "нет страницы «" + pageId + "» в диалоге «" + d.id() + "»");
            return;
        }
        final Map<String, String> vars = vars(p, ses.npc, d.id());
        final List<String> lines = new ArrayList<>();
        for (final String l : page.lines()) {
            lines.add(this.actions.mini(p, l, vars));
        }
        final Condition.Env env = new Condition.Env() {
            @Override
            public boolean hasPermission(final String node) {
                return p.hasPermission(node);
            }

            @Override
            public String placeholders(final String text) {
                return DialogManager.this.papi.apply(p, Placeholders.apply(text, vars));
            }
        };
        ses.flow.open(page, lines, a -> Condition.all(a.requires(), env));
        ses.chatSent = false;
        ses.lastFrame = "";
        ses.typedForSound = 0;
        Actions.sound(p, page.sound());
        final Actions.Flow f = this.actions.run(p, page.actions(), vars);
        if (f.close()) {
            this.close(ses, true);
            return;
        }
        if (f.target() != null) {
            this.show(ses, f.target(), depth + 1);
            return;
        }
        if (ses.mode == Settings.Mode.NATIVE) {
            this.showNative(ses, vars);
        } else {
            this.render(ses, true);
        }
    }

    private void broken(final Session ses, final String reason) {
        this.context.logger().warning("Диалог «" + ses.flow.dialog().id() + "» у " + ses.player.getName() + ": " + reason);
        Text.send(ses.player, this.settings.get(), "dialog-broken", Placeholder.unparsed("reason", reason));
        this.close(ses, false);
    }

    private void step(final Session ses, final DialogFlow.Step st) {
        final Player p = ses.player;
        switch (st.kind()) {
            case NONE -> {
            }
            case SKIPPED -> this.render(ses, true);
            case NEXT -> this.show(ses, st.target(), 0);
            case CHOSEN -> {
                Actions.sound(p, "minecraft:ui.button.click 0.4 1.4");
                final Dialog d = ses.flow.dialog();
                final Actions.Flow f = this.actions.run(p, st.answer().actions(), vars(p, ses.npc, d.id()));
                if (ses.closed) {
                    return;
                }
                if (f.close()) {
                    this.close(ses, true);
                } else {
                    this.show(ses, f.target() != null ? f.target() : st.target(), 0);
                }
            }
            default -> {
            }
        }
    }

    /** Подтверждение (F, ЛКМ, клик по NPC) — поток игрока. */
    public void confirm(final Player p) {
        final Session s = this.sessions.get(p.getUniqueId());
        if (s == null || s.closed) {
            return;
        }
        if (s.mode == Settings.Mode.NATIVE) {
            this.showNative(s, vars(p, s.npc, s.flow.dialog().id()));
            return;
        }
        this.step(s, s.flow.confirm());
    }

    /** Выбрать ответ по номеру (с нуля) — поток игрока. */
    public void choose(final Player p, final long seq, final int index) {
        final Session s = this.sessions.get(p.getUniqueId());
        if (s == null || s.closed || s.seq != seq) {
            return;
        }
        this.step(s, s.flow.choose(index));
    }

    // ------------------------------------------------------------------------------------------- тик и окно

    private void tick(final Session ses) {
        if (ses.closed) {
            if (ses.task != null) {
                ses.task.cancel();
            }
            return;
        }
        final Player p = ses.player;
        final Settings s = this.settings.get();
        ses.ticks++;
        if (ses.npc != null && s.dialog().maxDistance() > 0 && ses.ticks % 5 == 0) {
            final Location at = p.getLocation();
            final NpcDef n = ses.npc;
            if (!at.getWorld().getName().equals(n.world())
                || at.distanceSquared(new Location(at.getWorld(), n.x(), n.y(), n.z())) > s.dialog().maxDistance() * s.dialog().maxDistance()) {
                this.close(ses, false);
                return;
            }
        }
        if (ses.mode == Settings.Mode.NATIVE) {
            return;
        }
        final boolean wasTyping = ses.flow.typing();
        final int typed = ses.flow.tick();
        if (typed > 0) {
            ses.typedForSound += typed;
            if (ses.typedForSound >= s.dialog().typingEvery()) {
                ses.typedForSound = 0;
                if (!s.dialog().typingSound().isBlank()) {
                    p.playSound(p.getLocation(), s.dialog().typingSound(), s.dialog().typingVolume(), s.dialog().typingPitch());
                }
            }
        }
        if (wasTyping && !ses.flow.typing()) {
            ses.lastFrame = "";
        }
        this.render(ses, false);
    }

    private void render(final Session ses, final boolean force) {
        final Settings s = this.settings.get();
        final Player p = ses.player;
        final DialogFlow f = ses.flow;
        final boolean typing = f.typing();
        if (!typing && !ses.chatSent && s.dialog().chatAnswers()) {
            ses.chatSent = true;
            this.sendChat(ses, s);
        }
        final boolean blink = !typing && f.answers().isEmpty() && (ses.ticks / 10) % 2 == 0;
        final List<String> lines = f.visibleLines();
        final String frame = String.join("\n", lines) + '|' + f.selected() + '|' + blink + '|' + f.answers().size();
        ses.sinceFrame++;
        if (!force && frame.equals(ses.lastFrame) && ses.sinceFrame < s.dialog().refreshTicks()) {
            return;
        }
        ses.lastFrame = frame;
        ses.sinceFrame = 0;
        final Page page = f.page();
        final Map<String, String> vars = vars(p, ses.npc, f.dialog().id());
        final String speakerRaw = page.speaker() == null ? "" : this.actions.mini(p, page.speaker(), vars);
        final Component speaker = speakerRaw.isBlank() ? null
            : Text.parse(s.dialog().speakerFormat(), Placeholder.parsed("speaker", speakerRaw));
        final List<Component> rows = new ArrayList<>();
        for (final String l : lines) {
            rows.add(Text.parse(s.dialog().textColor() + l));
        }
        final List<Component> answers = new ArrayList<>();
        int selectedRow = -1;
        if (!typing && !f.answers().isEmpty()) {
            final int n = f.answers().size();
            final int window = HudLayout.ANSWER_ROWS;
            final int from = Math.max(0, Math.min(f.selected() - 1, n - window));
            for (int i = from; i < Math.min(n, from + window); i++) {
                final boolean sel = i == f.selected();
                final String txt = this.actions.mini(p, f.answers().get(i).text(), vars);
                answers.add(Text.parse(sel ? s.dialog().answerSelectedFormat() : s.dialog().answerFormat(),
                    Placeholder.unparsed("index", Integer.toString(i + 1)), Placeholder.parsed("text", txt)));
                if (sel) {
                    selectedRow = i - from;
                }
            }
        }
        p.sendActionBar(this.hud.render(speaker, rows, answers, selectedRow, blink));
    }

    /** Ответы в чат кликабельными строками (запасной ввод). */
    private void sendChat(final Session ses, final Settings s) {
        final DialogFlow f = ses.flow;
        if (f.answers().isEmpty()) {
            return;
        }
        final Player p = ses.player;
        final Map<String, String> vars = vars(p, ses.npc, f.dialog().id());
        final long seq = ses.seq;
        for (int i = 0; i < f.answers().size(); i++) {
            final int idx = i;
            final String txt = this.actions.mini(p, f.answers().get(i).text(), vars);
            // custom-клик (corenpc:c<seq>_<i>) ловит onCustomClick: без ClickEvent.callback, который держал бы ссылку
            // на классы системы в общем реестре Paper ещё 10 минут после выключения
            final ClickEvent click = ClickEvent.custom(Key.key(KEY_NS, "c" + seq + "_" + idx), EMPTY_TAG);
            p.sendMessage(Text.parse(s.dialog().chatAnswerFormat(),
                Placeholder.unparsed("index", Integer.toString(i + 1)), Placeholder.parsed("text", txt),
                net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver("click",
                    (args, ctx) -> net.kyori.adventure.text.minimessage.tag.Tag.styling(st -> st.clickEvent(click)))));
        }
        if (!s.dialog().chatHint().isBlank()) {
            p.sendMessage(Text.parse(s.dialog().chatHint()));
        }
    }

    // ------------------------------------------------------------------------------------------- обычное окно (1.21.6+)

    private void showNative(final Session ses, final Map<String, String> vars) {
        final Settings s = this.settings.get();
        final Player p = ses.player;
        final DialogFlow f = ses.flow;
        final Page page = f.page();
        final String speakerRaw = page.speaker() == null || page.speaker().isBlank()
            ? (ses.npc != null ? ses.npc.name() : f.dialog().id()) : this.actions.mini(p, page.speaker(), vars);
        final List<DialogBody> body = new ArrayList<>();
        final List<String> full = f.fullLines();
        if (!full.isEmpty()) {
            body.add(DialogBody.plainMessage(Text.parse(s.dialog().textColor() + String.join("<newline>", full)),
                s.dialog().nativeWidth()));
        }
        final List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < f.answers().size(); i++) {
            final String txt = this.actions.mini(p, f.answers().get(i).text(), vars);
            buttons.add(ActionButton.builder(Text.parse(txt)).width(Math.min(s.dialog().nativeWidth(), 1024))
                .action(DialogAction.customClick(Key.key(KEY_NS, "s" + ses.seq + "_" + i), null)).build());
        }
        if (buttons.isEmpty()) {
            buttons.add(ActionButton.builder(Text.parse(s.dialog().nextButton())).width(200)
                .action(DialogAction.customClick(Key.key(KEY_NS, "s" + ses.seq + "_next"), null)).build());
        }
        final ActionButton exit = ActionButton.builder(Text.parse(s.dialog().closeButton())).width(200)
            .action(DialogAction.customClick(Key.key(KEY_NS, "s" + ses.seq + "_close"), null)).build();
        final DialogBase base = DialogBase.builder(Text.parse(speakerRaw))
            .body(body)
            .canCloseWithEscape(true)
            .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
            .build();
        p.showDialog(io.papermc.paper.dialog.Dialog.create(b -> b.empty().base(base)
            .type(DialogType.multiAction(buttons).columns(1).exitAction(exit).build())));
    }

    /**
     * Кнопка обычного окна (corenpc:s<seq>_<i|next|close>) или ответ в чате (corenpc:c<seq>_<i>): может прийти не из
     * потока игрока — дальше в его потоке. seq — номер сессии игрока: чужой или старый клик не сработает.
     */
    @EventHandler
    public void onCustomClick(final PlayerCustomClickEvent event) {
        final Key k = event.getIdentifier();
        if (!KEY_NS.equals(k.namespace()) || !(k.value().startsWith("s") || k.value().startsWith("c"))
            || !(event.getCommonConnection() instanceof final PlayerGameConnection game)) {
            return;
        }
        final Player p = game.getPlayer();
        final String v = k.value();
        final int us = v.indexOf('_');
        if (us < 0) {
            return;
        }
        final long seq;
        try {
            seq = Long.parseLong(v.substring(1, us));
        } catch (final NumberFormatException e) {
            return;
        }
        final String what = v.substring(us + 1);
        if (v.charAt(0) == 'c' && (what.equals("close") || what.equals("next"))) {
            return;
        }
        this.context.runOnceForPlayer(p, () -> {
            final Session s = this.sessions.get(p.getUniqueId());
            if (s == null || s.seq != seq || s.closed) {
                return;
            }
            switch (what) {
                case "close" -> this.close(s, false);
                case "next" -> this.step(s, s.flow.confirm());
                default -> {
                    try {
                        this.step(s, s.flow.choose(Integer.parseInt(what)));
                    } catch (final NumberFormatException ignored) {
                        // чужой ключ
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------- ввод
    // Приоритет LOWEST: событие отменяется раньше других систем (rezusmenu открывает меню по F с ignoreCancelled).

    private Session hudSession(final Player p) {
        final Session s = this.sessions.get(p.getUniqueId());
        return s != null && !s.closed && s.mode == Settings.Mode.HUD ? s : null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHeld(final PlayerItemHeldEvent event) {
        final Session s = this.hudSession(event.getPlayer());
        if (s == null || !this.settings.get().dialog().scrollSelects()) {
            return;
        }
        event.setCancelled(true);
        final int diff = ((event.getNewSlot() - event.getPreviousSlot()) % 9 + 9) % 9;
        if (diff == 0) {
            return;
        }
        if (s.flow.move(diff <= 4 ? 1 : -1)) {
            Actions.sound(s.player, "minecraft:ui.hud.bubble_pop 0.3 1.8");
            this.render(s, true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwap(final PlayerSwapHandItemsEvent event) {
        final Session s = this.hudSession(event.getPlayer());
        if (s == null || !this.settings.get().dialog().swapConfirms()) {
            return;
        }
        event.setCancelled(true);
        this.step(s, s.flow.confirm());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLeftClick(final PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK
            || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Session s = this.hudSession(event.getPlayer());
        if (s == null || !this.settings.get().dialog().leftClickConfirms()) {
            return;
        }
        event.setCancelled(true);
        this.step(s, s.flow.confirm());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(final PlayerToggleSneakEvent event) {
        if (!event.isSneaking() || !this.settings.get().dialog().sneakCloses()) {
            return;
        }
        final Session s = this.sessions.get(event.getPlayer().getUniqueId());
        if (s != null) {
            this.close(s, false);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Session s = this.sessions.get(event.getPlayer().getUniqueId());
        if (s == null) {
            return;
        }
        final Settings.Dialogs d = this.settings.get().dialog();
        final Location from = event.getFrom();
        final Location to = event.getTo();
        final boolean moved = from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY();
        if (d.freeze() && moved || d.cameraLock() && (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch())) {
            final Location fixed = from.clone();
            fixed.setY(Math.min(from.getY(), to.getY()));   // падать можно
            if (!d.cameraLock()) {
                fixed.setYaw(to.getYaw());
                fixed.setPitch(to.getPitch());
            }
            event.setTo(fixed);
        }
    }

    /** Смерть — диалог закрывается (поток игрока). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent event) {
        this.close(event.getPlayer());
    }

    /** Другой мир — закрыть (поток игрока). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        this.close(event.getPlayer());
    }

    /**
     * Телепорт в другой мир или далеко (от NPC — дальше dialog.max-distance или 16, если она 0; без NPC — дальше
     * 16 блоков от места) закрывает
     * диалог (поток игрока). Короткий телепорт рядом (например, командой из ответа) — нет.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        final Session s = this.sessions.get(event.getPlayer().getUniqueId());
        if (s == null) {
            return;
        }
        final Location to = event.getTo();
        final Location from = event.getFrom();
        final boolean close;
        if (to.getWorld() != from.getWorld()) {
            close = true;
        } else if (s.npc != null) {
            final double md = this.settings.get().dialog().maxDistance();
            final double max = md > 0 ? md : TELEPORT_CLOSE_DISTANCE;
            close = !to.getWorld().getName().equals(s.npc.world())
                || to.distanceSquared(new Location(to.getWorld(), s.npc.x(), s.npc.y(), s.npc.z())) > max * max;
        } else {
            close = to.distanceSquared(from) > TELEPORT_CLOSE_DISTANCE * TELEPORT_CLOSE_DISTANCE;
        }
        if (close) {
            this.close(s, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(final AsyncChatEvent event) {
        // пока игрок в диалоге, сообщения других игроков ему не приходят (асинхронно: только читаем карту сессий)
        if (this.sessions.isEmpty() || !this.settings.get().dialog().hideChat()) {
            return;
        }
        event.viewers().removeIf(a -> a instanceof Player pl && this.sessions.containsKey(pl.getUniqueId()));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Session s = this.sessions.remove(event.getPlayer().getUniqueId());
        if (s != null) {
            s.closed = true;
            if (s.task != null) {
                s.task.cancel();
            }
        }
        event.getPlayer().getPersistentDataContainer().remove(this.flagKey);
    }

    /** Флаг мог остаться после падения сервера. */
    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        event.getPlayer().getPersistentDataContainer().remove(this.flagKey);
    }

    /** Убрать флаг у тех, кто онлайн (включение системы). */
    public void clearFlags(final Iterable<? extends Player> players) {
        for (final Player p : players) {
            this.context.runOnceForPlayer(p, () -> p.getPersistentDataContainer().remove(this.flagKey));
        }
    }
}
