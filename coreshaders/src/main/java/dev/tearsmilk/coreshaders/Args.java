package dev.tearsmilk.coreshaders;

public final class Args {
    private Args() {
    }

    public static Double parseRadius(String raw) {
        if (raw == null) {
            return null;
        }
        double r;
        try {
            r = Double.parseDouble(raw.trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
        if (Double.isNaN(r) || Double.isInfinite(r) || r <= 0.0 || r > 512.0) {
            return null;
        }
        return Math.max(0.5, r);
    }
}
