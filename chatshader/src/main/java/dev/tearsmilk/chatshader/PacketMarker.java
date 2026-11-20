package dev.tearsmilk.chatshader;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

final class PacketMarker {

    static final String HANDLER = "chatshader_mark";
    private static final Key KEY = Key.key("chatshader", "mark");

    private final Logger log;
    private final Runnable onFailure;
    private final AtomicLong marked = new AtomicLong();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final Map<Method, Method> forwards = new ConcurrentHashMap<>();
    private volatile boolean active;
    private volatile String error;
    private boolean installed;
    private int hookedOnline;
    private Class<?> systemChat;
    private Class<?> playerChat;
    private Class<?> disguisedChat;
    private Class<?> bound;
    private Class<?> nmsComponent;
    private Method asAdventure;
    private Method asVanilla;
    private Method holderValue;
    private Method holderDirect;
    private Method styleColor;
    private Method styleShadow;
    private Method styleWithColor;
    private Method styleWithShadow;
    private Method colorValue;
    private Class<?> handlerType;
    private Class<?> contextType;
    private ClassLoader loader;

    PacketMarker(Logger log, Runnable onFailure) {
        this.log = log;
        this.onFailure = onFailure;
    }

    boolean failed() {
        return failed.get();
    }

    boolean installed() {
        return installed;
    }

    String error() {
        return error;
    }

    long marked() {
        return marked.get();
    }

    int hookedOnline() {
        return hookedOnline;
    }

    void active(boolean on) {
        active = on && !failed.get();
    }

    boolean active() {
        return active;
    }

