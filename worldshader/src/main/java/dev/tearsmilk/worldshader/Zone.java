package dev.tearsmilk.worldshader;

import java.util.UUID;

public record Zone(UUID id, String world, double x, double y, double z, int slot, int radius, int strength, long created,
                   long expires) {

    public boolean expired(long now) {
        return expires > 0 && now >= expires;
    }

    public long secondsLeft(long now) {
        return expires <= 0 ? -1 : Math.max(0, (expires - now + 999) / 1000);
    }

    public int rgb() {
        return ZoneCode.rgb(slot, radius, strength);
    }

    public Zone withSlot(int s) {
        return new Zone(id, world, x, y, z, s, radius, strength, created, expires);
    }

    public Zone at(String w, double nx, double ny, double nz) {
        return new Zone(id, w, nx, ny, nz, slot, radius, strength, created, expires);
    }

    public double distanceSquared(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
