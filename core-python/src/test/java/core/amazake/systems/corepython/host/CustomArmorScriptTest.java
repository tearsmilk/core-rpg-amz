package core.amazake.systems.corepython.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Python-система custom_armor из репозитория (scripts/custom_armor) грузится и регистрирует то, что должна. */
class CustomArmorScriptTest {

    @TempDir
    Path root;
    ScriptManager manager;

    @AfterEach
    void tearDown() {
        if (this.manager != null) {
            this.manager.close();
        }
    }

    private static void copy(final Path from, final Path to) throws Exception {
        try (Stream<Path> s = Files.walk(from)) {
            for (final Path p : s.toList()) {
                final Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t);
                }
            }
        }
    }

    @Test
    void loadsAndRegistersEventsAndCommand() throws Exception {
        final Path src = Path.of(System.getProperty("corepython.scripts")).resolve("custom_armor");
        copy(src, this.root.resolve("scripts/custom_armor"));
        final FakePlatform platform = new FakePlatform();
        this.manager = new ScriptManager(platform, this.root, new ScriptManager.Options(false), CustomArmorScriptTest.class.getClassLoader());
        assertEquals(java.util.Map.of("custom_armor", true), this.manager.discover());
        final PyScript s = this.manager.load("custom_armor");
        final Set<String> events = platform.listeners.stream().map(r -> r.type().getSimpleName()).collect(Collectors.toSet());
        assertTrue(events.containsAll(Set.of("PlayerArmorChangeEvent", "PlayerJoinEvent", "PlayerPostRespawnEvent", "PlayerQuitEvent",
            "EntityDamageByEntityEvent")), events.toString());
        assertEquals(2, platform.listeners.stream().filter(r -> r.type().getSimpleName().equals("EntityDamageByEntityEvent")).count());
        final Command c = platform.command("carmor");
        assertNotNull(c);
        assertEquals("customarmor.admin", c.getPermission());
        assertEquals(List.of("防具", "customarmor"), c.getAliases());
        assertEquals(List.of("guren"), c.tabComplete(ScriptManagerTest.sender(new java.util.ArrayList<>()), "carmor", new String[]{"give", "gu"}));
        assertEquals(List.of("chestplate"), c.tabComplete(ScriptManagerTest.sender(new java.util.ArrayList<>()), "carmor", new String[]{"give", "guren", "ch"}));
        // Логика бонусов на чистом Python.
        final var ctx = s.context;
        assertEquals("[('guren', 2), ('guren', 4), ('morikashi', 2)]",
            ctx.eval("python", "import bonuses; str(sorted(bonuses.有効な段階({'guren': 4, 'morikashi': 3, 'tsukikori': 1})))").asString());
        assertEquals("azerus_tsukikori_boots", ctx.eval("python", "import items; items.nexoのID('tsukikori', 'boots')").asString());
        assertEquals(12, ctx.eval("python", "import items; len(items.索引)").asInt());
        assertTrue(platform.log.stream().anyMatch(l -> l.contains("guren (紅蓮)")), platform.log.toString());
    }
}
