package dev.tearsmilk.coreshaders;

public record EffectData(Shape shape, String image, int color, Dir face, int bx, int by, int bz, boolean rim) {
    public EffectData withShape(Shape s) {
        return new EffectData(s, image, color, face, bx, by, bz, rim);
    }

    public EffectData withImage(String i) {
        return new EffectData(shape, i, color, face, bx, by, bz, rim);
    }

    public EffectData withColor(int c) {
        return new EffectData(shape, image, c, face, bx, by, bz, rim);
    }

    public EffectData withRim(boolean r) {
        return new EffectData(shape, image, color, face, bx, by, bz, r);
    }

    public String modelKey() {
        return shape.id() + "_" + image;
    }
}
