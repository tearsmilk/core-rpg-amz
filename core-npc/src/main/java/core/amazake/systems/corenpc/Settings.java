package core.amazake.systems.corenpc;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/** Неизменяемый снимок config.yml. */
public record Settings(String prefix, Dialogs dialog, Npcs npc, boolean modelEngine, Map<String, String> messages) {

    /** Как показывать диалог: окно над хотбаром (пакет ресурсов) или обычное окно диалога клиента 1.21.6+. */
    public enum Mode { HUD, NATIVE }

    public record Dialogs(Mode mode, boolean chatAnswers, double maxDistance, boolean freeze, boolean cameraLock,
                          boolean faceNpc, boolean sneakCloses, boolean leftClickConfirms, boolean swapConfirms,
                          boolean scrollSelects, boolean preventSkip, double charsPerTick, String typingSound,
                          float typingVolume, float typingPitch, int typingEvery, int refreshTicks, String speakerFormat,
                          String textColor, String answerFormat, String answerSelectedFormat, String chatAnswerFormat,
                          String chatHint, int nativeWidth, String nextButton, String closeButton, boolean flag) {
    }

    public record Npcs(int lookIntervalTicks, double lookRadius, double cooldown, double hologramOffset) {
    }

    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
        Map.entry("usage", "<gray>/npc create|remove|list|info|tp|tphere|rename|skin|type|model|dialog|command|set|talk|open|reload</gray>"),
        Map.entry("no-player-sender", "<red>Только для игрока.</red>"),
        Map.entry("bad-id", "<red>Id: латиница, цифры, _ - . (до 48).</red>"),
        Map.entry("exists", "<red>NPC <white><id></white> уже есть.</red>"),
        Map.entry("no-npc", "<red>Нет NPC <white><id></white>.</red>"),
        Map.entry("bad-type", "<red>Тип <white><type></white> не подходит: нужен живой моб (mannequin, villager, zombie…).</red>"),
        Map.entry("created", "<green>NPC <white><id></white> (<type>) создан здесь.</green>"),
        Map.entry("removed", "<green>NPC <white><id></white> удалён.</green>"),
        Map.entry("look-on", "<green>NPC <white><id></white> следит за игроками.</green>"),
        Map.entry("look-off", "<green>NPC <white><id></white> стоит статично.</green>"),
        Map.entry("look-me", "<green>NPC <white><id></white> стоит статично лицом к вам.</green>"),
        Map.entry("look-other-world", "<red>NPC <white><id></white> в другом мире.</red>"),
        Map.entry("moved", "<green>NPC <white><id></white> перенесён к вам.</green>"),
        Map.entry("teleported", "<green>Вы у NPC <white><id></white>.</green>"),
        Map.entry("renamed", "<green>Имя <white><id></white>: <name></green>"),
        Map.entry("skin-loading", "<gray>Ищу скин <white><name></white>…</gray>"),
        Map.entry("skin-set", "<green>Скин <white><id></white>: <white><name></white>.</green>"),
        Map.entry("skin-failed", "<red>Скин <white><name></white> не найден: <reason></red>"),
        Map.entry("skin-cleared", "<green>Скин <white><id></white> сброшен.</green>"),
        Map.entry("skin-not-mannequin", "<yellow>Скин виден только у типа mannequin (сейчас <type>), но сохранён.</yellow>"),
        Map.entry("type-set", "<green>Тип <white><id></white>: <type>.</green>"),
        Map.entry("model-set", "<green>Модель <white><id></white>: <white><model></white> ×<scale>.</green>"),
        Map.entry("model-removed", "<green>Модель с <white><id></white> снята.</green>"),
        Map.entry("model-unknown", "<yellow>В ModelEngine нет модели <white><model></white> — сохранено, появится после её загрузки.</yellow>"),
        Map.entry("no-modelengine", "<yellow>ModelEngine не установлен или выключен: модель сохранена, но не показывается.</yellow>"),
        Map.entry("dialog-set", "<green>Диалог <white><id></white>: <white><dialog></white>.</green>"),
        Map.entry("dialog-cleared", "<green>Диалог с <white><id></white> снят.</green>"),
        Map.entry("no-dialog", "<red>Нет диалога <white><dialog></white> (файл dialogs/<dialog>.yml).</red>"),
        Map.entry("command-added", "<green>Команда #<index> у <white><id></white>: <white><command></white></green>"),
        Map.entry("command-removed", "<green>Команда #<index> у <white><id></white> удалена.</green>"),
        Map.entry("command-bad-index", "<red>Нет команды #<index>.</red>"),
        Map.entry("commands-empty", "<gray>У <white><id></white> нет команд.</gray>"),
        Map.entry("command-line", "<gray>#<index></gray> <white><command></white>"),
        Map.entry("set", "<green><id>: <key> = <white><value></white></green>"),
        Map.entry("bad-value", "<red>Не понял значение <white><value></white> для <key>.</red>"),
        Map.entry("bad-key", "<red>Нет настройки <white><key></white>. Есть: <keys></red>"),
        Map.entry("list-empty", "<gray>NPC нет.</gray>"),
        Map.entry("list", "<gray>NPC (<count>):</gray> <list>"),
        Map.entry("no-target", "<red>Игрок <white><player></white> не в сети.</red>"),
        Map.entry("talk-started", "<green>Диалог <white><dialog></white> открыт игроку <white><player></white>.</green>"),
        Map.entry("reloaded", "<green>core-npc: настройки, диалоги и NPC перечитаны (<npcs> NPC, <dialogs> диалогов).</green>"),
        Map.entry("reload-failed", "<red>core-npc: не перечитано, всё осталось как было: <reason></red>"),
        Map.entry("no-console-permission", "<red>Консольные команды NPC добавляет только право <white>corenpc.admin.console</white>.</red>"),
        Map.entry("no-permission", "<red>Вам нельзя с ним говорить.</red>"),
        Map.entry("cooldown", ""),
        Map.entry("dialog-broken", "<red>Диалог сломан: <reason></red>")
    );

    public static final Settings DEFAULTS = new Settings("<dark_gray>[<gold>NPC</gold>]</dark_gray> ",
        new Dialogs(Mode.HUD, true, 6, true, false, true, true, true, true, true, false, 1.0,
            "minecraft:block.note_block.hat", 0.25f, 1.6f, 2, 2, "<#ffd37a><speaker>", "<#ece6d6>",
            "<#bdb6a6><index>. <text>", "<#ffd37a>» <index>. <text>", "<click><#ffd37a>[<index>]</#ffd37a> <text></click>",
            "<dark_gray>колёсико — выбрать, F или ЛКМ — ответить, Shift — уйти</dark_gray>", 300, "<gray>Дальше</gray>",
            "<gray>Уйти</gray>", true),
        new Npcs(4, 8, 1.0, 0.3), true, DEFAULT_MESSAGES);

    public String message(final String key) {
        final String m = this.messages.get(key);
        return m != null ? m : DEFAULT_MESSAGES.getOrDefault(key, "");
    }

    public static Settings from(final ConfigurationSection c, final Consumer<String> warn) {
        final Settings d = DEFAULTS;
        final ConfigurationSection ds = section(c, "dialog");
        final Dialogs dd = d.dialog();
        Mode mode = dd.mode();
        final String rawMode = ds.getString("mode", "hud").strip().toLowerCase(Locale.ROOT);
        switch (rawMode) {
            case "hud", "actionbar" -> mode = Mode.HUD;
            case "native", "dialog", "screen" -> mode = Mode.NATIVE;
            default -> warn.accept("dialog.mode: «" + rawMode + "» — нужно hud или native, беру hud");
        }
        final Dialogs dialogs = new Dialogs(mode,
            ds.getBoolean("chat-answers", dd.chatAnswers()),
            range(ds, "max-distance", dd.maxDistance(), 0, 128, warn),
            ds.getBoolean("freeze", dd.freeze()),
            ds.getBoolean("camera-lock", dd.cameraLock()),
            ds.getBoolean("face-npc", dd.faceNpc()),
            ds.getBoolean("input.sneak-closes", dd.sneakCloses()),
            ds.getBoolean("input.left-click-confirms", dd.leftClickConfirms()),
            ds.getBoolean("input.swap-hands-confirms", dd.swapConfirms()),
            ds.getBoolean("input.scroll-selects", dd.scrollSelects()),
            ds.getBoolean("prevent-skip", dd.preventSkip()),
            range(ds, "chars-per-tick", dd.charsPerTick(), 0.05, 50, warn),
            ds.getString("typing-sound.sound", dd.typingSound()),
            (float) range(ds, "typing-sound.volume", dd.typingVolume(), 0, 10, warn),
            (float) range(ds, "typing-sound.pitch", dd.typingPitch(), 0.5, 2, warn),
            (int) range(ds, "typing-sound.every", dd.typingEvery(), 1, 100, warn),
            (int) range(ds, "refresh-ticks", dd.refreshTicks(), 1, 40, warn),
            ds.getString("format.speaker", dd.speakerFormat()),
            ds.getString("format.text", dd.textColor()),
            ds.getString("format.answer", dd.answerFormat()),
            ds.getString("format.answer-selected", dd.answerSelectedFormat()),
            ds.getString("format.chat-answer", dd.chatAnswerFormat()),
            ds.getString("format.chat-hint", dd.chatHint()),
            (int) range(ds, "native.width", dd.nativeWidth(), 50, 1024, warn),
            ds.getString("native.next-button", dd.nextButton()),
            ds.getString("native.close-button", dd.closeButton()),
            ds.getBoolean("player-flag", dd.flag()));
        final ConfigurationSection ns = section(c, "npc");
        final Npcs nd = d.npc();
        final Npcs npcs = new Npcs((int) range(ns, "look-interval-ticks", nd.lookIntervalTicks(), 1, 100, warn),
            range(ns, "look-radius", nd.lookRadius(), 0, 64, warn),
            range(ns, "cooldown-seconds", nd.cooldown(), 0, 3600, warn),
            range(ns, "hologram-offset", nd.hologramOffset(), -5, 5, warn));
        final Map<String, String> msgs = new HashMap<>(DEFAULT_MESSAGES);
        final ConfigurationSection ms = c.getConfigurationSection("messages");
        if (ms != null) {
            for (final String k : ms.getKeys(false)) {
                msgs.put(k, ms.getString(k, ""));
            }
        }
        return new Settings(c.getString("prefix", d.prefix()), dialogs, npcs, c.getBoolean("modelengine.enabled", true),
            Map.copyOf(msgs));
    }

    private static ConfigurationSection section(final ConfigurationSection c, final String key) {
        final ConfigurationSection s = c.getConfigurationSection(key);
        return s != null ? s : c.createSection(key);
    }

    private static double range(final ConfigurationSection c, final String key, final double def, final double min,
                                final double max, final Consumer<String> warn) {
        if (!c.contains(key)) {
            return def;
        }
        final double v = c.getDouble(key, def);
        if (v < min || v > max) {
            warn.accept(c.getCurrentPath() + "." + key + ": " + v + " вне " + min + ".." + max + ", беру " + def);
            return def;
        }
        return v;
    }
}
