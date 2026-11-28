package ink.hoka.odyssey.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarFile;

/**
 * Клиентские модули: список с сервера (подписан Ed25519 — без подписи ничего не запускаем), кэш jar по sha256
 * в {@code .minecraft/hoka_odyssey/cache}, докачка кусками, загрузка (свой ClassLoader на модуль), выгрузка.
 */
final class ModuleManager {

    private ModuleManager() {}

    /** Открытый ключ сервера (Ed25519, X.509). Закрытый — только на сервере (module/clientmods/signing.key). */
    private static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAL8SOl7ZKjC4IikKFoBbTauEDb/Yh20HiOSls8o+Geqo=";

    static final Path ROOT = FabricLoader.getInstance().getGameDir().resolve("hoka_odyssey");
    private static final Path CACHE = ROOT.resolve("cache");                // объекты (jar модулей и ресурсы) по sha256

    record Entry(String id, String version, String sha, int size) {}

    private static final class Loaded {
        final Entry entry;
        final URLClassLoader loader;
        final ClientModule module;
        final Ctx ctx;
        Loaded(Entry e, URLClassLoader l, ClientModule m, Ctx c) { entry = e; loader = l; module = m; ctx = c; }
    }

    private static List<Entry> wanted = List.of();
    private static final Map<String, Loaded> LOADED = new LinkedHashMap<>();
    private static final Map<String, byte[]> PARTIAL = new HashMap<>();

    static String coreVersion() {
        return FabricLoader.getInstance().getModContainer(Net.NS).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    static void hello() {
        ink.hoka.odyssey.client.block.CustomBlocks.connect();
        ink.hoka.odyssey.client.sound.CustomSounds.connect();
        Net.send(1, out -> {
            out.writeInt(Net.PROTOCOL);
            out.writeUTF(coreVersion());
        });
    }

    static void handle(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int op = in.readUnsignedByte();
            switch (op) {
                case 1 -> manifest(in);
                case 2 -> chunk(in);
                case 3 -> message(in);
                case 4 -> AssetSync.manifest(in);
                case 5 -> FileStore.manifest(in);
                case 6 -> ink.hoka.odyssey.client.model.Models.handle(in);
                case 7 -> ink.hoka.odyssey.client.ui.UiScreen.handle(in);
                case 8 -> ink.hoka.odyssey.client.ui.UiHud.handle(in);
                default -> HokaOdysseyClient.LOG.warn("Hoka Odyssey: неизвестная операция {}", op);
            }
        } catch (Exception e) {
            HokaOdysseyClient.LOG.error("Hoka Odyssey: пакет сервера не разобран", e);
        }
    }

    // ───────────────────────── список модулей ─────────────────────────

