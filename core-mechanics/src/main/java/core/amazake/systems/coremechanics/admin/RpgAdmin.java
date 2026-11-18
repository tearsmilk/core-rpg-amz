package core.amazake.systems.coremechanics.admin;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * «Режим админа» ({@code /rpgadmin}): творческий режим, и RPG-системы оставляют игрока в покое — не переписывают его
 * инвентарь, не перехватывают F, не снимают бижутерию, не превращают монеты в баланс при клике.
 *
 * <p>Флаг лежит в PDC игрока: {@code coremechanics:rpgadmin} (BYTE, 1 — включён), прежний режим игры —
 * {@code coremechanics:rpgadmin_prev} (STRING, имя {@link GameMode}). Другие системы читают только флаг (см. README,
 * «Режим админа»), core-mechanics им не нужна.</p>
 *
 * <p>Все методы с игроком — в его потоке (Folia/регионы).</p>
 */
public final class RpgAdmin implements Listener {

    public static final String PERMISSION = "coremechanics.rpgadmin";
    /** Флаг режима админа: BYTE 1. Ключ общий для всех систем — не переименовывать. */
    public static final NamespacedKey FLAG = new NamespacedKey("coremechanics", "rpgadmin");
    /** Режим игры до включения (STRING, имя GameMode). */
    public static final NamespacedKey PREVIOUS = new NamespacedKey("coremechanics", "rpgadmin_prev");
    /** Через сколько тиков после входа вернуть творческий режим: Multiverse ставит режим мира через 1 тик. */
    private static final long JOIN_REAPPLY_TICKS = 10L;

    private final Plugin plugin;

    public RpgAdmin(final Plugin plugin) {
        this.plugin = plugin;
    }

    /** Включён ли режим админа у игрока (или любого держателя PDC). Можно звать из любого потока игрока. */
    public static boolean isOn(final PersistentDataHolder holder) {
        if (holder == null) {
            return false;
        }
        final Byte b = holder.getPersistentDataContainer().get(FLAG, PersistentDataType.BYTE);
        return b != null && b != 0;
    }

    /** Режим, который вернуть при выключении: сохранённый, а если его нет или он испорчен — выживание. */
    static GameMode restoreMode(final String saved) {
        if (saved == null || saved.isBlank()) {
            return GameMode.SURVIVAL;
        }
        try {
            return GameMode.valueOf(saved.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return GameMode.SURVIVAL;
        }
    }

    /** Включить. false — уже был включён (тогда только снова творческий режим). Поток игрока. */
    public static boolean enable(final Player p) {
        final PersistentDataContainer pdc = p.getPersistentDataContainer();
        final boolean was = isOn(p);
        if (!was) {
            // Сначала флаг, потом режим: слушатели смены режима (и наш onGameMode) уже видят режим админа.
            pdc.set(PREVIOUS, PersistentDataType.STRING, p.getGameMode().name());
            pdc.set(FLAG, PersistentDataType.BYTE, (byte) 1);
        }
        if (p.getGameMode() != GameMode.CREATIVE) {
            p.setGameMode(GameMode.CREATIVE);
        }
        return !was;
    }

    /** Выключить и вернуть прежний режим игры. Возвращает этот режим или null, если режим админа не был включён. */
    public static GameMode disable(final Player p) {
        if (!isOn(p)) {
            return null;
        }
        final PersistentDataContainer pdc = p.getPersistentDataContainer();
        final GameMode back = restoreMode(pdc.get(PREVIOUS, PersistentDataType.STRING));
        pdc.remove(FLAG);
        pdc.remove(PREVIOUS);
        if (p.getGameMode() != back) {
            p.setGameMode(back);
        }
        return back;
    }

    /** Название режима по-русски для сообщений. */
    static String modeName(final GameMode m) {
        return switch (m) {
            case SURVIVAL -> "выживание";
            case CREATIVE -> "творческий";
            case ADVENTURE -> "приключение";
            case SPECTATOR -> "наблюдатель";
        };
    }

    // ------------------------------------------------------------------------------------------- события

    /** Вход: флаг без права (право отобрали) — выключить; с правом — напомнить и вернуть творческий режим. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        if (!isOn(p)) {
            return;
        }
        if (!p.hasPermission(PERMISSION)) {
            final GameMode back = disable(p);
            p.sendMessage(Component.text("Режим админа выключен: нет права " + PERMISSION + ". Режим игры: "
                + modeName(back) + ".", NamedTextColor.YELLOW));
            return;
        }
        // Сразу и ещё раз чуть позже: Multiverse (enforce-gamemode) после входа ставит режим мира.
        if (p.getGameMode() != GameMode.CREATIVE) {
            p.setGameMode(GameMode.CREATIVE);
        }
        p.getScheduler().runDelayed(this.plugin, t -> {
            if (p.isOnline() && isOn(p) && p.getGameMode() != GameMode.CREATIVE) {
                p.setGameMode(GameMode.CREATIVE);
            }
        }, null, JOIN_REAPPLY_TICKS);
        p.sendMessage(Component.text("Вы в режиме админа: творческий режим, RPG-системы вас не трогают. "
            + "/rpgadmin — выключить.", NamedTextColor.GOLD));
    }

    /**
     * Админ сам сменил режим командой (/gamemode survival) — значит, режим админа ему больше не нужен: снимаем флаг,
     * чтобы RPG-системы снова работали с ним как с игроком. Смены режима плагинами (меню-наблюдатель и т. п.) флаг не
     * снимают.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(final PlayerGameModeChangeEvent e) {
        final Player p = e.getPlayer();
        if (e.getNewGameMode() == GameMode.CREATIVE || e.getCause() != PlayerGameModeChangeEvent.Cause.COMMAND || !isOn(p)) {
            return;
        }
        final PersistentDataContainer pdc = p.getPersistentDataContainer();
        pdc.remove(FLAG);
        pdc.remove(PREVIOUS);
        p.sendMessage(Component.text("Режим админа выключен: режим игры сменён командой.", NamedTextColor.YELLOW));
    }
}
