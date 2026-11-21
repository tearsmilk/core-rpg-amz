package ink.hoka.odyssey.respack;

import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * /hopack — ресурспак (только OP и консоль):
 * без аргументов — состояние; rebuild — пересобрать; reload — перечитать config.yml и пересобрать;
 * send [ник|all] — отправить пак заново.
 */
final class HoPackCommand extends Command {

    private final RespackModule m;

    HoPackCommand(RespackModule m) {
        super("hopack");
        this.m = m;
        setCondition((s, c) -> !(s instanceof Player p) || m.server().isOp(p));
        setDefaultExecutor((s, c) -> status(s));

        addSyntax((s, c) -> report(s, m.rebuild(), "Пересборка ресурспака…"), ArgumentType.Literal("rebuild"));
        addSyntax((s, c) -> {
            CompletableFuture<ResourcePackApi.PackInfo> f;
            try {
                f = m.reloadSettings();
            } catch (IllegalStateException e) {
                s.sendMessage(Component.text("Конфиг не применён: " + e.getMessage(), NamedTextColor.RED));
                return;
            }
            report(s, f, "Конфиг перечитан, пересборка ресурспака…");
        }, ArgumentType.Literal("reload"));

        addSyntax((s, c) -> {
            if (s instanceof Player p) sendTo(s, p); else s.sendMessage(Component.text("/hopack send <ник|all>", NamedTextColor.AQUA));
        }, ArgumentType.Literal("send"));
        ArgumentWord target = ArgumentType.Word("ник");
        target.setSuggestionCallback((s, c, sug) -> {
            sug.addEntry(new SuggestionEntry("all"));
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) sug.addEntry(new SuggestionEntry(p.getUsername()));
        });
        addSyntax((s, c) -> {
            String name = c.get(target);
            if (name.equalsIgnoreCase("all")) {
                int n = m.sendAll();
                s.sendMessage(Component.text(m.current().isEmpty() ? "Пак ещё не собран." : "Пак отправлен игрокам: " + n,
                        NamedTextColor.GREEN));
                return;
            }
            Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
            if (p == null) s.sendMessage(Component.text("Игрок " + name + " не в сети.", NamedTextColor.RED));
            else sendTo(s, p);
        }, ArgumentType.Literal("send"), target);
    }

    private void sendTo(CommandSender s, Player p) {
        s.sendMessage(m.send(p) ? Component.text("Пак отправлен: " + p.getUsername(), NamedTextColor.GREEN)
                : Component.text("Пак ещё не собран.", NamedTextColor.RED));
    }

    private void report(CommandSender s, CompletableFuture<ResourcePackApi.PackInfo> f, String start) {
        String before = m.current().map(ResourcePackApi.PackInfo::sha1).orElse("");
        s.sendMessage(Component.text(start, NamedTextColor.GRAY));
        f.whenComplete((info, err) -> {
            Component msg;
            if (err != null) {
                Throwable c = err.getCause() != null ? err.getCause() : err;
                msg = Component.text("Ресурспак не собран: " + c.getMessage(), NamedTextColor.RED);
            } else if (info.sha1().equals(before)) {
                msg = Component.text("Ресурспак без изменений (файлов " + info.files() + ", " + kb(info.size()) + ").", NamedTextColor.YELLOW);
            } else {
                msg = Component.text("Ресурспак собран: файлов " + info.files() + ", " + kb(info.size()) + ", sha1 "
                        + info.sha1().substring(0, 12) + "…" + (m.settings().resendOnRebuild() ? " Отправлен игрокам онлайн." : ""),
                        NamedTextColor.GREEN);
            }
            try {
                m.scheduler().run(() -> s.sendMessage(msg));
            } catch (IllegalStateException ignored) {
                // модуль уже выключен
            }
        });
    }

    private static String kb(long bytes) { return (bytes / 1024) + " КБ"; }

    private void status(CommandSender s) {
        s.sendMessage(Component.text("Ресурспак Hoka Odyssey", NamedTextColor.AQUA));
        m.current().ifPresentOrElse(info -> {
            s.sendMessage(Component.text(" файлов " + info.files() + ", " + kb(info.size()) + ", sha1 " + info.sha1(), NamedTextColor.GRAY));
            String url = s instanceof Player p ? m.url(p, info) : m.url(null, info);
            s.sendMessage(Component.text(" адрес: " + url, NamedTextColor.GRAY));
        }, () -> s.sendMessage(Component.text(" ещё не собран", NamedTextColor.YELLOW)));
        RespackModule.Settings st = m.settings();
        s.sendMessage(Component.text(" HTTP: " + st.bind() + ":" + m.httpPort() + (st.publicUrl().isEmpty()
                ? " (адрес для клиентов — адрес входа игрока)" : ", public-url " + st.publicUrl()), NamedTextColor.GRAY));
        s.sendMessage(Component.text(" при входе: " + (st.sendOnJoin() ? "да" : "нет") + ", обязательный: " + (st.required() ? "да" : "нет")
                + ", кик при отказе: " + (st.kickOnDecline() ? "да" : "нет") + ", при ошибке: " + (st.kickOnFail() ? "да" : "нет"),
                NamedTextColor.GRAY));
        for (String line : m.sources().describe()) s.sendMessage(Component.text(" модуль " + line, NamedTextColor.DARK_GRAY));
        s.sendMessage(Component.text(" своя папка: " + m.adminDir(), NamedTextColor.DARK_GRAY));
        Map<ResourcePackStatus, Integer> counts = m.statusCounts();
        if (!counts.isEmpty()) {
            StringBuilder b = new StringBuilder(" игроки:");
            counts.forEach((k, v) -> b.append(' ').append(name(k)).append(' ').append(v));
            s.sendMessage(Component.text(b.toString(), NamedTextColor.GRAY));
        }
        s.sendMessage(Component.text("/hopack rebuild | reload | send [ник|all]", NamedTextColor.DARK_AQUA));
    }

    static String name(ResourcePackStatus st) {
        return switch (st) {
            case SUCCESSFULLY_LOADED -> "загружен";
            case ACCEPTED -> "принят";
            case DOWNLOADED -> "скачан";
            case DECLINED -> "отказ";
            case FAILED_DOWNLOAD -> "не скачался";
            case INVALID_URL -> "неверный адрес";
            case FAILED_RELOAD -> "не применился";
            case DISCARDED -> "сброшен";
        };
    }
}
