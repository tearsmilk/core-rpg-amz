package core.amazake.systems.corerpg.progression.command;

import core.amazake.systems.corerpg.profile.PlayerProfile;
import core.amazake.systems.corerpg.progression.ClassCatalog;
import core.amazake.systems.corerpg.progression.ProgressionModule;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /profession [имя]} (алиасы {@code профессия}, {@code prof}): без имени — меню выбора, с именем — выбор по
 * ключу, русскому или английскому названию.
 */
public final class ProfessionCommand extends Command {

    private final ProgressionModule module;

    public ProfessionCommand(final ProgressionModule module) {
        super("profession", "Выбор профессии", "/profession [имя]", List.of("профессия", "prof"));
        this.module = module;
    }

    @Override
    public boolean execute(final @NotNull CommandSender sender, final @NotNull String label, final @NotNull String[] args) {
        if (!(sender instanceof final Player player)) {
            this.module.send(sender, "only-players");
            return true;
        }
        if (!this.module.isLoaded(player)) {
            this.module.send(player, "profile-loading");
            return true;
        }
        final PlayerProfile profile = this.module.profile(player);
        if (!profile.hasClass()) {
            this.module.send(player, "must-choose-class");
            return true;
        }
        if (profile.hasProfession()) {
            this.module.send(player, "profession-already-chosen");
            return true;
        }
        final int required = this.module.professionLevel();
        if (profile.level() < required) {
            this.module.send(player, "level-too-low", Placeholder.unparsed("level", String.valueOf(required)),
                Placeholder.unparsed("current", String.valueOf(profile.level())));
            return true;
        }
        if (args.length == 0) {
            this.module.openProfessionMenu(player, true);
            return true;
        }
        final String input = String.join(" ", args);
        final ClassCatalog.RpgProfession profession = this.module.catalog().findProfession(input);
        if (profession == null || !profession.parent().equals(profile.classId())) {
            this.module.send(player, "invalid-profession", Placeholder.unparsed("input", input),
                Placeholder.unparsed("list", this.available(profile.classId())));
            return true;
        }
        this.module.chooseProfession(player, profession);
        return true;
    }

    private String available(final String classId) {
        final List<String> out = new ArrayList<>();
        for (final ClassCatalog.RpgProfession p : this.module.catalog().professionsOf(classId)) {
            out.add(Completions.token(p.displayName()).replace('_', ' ') + " (" + p.key() + ")");
        }
        return String.join(", ", out);
    }

    @Override
    public @NotNull List<String> tabComplete(final @NotNull CommandSender sender, final @NotNull String alias,
                                             final @NotNull String[] args) {
        if (!(sender instanceof final Player player) || args.length != 1) {
            return List.of();
        }
        final PlayerProfile profile = this.module.profile(player);
        if (!profile.hasClass() || profile.hasProfession()) {
            return List.of();
        }
        final List<String> options = new ArrayList<>();
        for (final ClassCatalog.RpgProfession p : this.module.catalog().professionsOf(profile.classId())) {
            options.add(Completions.token(p.displayName()));
            options.add(p.key());
            for (final String a : p.aliases()) {
                options.add(Completions.token(a));
            }
        }
        return Completions.filter(options, args[0]);
    }
}
