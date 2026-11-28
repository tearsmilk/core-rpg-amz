package ink.hoka.odyssey.respack;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minestom.server.entity.Player;

/**
 * Сервис модуля respack (Takuyamo), который стоит на сервере Hoka Odyssey. Здесь — только сигнатуры, снятые с
 * module/respack.jar (javap), для компиляции coreshaders; реализация и сам модуль — не в этом репозитории.
 * Если в respack поменяют интерфейс — обновить этот файл по новому jar.
 * <p>
 * Как работает реализация (по байткоду 0.1.0): {@code put} хранит файл в памяти под путём внутри zip
 * ({@code assets/…}; «\» → «/», ведущий «/» срезается, «..» запрещено); {@code rebuild} собирает пак через 10 тиков
 * (повторные вызовы склеиваются).
 */
public interface ResourcePackApi {
    void put(String owner, String path, byte[] data);

    void remove(String owner, String path);

    void clear(String owner);

    CompletableFuture<Void> rebuild();

    boolean hasPack(Player player);

    Optional<String> url();

    Optional<byte[]> vanilla(String path);

    CompletableFuture<Void> vanillaReady();
}
