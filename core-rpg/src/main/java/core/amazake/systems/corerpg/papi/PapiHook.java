package core.amazake.systems.corerpg.papi;

import java.lang.reflect.InvocationTargetException;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * Регистрация моста в PlaceholderAPI рефлексией: класс моста определяется в {@link BridgeLoader}, его методы
 * {@code register()}, {@code unregister()} и {@code detach()} вызываются через отражение. Все методы — в глобальном
 * потоке тика (enable/reload/disable системы и события плагинов); состояние всё равно volatile.
 *
 * <p>PlaceholderAPI 2.11.6 у внутренних (созданных кодом) расширений не отказывает при занятом идентификаторе, а
 * молча снимает чужое расширение; а {@code unregister()} удаляет запись по идентификатору, не проверяя, чья она.
 * Поэтому хук сам сверяется с {@code LocalExpansionManager.getExpansion(id)}: не регистрируется поверх чужого
 * расширения и не снимает чужое, если его расширение уже вытеснили.</p>
 */
final class PapiHook {

    private final Logger logger;
    /** Подсказка в предупреждении о занятом идентификаторе: где его сменить. */
    private final String occupiedHint;
    private volatile Object expansion;
    private volatile String identifier;
    private volatile Plugin placeholderApi;

    PapiHook(final Logger logger, final String occupiedHint) {
        this.logger = logger;
        this.occupiedHint = occupiedHint;
    }

    /** Наше расширение сейчас действительно стоит в PlaceholderAPI под своим идентификатором (не вытеснено чужим). */
    boolean isRegistered() {
        final Object current = this.expansion;
        final Plugin papi = this.placeholderApi;
        final String id = this.identifier;
        if (current == null || papi == null || id == null) {
            return false;
        }
        final Holder holder = this.holder(papi, id);
        return !holder.known() || holder.value() == current;
    }

    /** Есть ли у нас объект расширения (даже если PlaceholderAPI его уже вытеснил чужим). */
    boolean hasExpansion() {
        return this.expansion != null;
    }

    String identifier() {
        return this.identifier;
    }

    /**
     * Зарегистрировать расширение {@code %<identifier>_…%}. Прежнее, если было, сначала снимается. Если идентификатор
     * занят чужим расширением — не регистрируемся и предупреждаем.
     *
     * @return true, если PlaceholderAPI принял расширение
     */
    boolean register(final Plugin placeholderApi, final String identifier, final String author, final String version,
                     final BiFunction<OfflinePlayer, String, String> resolver) {
        this.unregister();
        if (this.isOccupied(placeholderApi, identifier)) {
            this.logger.warning("Идентификатор PlaceholderAPI «" + identifier + "» уже занят другим расширением — "
                + "плейсхолдеры %" + identifier + "_…% CoreRpg не зарегистрированы. " + this.occupiedHint);
            return false;
        }
        try {
            final BridgeLoader loader = new BridgeLoader(placeholderApi.getClass().getClassLoader(), PapiHook.class.getClassLoader());
            final Class<?> type = Class.forName(BridgeLoader.BRIDGE_CLASS, true, loader);
            final Object created = type.getConstructor(String.class, String.class, String.class, BiFunction.class)
                .newInstance(identifier, author, version, resolver);
            final Object ok = type.getMethod("register").invoke(created);
            if (!Boolean.TRUE.equals(ok)) {
                detach(created);
                this.logger.warning("PlaceholderAPI не принял расширение «" + identifier + "». " + this.occupiedHint);
                return false;
            }
            this.expansion = created;
            this.identifier = identifier;
            this.placeholderApi = placeholderApi;
            this.logger.info("Плейсхолдеры PlaceholderAPI зарегистрированы: %" + identifier + "_…%.");
            return true;
        } catch (final InvocationTargetException e) {
            this.logger.log(Level.WARNING, "Не удалось зарегистрировать плейсхолдеры в PlaceholderAPI", e.getCause());
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            this.logger.log(Level.WARNING, "Не удалось загрузить мост PlaceholderAPI (версия PlaceholderAPI не та?)", e);
        }
        return false;
    }

    /**
     * Снять расширение (если было) и отпустить ссылки на систему. Безопасно вызывать повторно. Если под нашим
     * идентификатором уже стоит чужое расширение, PlaceholderAPI не трогаем — только забываем своё.
     */
    void unregister() {
        final Object current = this.expansion;
        final Plugin papi = this.placeholderApi;
        final String id = this.identifier;
        this.expansion = null;
        this.identifier = null;
        this.placeholderApi = null;
        if (current == null) {
            return;
        }
        final Holder holder = papi == null || id == null ? Holder.UNKNOWN : this.holder(papi, id);
        if (!holder.known() || holder.value() == current) {
            try {
                current.getClass().getMethod("unregister").invoke(current);
            } catch (final InvocationTargetException e) {
                this.logger.log(Level.FINE, "PlaceholderAPI: unregister завершился с ошибкой", e.getCause());
            } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
                this.logger.log(Level.FINE, "PlaceholderAPI: unregister недоступен", e);
            }
        }
        detach(current);
    }

    /** Занят ли идентификатор в PlaceholderAPI расширением, которое не наше. */
    private boolean isOccupied(final Plugin papi, final String id) {
        final Holder holder = this.holder(papi, id);
        return holder.known() && holder.value() != null && holder.value() != this.expansion;
    }

    /** Кто держит идентификатор: {@code LocalExpansionManager.getExpansion(id)} рефлексией. */
    private Holder holder(final Plugin papi, final String id) {
        try {
            final Object manager = papi.getClass().getMethod("getLocalExpansionManager").invoke(papi);
            if (manager == null) {
                return Holder.UNKNOWN;
            }
            return new Holder(true, manager.getClass().getMethod("getExpansion", String.class).invoke(manager, id));
        } catch (final InvocationTargetException e) {
            this.logger.log(Level.FINE, "PlaceholderAPI: getExpansion завершился с ошибкой", e.getCause());
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            this.logger.log(Level.FINE, "PlaceholderAPI: getExpansion недоступен", e);
        }
        return Holder.UNKNOWN;
    }

    /** Результат поиска: known — PlaceholderAPI удалось спросить; value — расширение под идентификатором или null. */
    private record Holder(boolean known, Object value) {
        static final Holder UNKNOWN = new Holder(false, null);
    }

    private static void detach(final Object expansion) {
        try {
            expansion.getClass().getMethod("detach").invoke(expansion);
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // мост старой сборки без detach: ссылку отпустит сам PlaceholderAPI после unregister
        }
    }
}
