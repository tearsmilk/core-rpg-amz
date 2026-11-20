package dev.tearsmilk.textshader;

public record FxSpec(Effect effect, Palette palette) {

    public FxSpec {
        if (effect == null) {
            throw new IllegalArgumentException("effect");
        }
        if (palette == null) {
            palette = Palette.WHITE;
        }
    }

    public static FxSpec of(Effect effect) {
        return new FxSpec(effect, Palette.WHITE);
    }

    public static FxSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(":", 2);
        Effect e = Effect.byKey(parts[0]);
        if (e == null) {
            return null;
        }
        if (parts.length == 1 || parts[1].isEmpty()) {
            return of(e);
        }
        Palette p = Palette.byKey(parts[1]);
        return p == null ? null : new FxSpec(e, p);
    }

    public FxSpec withPalette(Palette p) {
        return new FxSpec(effect, p);
    }

    public int code() {
        return FxCode.encode(effect, palette);
    }

    public int code(int letter) {
        return FxCode.encode(effect, palette, letter);
    }

    public String serialize() {
        return effect.key() + ":" + palette.key();
    }
}
