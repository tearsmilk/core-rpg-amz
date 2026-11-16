package core.amazake.systems.corepython.host;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProbeScratchTest {
    @TempDir
    Path root;

    @Test
    void probe() throws Exception {
        final FakePlatform platform = new FakePlatform();
        final ScriptManager m = new ScriptManager(platform, this.root, new ScriptManager.Options(false), ProbeScratchTest.class.getClassLoader());
        final Path dir = this.root.resolve("scripts/probe");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.yml"), "name: probe\nversion: 1\nmain: main.py\n");
        final String repo = Path.of("scripts").toAbsolutePath().toString();
        Files.writeString(dir.resolve("main.py"), """
            import sys, amazake, java
            sys.path.insert(0, "%1$s/core_weapons")
            import dougu
            assert dougu._新API
            assert str(dougu.プラグイン().getName()) == "core-python"
            sb = java.type("java.lang.StringBuilder")()
            class E:
                pass
            ent = amazake.java_class("core.amazake.systems.corepython.host.JavaTasksTest")
            print("weapons ok")
            sys.path.insert(0, "%1$s/core_effects")
            try:
                import common
            except BaseException as e:
                print("common import failed:", repr(e))
            else:
                assert common._新API
                assert str(common.プラグイン().getName()) == "core-python"
                assert str(common._クラス("org.bukkit.entity.Entity").getName()) == "org.bukkit.entity.Entity"
                r = common.ジャバ処理("java.lang.StringBuilder", "append", ["java.lang.String"], sb, "あ")
                assert common.ジャバで全体へ(r)
                assert str(sb.toString()) == "あ"
                print("effects ok")
            """.formatted(repo));
        m.load("probe");
        System.out.println(platform.log);
        m.close();
    }
}
