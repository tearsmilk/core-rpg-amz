package core.amazake.systems.corerpg.progression;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Сообщения модуля (раздел {@code messages} в progression.yml), MiniMessage. Подстановки — теги вида
 * {@code <level>}, значения подставляются как текст (без разбора тегов). Отсутствующие ключи берутся по умолчанию.
 */
public record Messages(Map<String, String> values) {

    public static final Map<String, String> DEFAULTS;

    static {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("only-players", "<red>Эта команда только для игроков.</red>");
        m.put("no-permission", "<red>У вас нет прав на это.</red>");
        m.put("profile-loading", "<yellow>Профиль ещё загружается, попробуйте через секунду.</yellow>");
        m.put("class-already-chosen", "<red>Вы уже выбрали класс! Смена невозможна.</red>");
        m.put("class-chosen", "<green>Вы выбрали класс: <yellow><class></yellow>!</green>");
        m.put("class-unknown", "<red>Нет такого класса: <white><input></white>.</red> <gray>Доступно: <list></gray>");
        m.put("must-choose-class", "<red>Сначала выберите класс:</red> <aqua>/class</aqua>");
        m.put("level-too-low", "<red>Профессию можно выбрать только с <level> уровня!</red> <gray>Сейчас: <current>.</gray>");
        m.put("profession-already-chosen", "<red>Вы уже выбрали профессию! Смена невозможна.</red>");
        m.put("profession-chosen", "<green>Вы стали <yellow><profession></yellow>!</green>");
        m.put("invalid-profession", "<red>Профессия «<input>» не подходит вашему классу.</red> <gray>Доступно: <list></gray>");
        m.put("need-profession", "<gold>Вы достигли <level> уровня!</gold>\n<yellow>Теперь нужно выбрать профессию.</yellow>\n"
            + "<gray>Доступно: <professions></gray>\n<aqua>Откройте меню: /profession</aqua>\n"
            + "<red>Пока профессия не выбрана — опыт не начисляется.</red>");
        m.put("exp-blocked", "<red>Опыт заблокирован!</red> <yellow>Выберите профессию:</yellow> <aqua>/profession</aqua>");
        m.put("exp-blocked-no-class", "<red>Опыт не начисляется без класса.</red> <yellow>Выберите класс:</yellow> <aqua>/class</aqua>");
        m.put("exp-gained", "<gray>+<amount> опыта</gray> <dark_gray>(<exp>/<needed>)</dark_gray>");
        m.put("level-up", "<green>★ Вы достигли <yellow><level></yellow> уровня! ★</green>");
        m.put("level-up-title", "<gold>Уровень <level>!</gold>");
        m.put("level-up-subtitle", "<yellow>Персонаж стал сильнее</yellow>");
        m.put("max-level", "<gold>Вы достигли максимального уровня <level>!</gold>");
        m.put("points-gained", "<gold>+<points> очка параметров!</gold> <aqua>Распределите: /stats</aqua>");
        m.put("stats-no-points", "<red>Нет свободных очков параметров.</red> <gray>Очки дают каждые <every> уровней.</gray>");
        m.put("stats-added", "<green><stat>: +<amount></green> <gray>(свободно очков: <free>)</gray>");
        m.put("stats-unknown", "<red>Нет такого параметра: <white><input></white>.</red> "
            + "<gray>Доступно: сила/точность/интеллект, ловкость, выносливость, удача</gray>");
        m.put("bad-number", "<red>«<input>» — не подходящее число.</red>");
        m.put("player-not-found", "<red>Игрок <white><input></white> не в сети.</red>");
        m.put("admin-reset", "<green>Игроку <yellow><player></yellow> сброшено: <type>.</green>");
        m.put("admin-reset-notify", "<yellow>Администратор сбросил ваши данные персонажа (<type>).</yellow>");
        m.put("admin-setlevel", "<green>Уровень игрока <yellow><player></yellow> установлен на <level>.</green>");
        m.put("admin-setlevel-notify", "<yellow>Администратор установил вам <level> уровень.</yellow>");
        m.put("admin-giveexp", "<green>Игроку <yellow><player></yellow> выдано <amount> опыта.</green>");
        m.put("admin-giveexp-blocked", "<red>Опыт игроку <yellow><player></yellow> не начислен: <reason>.</red>");
        m.put("admin-set", "<green>Игроку <yellow><player></yellow> установлено: <type> <value>.</green>");
        m.put("admin-set-notify", "<yellow>Администратор изменил вам <type>: <value>.</yellow>");
        m.put("admin-unknown-key", "<red>Нет такого ключа: <white><input></white>.</red> <gray>Доступно: <list></gray>");
        m.put("admin-reload", "<green>CoreRpg перезагружен.</green>");
        m.put("admin-reload-failed", "<red>Ошибка перезагрузки: <error></red> <gray>Подробности — в консоли.</gray>");
        m.put("admin-info", "<gold>═══ <player> ═══</gold>\n<yellow>Класс:</yellow> <class>\n<yellow>Профессия:</yellow> <profession>\n"
            + "<yellow>Уровень:</yellow> <level>/<max>\n<yellow>Опыт:</yellow> <exp>/<needed>\n"
            + "<yellow>Параметры:</yellow> <primary-name> <primary>, ловкость <agility>, выносливость <stamina>, удача <luck>\n"
            + "<yellow>Свободных очков:</yellow> <free>\n<yellow>Пассивки:</yellow> <passives>");
        m.put("info", "<gold>═══ Ваш персонаж ═══</gold>\n<yellow>Класс:</yellow> <class>\n<yellow>Профессия:</yellow> <profession>\n"
            + "<yellow>Уровень:</yellow> <level>\n<yellow>Опыт:</yellow> <exp>/<needed>");
        m.put("no-class", "Класс не выбран");
        m.put("no-profession", "Профессия не выбрана");
        m.put("menu-class-title", "<gold>⚔ Выбор класса</gold>");
        m.put("menu-profession-title", "<gold>⚔ Выбор профессии</gold>");
        m.put("menu-character-title", "<gold>📜 Мой персонаж</gold>");
        m.put("menu-stats-title", "<gold>Параметры</gold>");
        DEFAULTS = java.util.Collections.unmodifiableMap(m);
    }

    public static final Messages DEFAULT = new Messages(DEFAULTS);

    public Messages {
        values = Map.copyOf(values);
    }

    public String get(final String key) {
        final String v = this.values.get(key);
        if (v != null) {
            return v;
        }
        final String d = DEFAULTS.get(key);
        return d != null ? d : key;
    }

    public static Messages from(final ConfigurationSection section, final Consumer<String> warn) {
        final Map<String, String> out = new LinkedHashMap<>(DEFAULTS);
        if (section == null) {
            return new Messages(out);
        }
        for (final String key : section.getKeys(false)) {
            final Object raw = section.get(key);
            if (!DEFAULTS.containsKey(key)) {
                warn.accept("messages." + key + ": неизвестное сообщение, пропускаю");
                continue;
            }
            if (raw instanceof java.util.List<?> list) {
                final StringBuilder sb = new StringBuilder();
                for (final Object line : list) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(line);
                }
                out.put(key, sb.toString());
            } else if (raw == null || raw instanceof ConfigurationSection) {
                warn.accept("messages." + key + ": нужна строка, беру значение по умолчанию");
            } else {
                out.put(key, String.valueOf(raw));
            }
        }
        return new Messages(out);
    }
}