    boolean install() {
        if (installed) {
            return true;
        }
        try {
            loader = Bukkit.getServer().getClass().getClassLoader();
            systemChat = cls("net.minecraft.network.protocol.game.ClientboundSystemChatPacket");
            playerChat = cls("net.minecraft.network.protocol.game.ClientboundPlayerChatPacket");
            disguisedChat = cls("net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket");
            bound = cls("net.minecraft.network.chat.ChatType$Bound");
            nmsComponent = cls("net.minecraft.network.chat.Component");
            Class<?> paper = cls("io.papermc.paper.adventure.PaperAdventure");
            asAdventure = paper.getMethod("asAdventure", nmsComponent);
            asVanilla = paper.getMethod("asVanilla", Component.class);
            Class<?> holder = cls("net.minecraft.core.Holder");
            holderValue = holder.getMethod("value");
            holderDirect = holder.getMethod("direct", Object.class);
            Class<?> style = cls("net.minecraft.network.chat.Style");
            styleColor = style.getMethod("getColor");
            styleShadow = style.getMethod("getShadowColor");
            styleWithColor = style.getMethod("withColor", int.class);
            styleWithShadow = style.getMethod("withShadowColor", int.class);
            colorValue = cls("net.minecraft.network.chat.TextColor").getMethod("getValue");
            selfTest(style);
            handlerType = cls("io.netty.channel.ChannelOutboundHandler");
            contextType = cls("io.netty.channel.ChannelHandlerContext");
            Class<?> listenerType = cls("io.papermc.paper.network.ChannelInitializeListener");
            Class<?> listeners = cls("io.papermc.paper.network.ChannelInitializeListenerHolder");
            Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType}, (proxy, m, args) -> {
                if (m.getName().equals("afterInitChannel")) {
                    attach(args[0]);
                    return null;
                }
                return objectMethod(proxy, m, args);
            });
            if ((boolean) listeners.getMethod("hasListener", Key.class).invoke(null, KEY)) {
                listeners.getMethod("removeListener", Key.class).invoke(null, KEY);
            }
            listeners.getMethod("addListener", Key.class, listenerType).invoke(null, KEY, listener);
            installed = true;
            hookedOnline = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (attach(channelOf(p))) {
                    hookedOnline++;
                }
            }
            error = null;
            return true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.severe("Не удалось подключиться к отправке сообщений чата (" + error + "): позиция чата кроме bottom-left с отступом 0 недоступна.");
            return false;
        }
    }

    void uninstall() {
        if (!installed) {
            return;
        }
        try {
            cls("io.papermc.paper.network.ChannelInitializeListenerHolder").getMethod("removeListener", Key.class).invoke(null, KEY);
        } catch (Throwable ignored) {
            log.fine("removeListener failed");
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                Object channel = channelOf(p);
                Object pipeline = channel.getClass().getMethod("pipeline").invoke(channel);
                Object existing = pipeline.getClass().getMethod("get", String.class).invoke(pipeline, HANDLER);
                if (existing != null) {
                    pipeline.getClass().getMethod("remove", String.class).invoke(pipeline, HANDLER);
                }
            } catch (Throwable ignored) {
                log.fine("pipeline cleanup failed");
            }
        }
        installed = false;
    }

    private void selfTest(Class<?> style) throws ReflectiveOperationException {
        RecordInfo system = RECORDS.get(systemChat);
        system.index("content");
        system.index("overlay");
        RecordInfo player = RECORDS.get(playerChat);
        player.index("unsignedContent");
        player.index("chatType");
        RECORDS.get(playerChat.getRecordComponents()[player.index("body")].getType()).index("content");
        RecordInfo disguised = RECORDS.get(disguisedChat);
        disguised.index("message");
        disguised.index("chatType");
        RecordInfo b = RECORDS.get(bound);
        b.index("name");
        b.index("targetName");
        RECORDS.get(cls("net.minecraft.network.chat.ChatType")).index("chat");
        RECORDS.get(cls("net.minecraft.network.chat.ChatTypeDecoration")).index("style");
        Object packet = system.ctor.newInstance(asVanilla.invoke(null, Component.text("chatshader")), false);
        Object out = system.copy(packet, Map.of("content", this::markVanilla));
        Component back = (Component) asAdventure.invoke(null, system.component(out, "content"));
        if (back.color() == null || !ChatMark.marked(back.color().value())) {
            throw new IllegalStateException("self-test: system chat was not marked");
        }
        Object marked = markStyle(style.getField("EMPTY").get(null));
        Object c = styleColor.invoke(marked);
        if (c == null || !ChatMark.marked((int) colorValue.invoke(c))) {
            throw new IllegalStateException("self-test: chat type style was not marked");
        }
        this.marked.set(0);
    }

    private Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private Object channelOf(Player p) throws ReflectiveOperationException {
        Object handle = p.getClass().getMethod("getHandle").invoke(p);
        Object listener = field(handle, "connection");
        Object connection = field(listener, "connection");
        return field(connection, "channel");
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) {
                continue;
            }
        }
        throw new NoSuchFieldException(name);
    }

    private boolean attach(Object channel) {
        try {
            Object pipeline = channel.getClass().getMethod("pipeline").invoke(channel);
            Method get = pipeline.getClass().getMethod("get", String.class);
            if (get.invoke(pipeline, HANDLER) != null || get.invoke(pipeline, "packet_handler") == null) {
                return false;
            }
            Class<?> handlerBase = cls("io.netty.channel.ChannelHandler");
            Object handler = Proxy.newProxyInstance(loader, new Class<?>[]{handlerType}, new Forward());
            pipeline.getClass().getMethod("addBefore", String.class, String.class, handlerBase).invoke(pipeline, "packet_handler", HANDLER, handler);
            return true;
        } catch (Throwable t) {
            log.warning("Не удалось подключить метку чата к соединению: " + t);
            return false;
        }
    }

    private static Object objectMethod(Object proxy, Method m, Object[] args) {
        return switch (m.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "chatshader-mark";
            default -> null;
        };
    }

    private final class Forward implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            if (m.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, m, args);
            }
            Method target = forwards.get(m);
            if (target == null) {
                target = forwards.computeIfAbsent(m, PacketMarker.this::forwardOf);
            }
            if (target == NO_FORWARD) {
                return null;
            }
            Object ctx = args[0];
            Object[] rest = Arrays.copyOfRange(args, 1, args.length);
            if (m.getName().equals("write") && active) {
                rest[0] = rewrite(rest[0]);
            }
            try {
                return target.invoke(ctx, rest);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private Method forwardOf(Method m) {
        String name = m.getName();
        if (name.equals("handlerAdded") || name.equals("handlerRemoved")) {
            return NO_FORWARD;
        }
        String ctxName = name.equals("exceptionCaught") ? "fireExceptionCaught" : name;
        Class<?>[] declared = m.getParameterTypes();
        try {
            return contextType.getMethod(ctxName, Arrays.copyOfRange(declared, 1, declared.length));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Method NO_FORWARD;

    static {
        try {
            NO_FORWARD = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    Object rewrite(Object msg) {
        try {
            if (systemChat.isInstance(msg)) {
                RecordInfo info = RECORDS.get(msg.getClass());
                if ((boolean) info.component(msg, "overlay")) {
                    return msg;
                }
                return info.copy(msg, Map.of("content", this::markVanilla));
            }
            if (playerChat.isInstance(msg)) {
                RecordInfo info = RECORDS.get(msg.getClass());
                Object unsigned = info.component(msg, "unsignedContent");
                UnaryOperator<Object> content;
                if (unsigned == null) {
                    Object body = info.component(msg, "body");
                    String text = (String) RECORDS.get(body.getClass()).component(body, "content");
                    Object literal = asVanilla.invoke(null, ChatMark.apply(Component.text(text)));
                    marked.incrementAndGet();
                    content = v -> literal;
                } else {
                    content = this::markVanilla;
                }
                return info.copy(msg, Map.of("unsignedContent", content, "chatType", this::markBound));
            }
            if (disguisedChat.isInstance(msg)) {
                return RECORDS.get(msg.getClass()).copy(msg, Map.of("message", this::markVanilla, "chatType", this::markBound));
            }
        } catch (Throwable t) {
            fail("rewrite: " + t);
        }
        return msg;
    }

    private void fail(String why) {
        if (failed.compareAndSet(false, true)) {
            error = why;
            active = false;
            log.severe("Метка чата сломалась (" + why + "): чат возвращается вниз влево без отступа.");
            onFailure.run();
        }
    }

    private Object markVanilla(Object component) {
        if (component == null) {
            return null;
        }
        try {
            Component adv = (Component) asAdventure.invoke(null, component);
            marked.incrementAndGet();
            return asVanilla.invoke(null, ChatMark.apply(adv));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object markBound(Object b) {
        return RECORDS.get(b.getClass()).copy(b, Map.of(
                "name", this::markVanilla,
                "targetName", v -> ((Optional<?>) v).map(this::markVanilla),
                "chatType", this::markType));
    }

    private Object markType(Object holder) {
        try {
            Object type = holderValue.invoke(holder);
            Object marked = RECORDS.get(type.getClass()).copy(type, Map.of("chat",
                    deco -> RECORDS.get(deco.getClass()).copy(deco, Map.of("style", this::markStyle))));
            return holderDirect.invoke(null, marked);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object markStyle(Object style) {
        try {
            Object color = styleColor.invoke(style);
            Integer shadow = (Integer) styleShadow.invoke(style);
            int[] p = ChatMark.pair(color == null ? null : (Integer) colorValue.invoke(color), shadow);
            return styleWithShadow.invoke(styleWithColor.invoke(style, p[0]), p[1]);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class RecordInfo {

        final Class<?> type;
        final Method[] accessors;
        final String[] names;
        final Constructor<?> ctor;

        RecordInfo(Class<?> type) {
            if (!type.isRecord()) {
                throw new IllegalStateException(type.getName() + " is not a record");
            }
            this.type = type;
            RecordComponent[] parts = type.getRecordComponents();
            accessors = new Method[parts.length];
            names = new String[parts.length];
            Class<?>[] types = new Class<?>[parts.length];
            for (int i = 0; i < parts.length; i++) {
                accessors[i] = parts[i].getAccessor();
                accessors[i].setAccessible(true);
                names[i] = parts[i].getName();
                types[i] = parts[i].getType();
            }
            try {
                ctor = type.getDeclaredConstructor(types);
                ctor.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }

        int index(String name) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(name)) {
                    return i;
                }
            }
            throw new IllegalStateException("no record component " + type.getName() + "." + name);
        }

        Object component(Object record, String name) {
            try {
                return accessors[index(name)].invoke(record);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        Object copy(Object record, Map<String, UnaryOperator<Object>> changes) {
            try {
                Object[] values = new Object[accessors.length];
                for (int i = 0; i < accessors.length; i++) {
                    values[i] = accessors[i].invoke(record);
                }
                for (Map.Entry<String, UnaryOperator<Object>> e : changes.entrySet()) {
                    int i = index(e.getKey());
                    values[i] = e.getValue().apply(values[i]);
                }
                return ctor.newInstance(values);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static final ClassValue<RecordInfo> RECORDS = new ClassValue<>() {
        @Override
        protected RecordInfo computeValue(Class<?> type) {
            return new RecordInfo(type);
        }
    };

    static Object copy(Object record, String component, UnaryOperator<Object> change) {
        return RECORDS.get(record.getClass()).copy(record, Map.of(component, change));
    }
}
