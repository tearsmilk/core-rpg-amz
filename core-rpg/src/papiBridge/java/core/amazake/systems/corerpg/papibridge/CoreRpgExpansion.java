package core.amazake.systems.corerpg.papibridge;

import java.util.function.BiFunction;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * Мост CoreRpg → PlaceholderAPI. Компилируется отдельно и лежит в .azs ресурсом {@code papi-bridge/...class.bin}:
 * система определяет этот класс в загрузчике-потомке загрузчика PlaceholderAPI (см. {@code papi/BridgeLoader}).
 *
 * <p>Зависит только от PlaceholderAPI, Bukkit и JDK. Вся логика — в {@code resolver} на стороне системы; мост лишь
 * передаёт запрос. {@link #detach()} отпускает resolver, чтобы выключенная система не осталась в памяти, даже если
 * PlaceholderAPI почему-то продолжит держать объект расширения.</p>
 */
public final class CoreRpgExpansion extends PlaceholderExpansion {

    private final String identifier;
    private final String author;
    private final String version;
    private volatile BiFunction<OfflinePlayer, String, String> resolver;

    public CoreRpgExpansion(final String identifier, final String author, final String version,
                            final BiFunction<OfflinePlayer, String, String> resolver) {
        this.identifier = identifier;
        this.author = author;
        this.version = version;
        this.resolver = resolver;
    }

    @Override
    public String getIdentifier() {
        return this.identifier;
    }

    @Override
    public String getAuthor() {
        return this.author;
    }

    @Override
    public String getVersion() {
        return this.version;
    }

    /** Не выгружать при /papi reload: расширение живёт, пока включена система. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(final OfflinePlayer player, final String params) {
        final BiFunction<OfflinePlayer, String, String> current = this.resolver;
        if (current == null) {
            return null;
        }
        return current.apply(player, params);
    }

    /** Отпустить resolver системы (вызывается при выключении системы после unregister). */
    public void detach() {
        this.resolver = null;
    }
}
