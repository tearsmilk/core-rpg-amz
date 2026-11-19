package dev.tearsmilk.coreshaders;

public record EffectData(Shape shape, String image, int color, Dir face, int bx, int by, int bz) {
    public EffectData withShape(Shape s) {
        return new EffectData(s, image, color, face, bx, by, bz);
    }

    public EffectData withImage(String i) {
        return new EffectData(shape, i, color, face, bx, by, bz);
    }

    public EffectData withColor(int c) {
        return new EffectData(shape, image, c, face, bx, by, bz);
    }

    public String modelKey() {
        return shape.id() + "_" + image;
    }
}
