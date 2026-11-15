package core.amazake.systems.corevault.cmd;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corevault.Settings;
import core.amazake.systems.corevault.Text;
import core.amazake.systems.corevault.data.AccountStore;
import core.amazake.systems.corevault.data.TopCache;
import core.amazake.systems.corevault.eco.Treasury;
import core.amazake.systems.corevault.level.LevelService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/**
 * Всё, что нужно командам. Команда игрока выполняется в его потоке, консоли — в глобальном; сами счета
 * потокобезопасны, ответы уходят через {@link Text#reply} (в поток получателя).
 *
 * @param reload   перечитать config.yml; получает отправителя для ответа
 * @param importer импорт балансов из плагина Vault: (отправитель, заменять ли существующие)
 */
public record Env(SystemContext context, Supplier<Settings> settings, AccountStore store, Treasury treasury,
                  LevelService levels, TopCache top, Consumer<CommandSender> reload,
                  BiConsumer<CommandSender, Boolean> importer) {

    public static final String ADMIN = "corevault.admin";

    public Settings s() {
        return this.settings.get();
    }

    public void msg(final CommandSender to, final String key, final TagResolver... r) {
        Text.reply(this.context, to, this.s(), key, r);
    }

    /** Модуль выключен — сообщить и вернуть false. */
    public boolean need(final CommandSender to, final boolean on, final String module) {
        if (!on) {
            this.msg(to, "module-off", Text.p("module", module));
        }
        return on;
    }

    public String money(final long minor) {
        return this.s().economy().currency().format(minor);
    }
}
