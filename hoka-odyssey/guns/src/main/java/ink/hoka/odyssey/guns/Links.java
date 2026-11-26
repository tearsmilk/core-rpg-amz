package ink.hoka.odyssey.guns;

import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import org.slf4j.Logger;

/**
 * Связь с чужими модулями через рефлексию (softdepends): clientmods (ClientModsApi), hoitems (HoItems),
 * hosounds (HoSounds). Их классов при сборке нет, а на сервере модуля может не быть — тогда ссылка пустая и модуль
 * работает без него (как SpaceflightLink в gravity). Сервисы берутся заново раз в несколько секунд
 * ({@link #resolve}) — после перезагрузки чужого модуля это другой объект.
 */
final class Links {
    private static final String CLIENTMODS = "ink.hoka.odyssey.clientmods.ClientModsApi";
    private static final String HOITEMS = "ink.hoka.odyssey.hoitems.HoItems";
    private static final String HOSOUNDS = "ink.hoka.odyssey.hosounds.HoSounds";

    private final GunsModule mod;
    private final Logger log;
    private boolean warned;

    private Object client;
    private Method cSend;
    private Method cOn;
    private Method cOff;
    private Method cOnReady;
    private Method cIsReady;
    private Method cPutFile;
    private Method cClearFiles;
    private Method cPublish;

    private Object items;
    private Method iItem;
    private Method iIdOf;

    private Object sounds;
    private Method sPlay;
    private Method sExists;

    Links(GunsModule mod, Logger log) {
        this.mod = mod;
        this.log = log;
    }

    /**
     * Найти сервисы заново. true — clientmods новый (подписались заново: надо снова выложить файлы и разослать
     * настройки).
     */
    boolean resolve(BiConsumer<Player, byte[]> onMessage, Consumer<Player> onReady) {
        boolean fresh = false;
        Object c = service(CLIENTMODS);
        if (c != client) {
            client = null;
            if (c != null) {
                try {
                    Class<?> t = Class.forName(CLIENTMODS, false, Links.class.getClassLoader());
                    cSend = t.getMethod("send", Player.class, String.class, byte[].class);
                    cOn = t.getMethod("on", String.class, BiConsumer.class);
                    cOff = t.getMethod("off", String.class);
                    cOnReady = t.getMethod("onReady", String.class, Consumer.class);
                    cIsReady = t.getMethod("isReady", Player.class, String.class);
                    cPutFile = t.getMethod("putFile", String.class, String.class, byte[].class);
                    cClearFiles = t.getMethod("clearFiles", String.class);
                    cPublish = t.getMethod("publishFiles");
                    cOn.invoke(c, Proto.CHANNEL, onMessage);
                    cOnReady.invoke(c, Proto.CHANNEL, onReady);
                    client = c;
                    fresh = true;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    warn(e);
                }
            }
        }
        Object i = service(HOITEMS);
        if (i != items) {
            items = null;
            if (i != null) {
                try {
                    Class<?> t = Class.forName(HOITEMS, false, Links.class.getClassLoader());
                    iItem = t.getMethod("item", String.class, int.class);
                    iIdOf = t.getMethod("idOf", ItemStack.class);
                    items = i;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    warn(e);
                }
            }
        }
        Object s = service(HOSOUNDS);
        if (s != sounds) {
            sounds = null;
            if (s != null) {
                try {
                    Class<?> t = Class.forName(HOSOUNDS, false, Links.class.getClassLoader());
                    sPlay = t.getMethod("play", Player.class, String.class, Point.class, float.class, float.class);
                    sExists = t.getMethod("exists", String.class);
                    sounds = s;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    warn(e);
                }
            }
        }
        return fresh;
    }

    private Object service(String name) {
        try {
            Class<?> t = Class.forName(name, false, Links.class.getClassLoader());
            return mod.service(t);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        } catch (RuntimeException e) {
            warn(e);
            return null;
        }
    }

    boolean hasClient() {
        return client != null;
    }

    boolean hasItems() {
        return items != null;
    }

    boolean hasSounds() {
        return sounds != null;
    }

    // ---------------------------------------------------------------- clientmods

    void send(Player p, byte[] data) {
        Object c = client;
        if (c == null) {
            return;
        }
        try {
            cSend.invoke(c, p, Proto.CHANNEL, data);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
        }
    }

    boolean ready(Player p) {
        Object c = client;
        if (c == null) {
            return false;
        }
        try {
            return (boolean) cIsReady.invoke(c, p, Proto.CHANNEL);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
            return false;
        }
    }

    void putFile(String path, byte[] data) {
        Object c = client;
        if (c == null) {
            return;
        }
        try {
            cPutFile.invoke(c, mod.id(), path, data);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
        }
    }

    void clearFiles() {
        Object c = client;
        if (c == null) {
            return;
        }
        try {
            cClearFiles.invoke(c, mod.id());
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
        }
    }

    void publish() {
        Object c = client;
        if (c == null) {
            return;
        }
        try {
            cPublish.invoke(c);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
        }
    }

    /** Отписаться от clientmods (onDisable). Файлы убрать — {@link #clearFiles()} до этого. */
    void close() {
        Object c = client;
        client = null;
        items = null;
        sounds = null;
        if (c != null) {
            try {
                cOff.invoke(c, Proto.CHANNEL);
            } catch (ReflectiveOperationException | RuntimeException e) {
                warn(e);
            }
        }
    }

    // ---------------------------------------------------------------- hoitems

    /** Предмет hoitems (null — нет модуля или такого id). */
    ItemStack item(String id, int count) {
        Object i = items;
        if (i == null || id == null || id.isEmpty()) {
            return null;
        }
        try {
            return (ItemStack) iItem.invoke(i, id, count);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
            return null;
        }
    }

    String idOf(ItemStack stack) {
        Object i = items;
        if (i == null || stack == null || stack.isAir()) {
            return null;
        }
        try {
            return (String) iIdOf.invoke(i, stack);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
            return null;
        }
    }

    // ---------------------------------------------------------------- hosounds

    /** Звук hoka:id игроку из точки. Без hosounds — напрямую (мод всё равно знает звук по имени hoka:id). */
    void play(Player p, String id, Point at, float volume, float pitch) {
        if (id == null || id.isEmpty()) {
            return;
        }
        Object s = sounds;
        if (s != null) {
            try {
                sPlay.invoke(s, p, id, at, volume, pitch);
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                warn(e);
            }
        }
        p.playSound(net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key("hoka", id),
                net.kyori.adventure.sound.Sound.Source.PLAYER, volume, pitch), at.x(), at.y(), at.z());
    }

    /** Есть ли такой звук в hosounds (без hosounds — неизвестно, true). */
    boolean soundExists(String id) {
        Object s = sounds;
        if (s == null) {
            return true;
        }
        try {
            return (boolean) sExists.invoke(s, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    private void warn(Throwable e) {
        if (!warned) {
            warned = true;
            log.warn("guns: связь с другим модулем не работает: {}", e.toString());
        }
    }
}
