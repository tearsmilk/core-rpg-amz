package core.amazake.systems.corevault.level;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corevault.Settings;
import core.amazake.systems.corevault.Text;
import core.amazake.systems.corevault.data.Account;
import core.amazake.systems.corevault.eco.InternalLedger;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Уровни игроков: опыт хранится в счёте ({@link Account}), изменения атомарны под его замком. Эффекты (сообщение,
 * заголовок, звук, actionbar, босс-бар, ванильная полоска) — в потоке игрока; награды-команды — в глобальном
 * потоке; полученные не в сети награды выдаются при входе.
 */
public final class LevelService {

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final InternalLedger accounts;
    private final ConcurrentHashMap<UUID, Bar> bars = new ConcurrentHashMap<>();

    /** Босс-бар игрока: живёт в его потоке. */
    private static final class Bar {
        BossBar bar;
        long hideAt;
        ScheduledTask task;
    }

    public LevelService(final SystemContext context, final Supplier<Settings> settings, final InternalLedger accounts) {
        this.context = context;
        this.settings = settings;
        this.accounts = accounts;
    }

    public LevelCurve curve() {
        return this.settings.get().levels().curve();
    }

    /** Текущее состояние (нормализованное по кривой; кривая могла смениться после reload). */
    public LevelMath.State state(final UUID id) {
        final Account a = this.accountOrNull(id);
        final LevelCurve c = this.curve();
        if (a == null) {
            return new LevelMath.State(c.startLevel(), 0);
        }
        synchronized (a) {
            return LevelMath.normalize(c, a.level(), a.exp());
        }
    }

    private Account accountOrNull(final UUID id) {
        return this.accounts.hasAccount(id) ? this.accounts.account(id, null) : null;
    }

    /** Итог изменения: было, стало, новые уровни (по возрастанию). */
    public record Change(LevelMath.State before, LevelMath.State after, List<Integer> gained) {
    }

    /** Изменить уровень/опыт игрока (любой поток). Онлайн — эффекты; офлайн — награды откладываются до входа. */
    public Change apply(final UUID id, final String name, final UnaryOperator<LevelMath.State> op, final long shownExp) {
        final Account a = this.accounts.account(id, name);
        final LevelCurve c = this.curve();
        final LevelMath.State before;
        final LevelMath.State after;
        synchronized (a) {
            before = LevelMath.normalize(c, a.level(), a.exp());
            after = op.apply(before);
            a.levelExp(after.level(), after.exp());
        }
        final List<Integer> gained = new ArrayList<>();
        for (int l = before.level() + 1; l <= after.level(); l++) {
            gained.add(l);
        }
        final Change change = new Change(before, after, gained);
        final Player online = Bukkit.getPlayer(id);
        if (online != null) {
            this.context.runOnceForPlayer(online, () -> this.effects(online, change, shownExp));
            if (!gained.isEmpty()) {
                this.rewards(online.getName(), id, gained);
            }
        } else if (!gained.isEmpty()) {
            a.addPending(gained);
        }
        return change;
    }

    public Change addExp(final UUID id, final String name, final long delta, final boolean show) {
        final LevelCurve c = this.curve();
        return this.apply(id, name, s -> LevelMath.addExp(c, s, delta), show ? delta : 0);
    }

    /** Вход игрока (его поток): отложенные награды и ванильная полоска. */
    public void join(final Player p) {
        final Account a = this.accounts.account(p.getUniqueId(), p.getName());
        final List<Integer> pending = a.drainPending();
        if (!pending.isEmpty()) {
            this.rewards(p.getName(), p.getUniqueId(), pending);
        }
        this.syncBar(p);
    }

    /** Выход: убрать босс-бар из памяти (игрок его уже не видит). */
    public void quit(final UUID id) {
        final Bar b = this.bars.remove(id);
        if (b != null && b.task != null) {
            b.task.cancel();
        }
    }

    /** Поток игрока: ванильная полоска опыта показывает уровень core-vault (если включено). */
    public void syncBar(final Player p) {
        final Settings s = this.settings.get();
        if (!s.modules().levels() || !s.levels().vanillaBar()) {
            return;
        }
        final LevelMath.State st = this.state(p.getUniqueId());
        p.setLevel(st.level());
        p.setExp((float) Math.min(0.9999, LevelMath.progress(this.curve(), st)));
    }

    public TagResolver[] tags(final LevelMath.State st, final long exp, final String player) {
        final long need = this.curve().need(st.level());
        final int percent = (int) Math.floor(LevelMath.progress(this.curve(), st) * 100);
        return new TagResolver[] {Text.p("level", st.level()), Text.p("current", st.exp()), Text.p("needed", need),
            Text.p("percent", percent), Text.p("exp", exp), Text.p("player", player == null ? "" : player)};
    }

