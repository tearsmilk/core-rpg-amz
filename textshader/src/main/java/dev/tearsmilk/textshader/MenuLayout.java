package dev.tearsmilk.textshader;

import org.bukkit.event.inventory.ClickType;

import java.util.List;

final class MenuLayout {

    enum Action {
        NICK(Choices.Target.NICK, false),
        CHAT(Choices.Target.CHAT, false),
        COLOR_NICK(Choices.Target.NICK, true),
        COLOR_CHAT(Choices.Target.CHAT, true),
        NONE(null, false);

        private final Choices.Target target;
        private final boolean color;

        Action(Choices.Target target, boolean color) {
            this.target = target;
            this.color = color;
        }

        Choices.Target target() {
            return target;
        }

        boolean color() {
            return color;
        }
    }

    static final int SIZE = 54;
    static final int FIRST_EFFECT = 9;
    static final int PER_PAGE = 36;

    static final int NICK_INFO = 0;
    static final int NICK_COLOR = 1;
    static final int NICK_RESET = 2;
    static final int HEADER = 4;
    static final int CHAT_RESET = 6;
    static final int CHAT_COLOR = 7;
    static final int CHAT_INFO = 8;
    static final int PREV = 45;
    static final int CLOSE = 49;
    static final int NEXT = 53;

    static final int COLOR_INFO = 4;
    static final int COLOR_SAMPLE = 22;
    static final int BACK = 49;
    private static final int[] COLOR_SLOTS = {18, 19, 20, 21, 23, 24, 25, 26, 27, 28, 29, 30, 32, 33, 34, 35};

    private MenuLayout() {
    }

    static List<Effect> effects() {
        return Effect.visible();
    }

    static int pages(int count) {
        return Math.max(1, (count + PER_PAGE - 1) / PER_PAGE);
    }

    static int clampPage(int page, int count) {
        return Math.max(0, Math.min(page, pages(count) - 1));
    }

    static int indexAt(int page, int slot, int count) {
        if (slot < FIRST_EFFECT || slot >= FIRST_EFFECT + PER_PAGE || page < 0) {
            return -1;
        }
        int i = page * PER_PAGE + slot - FIRST_EFFECT;
        return i < count ? i : -1;
    }

    static int pageOf(int index) {
        return index / PER_PAGE;
    }

    static int slotOf(int index) {
        return FIRST_EFFECT + index % PER_PAGE;
    }

    static Effect effectAt(int page, int slot) {
        List<Effect> all = effects();
        int i = indexAt(page, slot, all.size());
        return i < 0 ? null : all.get(i);
    }

    static int colorSlot(int paletteId) {
        return paletteId >= 0 && paletteId < COLOR_SLOTS.length ? COLOR_SLOTS[paletteId] : -1;
    }

    static int colorAt(int slot) {
        for (int i = 0; i < COLOR_SLOTS.length; i++) {
            if (COLOR_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    static Action action(ClickType click) {
        if (click == null) {
            return Action.NONE;
        }
        return switch (click) {
            case LEFT -> Action.NICK;
            case RIGHT -> Action.CHAT;
            case SHIFT_LEFT -> Action.COLOR_NICK;
            case SHIFT_RIGHT -> Action.COLOR_CHAT;
            default -> Action.NONE;
        };
    }

    static boolean button(ClickType click) {
        return click == ClickType.LEFT || click == ClickType.RIGHT || click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
    }
}
