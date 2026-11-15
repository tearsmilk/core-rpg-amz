package core.amazake.systems.coreweapons;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreweapons.runtime.ItemTools;
import core.amazake.systems.coreweapons.runtime.WeaponManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** /cweapons give &lt;оружие&gt; [игрок] | list | reload — право coreweapons.admin. */
final class WeaponsCommand extends Command {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final WeaponManager manager;
    private final Runnable reload;

    WeaponsCommand(final SystemContext context, final Supplier<Settings> settings, final WeaponManager manager,
                   final Runnable reload) {
        super("cweapons", "Эффекты оружия core-weapons", "/cweapons give <оружие> [игрок] | list | reload",
            List.of("coreweapons"));
        this.setPermission("coreweapons.admin");
        this.context = context;
        this.settings = settings;
        this.manager = manager;
        this.reload = reload;
    }

    private void send(final CommandSender to, final String key, final TagResolver... r) {
        final Settings s = this.settings.get();
        final String t = s.messages().getOrDefault(key, "");
        if (!t.isBlank()) {
            to.sendMessage(MM.deserialize(s.prefix() + t, r));
        }
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        final String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                this.reload.run();
                this.send(sender, "reloaded");
            }
            case "list" -> this.send(sender, "list", Placeholder.unparsed("list",
                String.join(", ", this.settings.get().weapons().keySet()) + " | игроков " + this.manager.players()
                    + ", копий блоков " + this.manager.displays()));
            case "give" -> this.give(sender, args);
            default -> this.send(sender, "usage");
        }
        return true;
    }

    /** Предмет для проверки без Nexo: материал, item_model и метка nexo:id из test-item. */
    private void give(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            this.send(sender, "usage");
            return;
        }
        final Settings.Weapon w = this.settings.get().weapon(args[1]);
        if (w == null || w.testItem() == null) {
            this.send(sender, "no-weapon", Placeholder.unparsed("weapon", args[1]));
            return;
        }
        final Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                this.send(sender, "no-player", Placeholder.unparsed("player", args[2]));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            this.send(sender, "console");
            return;
        }
        final Material m = Material.matchMaterial(w.testItem().material());
        final ItemStack item = new ItemStack(m == null || !m.isItem() ? Material.NETHERITE_SWORD : m);
        if (!w.testItem().itemModel().isBlank()) {
            item.setData(DataComponentTypes.ITEM_MODEL, Key.key(w.testItem().itemModel()));
        }
        item.setData(DataComponentTypes.ITEM_NAME, MM.deserialize(w.testItem().name()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        item.editPersistentDataContainer(pdc -> pdc.set(ItemTools.NEXO_ID, PersistentDataType.STRING, w.id()));
        this.context.runOnceForPlayer(target, () -> target.getInventory().addItem(item));
        this.send(sender, "given", Placeholder.unparsed("weapon", w.id()), Placeholder.unparsed("player", target.getName()));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        final List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (final String s : List.of("give", "list", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            out.addAll(this.settings.get().weapons().keySet());
        } else if (args.length == 3) {
            for (final Player p : Bukkit.getOnlinePlayers()) {
                out.add(p.getName());
            }
        }
        return out;
    }
}
