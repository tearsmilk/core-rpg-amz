package core.amazake.systems.corepython.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** amazake.plugin / find_class / java_task / run_java_* / remove_entity: Java-задачи, которые переживают выгрузку. */
public class JavaTasksTest {

    /** Вызовы методов поддельных сущности и игрока (Python берёт их через java_class этого теста). */
    public static final List<String> CALLS = new CopyOnWriteArrayList<>();
    public static volatile Entity entity;
    public static volatile Player player;

    @TempDir
    Path root;
    FakePlatform platform;
    ScriptManager manager;

    @SuppressWarnings("unchecked")
    private static <T> T fake(final Class<T> type, final String name) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            switch (m.getName()) {
                case "hashCode" -> {
                    return System.identityHashCode(p);
                }
                case "equals" -> {
                    return p == a[0];
                }
                case "toString" -> {
                    return name;
                }
                case "isValid", "isOnline" -> {
                    return true;
                }
                default -> {
                    CALLS.add(name + "." + m.getName());
                    return null;
                }
            }
        });
    }

    /** Прокси GraalVM поверх функции Python (как Value.as(Runnable.class)) — должен отвергаться. */
    public static volatile Object lastProxy;

    public static Object proxyOf(final org.graalvm.polyglot.Value fn) {
        lastProxy = fn.as(Runnable.class);
        return lastProxy;
    }

    /** Класс реализации из неэкспортируемого пакета (sun.nio.ch.FileChannelImpl) — метод берётся из FileChannel. */
    @Test
    void nonExportedPackagesAreSkipped() throws Exception {
        final Path f = Files.writeString(this.root.resolve("x.bin"), "abc");
        try (var ch = java.nio.channels.FileChannel.open(f)) {
            assertTrue(!ch.getClass().getModule().isExported(ch.getClass().getPackageName()), ch.getClass().getName());
            final JavaCall c = JavaCall.of(ch, "size", new org.graalvm.polyglot.Value[0], null, null);
            assertTrue(c.toString().startsWith("FileChannel.size"), c.toString());
            c.run();
        }
    }

    @BeforeEach
    void setUp() {
        CALLS.clear();
        entity = fake(Entity.class, "entity");
        player = fake(Player.class, "player");
        this.platform = new FakePlatform();
        this.manager = new ScriptManager(this.platform, this.root, new ScriptManager.Options(false), JavaTasksTest.class.getClassLoader());
    }

    @AfterEach
    void tearDown() {
        this.manager.close();
    }

    @Test
    void javaTasksOutliveTheScript() throws Exception {
        final Path dir = this.root.resolve("scripts").resolve("jt");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.yml"), "name: jt\nversion: 1\nmain: main.py\n");
        Files.writeString(dir.resolve("main.py"), """
            import amazake, java
            試験 = amazake.java_class("core.amazake.systems.corepython.host.JavaTasksTest")
            assert str(amazake.plugin().getName()) == "core-python"
            型 = amazake.find_class("org.bukkit.Material")
            assert str(型.getName()) == "org.bukkit.Material" and 型.isEnum()

            # перегрузки и числа: int → append(int), float → append(double) (и 2.0 тоже), str → append(String)
            sb = java.type("java.lang.StringBuilder")()
            for v in (3, 2.5, 2.0, "x", True):
                amazake.java_task(sb, "append", v).run()
            assert str(sb.toString()) == "32.52.0xtrue", str(sb.toString())
            # float 2.0 в параметр Object → Double, не Integer; int → Integer
            спис = java.type("java.util.ArrayList")()
            for v in (2.0, 2, None):
                amazake.java_task(спис, "add", v).run()
            assert str(спис.toString()) == "[2.0, 2, null]", str(спис.toString())
            # float в int-параметр не подходит
            try:
                amazake.java_task(спис, "get", 1.0)
                assert False, "get(1.0) принят"
            except ValueError:
                pass
            # None в append(String/Object/CharSequence/char[]...) — неоднозначно, как в javac
            try:
                amazake.java_task(sb, "append", None)
                assert False, "append(None) принят"
            except ValueError as e:
                assert "неоднозначно" in str(e), str(e)
            # равные по счёту, но одна специфичнее: append(CharSequence) vs append(Object) для StringBuilder → CharSequence
            sb2 = java.type("java.lang.StringBuilder")()
            amazake.java_task(sb2, "append", java.type("java.lang.StringBuilder")("ok")).run()
            assert str(sb2.toString()) == "ok"

            保存 = {}

            @amazake.on("core.amazake.systems.corepython.host.TestEvent")
            def 受信(event):
                p = event.getPayload()
                if p == "hold":
                    保存["ev"] = event
                elif p == "global":
                    event.mark(str(amazake.run_java_global(amazake.java_task(event, "mark", "全体"))))
                elif p == "stop":
                    event.mark(str(amazake.run_java_on_entity(試験.entity, amazake.java_task(event, "mark", "止"))))
                elif p == "stopG":
                    event.mark(str(amazake.run_java_global(amazake.java_task(event, "mark", "全"), delay=40)))
                elif p == "reject":
                    try:
                        amazake.run_java_global(lambda: None)
                        event.mark("accepted")
                    except TypeError:
                        event.mark("rejected")
                    try:
                        amazake.run_java_global(試験.proxyOf(lambda: None))
                        event.mark("proxy-accepted")
                    except TypeError:
                        event.mark("proxy-rejected")
                    try:
                        amazake.java_task(event, "nope")
                    except ValueError:
                        event.mark("no-method")
                    try:
                        amazake.java_task(event, "mark", [1, 2])
                    except ValueError:
                        event.mark("no-list")
                    try:
                        amazake.remove_entity(試験.player)
                    except ValueError:
                        event.mark("no-player")
                elif p == "boom":
                    event.mark(str(amazake.run_java_global(amazake.java_task(java.type("java.util.ArrayList")(), "get", 5))))

            @amazake.on_unload
            def 片付け():
                ev = 保存["ev"]
                amazake.remove_entity(試験.entity)
                amazake.run_java_on_entity(試験.entity, amazake.java_task(ev, "mark", "後で"), delay=20)
                amazake.run_java_for_player(試験.player, amazake.java_task(試験.player, "resetTitle"))
            """);
        this.manager.load("jt");

        final TestEvent global = new TestEvent("global");
        this.platform.fire(global);
        assertEquals(List.of("全体", "True"), global.marks, "глобальный поток — сразу");

        final TestEvent reject = new TestEvent("reject");
        this.platform.fire(reject);
        assertEquals(List.of("rejected", "proxy-rejected", "no-method", "no-list", "no-player"), reject.marks, this.platform.log.toString());

        assertTrue(lastProxy != null && ScriptApi.pythonBacked(lastProxy), String.valueOf(lastProxy));
        assertTrue(!ScriptApi.pythonBacked(new Thread()));

        final TestEvent boom = new TestEvent("boom");
        this.platform.fire(boom);
        assertEquals(List.of("True"), boom.marks);
        assertTrue(this.platform.log.stream().anyMatch(l -> l.startsWith("WARNING") && l.contains("run_java_global")
            && l.contains("IndexOutOfBounds")), this.platform.log.toString());

        // Сервер останавливается, поток не владеет сущностью: сначала планировщик сущности (не трогаем чужую сущность
        // из своего потока), и только если он задачу не принимает — сразу, здесь.
        this.platform.ownsAll = false;
        this.platform.stopping = true;
        final TestEvent stop = new TestEvent("stop");
        this.platform.fire(stop);
        assertEquals(List.of("True"), stop.marks, "остановка, чужой поток — в планировщик сущности");
        assertEquals(1, this.platform.entityTasks.size());
        this.platform.runEntityTasks();
        assertEquals(List.of("True", "止"), stop.marks);
        this.platform.entityTasks.clear();
        this.platform.refuseTasks = true;
        final TestEvent stop2 = new TestEvent("stop");
        this.platform.fire(stop2);
        assertEquals(List.of("止", "True"), stop2.marks, "планировщик не принял — сразу");
        // Глобальная: в глобальном потоке при остановке delay не ждём; в чужом потоке — планировщик, отказ → сразу.
        this.platform.refuseTasks = false;
        final TestEvent g1 = new TestEvent("stopG");
        this.platform.fire(g1);
        assertEquals(List.of("全", "True"), g1.marks);
        this.platform.globalThread = false;
        this.platform.refuseTasks = true;
        final TestEvent g2 = new TestEvent("stopG");
        this.platform.fire(g2);
        assertEquals(List.of("全", "True"), g2.marks);
        // Не останавливается, планировщик отказал — False и предупреждение в логе.
        this.platform.stopping = false;
        final TestEvent g3 = new TestEvent("stopG");
        this.platform.fire(g3);
        assertEquals(List.of("False"), g3.marks);
        assertTrue(this.platform.log.stream().anyMatch(l -> l.startsWith("WARNING") && l.contains("run_java_global: ")),
            this.platform.log.toString());
        this.platform.refuseTasks = false;
        this.platform.globalThread = true;

        final TestEvent held = new TestEvent("hold");
        this.platform.fire(held);
        final PyScript s = this.manager.get("jt");
        assertTrue(this.manager.unload("jt"));
        assertNull(s.context, "контекст Python закрыт");
        assertEquals(List.of("player.resetTitle"), CALLS, "поток игрока (FakePlatform выполняет сразу)");
        assertEquals(2, this.platform.entityTasks.size(), "чужой поток — задачи в планировщике сущности");
        assertTrue(held.marks.isEmpty());

        this.platform.runEntityTasks();
        assertEquals(List.of("player.resetTitle", "entity.remove"), CALLS);
        assertEquals(List.of("後で"), held.marks);
    }
}
