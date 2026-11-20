package dev.tearsmilk.textshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class Menu implements Listener {

    static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_TARGET = 46;
    private static final int SLOT_COLOR = 47;
    private static final int SLOT_OFF = 49;
    private static final int SLOT_HELP = 51;
    private static final int SLOT_NEXT = 52;
    private static final int SLOT_CLOSE = 53;
    private static final int SLOT_BACK = 49;

    private final TextShaderPlugin plugin;

    public Menu(TextShaderPlugin plugin) {
        this.plugin = plugin;
    }

    static final class View implements InventoryHolder {
        private Inventory inventory;
        private Choices.Target target = Choices.Target.CHAT;
        private int page;
        private Effect pending;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    static int pages() {
        return (Effect.visible().size() + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    static Effect effectAt(int page, int slot) {
        int i = page * PAGE_SIZE + slot;
        List<Effect> all = Effect.visible();
        return slot >= 0 && slot < PAGE_SIZE && i < all.size() ? all.get(i) : null;
    }

    public void open(Player p, Choices.Target target) {
        View v = new View();
        v.target = target;
        show(p, v);
    }

    private void later(Player p, View v) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                show(p, v);
            }
        });
    }

    private void show(Player p, View v) {
        String title = plugin.msg().raw(v.pending == null ? "menu-title" : "menu-color-title")
                .replace("<target>", v.target.title());
        Inventory inv = Bukkit.createInventory(v, 54, plugin.msg().mini().deserialize(title));
        v.inventory = inv;
        fill(p, v);
        p.openInventory(inv);
    }

    private FxSpec current(Player p, View v) {
        return plugin.choices().get(p.getUniqueId(), v.target);
    }

    private String sample(Player p, View v) {
        return v.target == Choices.Target.NICK ? p.getName() : plugin.getConfig().getString("menu-sample", "Пример текста");
    }

    private void fill(Player p, View v) {
        Inventory inv = v.inventory;
        inv.clear();
        FxSpec cur = current(p, v);
        Palette color = cur != null ? cur.palette() : Palette.WHITE;
        if (v.pending != null) {
            int i = 0;
            for (Palette c : Palette.values()) {
                FxSpec spec = new FxSpec(v.pending, c);
                Component name = Component.text(c.title() + " - ", TextColor.color(c.rgb()))
                        .append(FxText.render(sample(p, v), spec));
                List<Component> lore = new ArrayList<>();
                lore.add(line("menu-color-lore"));
                if (cur != null && cur.equals(spec)) {
                    lore.add(line("menu-selected"));
                }
                inv.setItem(10 + (i / 7) * 9 + (i % 7), item(c.icon(), name, lore, cur != null && cur.equals(spec)));
                i++;
            }
            inv.setItem(SLOT_BACK, item("ARROW", line("menu-back"), List.of(), false));
            return;
        }
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            Effect e = effectAt(v.page, slot);
            if (e == null) {
                break;
            }
            boolean allowed = p.hasPermission(e.permission());
            boolean selected = cur != null && cur.effect() == e;
            Component name = Component.text(e.title() + " - ", NamedTextColor.GRAY)
                    .append(FxText.render(sample(p, v), new FxSpec(e, color)));
            List<Component> lore = new ArrayList<>();
            lore.add(line("menu-effect-tag", "key", e.key()));
            if (e.ownColors()) {
                lore.add(line("menu-own-colors"));
            }
            lore.add(line(allowed ? "menu-effect-lore" : "menu-locked"));
            if (selected) {
                lore.add(line("menu-selected"));
            }
            inv.setItem(slot, item(allowed ? e.icon() : "GRAY_STAINED_GLASS_PANE", name, lore, selected));
        }
        if (v.page > 0) {
            inv.setItem(SLOT_PREV, item("ARROW", line("menu-prev"), List.of(), false));
        }
        if (v.page + 1 < pages()) {
            inv.setItem(SLOT_NEXT, item("ARROW", line("menu-next"), List.of(), false));
        }
        inv.setItem(SLOT_TARGET, item(v.target == Choices.Target.CHAT ? "WRITABLE_BOOK" : "NAME_TAG",
                line("menu-target", "target", v.target.title()), List.of(line("menu-target-lore")), false));
        inv.setItem(SLOT_COLOR, item(color.icon(), line("menu-color", "colour", color.title()),
                List.of(line("menu-color-hint")), false));
        inv.setItem(SLOT_OFF, item("BARRIER", line("menu-off", "target", v.target.title()), List.of(), false));
        inv.setItem(SLOT_HELP, item("BOOK", line("menu-help"), List.of(line("menu-help-1"), line("menu-help-2"),
                line("menu-help-3")), false));
        inv.setItem(SLOT_CLOSE, item("OAK_DOOR", line("menu-close"), List.of(), false));
    }

    private Component line(String key, Object... pairs) {
        return plugin.msg().get(key, pairs).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static ItemStack item(String material, Component name, List<Component> lore, boolean glint) {
        Material m = Material.matchMaterial(material);
        ItemStack it = new ItemStack(m == null ? Material.PAPER : m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        if (glint) {
            meta.setEnchantmentGlintOverride(true);
        }
        it.setItemMeta(meta);
        return it;
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof View v)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }
        int slot = e.getSlot();
        if (v.pending != null) {
            if (slot == SLOT_BACK) {
                v.pending = null;
                later(p, v);
                return;
            }
            int row = slot / 9 - 1;
            int col = slot % 9 - 1;
            if (row < 0 || col < 0 || col >= 7) {
                return;
            }
            Palette c = Palette.byId(row * 7 + col);
            if (c == null) {
                return;
            }
            plugin.apply(p, v.target, new FxSpec(v.pending, c), true);
            click(p);
            v.pending = null;
            later(p, v);
            return;
        }
        Effect ef = effectAt(v.page, slot);
        if (ef != null) {
            if (!p.hasPermission(ef.permission())) {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                plugin.msg().send(p, "no-effect-permission", "effect", ef.title());
                return;
            }
            if (e.isRightClick()) {
                v.pending = ef;
                later(p, v);
                return;
            }
            FxSpec cur = current(p, v);
            plugin.apply(p, v.target, new FxSpec(ef, cur != null ? cur.palette() : Palette.WHITE), true);
            click(p);
            fill(p, v);
            return;
        }
        switch (slot) {
            case SLOT_PREV -> {
                if (v.page > 0) {
                    v.page--;
                    fill(p, v);
                }
            }
            case SLOT_NEXT -> {
                if (v.page + 1 < pages()) {
                    v.page++;
                    fill(p, v);
                }
            }
            case SLOT_TARGET -> {
                v.target = v.target.other();
                later(p, v);
            }
            case SLOT_COLOR -> {
                FxSpec cur = current(p, v);
                if (cur == null) {
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                    plugin.msg().send(p, "menu-pick-effect-first");
                    return;
                }
                v.pending = cur.effect();
                later(p, v);
            }
            case SLOT_OFF -> {
                plugin.apply(p, v.target, null, true);
                click(p);
                fill(p, v);
            }
            case SLOT_CLOSE -> Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof View) {
            e.setCancelled(true);
        }
    }
}
