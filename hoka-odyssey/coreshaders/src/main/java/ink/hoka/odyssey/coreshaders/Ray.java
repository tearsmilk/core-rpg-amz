package ink.hoka.odyssey.coreshaders;

import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

/** Луч по блокам (обход вокселей): расстояние до первого твёрдого блока, чтобы не выбирать эффект сквозь стену. */
final class Ray {
    interface Blocks {
        boolean solid(int x, int y, int z);
    }

    private Ray() {
    }

    static double firstSolid(Instance instance, double[] o, double[] d, double max) {
        return firstSolid((x, y, z) -> {
            if (!instance.isChunkLoaded(x >> 4, z >> 4)) {
                return false;
            }
            Block b = instance.getBlock(x, y, z, Block.Getter.Condition.TYPE);
            return !b.isAir() && b.isSolid();
        }, o, d, max);
    }

    /** -1 — до max твёрдых блоков нет. Блок, в котором стоит начало луча, не считается. */
    static double firstSolid(Blocks blocks, double[] o, double[] d, double max) {
        int x = (int) Math.floor(o[0]);
        int y = (int) Math.floor(o[1]);
        int z = (int) Math.floor(o[2]);
        int sx = d[0] > 0 ? 1 : d[0] < 0 ? -1 : 0;
        int sy = d[1] > 0 ? 1 : d[1] < 0 ? -1 : 0;
        int sz = d[2] > 0 ? 1 : d[2] < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / d[0]);
        double tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / d[1]);
        double tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / d[2]);
        double tx = sx == 0 ? Double.MAX_VALUE : ((sx > 0 ? x + 1 - o[0] : o[0] - x) * tdx);
        double ty = sy == 0 ? Double.MAX_VALUE : ((sy > 0 ? y + 1 - o[1] : o[1] - y) * tdy);
        double tz = sz == 0 ? Double.MAX_VALUE : ((sz > 0 ? z + 1 - o[2] : o[2] - z) * tdz);
        double t = 0;
        while (t <= max) {
            if (tx <= ty && tx <= tz) {
                t = tx;
                x += sx;
                tx += tdx;
            } else if (ty <= tz) {
                t = ty;
                y += sy;
                ty += tdy;
            } else {
                t = tz;
                z += sz;
                tz += tdz;
            }
            if (t > max) {
                break;
            }
            if (blocks.solid(x, y, z)) {
                return t;
            }
        }
        return -1.0;
    }
}
