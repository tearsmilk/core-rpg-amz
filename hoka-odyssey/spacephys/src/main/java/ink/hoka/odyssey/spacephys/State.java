package ink.hoka.odyssey.spacephys;

/**
 * Изменяемое состояние точки: позиция (блоки) и скорость (блоки/с). {@link Integrator} меняет его на месте —
 * без аллокаций в тике. Поля открыты намеренно: так проще перекладывать из Minestom/LocalPlayer и обратно.
 */
public final class State {
    public double x, y, z;
    public double vx, vy, vz;

    public State() {}

    public State(double x, double y, double z, double vx, double vy, double vz) {
        set(x, y, z, vx, vy, vz);
    }

    public State(Vec3d position, Vec3d velocity) {
        this(position.x(), position.y(), position.z(), velocity.x(), velocity.y(), velocity.z());
    }

    public State set(double x, double y, double z, double vx, double vy, double vz) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        return this;
    }

    public State set(State o) { return set(o.x, o.y, o.z, o.vx, o.vy, o.vz); }

    public State setPosition(double nx, double ny, double nz) {
        x = nx;
        y = ny;
        z = nz;
        return this;
    }

    public State setVelocity(double nvx, double nvy, double nvz) {
        vx = nvx;
        vy = nvy;
        vz = nvz;
        return this;
    }

    public State copy() { return new State(x, y, z, vx, vy, vz); }

    public Vec3d position() { return new Vec3d(x, y, z); }

    public Vec3d velocity() { return new Vec3d(vx, vy, vz); }

    public double speed() { return Math.sqrt(vx * vx + vy * vy + vz * vz); }

    /** Удельная механическая энергия ½v² + φ, блоки²/с². */
    public double energy(Field field) {
        return 0.5 * (vx * vx + vy * vy + vz * vz) + field.potential(x, y, z);
    }

    /** Удельный момент импульса r × v относительно точки (cx, cy, cz), блоки²/с. */
    public Vec3d angularMomentum(double cx, double cy, double cz) {
        return new Vec3d(x - cx, y - cy, z - cz).cross(velocity());
    }

    @Override
    public String toString() {
        return "State[pos=(" + x + ", " + y + ", " + z + "), vel=(" + vx + ", " + vy + ", " + vz + ")]";
    }
}
