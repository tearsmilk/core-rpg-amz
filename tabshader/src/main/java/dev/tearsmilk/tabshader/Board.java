package dev.tearsmilk.tabshader;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class Board {

    static final int MAX_LINES = 15;

    private final Scoreboard scoreboard;
    private final Objective objective;
    private final List<Component> shown = new ArrayList<>();
    private Component title;

    Board() {
        this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        this.objective = scoreboard.registerNewObjective("tabshader", Criteria.DUMMY, Component.empty());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
    }

    static String entry(int i) {
        return "tsh" + (char) ('a' + i);
    }

    Scoreboard scoreboard() {
        return scoreboard;
    }

    boolean owns(Player p) {
        return p.getScoreboard() == scoreboard;
    }

    int update(Component newTitle, List<Component> lines) {
        int changes = 0;
        if (!Objects.equals(title, newTitle)) {
            objective.displayName(newTitle);
            title = newTitle;
            changes++;
        }
        int n = Math.min(MAX_LINES, lines.size());
        if (n != shown.size()) {
            for (int i = n; i < shown.size(); i++) {
                scoreboard.resetScores(entry(i));
            }
            while (shown.size() > n) {
                shown.removeLast();
            }
            for (int i = 0; i < n; i++) {
                objective.getScore(entry(i)).setScore(n - i);
            }
            while (shown.size() < n) {
                shown.add(null);
            }
            changes++;
        }
        for (int i = 0; i < n; i++) {
            Component c = lines.get(i);
            if (!Objects.equals(shown.get(i), c)) {
                objective.getScore(entry(i)).customName(c);
                shown.set(i, c);
                changes++;
            }
        }
        return changes;
    }

    void show(Player p) {
        if (p.getScoreboard() != scoreboard) {
            p.setScoreboard(scoreboard);
        }
    }

    void hide(Player p) {
        if (p.getScoreboard() == scoreboard) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }
}
