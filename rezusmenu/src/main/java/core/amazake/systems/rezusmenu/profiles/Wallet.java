package core.amazake.systems.rezusmenu.profiles;

import core.amazake.systems.rezusmenu.util.Papi;
import java.lang.reflect.Method;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Оплата слота профиля. {@code vault} — провайдер {@code net.milkbowl.vault.economy.Economy} (на проде — встроенная
 * экономика форка Vault, на ней же core-vault), отражением; {@code command} — баланс плейсхолдером и списание
 * командами консоли; {@code none} — бесплатно. Вызывать в потоке игрока.
 */
final class Wallet {

    /** Итог оплаты: {@code error} null — списано. */
    record Result(String error) {
        boolean ok() {
            return this.error == null;
        }
    }

    private Wallet() {
    }

    static Result charge(final Player p, final ProfileConfig cfg, final Plugin owner) {
        final double price = cfg.price();
        if (price <= 0 || cfg.currency().equals("none")) {
            return new Result(null);
        }
        if (cfg.currency().equals("command")) {
            final String raw = Papi.value(p, cfg.balancePlaceholder());
            double balance;
            try {
                balance = raw == null ? Double.NaN : Double.parseDouble(raw.replace(" ", "").replace(',', '.'));
            } catch (final NumberFormatException e) {
                balance = Double.NaN;
            }
            if (Double.isNaN(balance)) {
                return new Result("баланс не узнать (" + cfg.balancePlaceholder() + ")");
            }
            if (balance < price) {
                return new Result("не хватает: нужно " + cfg.priceLabel());
            }
            final String name = p.getName();
            final String amount = price == Math.rint(price) ? String.valueOf((long) price) : String.format(Locale.ROOT, "%.2f", price);
            Bukkit.getGlobalRegionScheduler().execute(owner, () -> {
                for (final String c : cfg.takeCommands()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", name).replace("{price}", amount));
                }
            });
            return new Result(null);
        }
        try {
            Class<?> economy = null;
            for (final Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (c.getName().equals("net.milkbowl.vault.economy.Economy")) {
                    economy = c;
                    break;
                }
            }
            if (economy == null) {
                return new Result("экономика (Vault) не найдена");
            }
            final RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economy);
            if (rsp == null) {
                return new Result("экономика (Vault) не найдена");
            }
            final Object eco = rsp.getProvider();
            final Method has = economy.getMethod("has", OfflinePlayer.class, double.class);
            if (!Boolean.TRUE.equals(has.invoke(eco, p, price))) {
                return new Result("не хватает: нужно " + cfg.priceLabel());
            }
            final Method withdraw = economy.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            final Object r = withdraw.invoke(eco, p, price);
            final boolean ok = Boolean.TRUE.equals(r.getClass().getMethod("transactionSuccess").invoke(r));
            if (!ok) {
                Object msg = null;
                try {
                    msg = r.getClass().getField("errorMessage").get(r);
                } catch (final ReflectiveOperationException ignored) {
                    // без текста
                }
                return new Result(msg == null || String.valueOf(msg).isBlank() ? "оплата отклонена" : String.valueOf(msg));
            }
            return new Result(null);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return new Result("ошибка экономики: " + e);
        }
    }
}
