package core.amazake.systems.corepython.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Настоящий GraalPy (интерпретатор) + поддельная платформа: загрузка, события, команды, потоки, выгрузка. */
class ScriptManagerTest {

    @TempDir
    Path root;
    FakePlatform platform;
    ScriptManager manager;

    @BeforeEach
    void setUp() {
        this.platform = new FakePlatform();
        this.manager = new ScriptManager(this.platform, this.root, new ScriptManager.Options(false), ScriptManagerTest.class.getClassLoader());
    }

    @AfterEach
    void tearDown() {
        this.manager.close();
    }

    private void script(final String name, final String manifest, final String main) throws Exception {
        final Path dir = this.root.resolve("scripts").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.yml"), "name: " + name + "\nversion: 1.2.3\nmain: main.py\n" + manifest);
        Files.writeString(dir.resolve("main.py"), main);
    }

    private long testListeners() {
        return this.platform.listeners.stream().filter(r -> r.type() == TestEvent.class).count();
    }

    /** Отправитель команды: собирает сообщения, права — все. */
    static CommandSender sender(final List<String> out) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "sendMessage" -> {
                    if (a[0] instanceof Component c) {
                        out.add(PlainTextComponentSerializer.plainText().serialize(c));
                    } else {
                        out.add(String.valueOf(a[0]));
                    }
                    return null;
                }
                case "hasPermission", "isOp" -> {
                    return true;
                }
                case "getName" -> {
                    return "Tester";
                }
                case "hashCode" -> {
                    return 1;
                }
                case "equals" -> {
                    return p == a[0];
                }
                case "toString" -> {
                    return "Tester";
                }
                default -> {
                    return null;
                }
            }
        });
    }

    @Test
    void loadsListensAndFiresEvents() throws Exception {
        this.script("hello", "", """
            import amazake
            # 日本語の識別子も使える
            回数 = 0

            @amazake.on("core.amazake.systems.corepython.host.TestEvent")
            def 受信(event):
                global 回数
                回数 += 1
                event.mark("получено:" + event.getPayload() + ":" + str(回数))
            """);
        final PyScript s = this.manager.load("hello");
        assertEquals(PyScript.State.ACTIVE, s.state());
        assertEquals(1, this.testListeners());
        final TestEvent e = new TestEvent("x");
        this.platform.fire(e);
        this.platform.fire(e);
        assertEquals(List.of("получено:x:1", "получено:x:2"), e.marks);
        assertTrue(this.manager.engine().getImplementationName() != null);
    }

    @Test
    void commandsWithManifestDefaultsAndTabComplete() throws Exception {
        this.script("cmds", """
            commands:
              greet:
                permission: test.greet
                aliases: [привет, 挨拶]
            """, """
            import amazake

            @amazake.command("greet")
            def 挨拶(sender, args):
                amazake.send(sender, "<gold>Привет, <who>! " + ",".join(args), who=sender.getName())

            @挨拶.tab
            def 補完(sender, args):
                return ["alpha", "beta", "gamma"]
            """);
        this.manager.load("cmds");
        final Command c = this.platform.command("greet");
        assertNotNull(c);
        assertEquals("test.greet", c.getPermission());
        assertEquals(List.of("привет", "挨拶"), c.getAliases());
        final List<String> out = new ArrayList<>();
        c.execute(sender(out), "greet", new String[]{"a", "b"});
        assertEquals(List.of("Привет, Tester! a,b"), out);
        assertEquals(List.of("beta"), c.tabComplete(sender(out), "greet", new String[]{"b"}));
    }

    @Test
    void configStorageAndTasks() throws Exception {
        this.script("cfg", "", """
            import amazake
            設定 = amazake.config("sets.yml")
            assert 設定["sets"]["guren"]["armor"] == 8, 設定
            assert 設定["sets"]["guren"]["bonus"]["2"] == "x", 設定
            assert amazake.config("missing.yml", {"a": 1}) == {"a": 1}
            倉庫 = amazake.storage("state.json")
            倉庫.save({"名前": "テスト", "n": 3})
            assert 倉庫.load() == {"名前": "テスト", "n": 3}
            count = [0]
            def 毎回():
                count[0] += 1
                if count[0] >= 3:
                    return False
            task = amazake.run_global(毎回, period=1)

            @amazake.on("core.amazake.systems.corepython.host.TestEvent")
            def 状態(event):
                event.mark(str(count[0]) + ":" + str(task.isCancelled()))
            """);
        Files.writeString(this.root.resolve("scripts/cfg/sets.yml"), "sets:\n  guren:\n    armor: 8\n    bonus:\n      2: x\n");
        this.manager.load("cfg");
        assertTrue(Files.readString(this.root.resolve("data/cfg/state.json")).contains("テスト"));
        for (int i = 0; i < 5; i++) {
            this.platform.tick();
        }
        final TestEvent e = new TestEvent("");
        this.platform.fire(e);
        assertEquals(List.of("3:True"), e.marks);
        assertTrue(this.platform.repeating.isEmpty());
    }

    @Test
    void manyThreadsEnterOneContextWithoutDeadlock() throws Exception {
        this.script("mt", "", """
            import amazake
            import threading
            n = [0]
            鍵 = threading.Lock()   # GIL переключается между байткодами: составные изменения — под замком

            @amazake.on("core.amazake.systems.corepython.host.TestEvent")
            def 加算(event):
                if event.getPayload() == "count":
                    event.mark(str(n[0]))
                    return
                with 鍵:
                    n[0] += 1
                    event.mark(str(n[0]))
            """);
        this.manager.load("mt");
        final int threads = 8;
        final int each = 200;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch done = new CountDownLatch(threads);
        final List<TestEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        final long t0 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    for (int i = 0; i < each; i++) {
                        final TestEvent e = new TestEvent("");
                        this.platform.fire(e);
                        events.add(e);
                    }
                } catch (final Exception ex) {
                    throw new RuntimeException(ex);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(120, TimeUnit.SECONDS), "deadlock?");
        pool.shutdown();
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[mt] " + threads * each + " вызовов Python из " + threads + " потоков за " + ms + " мс");
        assertEquals(threads * each, events.size());
        final java.util.Set<String> marks = new java.util.HashSet<>();
        events.forEach(e -> marks.addAll(e.marks));
        assertEquals(threads * each, marks.size(), "каждый вызов получил свой номер");
        final TestEvent count = new TestEvent("count");
        this.platform.fire(count);
        assertEquals(List.of(String.valueOf(threads * each)), count.marks);
    }

    @Test
    void unloadRemovesEverythingAndClosesContext() throws Exception {
        this.script("un", "commands:\n  untest:\n    permission: x\n", """
            import amazake
            @amazake.on("core.amazake.systems.corepython.host.TestEvent")
            def 受信(event):
                event.mark("alive")

            @amazake.command("untest")
            def 命令(sender, args):
                pass

            amazake.run_global(lambda: None, period=5)

            @amazake.on_unload
            def 終了():
                amazake.log.info("bye-from-python")
            """);
        final PyScript s = this.manager.load("un");
        final var oldExecutor = this.platform.listeners.stream().filter(r -> r.type() == TestEvent.class).findFirst().orElseThrow();
        assertEquals(1, this.platform.commands.size());
        assertEquals(1, this.platform.repeating.size());
        assertTrue(this.manager.unload("un"));
        assertEquals(PyScript.State.CLOSED, s.state());
        assertEquals(0, this.testListeners());
        assertTrue(this.platform.commands.isEmpty());
        assertTrue(this.platform.repeating.isEmpty());
        assertTrue(this.platform.log.stream().anyMatch(l -> l.contains("bye-from-python")));
        assertNull(s.context);
        // Старый обработчик (если бы где-то остался) больше не зовёт Python и не падает.
        final TestEvent e = new TestEvent("");
        oldExecutor.executor().execute(oldExecutor.listener(), e);
        assertTrue(e.marks.isEmpty());
        assertFalse(this.manager.unload("un"));
    }

    @Test
    void errorsAreLoggedNotThrownAndBadModulesDoNotRegister() throws Exception {
        this.script("bad", "", """
            import amazake
            @amazake.on("core.amazake.systems.corepython.host.TestEvent")
            def 失敗(event):
                raise ValueError("сломалось")
            """);
        this.manager.load("bad");
        this.platform.fire(new TestEvent(""));
        this.platform.fire(new TestEvent(""));
        assertEquals(1, this.platform.log.stream().filter(l -> l.contains("сломалось")).count(), "одинаковые ошибки — раз в 30 с");
        assertTrue(this.platform.log.stream().anyMatch(l -> l.contains("main.py") && l.contains("失敗")), this.platform.log.toString());

        this.script("broken", "", """
            import amazake
            @amazake.on("core.amazake.systems.corepython.host.TestEvent")
            def 受信(event):
                pass
            1 / 0
            """);
        final int before = this.platform.listeners.size();
        final Exception ex = assertThrows(IllegalStateException.class, () -> this.manager.load("broken"));
        assertTrue(ex.getMessage().contains("ZeroDivisionError"), ex.getMessage());
        assertEquals(before, this.platform.listeners.size());
        assertNull(this.manager.get("broken"));
    }

    @Test
    void reloadManyTimes() throws Exception {
        this.script("rl", "", """
            import amazake
            big = list(range(200000))
            @amazake.on("core.amazake.systems.corepython.host.TestEvent")
            def 受信(event):
                event.mark(str(len(big)))
            """);
        this.manager.load("rl");
        final Runtime rt = Runtime.getRuntime();
        long first = 0;
        for (int i = 0; i < 8; i++) {
            final long t0 = System.nanoTime();
            this.manager.reload("rl");
            final long ms = (System.nanoTime() - t0) / 1_000_000;
            System.gc();
            final long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
            if (i == 1) {
                first = used;
            }
            System.out.println("[reload " + i + "] " + ms + " мс, куча " + used + " МБ");
        }
        final TestEvent e = new TestEvent("");
        this.platform.fire(e);
        assertEquals(List.of("200000"), e.marks);
        assertEquals(1, this.testListeners());
        System.gc();
        final long last = (rt.totalMemory() - rt.freeMemory()) >> 20;
        assertTrue(last < first + 64, "куча растёт с перезагрузками: " + first + " → " + last);
    }

    @Test
    void javaInteropAndStaticClass() throws Exception {
        this.script("ji", "", """
            import amazake, java
            Material = amazake.java_class("org.bukkit.Material")
            assert str(Material.DIAMOND_CHESTPLATE.name()) == "DIAMOND_CHESTPLATE"
            Sys = amazake.java_class("java.lang.System")
            assert Sys.currentTimeMillis() > 0
            k = amazake.key("名前".encode("utf-8").hex())
            assert str(k.getNamespace()) == "py_ji"
            assert str(amazake.key("nexo:id")) == "nexo:id"
            """);
        this.manager.load("ji");
    }
}
