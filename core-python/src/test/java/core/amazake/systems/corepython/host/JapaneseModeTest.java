package core.amazake.systems.corepython.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.persistence.PersistentDataContainerView;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Японский режим (言語: 日本語): переводчик, трассы по исходным строкам, обёртки 包む, псевдонимы, core_potions. */
class JapaneseModeTest {

    private static final String TEST_EVENT = "core.amazake.systems.corepython.host.TestEvent";

    @TempDir
    Path root;
    FakePlatform platform;
    ScriptManager manager;

    @BeforeEach
    void setUp() {
        this.platform = new FakePlatform();
        this.manager = new ScriptManager(this.platform, this.root, new ScriptManager.Options(false), JapaneseModeTest.class.getClassLoader());
    }

    @AfterEach
    void tearDown() {
        this.manager.close();
    }

    private Path script(final String name, final String main) throws Exception {
        final Path dir = this.root.resolve("scripts").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.yml"), "name: " + name + "\nversion: 1.0.0\nmain: main.py\n言語: 日本語\n");
        Files.writeString(dir.resolve("main.py"), main);
        return dir;
    }

    private static String resource(final String path) throws Exception {
        try (InputStream in = JapaneseModeTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Поддельные объекты Bukkit (java.lang.reflect.Proxy)
    // ------------------------------------------------------------------------------------------

    /** Прокси интерфейса: методы из таблицы, остальное — значения по умолчанию (0 / false / null). */
    @SuppressWarnings("unchecked")
    static <T> T fake(final Class<T> type, final String label, final Map<String, Function<Object[], Object>> methods) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            final Function<Object[], Object> f = methods.get(m.getName());
            if (f != null) {
                return f.apply(a == null ? new Object[0] : a);
            }
            return switch (m.getName()) {
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                case "toString" -> label;
                default -> {
                    final Class<?> r = m.getReturnType();
                    if (r == boolean.class) {
                        yield false;
                    } else if (r == int.class || r == long.class || r == short.class || r == byte.class) {
                        yield r == long.class ? 0L : r == short.class ? (short) 0 : r == byte.class ? (byte) 0 : 0;
                    } else if (r == double.class) {
                        yield 0.0;
                    } else if (r == float.class) {
                        yield 0.0f;
                    }
                    yield null;
                }
            };
        });
    }

    /** PDC на карте «ns:key» → строка. */
    static PersistentDataContainer pdc(final Map<String, Object> store) {
        final Map<String, Function<Object[], Object>> m = new HashMap<>();
        m.put("get", a -> store.get(a[0].toString()));
        m.put("set", a -> store.put(a[0].toString(), a[2]));
        m.put("remove", a -> store.remove(a[0].toString()));
        m.put("has", a -> store.containsKey(a[0].toString()));
        return fake(PersistentDataContainer.class, "PDC", m);
    }

    /** Игрок: имя Tester, здоровье в health[0], PDC в data, сообщения и actionbar — в out. */
    static Player player(final double[] health, final Map<String, Object> data, final List<String> out) {
        final PersistentDataContainer c = pdc(data);
        final UUID id = UUID.fromString("00000000-0000-0000-0000-00000000abcd");
        final Map<String, Function<Object[], Object>> m = new HashMap<>();
        m.put("getName", a -> "Tester");
        m.put("getUniqueId", a -> id);
        m.put("getHealth", a -> health[0]);
        m.put("setHealth", a -> {
            health[0] = (Double) a[0];
            return null;
        });
        m.put("getPersistentDataContainer", a -> c);
        m.put("hasPermission", a -> true);
        m.put("isOp", a -> true);
        m.put("sendMessage", a -> {
            out.add("chat:" + (a[0] instanceof Component comp ? PlainTextComponentSerializer.plainText().serialize(comp) : a[0]));
            return null;
        });
        m.put("sendActionBar", a -> {
            out.add("bar:" + PlainTextComponentSerializer.plainText().serialize((Component) a[0]));
            return null;
        });
        m.put("addPotionEffect", a -> {
            out.add("effect:" + a[0]);
            return true;
        });
        return fake(Player.class, "Player(Tester)", m);
    }

    /** Предмет без сервера: тип, пустота и nexo:id в PDC. */
    static final class FakeItem extends ItemStack {
        private final String nexo;
        private final boolean empty;

        FakeItem(final String nexo, final boolean empty) {
            this.nexo = nexo;
            this.empty = empty;
        }

        @Override
        public boolean isEmpty() {
            return this.empty;
        }

        @Override
        public Material getType() {
            return this.empty ? Material.AIR : Material.POTION;
        }

        @Override
        public ItemStack clone() {
            return this;
        }

        @Override
        public PersistentDataContainerView getPersistentDataContainer() {
            final Map<String, Object> m = new HashMap<>();
            if (this.nexo != null) {
                m.put("nexo:id", this.nexo);
            }
            return pdc(m);
        }

        @Override
        public boolean equals(final Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        @Override
        public String toString() {
            return "FakeItem(" + this.nexo + ")";
        }
    }

    // ------------------------------------------------------------------------------------------
    // Переводчик
    // ------------------------------------------------------------------------------------------

    /** Проверки переводчика (src/test/resources/ja/translator_checks.py) — в настоящем GraalPy. */
    @Test
    void translatorChecksPassInGraalPy() throws Exception {
        final Path lib = this.root.resolve("lib");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("amazake_ja.py"), resource("python/amazake_ja.py"));
        final Path checks = lib.resolve("translator_checks.py");
        Files.writeString(checks, resource("ja/translator_checks.py"));
        try (Context c = Context.newBuilder("python").allowAllAccess(true).option("python.PosixModuleBackend", "java")
            .option("python.PythonPath", lib.toString()).arguments("python", new String[]{checks.toString()}).build()) {
            c.eval(org.graalvm.polyglot.Source.newBuilder("python", checks.toFile()).build());
            final Value g = c.getBindings("python");
            final String result = g.getMember("RESULT").asString();
            final String[] parts = result.split("/");
            assertEquals(parts[1], parts[0], "translator checks failed: " + g.getMember("results"));
            assertTrue(Integer.parseInt(parts[1]) >= 18, result);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Японская система целиком
    // ------------------------------------------------------------------------------------------

    @Test
    void japaneseSystemLoadsImportsModulesAndHandlesEvents() throws Exception {
        final Path dir = this.script("nihon", """
            # 日本語モードの試験
            取り込む 甘酒
            取り込む dougu
            から 甘酒 取り込む 送る

            回数 = 0
            記録 = []

            @甘酒.イベント時("%s", 優先度="高")
            関数 受信(イベント):
                大域 回数
                回数 += 1
                文 = f"{イベント.getPayload()}:{回数}:{dougu.二倍(回数)}"
                イベント.mark(文)

            @甘酒.起動時
            関数 起動():
                記録.追加("起動")

            型定義 袋:
                関数 __初期化__(自分):
                    自分.中 = []
                関数 追加(自分, 物):
                    自分.中.追加(物)
                    返す 自分

            関数 試す_袋():
                返す 長さ(袋().追加(1).追加(2).中)
            """.formatted(TEST_EVENT));
        Files.writeString(dir.resolve("dougu.py"), """
            # 道具
            関数 二倍(甲):
                返す 甲 * 2
            """);
        final PyScript s = this.manager.load("nihon");
        assertEquals(PyScript.State.ACTIVE, s.state());
        assertEquals(1, this.platform.listeners.stream().filter(r -> r.type() == TestEvent.class).count());
        assertEquals(org.bukkit.event.EventPriority.HIGH,
            this.platform.listeners.stream().filter(r -> r.type() == TestEvent.class).findFirst().orElseThrow().priority());
        final TestEvent e = new TestEvent("x");
        this.platform.fire(e);
        this.platform.fire(e);
        assertEquals(List.of("x:1:2", "x:2:4"), e.marks);
        final Value g = s.context.getBindings("python");
        assertEquals("['起動']", g.getMember("記録").toString());
        assertEquals(2, g.getMember("試す_袋").execute().asInt());
        // Модуль 甘酒 — тот же, что amazake; японские встроенные — в builtins.
        assertTrue(s.context.eval("python", "import amazake, sys; sys.modules['甘酒'] is amazake and 長さ is len").asBoolean());
    }

    @Test
    void errorInJapaneseFilePointsToOriginalFileAndLine() throws Exception {
        final Path dir = this.script("ayamari", """
            取り込む 甘酒
            取り込む sukeru

            @甘酒.イベント時("%s")
            関数 受信(イベント):
                もし イベント.getPayload() == "boom":
                    投げる 値エラー("broken in japanese")
                もし イベント.getPayload() == "deep":
                    sukeru.壊れる(無し)
                もし イベント.getPayload() == "java":
                    甘酒.呼ぶ(甘酒.ジャバ型("java.lang.Integer"), "parseInt", "abc")
                もし イベント.getPayload() == "glued":
                    返す値
                イベント.mark("ok")
            """.formatted(TEST_EVENT));
        Files.writeString(dir.resolve("sukeru.py"), """
            # 補助

            関数 壊れる(値):
                返す 値.存在しない属性
            """);
        this.manager.load("ayamari");
        this.platform.fire(new TestEvent("boom"));
        final String log = String.join("\n", this.platform.log);
        assertTrue(log.contains("ValueError: broken in japanese"), log);
        assertTrue(log.contains("main.py\", line 7, in 受信"), log);
        this.platform.fire(new TestEvent("deep"));
        final String log2 = String.join("\n", this.platform.log);
        assertTrue(log2.contains("AttributeError"), log2);
        assertTrue(log2.contains("sukeru.py\", line 4, in 壊れる"), log2);
        assertTrue(log2.contains("main.py\", line 9, in 受信"), log2);
        assertFalse(log2.contains("amazake_ja.py"), log2);
        this.platform.fire(new TestEvent("java"));
        final String log3 = String.join("\n", this.platform.log);
        assertTrue(log3.contains("JavaError: java.lang.NumberFormatException"), log3);
        assertTrue(log3.contains("main.py\", line 11, in 受信"), log3);
        // JavaError: кадры и причина исходного исключения Java сохраняются
        assertTrue(log3.contains("(java) java.base/java.lang.NumberFormatException") && log3.contains("причина в Java: java.lang.NumberFormatException"), log3);
        this.platform.fire(new TestEvent("glued"));
        final String log4 = String.join("\n", this.platform.log);
        assertTrue(log4.contains("NameError") && log4.contains("put a space after it"), log4);
        assertTrue(log4.contains("main.py\", line 13, in 受信"), log4);
        final TestEvent ok = new TestEvent("fine");
        this.platform.fire(ok);
        assertEquals(List.of("ok"), ok.marks);
    }

    @Test
    void syntaxErrorShowsOriginalLineAndHint() throws Exception {
        this.script("kuuhaku", """
            取り込む 甘酒

            もし甲 > 0:
                何もしない
            """);
        final Exception ex = assertThrows(IllegalStateException.class, () -> this.manager.load("kuuhaku"));
        final String m = ex.getMessage();
        assertTrue(m.contains("SyntaxError"), m);
        assertTrue(m.contains("put a space after the keyword 'もし' (if)"), m);
        assertTrue(m.contains("main.py\", line 3"), m);
        assertTrue(m.contains("もし甲 > 0:"), m);

        this.script("zenkaku", "取り込む 甘酒\n値 = 長さ（[1]）\n");
        final String m2 = assertThrows(IllegalStateException.class, () -> this.manager.load("zenkaku")).getMessage();
        assertTrue(m2.contains("full-width '（' (U+FF08); use ASCII '('"), m2);
        assertTrue(m2.contains("line 2"), m2);
    }

    @Test
    void manifestLanguageKey() throws Exception {
        final Path dir = this.root.resolve("scripts").resolve("lang");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("main.py"), "x = 1\n");
        Files.writeString(dir.resolve("manifest.yml"), "name: lang\nlanguage: JA\n");
        assertTrue(Manifest.read(dir).japanese());
        Files.writeString(dir.resolve("manifest.yml"), "name: lang\n言語: 日本語\n");
        assertTrue(Manifest.read(dir).japanese());
        Files.writeString(dir.resolve("manifest.yml"), "name: lang\n");
        assertFalse(Manifest.read(dir).japanese());
        Files.writeString(dir.resolve("manifest.yml"), "name: lang\nlanguage: klingon\n");
        assertThrows(IllegalArgumentException.class, () -> Manifest.read(dir));
    }

    // ------------------------------------------------------------------------------------------
    // Обёртки и псевдонимы
    // ------------------------------------------------------------------------------------------

    @Test
    void wrappersAndAliases() throws Exception {
        this.script("tsutsumi", """
            取り込む 甘酒

            関数 検査(生プレイヤー, 生場所, 生イベント, 生アイテム):
                プレイヤー = 甘酒.包む(生プレイヤー)
                表明 甘酒.包む(プレイヤー) である プレイヤー かつ 甘酒.解く(プレイヤー) である 生プレイヤー
                表明 プレイヤー == 生プレイヤー かつ ハッシュ一致(プレイヤー, 生プレイヤー)
                表明 プレイヤー.名前 == "Tester", プレイヤー.名前
                表明 文字列(プレイヤー.識別子).で終わる("abcd")
                プレイヤー.体力 = 7
                表明 プレイヤー.体力 == 7.0 かつ プレイヤー.getHealth() == 7.0
                表明 プレイヤー.権限あり("x") かつ プレイヤー.管理者か
                プレイヤー.送る("<red>やあ <名>!", 名="甲")
                甘酒.送る(プレイヤー, "<量> <amount>", 置換={"amount": "10"}, 量=5)
                甘酒.アクションバー(プレイヤー, "棒")
                プレイヤー.sendMessage(甘酒.装飾文("<b>生の呼び出し</b>"))   # 英語の名前: 生の Java メソッド
                プレイヤー.効果付与("再生能力", 100, 1) もし いいえ でなければ 無し
                試す:
                    プレイヤー.管理者か = いいえ
                    表明 いいえ, "read-only property was written"
                捕まえる 属性エラー として 誤り:
                    表明 "read-only" 於 文字列(誤り), 文字列(誤り)
                データ = 甘酒.永続データ(プレイヤー)
                データ.書く("kagi", {"a": [1, 2]})
                表明 データ.読む("kagi") == {"a": [1, 2]} かつ データ.ある("kagi")
                データ.消す("kagi")
                表明 データ.読む("kagi", 0) == 0

                場所 = 甘酒.包む(生場所)
                表明 (場所.東西, 場所.高さ, 場所.南北) == (1.0, 2.0, 3.0)
                次 = 場所.ずらす(東西=3, 南北=4)
                表明 次.東西 == 4.0 かつ 場所.東西 == 1.0
                表明 場所.距離(次) == 5.0
                写し = 場所.複製()
                写し.高さ = 10
                表明 場所.高さ == 2.0 かつ 写し.高さ == 10.0
                表明 甘酒.呼ぶ(場所, "getBlockY") == 2
                表明 場所.世界.名前 == "world"

                イベント = 甘酒.包む(生イベント)
                表明 イベント.プレイヤー == プレイヤー
                アイテム = イベント.アイテム
                表明 アイテム.ネクソ識別子 == "small_mana_potion" かつ 非 アイテム.空か
                表明 文字列(アイテム.種類) == "POTION"
                試す:
                    アイテム.種類 = "DIAMOND"
                    表明 いいえ, "item type must be read-only (ItemStack.setType is deprecated)"
                捕まえる 属性エラー:
                    何もしない
                一覧 = 甘酒.包む(甘酒.ジャバ型("java.util.ArrayList")())
                表明 非 一覧 かつ 長さ(一覧) == 0 かつ 甘酒.解く(一覧) である 一覧
                一覧.add(1)
                表明 [x 各 x 於 甘酒.包む(一覧)] == [1]
                表明 甘酒.永続データ(アイテム).文字列で読む("nexo:id") == "small_mana_potion"
                表明 イベント.取消済み である いいえ
                イベント.取り消す()
                表明 イベント.取消済み である はい
                イベント.取消済み = いいえ
                表明 生イベント.isCancelled() である いいえ
                表明 非 属性あり(イベント, "ダメージ")         # 消費イベントに getDamage は無い
                イベント.置き換えアイテム = 生アイテム
                表明 生イベント.getReplacement() である 生アイテム

                タスク = 甘酒.大域で実行(ラムダ: 無し, 周期=5)
                表明 タスク.取消済み である いいえ
                タスク.取り消す()
                表明 タスク.取消済み である はい
                表明 甘酒.現在ミリ秒() > 0
                表明 文字列(甘酒.素材("diamond")) == "DIAMOND" かつ 甘酒.素材("no_such_thing") である 無し
                表明 文字列(甘酒.エンティティ種類("minecraft:zombie")) == "ZOMBIE"
                表明 甘酒.記録 である 甘酒.log かつ 甘酒.名前 == "tsutsumi"
                甘酒.記録.情報("情報ログ", 1)
                試す:
                    試す:
                        甘酒.呼ぶ(甘酒.ジャバ型("java.lang.Integer"), "parseInt", "x")
                    捕まえる 例外:
                        表明 いいえ, "例外 must not catch Java errors (as in English mode)"
                捕まえる ジャバ例外 として 誤り:
                    表明 "NumberFormatException" 於 文字列(誤り), 文字列(誤り)
                試す:
                    甘酒.ジャバ型("java.lang.Integer").parseInt("y")      # 生の Java 呼び出し
                捕まえる ジャバ例外:
                    何もしない
                返す "ok"

            関数 ハッシュ一致(甲, 乙):
                返す ハッシュ(甲) == ハッシュ(乙)

            ハッシュ = hash
            """);
        final PyScript s = this.manager.load("tsutsumi");
        final double[] hp = {20.0};
        final Map<String, Object> data = new ConcurrentHashMap<>();
        final List<String> out = new ArrayList<>();
        final Player p = player(hp, data, out);
        final FakeItem item = new FakeItem("small_mana_potion", false);
        final PlayerItemConsumeEvent ev = new PlayerItemConsumeEvent(p, item, EquipmentSlot.HAND);
        final org.bukkit.World world = fake(org.bukkit.World.class, "World", Map.of("getName", a -> "world"));
        final Value r;
        try {
            r = s.context.getBindings("python").getMember("検査").execute(p, new Location(world, 1, 2, 3), ev, item);
        } catch (final org.graalvm.polyglot.PolyglotException e) {
            throw new AssertionError(ErrorLog.format(e, this.root.resolve("scripts/tsutsumi")), e);
        }
        assertEquals("ok", r.asString());
        assertEquals(7.0, hp[0]);
        assertEquals(List.of("chat:やあ 甲!", "chat:5 10", "bar:棒", "chat:生の呼び出し"), out);
        assertTrue(this.platform.log.stream().anyMatch(l -> l.contains("情報ログ 1")), this.platform.log.toString());
        // Скорость обёртки (без проверки — для отчёта): чтение японского свойства.
        final Value bench = s.context.eval("python", """
            def _b(p, n):
                import time, amazake
                w = amazake.包む(p)
                t = time.perf_counter()
                for _ in range(n):
                    w.体力
                t1 = time.perf_counter()
                for _ in range(n):
                    p.getHealth()
                t2 = time.perf_counter()
                return "%.2f us wrapped / %.2f us raw" % ((t1 - t) / n * 1e6, (t2 - t1) / n * 1e6)
            _b
            """);
        System.out.println("[ja] property read: " + bench.execute(p, 20000).asString());
    }

    /** Обычная (английская) система: японские псевдонимы amazake доступны, японских встроенных и перевода нет. */
    @Test
    void englishSystemCanUseJapaneseAliases() throws Exception {
        final Path dir = this.root.resolve("scripts").resolve("eigo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.yml"), "name: eigo\n");
        Files.writeString(dir.resolve("main.py"), """
            import amazake, builtins
            from 甘酒 import 送る
            assert not hasattr(builtins, "長さ")
            もし = 1            # not a keyword in English mode

            @amazake.イベント時("%s")
            def handler(event):
                assert isinstance(event, amazake.包み)
                event.mark("wrapped:" + event.getPayload())

            @amazake.on("%s")
            def raw(event):
                assert not isinstance(event, amazake.包み)
                event.mark("raw")
            """.formatted(TEST_EVENT, TEST_EVENT));
        this.manager.load("eigo");
        final TestEvent e = new TestEvent("p");
        this.platform.fire(e);
        assertEquals(List.of("wrapped:p", "raw"), e.marks);
    }

    // ------------------------------------------------------------------------------------------
    // core_potions: японская версия ведёт себя как прежняя английская
    // ------------------------------------------------------------------------------------------

    private static void copy(final Path from, final Path to) throws Exception {
        try (Stream<Path> s = Files.walk(from)) {
            for (final Path p : s.toList()) {
                final Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else if (!p.toString().contains("__pycache__")) {
                    Files.copy(p, t);
                }
            }
        }
    }

    /** Один и тот же сценарий для любой версии core_potions → протокол того, что увидел сервер. */
    private static List<String> potionsScenario(final Path root, final FakePlatform platform) throws Exception {
        final List<String> trace = new ArrayList<>();
        final ScriptManager m = new ScriptManager(platform, root, new ScriptManager.Options(false), JapaneseModeTest.class.getClassLoader());
        try {
            m.load("core_potions");
            platform.listeners.stream().map(r -> "listen " + r.type().getSimpleName() + " " + r.priority()).sorted().forEach(trace::add);
            final Command c = platform.command("cpotions");
            trace.add("command " + c.getName() + " perm=" + c.getPermission() + " aliases=" + c.getAliases());
            final List<String> chat = new ArrayList<>();
            c.execute(ScriptManagerTest.sender(chat), "cpotions", new String[]{});
            c.execute(ScriptManagerTest.sender(chat), "cpotions", new String[]{"reload"});
            trace.addAll(chat);

            final double[] hp = {20.0};
            final Map<String, Object> data = new ConcurrentHashMap<>();
            final List<String> out = new ArrayList<>();
            final Player p = player(hp, data, out);
            for (final FakeItem item : List.of(new FakeItem("small_mana_potion", false), new FakeItem("medium_stamina_potion", false),
                new FakeItem("not_a_potion", false), new FakeItem(null, false), new FakeItem(null, true))) {
                final PlayerItemConsumeEvent e = new PlayerItemConsumeEvent(p, item, EquipmentSlot.HAND);
                platform.fire(e);
                trace.add("consume " + item + " cancelled=" + e.isCancelled() + " replacement=" + e.getReplacement() + " out=" + out);
                out.clear();
            }
            // Перезаход с истёкшим сроком прибавки: данные о прибавке стираются.
            data.put(new NamespacedKey("py_core_potions", "health_boost_until").toString(), "1000");
            data.put(new NamespacedKey("py_core_potions", "health_boost_hp").toString(), "6.0");
            data.put("other:key", "kept");
            platform.fire(new PlayerJoinEvent(p, (Component) null));
            trace.add("join data=" + new java.util.TreeMap<>(data) + " hp=" + hp[0] + " out=" + out);
            final List<String> errors = platform.log.stream().filter(l -> l.startsWith("SEVERE")).toList();
            trace.add("errors=" + errors);
        } finally {
            m.close();
        }
        return trace;
    }

    @Test
    void corePotionsJapaneseBehavesLikeTheEnglishOriginal() throws Exception {
        final Path src = Path.of(System.getProperty("corepython.scripts")).resolve("core_potions");
        assertTrue(Files.readString(src.resolve("manifest.yml")).contains("言語: 日本語"));

        final Path jaRoot = this.root.resolve("ja");
        copy(src, jaRoot.resolve("scripts/core_potions"));
        final Path enRoot = this.root.resolve("en");
        copy(src, enRoot.resolve("scripts/core_potions"));
        final Path enDir = enRoot.resolve("scripts/core_potions");
        Files.writeString(enDir.resolve("main.py"), resource("ja/core_potions_english_main.py"));
        Files.writeString(enDir.resolve("manifest.yml"), Files.readString(enDir.resolve("manifest.yml")).replaceAll("(?m)^言語:.*\\n", ""));
        assertFalse(Manifest.read(enDir).japanese());

        final List<String> ja = potionsScenario(jaRoot, new FakePlatform());
        final List<String> en = potionsScenario(enRoot, new FakePlatform());
        System.out.println("[core_potions] " + String.join("\n  ", ja));
        assertEquals(en, ja);
        // И сами результаты — те, что ожидаются от системы.
        assertTrue(ja.contains("listen PlayerItemConsumeEvent HIGH"), ja.toString());
        assertTrue(ja.contains("listen PlayerJoinEvent MONITOR"), ja.toString());
        assertTrue(ja.contains("command cpotions perm=corepotions.admin aliases=[ポーション]"), ja.toString());
        assertTrue(ja.stream().anyMatch(l -> l.startsWith("Зелья: small_health_potion, ")), ja.toString());
        assertTrue(ja.contains("potions.yml перечитан: зелий 9"), ja.toString());
        assertTrue(ja.stream().anyMatch(l -> l.startsWith("consume FakeItem(small_mana_potion) cancelled=true")), ja.toString());
        assertTrue(ja.stream().anyMatch(l -> l.startsWith("consume FakeItem(not_a_potion) cancelled=false")), ja.toString());
        assertTrue(ja.contains("join data={other:key=kept} hp=20.0 out=[]"), ja.toString());
        assertTrue(ja.contains("errors=[]"), ja.toString());
    }
}
