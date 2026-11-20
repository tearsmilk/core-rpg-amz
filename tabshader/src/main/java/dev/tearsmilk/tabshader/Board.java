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
        for (int i = 0; i < n; i++) {
            Component c = lines.get(i);
            if (i < shown.size() && Objects.equals(shown.get(i), c)) {
                continue;
            }
            var score = objective.getScore(entry(i));
            if (i >= shown.size()) {
                score.setScore(n - i);
                shown.add(c);
            } else {
                shown.set(i, c);
            }
            score.customName(c);
            changes++;
        }
        if (shown.size() != n) {
            for (int i = shown.size() - 1; i >= n; i--) {
                scoreboard.resetScores(entry(i));
                shown.remove(i);
                changes++;
            }
        }
        for (int i = 0; i < n; i++) {
            var score = objective.getScore(entry(i));
            if (score.getScore() != n - i) {
                score.setScore(n - i);
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