    /** Поток игрока. */
    private void effects(final Player p, final Change ch, final long shownExp) {
        if (!p.isOnline()) {
            return;
        }
        final Settings s = this.settings.get();
        final Settings.Levels l = s.levels();
        final TagResolver[] tags = this.tags(ch.after(), shownExp, p.getName());
        if (!ch.gained().isEmpty()) {
            if (!l.upMessage().isBlank()) {
                p.sendMessage(Text.parse(s.prefix() + l.upMessage(), tags));
            }
            if (!l.upTitle().isBlank() || !l.upSubtitle().isBlank()) {
                p.showTitle(Title.title(Text.parse(l.upTitle(), tags), Text.parse(l.upSubtitle(), tags),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
            }
            playSound(p, l.upSound(), l.upVolume(), l.upPitch());
        }
        if (shownExp > 0 && l.actionbar() && !l.actionbarFormat().isBlank()) {
            p.sendActionBar(Text.parse(l.actionbarFormat(), tags));
        }
        if (shownExp != 0 && l.bossbar()) {
            this.showBar(p, l, tags, ch.after());
        }
        this.syncBar(p);
    }

    public static void playSound(final Player p, final String sound, final float volume, final float pitch) {
        if (sound == null || sound.isBlank()) {
            return;
        }
        try {
            p.playSound(Sound.sound(Key.key(sound.trim().toLowerCase(Locale.ROOT)), Sound.Source.PLAYER, volume, pitch));
        } catch (final RuntimeException ignored) {
            // неверный ключ звука — молча (предупреждать на каждый подбор незачем)
        }
    }

    private void showBar(final Player p, final Settings.Levels l, final TagResolver[] tags, final LevelMath.State st) {
        BossBar.Color color;
        try {
            color = BossBar.Color.valueOf(l.bossbarColor());
        } catch (final IllegalArgumentException e) {
            color = BossBar.Color.GREEN;
        }
        final Component title = Text.parse(l.bossbarTitle(), tags);
        final float progress = (float) Math.max(0, Math.min(1, LevelMath.progress(this.curve(), st)));
        final Bar b = this.bars.computeIfAbsent(p.getUniqueId(), k -> new Bar());
        if (b.bar == null) {
            b.bar = BossBar.bossBar(title, progress, color, BossBar.Overlay.PROGRESS);
        } else {
            b.bar.name(title);
            b.bar.progress(progress);
            b.bar.color(color);
        }
        p.showBossBar(b.bar);
        b.hideAt = System.currentTimeMillis() + l.bossbarSeconds() * 1000L;
        if (b.task == null) {
            b.task = this.context.runForPlayer(p, t -> {
                if (System.currentTimeMillis() >= b.hideAt) {
                    p.hideBossBar(b.bar);
                    t.cancel();
                    b.task = null;
                }
            }, 10L);
        }
    }

    /** Награды: команды консоли в глобальном потоке. */
    private void rewards(final String name, final UUID id, final List<Integer> levels) {
        final Settings.Levels l = this.settings.get().levels();
        final List<String> cmds = new ArrayList<>();
        for (final int level : levels) {
            for (final String c : l.upCommands()) {
                cmds.add(fill(c, name, id, level));
            }
            for (final String c : l.rewards().getOrDefault(level, List.of())) {
                cmds.add(fill(c, name, id, level));
            }
        }
        if (cmds.isEmpty()) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> {
            for (final String c : cmds) {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                } catch (final RuntimeException e) {
                    this.context.logger().warning("Награда за уровень: команда «" + c + "» упала: " + e);
                }
            }
        });
    }

    static String fill(final String cmd, final String name, final UUID id, final int level) {
        String c = cmd.replace("%player%", name).replace("%uuid%", id.toString()).replace("%level%", Integer.toString(level));
        if (c.startsWith("/")) {
            c = c.substring(1);
        }
        return c;
    }

    /** Выключение: убрать босс-бары у игроков (в их потоках). */
    public void shutdown() {
        for (final var e : this.bars.entrySet()) {
            final Player p = Bukkit.getPlayer(e.getKey());
            final Bar b = e.getValue();
            if (b.task != null) {
                b.task.cancel();
            }
            if (p != null && b.bar != null) {
                final BossBar bar = b.bar;
                this.context.runOnceForPlayer(p, () -> p.hideBossBar(bar));
            }
        }
        this.bars.clear();
    }
}