    private static void manifest(DataInputStream in) throws Exception {
        byte[] body = new byte[in.readInt()];
        in.readFully(body);
        byte[] sig = new byte[in.readInt()];
        in.readFully(sig);
        if (!verify(body, sig)) {
            HokaOdysseyClient.LOG.error("Hoka Odyssey: подпись списка модулей НЕ сходится — ничего не загружаю");
            return;
        }
        JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        List<Entry> list = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("modules");
        if (arr != null) for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            list.add(new Entry(o.get("id").getAsString(), o.get("version").getAsString(), o.get("sha").getAsString(), o.get("size").getAsInt()));
        }
        wanted = list;
        // выгрузить то, чего больше нет или что обновилось
        for (String id : List.copyOf(LOADED.keySet())) {
            Loaded l = LOADED.get(id);
            boolean keep = list.stream().anyMatch(e -> e.id().equals(id) && e.sha().equals(l.entry.sha()));
            if (!keep) unload(id);
        }
        List<String> need = new ArrayList<>();
        for (Entry e : list) if (!cached(e)) need.add(e.sha());
        HokaOdysseyClient.LOG.info("Hoka Odyssey: модулей {}, докачать {}", list.size(), need.size());
        if (!need.isEmpty()) need(need);
        tryLoad();
    }

    /** Подпись нашего сервера (Ed25519). */
    static boolean verify(byte[] body, byte[] sig) throws Exception {
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
        Signature v = Signature.getInstance("Ed25519");
        v.initVerify(key);
        v.update(body);
        return v.verify(sig);
    }

    /** Попросить сервер прислать объекты (jar модулей или файлы ресурсов). */
    static void need(List<String> shas) {
        for (int i = 0; i < shas.size(); i += 2000) {
            List<String> part = shas.subList(i, Math.min(shas.size(), i + 2000));
            Net.send(2, out -> {
                out.writeInt(part.size());
                for (String s : part) out.writeUTF(s);
            });
        }
    }

    static Path object(String sha) { return CACHE.resolve(sha); }

    /** Объект уже в кэше (имя — его sha256; при записи сверяли). */
    static boolean hasObject(String sha) { return Files.isRegularFile(object(sha)); }

    private static boolean cached(Entry e) {
        Path p = object(e.sha());
        try {
            return Files.isRegularFile(p) && Files.size(p) == e.size();
        } catch (IOException ex) {
            return false;
        }
    }

    private static void chunk(DataInputStream in) throws Exception {
        String sha = in.readUTF();
        int total = in.readInt(), off = in.readInt(), len = in.readInt();
        if (total <= 0 || total > (32 << 20) || off < 0 || len < 0 || off + len > total) return;
        byte[] buf = PARTIAL.computeIfAbsent(sha, s -> new byte[total]);
        if (buf.length != total) return;
        in.readFully(buf, off, len);
        if (off + len < total) return;                                          // куски идут по порядку
        PARTIAL.remove(sha);
        if (!sha(buf).equals(sha)) {
            HokaOdysseyClient.LOG.error("Hoka Odyssey: jar {} пришёл битым", sha);
            return;
        }
        Files.createDirectories(CACHE);
        Path tmp = CACHE.resolve(sha + ".part");
        Files.write(tmp, buf);
        Files.move(tmp, object(sha), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        tryLoad();
        AssetSync.tryApply();
        FileStore.tryApply();
    }

    /** Все jar на месте — загрузить те, что ещё не загружены, по порядку списка, и сказать серверу. */
    private static void tryLoad() {
        for (Entry e : wanted) if (!LOADED.containsKey(e.id()) && !cached(e)) return;
        boolean any = false;
        for (Entry e : wanted) {
            if (LOADED.containsKey(e.id())) continue;
            load(e);
            any = true;
        }
        if (!any && !LOADED.isEmpty()) return;
        List<String> ids = List.copyOf(LOADED.keySet());
        Net.send(3, out -> {
            out.writeInt(ids.size());
            for (String id : ids) out.writeUTF(id);
        });
    }

    private static void load(Entry e) {
        Path jar = object(e.sha());
        URLClassLoader cl = null;
        try {
            String main;
            try (JarFile jf = new JarFile(jar.toFile()); InputStream in = jf.getInputStream(jf.getEntry("client-module.json"))) {
                main = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().get("main").getAsString();
            }
            cl = new URLClassLoader("hoka-module-" + e.id(), new URL[]{jar.toUri().toURL()}, HokaOdysseyClient.class.getClassLoader());
            ClientModule m = (ClientModule) Class.forName(main, true, cl).getDeclaredConstructor().newInstance();
            Ctx ctx = new Ctx(e.id());
            LOADED.put(e.id(), new Loaded(e, cl, m, ctx));
            m.enable(ctx);
            HokaOdysseyClient.LOG.info("Hoka Odyssey: модуль {} {} загружен", e.id(), e.version());
        } catch (Throwable ex) {
            HokaOdysseyClient.LOG.error("Hoka Odyssey: модуль {} не загрузился", e.id(), ex);
            LOADED.remove(e.id());
            Hooks.removeOwner(e.id());
            if (cl != null) try { cl.close(); } catch (IOException ignored) {}
        }
    }

    static void unload(String id) {
        Loaded l = LOADED.remove(id);
        if (l == null) return;
        try {
            l.module.disable();
        } catch (Throwable e) {
            HokaOdysseyClient.LOG.error("Hoka Odyssey: модуль {} упал при выгрузке", id, e);
        }
        Hooks.removeOwner(id);
        FileStore.removeOwner(id);
        ink.hoka.odyssey.client.model.Models.removeOwner(id);
        try { l.loader.close(); } catch (IOException ignored) {}
        HokaOdysseyClient.LOG.info("Hoka Odyssey: модуль {} выгружен", id);
    }

    static void unloadAll() {
        ink.hoka.odyssey.client.block.CustomBlocks.clear();
        ink.hoka.odyssey.client.sound.CustomSounds.clear();
        ink.hoka.odyssey.client.ui.UiScreen.clear();
        ink.hoka.odyssey.client.ui.UiHud.clear();
        ink.hoka.odyssey.client.model.Models.clear();
        FileStore.clear();
        for (String id : List.copyOf(LOADED.keySet()).reversed()) unload(id);
        Hooks.clear();
        PARTIAL.clear();
        wanted = List.of();
    }

    static void failed(String id) { unload(id); }

    // ───────────────────────── сообщения ─────────────────────────

    private static void message(DataInputStream in) throws IOException {
        String id = in.readUTF();
        byte[] b = new byte[in.readInt()];
        in.readFully(b);
        Loaded l = LOADED.get(id);
        if (l == null) return;
        for (Consumer<byte[]> h : List.copyOf(l.ctx.handlers)) {
            try {
                h.accept(b);
            } catch (Throwable e) {
                HokaOdysseyClient.LOG.error("Hoka Odyssey: модуль {} упал на сообщении", id, e);
                unload(id);
                return;
            }
        }
    }

    private static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ───────────────────────── контекст модуля ─────────────────────────

    private static final class Ctx implements ClientContext {
        final String id;
        final Logger log;
        final List<Consumer<byte[]>> handlers = new ArrayList<>();

        Ctx(String id) {
            this.id = id;
            this.log = LoggerFactory.getLogger("HokaOdyssey/" + id);
        }

        @Override public String id() { return id; }
        @Override public Logger logger() { return log; }

        @Override public Path dataDir() {
            Path p = ROOT.resolve("data").resolve(id);
            try { Files.createDirectories(p); } catch (IOException ignored) {}
            return p;
        }

        @Override public void send(byte[] data) {
            Net.send(4, out -> {
                out.writeUTF(id);
                out.writeInt(data.length);
                out.write(data);
            });
        }

        @Override public void onMessage(Consumer<byte[]> handler) { handlers.add(handler); }
        @Override public void onTick(Runnable r) { Hooks.TICK.add(new Hooks.Owned<>(id, r)); }
        @Override public void onHud(HudLayer layer) { Hooks.HUD.add(new Hooks.Owned<>(id, layer)); }
        @Override public void onMouse(MouseHook hook) { Hooks.MOUSE.add(new Hooks.Owned<>(id, hook)); }
        @Override public void onCamera(CameraHook hook) { Hooks.CAMERA.add(new Hooks.Owned<>(id, hook)); }
        @Override public java.util.Optional<byte[]> file(String path) { return FileStore.get(path); }
        @Override public void onFile(String path, Consumer<byte[]> listener) { FileStore.listen(id, path, listener); }
        @Override public net.minecraft.resources.Identifier texture(String path) { return FileStore.texture(path); }
        @Override public ink.hoka.odyssey.client.api.ModelHandle model(String path) { return ink.hoka.odyssey.client.model.Models.create(id, path); }

        // ───── мир и рендер ─────
        @Override public void onSky(SkyHook hook) { Hooks.SKY.add(new Hooks.Owned<>(id, hook)); }
        @Override public void onFog(FogHook hook) { Hooks.FOG.add(new Hooks.Owned<>(id, hook)); }
        @Override public void onClouds(java.util.function.BooleanSupplier hide) { Hooks.CLOUDS.add(new Hooks.Owned<>(id, hide)); }
        @Override public void onWeather(java.util.function.BooleanSupplier hide) { Hooks.WEATHER.add(new Hooks.Owned<>(id, hide)); }
        @Override public void onWorldPass(ink.hoka.odyssey.client.api.WorldStage stage, WorldPassHook hook) {
            Hooks.WORLD_PASS.get(stage).add(new Hooks.Owned<>(id, hook));
        }
        @Override public ink.hoka.odyssey.client.api.ModulePipeline pipeline(com.mojang.blaze3d.pipeline.RenderPipeline p,
                                                                         com.mojang.blaze3d.shaders.ShaderSource src) {
            return Pipelines.create(id, p, src);
        }
        @Override public <T extends AutoCloseable> T track(T resource) {
            Hooks.RESOURCES.add(new Hooks.Owned<>(id, resource));
            return resource;
        }
        @Override public void onWorld(Consumer<net.minecraft.resources.Identifier> handler) {
            var o = new Hooks.Owned<>(id, handler);
            Hooks.WORLD.add(o);
            Hooks.worldNow(o);
        }
        @Override public void onAvatarRotations(AvatarRotHook hook) { Hooks.AVATAR_ROT.add(new Hooks.Owned<>(id, hook)); }
        @Override public void onAvatarState(AvatarStateHook hook) { Hooks.AVATAR_STATE.add(new Hooks.Owned<>(id, hook)); }
        @Override public void forceLocalPose(net.minecraft.world.entity.Pose pose) { Hooks.forcePose(id, pose); }
        @Override public void onInput(InputHook hook) { Hooks.INPUT.add(new Hooks.Owned<>(id, hook)); }
        @Override public void onLocalTravel(TravelHook hook) { Hooks.TRAVEL.add(new Hooks.Owned<>(id, hook)); }
    }
}
