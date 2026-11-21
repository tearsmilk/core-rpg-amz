package ink.hoka.odyssey.respack;

import net.minestom.server.entity.Player;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Сервис модуля respack: другие модули кладут в общий ресурспак свои файлы, просят пересобрать и разослать.
 * <pre>
 * // module.yml: depends: [respack]
 * ResourcePackApi pack = services().require(ResourcePackApi.class);   // брать заново после перезагрузок
 * pack.putDirectory(id(), dataFolder().resolve("pack"));                // папка = корень пака (assets/...)
 * pack.putFile(id(), "assets/ships/textures/item/hull.png", bytes);    // или файл из памяти
 * pack.rebuild();                                                      // соберёт и (если изменился) разошлёт
 * // onDisable: pack.clear(id());
 * </pre>
 * Пути — внутри zip, через «/»: {@code assets/<namespace>/...}, {@code pack.png}. {@code pack.mcmeta} собирает
 * сам respack (свой — только из папки администратора module/respack/pack/). Если два модуля кладут один путь,
 * побеждает тот, кто зарегистрировал раньше, в лог — предупреждение; папка администратора перекрывает всех.
 * Все методы потокобезопасны. Сборка — в отдельном потоке, повторные вызовы rebuild() во время сборки
 * склеиваются в одну следующую.
 */
public interface ResourcePackApi {

    /** Файл из памяти (заменяет прежний файл этого владельца по тому же пути). Пак не пересобирается сам. */
    void putFile(String owner, String path, byte[] data);

    /** Убрать файл владельца. */
    void removeFile(String owner, String path);

    /** Папка на диске, содержимое которой — корень пака. Читается заново при каждой сборке. */
    void putDirectory(String owner, Path directory);

    /** Убрать все файлы и папки владельца (вызывать в onDisable модуля). Пак не пересобирается сам. */
    void clear(String owner);

    /**
     * Пересобрать пак. Будущее завершается, когда пак собран и выложен на HTTP (в потоке сервера). Если содержимое
     * изменилось и включено resend-on-rebuild — новый пак сразу уходит всем онлайн.
     */
    CompletableFuture<PackInfo> rebuild();

    /** Последний собранный пак (пусто — ещё не собран). */
    Optional<PackInfo> current();

    /** Отправить текущий пак игроку (false — пак ещё не собран). */
    boolean send(Player player);

    /** Отправить текущий пак всем онлайн; вернёт, скольким отправлен. */
    int sendAll();

    /** Собранный пак: SHA-1 (hex), размер zip в байтах, число файлов. */
    record PackInfo(String sha1, long size, int files) { }
}
