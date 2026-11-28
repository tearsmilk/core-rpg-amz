package ink.hoka.odyssey.e2e;

import ink.hoka.odyssey.api.OdysseyModule;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

/**
 * ТОЛЬКО ДЛЯ ЖИВОЙ ПРОВЕРКИ gravity (в сборку и на сервер не идёт): обычные ItemEntity, как у модуля items.
 * /hoitemtest spawn dx dy dz vx vy vz — предмет в (позиция игрока + d) со скоростью v (блоков/с);
 * /hoitemtest info — все предметы карты игрока: id, позиция, скорость, без гравитации ли;
 * /hoitemtest clear — убрать созданные.
 */
public final class ItemSpawnerModule extends OdysseyModule {
    private final List<ItemEntity> spawned = new ArrayList<>();

    @Override
    protected void onEnable() {
        Command c = new Command("hoitemtest");
        c.setCondition((s, ctx) -> !(s instanceof Player p) || server().isOp(p));
        var dx = ArgumentType.Double("dx");
        var dy = ArgumentType.Double("dy");
        var dz = ArgumentType.Double("dz");
        var vx = ArgumentType.Double("vx");
        var vy = ArgumentType.Double("vy");
        var vz = ArgumentType.Double("vz");
        c.addSyntax((s, ctx) -> {
            if (!(s instanceof Player p)) {
                return;
            }
            ItemEntity it = new ItemEntity(ItemStack.of(Material.DIAMOND));
            it.setPickupDelay(java.time.Duration.ofSeconds(3600));
            Pos at = p.getPosition().add(ctx.get(dx), ctx.get(dy), ctx.get(dz));
            it.setInstance(p.getInstance(), at).thenRun(() -> it.setVelocity(new Vec(ctx.get(vx), ctx.get(vy), ctx.get(vz))));
            spawned.add(it);
            s.sendMessage(Component.text("itemtest spawned " + it.getEntityId()));
        }, ArgumentType.Literal("spawn"), dx, dy, dz, vx, vy, vz);
        c.addSyntax((s, ctx) -> {
            if (!(s instanceof Player p)) {
                return;
            }
            StringBuilder b = new StringBuilder("itemtest info");
            for (Entity e : p.getInstance().getEntities()) {
                if (e instanceof ItemEntity it) {
                    Pos q = it.getPosition();
                    Vec v = it.getVelocity();
                    b.append(String.format(java.util.Locale.ROOT, " | %d %.3f %.3f %.3f v %.3f %.3f %.3f ng=%s",
                        it.getEntityId(), q.x(), q.y(), q.z(), v.x(), v.y(), v.z(), it.hasNoGravity()));
                }
            }
            s.sendMessage(Component.text(b.toString()));
        }, ArgumentType.Literal("info"));
        c.addSyntax((s, ctx) -> {
            spawned.forEach(Entity::remove);
            spawned.clear();
            s.sendMessage(Component.text("itemtest cleared"));
        }, ArgumentType.Literal("clear"));
        registerCommand(c);
    }

    @Override
    protected void onDisable() {
        spawned.forEach(Entity::remove);
        spawned.clear();
    }
}
