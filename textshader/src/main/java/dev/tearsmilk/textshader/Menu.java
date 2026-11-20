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
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class Menu implements Listener {

    private final TextShaderPlugin plugin;

    public Menu(TextShaderPlugin plugin) {
        this.plugin = plugin;
    }

    static final class View implements InventoryHolder {
        private Inventory inventory;
        private int page;
        private Choices.Target colorTarget;
        private Effect colorEffect;
        private final Map<Choices.Target, Palette> colors = new EnumMap<>(Choices.Target.class);

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        boolean picking() {
            return colorEffect != null && colorTarget != null;
        }
    }

    public void open(Player p) {
        open(p, 0);
    }

    public void open(Player p, int page) {
        View v = new View();
        v.page = MenuLayout.clampPage(page, MenuLayout.effects().size());
        for (Choices.Target t : Choices.Target.values()) {
            FxSpec cur = current(p, t);
            v.colors.put(t, cur != null ? cur.palette() : Palette.WHITE);
        }
        show(p, v);
    }

    private void later(Player p, View v) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() == v) {
                show(p, v);
            }
        });
    }

    private void refresh(Player p, View v) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() == v) {
                fill(p, v);
            }
        });
    }

    private void show(Player p, View v) {
        String title;
        if (v.picking()) {
            title = plugin.msg().raw("menu-color-title").replace("<target>", v.colorTarget.title())
                    .replace("<effect>", v.colorEffect.title());
        } else {
            title = plugin.msg().raw("menu-title").replace("<page>", String.valueOf(v.page + 1))
                    .replace("<pages>", String.valueOf(MenuLayout.pages(MenuLayout.effects().size())));
        }
        Inventory inv = Bukkit.createInventory(v, MenuLayout.SIZE, plugin.msg().mini().deserialize(title));
        v.inventory = inv;
        fill(p, v);
        p.openInventory(inv);
    }

    private FxSpec current(Player p, Choices.Target t) {
        return plugin.choices().get(p.getUniqueId(), t);
    }

    private String sample(Player p, Choices.Target t) {
        return t == Choices.Target.NICK ? p.getName() : plugin.getConfig().getString("menu-sample", "Привет всем!");
    }

    private Component preview(Player p, Choices.Target t, FxSpec spec) {
        return FxText.render(sample(p, t), spec);
    }

    private void fill(Player p, View v) {
        if (v.picking()) {
            fillColors(p, v);
        } else {
            fillEffects(p, v);
        }
    }

    private void fillEffects(Player p, View v) {
        Inventory inv = v.inventory;
        inv.clear();
        FxSpec nick = current(p, Choices.Target.NICK);
        FxSpec chat = current(p, Choices.Target.CHAT);
        Palette nickColor = v.colors.get(Choices.Target.NICK);
        Palette chatColor = v.colors.get(Choices.Target.CHAT);
        List<Effect> all = MenuLayout.effects();
        for (int slot = MenuLayout.FIRST_EFFECT; slot < MenuLayout.FIRST_EFFECT + MenuLayout.PER_PAGE; slot++) {
            Effect e = MenuLayout.effectAt(v.page, slot);
            if (e == null) {
                break;
            }
            boolean allowed = p.hasPermission(e.permission());
            boolean onNick = nick != null && nick.effect() == e;
            boolean onChat = chat != null && chat.effect() == e;
            Component name = allowed ? FxText.render(e.title(), new FxSpec(e, onNick ? nickColor : onChat ? chatColor : Palette.WHITE))
                    : Component.text(e.title(), NamedTextColor.DARK_GRAY);
            List<Component> lore = new ArrayList<>();
            lore.add(line("menu-lore-nick", "nick", preview(p, Choices.Target.NICK, new FxSpec(e, nickColor))));
            lore.add(line("menu-lore-chat", "sample", preview(p, Choices.Target.CHAT, new FxSpec(e, chatColor))));
            if (e.ownColors()) {
                lore.add(line("menu-own-colors"));
            }
            if (onNick || onChat) {
                lore.add(Component.empty());
            }
            if (onNick) {
                lore.add(line("menu-on-nick"));
            }
            if (onChat) {
                lore.add(line("menu-on-chat"));
            }
            lore.add(Component.empty());
            if (allowed) {
                lore.add(line("menu-hint-nick"));
                lore.add(line("menu-hint-chat"));
                lore.add(line("menu-hint-color"));
            } else {
                lore.add(line("menu-locked"));
            }
            lore.add(line("menu-effect-tag", "key", e.key()));
            inv.setItem(slot, item(allowed ? e.icon() : "GRAY_STAINED_GLASS_PANE", name, lore, onNick || onChat));
        }
        int pages = MenuLayout.pages(all.size());
        inv.setItem(MenuLayout.NICK_INFO, info(p, Choices.Target.NICK, nick, "NAME_TAG"));
        inv.setItem(MenuLayout.CHAT_INFO, info(p, Choices.Target.CHAT, chat, "WRITABLE_BOOK"));
        inv.setItem(MenuLayout.NICK_COLOR, colorButton(Choices.Target.NICK, nickColor));
        inv.setItem(MenuLayout.CHAT_COLOR, colorButton(Choices.Target.CHAT, chatColor));
        inv.setItem(MenuLayout.NICK_RESET, item("BARRIER", line("menu-reset-nick"),
                List.of(line(nick != null ? "menu-reset-lore" : "menu-reset-empty")), false));
        inv.setItem(MenuLayout.CHAT_RESET, item("BARRIER", line("menu-reset-chat"),
                List.of(line(chat != null ? "menu-reset-lore" : "menu-reset-empty")), false));
        inv.setItem(MenuLayout.HEADER, item("KNOWLEDGE_BOOK", line("menu-header"), List.of(line("menu-header-1"),
                line("menu-header-2"), line("menu-header-3"), line("menu-header-4"), line("menu-header-5"),
                line("menu-header-6")), false));
        if (v.page > 0) {
            inv.setItem(MenuLayout.PREV, item("ARROW", line("menu-prev"),
                    List.of(line("menu-page", "page", v.page, "pages", pages)), false));
        }
        if (v.page + 1 < pages) {
            inv.setItem(MenuLayout.NEXT, item("ARROW", line("menu-next"),
                    List.of(line("menu-page", "page", v.page + 2, "pages", pages)), false));
        }
        inv.setItem(MenuLayout.CLOSE, item("OAK_DOOR", line("menu-close"), List.of(), false));
        fillEmpty(inv);
    }

    private ItemStack info(Player p, Choices.Target t, FxSpec cur, String icon) {
        Component value = cur != null ? preview(p, t, cur) : line("menu-info-none");
        List<Component> lore = new ArrayList<>();
        if (cur != null) {
            lore.add(line("menu-info-effect", "effect", cur.effect().title(), "colour", cur.palette().title()));
        }
        lore.add(line(t == Choices.Target.NICK ? "menu-info-nick-lore" : "menu-info-chat-lore"));
        return item(icon, line(t == Choices.Target.NICK ? "menu-info-nick" : "menu-info-chat", "value", value), lore, cur != null);
    }

    private ItemStack colorButton(Choices.Target t, Palette color) {
        return item(color.icon(), line(t == Choices.Target.NICK ? "menu-color-nick" : "menu-color-chat", "colour", Component.text(color.title(), TextColor.color(color.rgb()))),
                List.of(line("menu-color-hint")), false);
    }

    private void fillColors(Player p, View v) {
        Inventory inv = v.inventory;
        inv.clear();
        FxSpec cur = current(p, v.colorTarget);
        Palette chosen = v.colors.get(v.colorTarget);
        for (Palette c : Palette.values()) {
            FxSpec spec = new FxSpec(v.colorEffect, c);
            boolean selected = cur != null && cur.equals(spec);
            Component name = Component.text(c.title() + " - ", TextColor.color(c.rgb()))
                    .append(preview(p, v.colorTarget, spec));
            List<Component> lore = new ArrayList<>();
            lore.add(line("menu-color-lore", "target", v.colorTarget.title()));
            if (selected) {
                lore.add(line("menu-selected"));
            }
            inv.setItem(MenuLayout.colorSlot(c.ordinal()), item(c.icon(), name, lore, selected));
        }
        inv.setItem(MenuLayout.COLOR_INFO, item(v.colorEffect.icon(),
                FxText.render(v.colorEffect.title(), new FxSpec(v.colorEffect, chosen)),
                List.of(line("menu-color-for", "target", v.colorTarget.title())), false));
        inv.setItem(MenuLayout.COLOR_SAMPLE, item("PAPER", preview(p, v.colorTarget, new FxSpec(v.colorEffect, chosen)),
                List.of(line("menu-color-sample")), false));
        inv.setItem(MenuLayout.BACK, item("ARROW", line("menu-back"), List.of(), false));
        fillEmpty(inv);
    }

    private static void fillEmpty(Inventory inv) {
        for (int i = 0; i < 9; i++) {
            pane(inv, i);
            pane(inv, MenuLayout.SIZE - 9 + i);
        }
    }

    private static void pane(Inventory inv, int slot) {
        if (inv.getItem(slot) == null) {
            inv.setItem(slot, item("BLACK_STAINED_GLASS_PANE", Component.text(" "), List.of(), false));
        }
    }

    private Component line(String key, Object... pairs) {
        return plugin.msg().get(key, pairs).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static ItemStack item(String material, Component name, List<Component> lore, boolean glint) {
        Material m = Material.matchMaterial(material);
        ItemStack it = new ItemStack(m == null ? Material.PAPER : m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        List<Component> fixed = new ArrayList<>();
        for (Component c : lore) {
            fixed.add(c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        meta.lore(fixed);
        meta.addItemFlags(ItemFlag.values());
        if (glint) {
            meta.setEnchantmentGlintOverride(true);
        }
        it.setItemMeta(meta);
        return it;
    }

    private static void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
    }

    private static void deny(Player p) {
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof View v)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }
        if (!MenuLayout.button(e.getClick())) {
            return;
        }
        if (v.picking()) {
            clickColors(p, v, e.getSlot());
        } else {
            clickEffects(p, v, e.getSlot(), MenuLayout.action(e.getClick()));
        }
    }

    private void clickColors(Player p, View v, int slot) {
        if (slot == MenuLayout.BACK) {
            click(p);
            v.colorEffect = null;
            v.colorTarget = null;
            later(p, v);
            return;
        }
        Palette c = Palette.byId(MenuLayout.colorAt(slot));
        if (c == null) {
            return;
        }
        if (!p.hasPermission(v.colorEffect.permission())) {
            deny(p);
            plugin.msg().send(p, "no-effect-permission", "effect", v.colorEffect.title());
            return;
        }
        plugin.apply(p, v.colorTarget, new FxSpec(v.colorEffect, c), true);
        v.colors.put(v.colorTarget, c);
        click(p);
        v.colorEffect = null;
        v.colorTarget = null;
        later(p, v);
    }

    private void clickEffects(Player p, View v, int slot, MenuLayout.Action action) {
        Effect ef = MenuLayout.effectAt(v.page, slot);
        if (ef != null) {
            if (action == MenuLayout.Action.NONE) {
                return;
            }
            if (!p.hasPermission(ef.permission())) {
                deny(p);
                plugin.msg().send(p, "no-effect-permission", "effect", ef.title());
                return;
            }
            click(p);
            if (action.color()) {
                v.colorTarget = action.target();
                v.colorEffect = ef;
                later(p, v);
                return;
            }
            plugin.apply(p, action.target(), new FxSpec(ef, v.colors.get(action.target())), true);
            refresh(p, v);
            return;
        }
        int pages = MenuLayout.pages(MenuLayout.effects().size());
        switch (slot) {
            case MenuLayout.PREV -> {
                if (v.page > 0) {
                    click(p);
                    v.page--;
                    later(p, v);
                }
            }
            case MenuLayout.NEXT -> {
                if (v.page + 1 < pages) {
                    click(p);
                    v.page++;
                    later(p, v);
                }
            }
            case MenuLayout.NICK_COLOR, MenuLayout.NICK_INFO -> openColors(p, v, Choices.Target.NICK);
            case MenuLayout.CHAT_COLOR, MenuLayout.CHAT_INFO -> openColors(p, v, Choices.Target.CHAT);
            case MenuLayout.NICK_RESET -> reset(p, v, Choices.Target.NICK);
            case MenuLayout.CHAT_RESET -> reset(p, v, Choices.Target.CHAT);
            case MenuLayout.CLOSE -> {
                click(p);
                Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
            }
            default -> {
            }
        }
    }

    private void openColors(Player p, View v, Choices.Target t) {
        FxSpec cur = current(p, t);
        if (cur == null) {
            deny(p);
            plugin.msg().send(p, "menu-pick-effect-first", "target", t.title());
            return;
        }
        click(p);
        v.colorTarget = t;
        v.colorEffect = cur.effect();
        later(p, v);
    }

    private void reset(Player p, View v, Choices.Target t) {
        if (current(p, t) == null) {
            deny(p);
            return;
        }
        plugin.apply(p, t, null, true);
        click(p);
        refresh(p, v);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof View) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClickLock(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof View) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDragLock(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof View) {
            e.setCancelled(true);
        }
    }
}
