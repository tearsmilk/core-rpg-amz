package dev.tearsmilk.nameshader;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public final class TestSpawner {

    public static final String TAG = "nameshader_test";

    private final NameShaderPlugin plugin;
    private final List<Entity> alive = new ArrayList<>();

    public TestSpawner(NameShaderPlugin plugin) {
        this.plugin = plugin;
    }

    public int spawn(Player p) {
        Settings s = plugin.settings();
        Texts t = plugin.texts();
        Location eye = p.getLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0e-4) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize();
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        Location base = eye.clone().add(dir.clone().multiply(s.testDistance()));
        float yaw = eye.getYaw() + 180.0f;
        World w = p.getWorld();
        List<Entity> made = new ArrayList<>();
        made.add(stand(w, place(base, side, 0.0, yaw), t, s.testName(), false));
        made.add(stand(w, place(base, side, -1.6, yaw), t, t.raw("test-sneak-name"), true));
        Location dl = place(base, side, 1.6, yaw).add(0, 1.9, 0);
        made.add(w.spawn(dl, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.text(t.parse(s.testDisplayText()));
            d.setBillboard(Display.Billboard.CENTER);
        }));
        alive.addAll(made);
        long ticks = s.testSeconds() * 20L;
        for (Entity e : made) {
            e.getScheduler().runDelayed(plugin, task -> remove(e), null, ticks);
        }
        return s.testSeconds();
    }

    private static Location place(Location base, Vector side, double offset, float yaw) {
        Location l = base.clone().add(side.clone().multiply(offset));
        l.setYaw(yaw);
        l.setPitch(0);
        return l;
    }

    private ArmorStand stand(World w, Location l, Texts t, String name, boolean sneaking) {
        return w.spawn(l, ArmorStand.class, a -> {
            a.setPersistent(false);
            a.addScoreboardTag(TAG);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setBasePlate(false);
            a.setArms(true);
            a.customName(t.parse(name));
            a.setCustomNameVisible(true);
            a.setSneaking(sneaking);
        });
    }

    private void remove(Entity e) {
        alive.remove(e);
        if (e.isValid()) {
            e.remove();
        }
    }

    public int active() {
        alive.removeIf(e -> !e.isValid());
        return alive.size();
    }

    public void removeAll() {
        for (Entity e : new ArrayList<>(alive)) {
            if (e.isValid()) {
                e.remove();
            }
        }
        alive.clear();
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(TAG)) {
                    e.remove();
                }
            }
        }
    }
}
