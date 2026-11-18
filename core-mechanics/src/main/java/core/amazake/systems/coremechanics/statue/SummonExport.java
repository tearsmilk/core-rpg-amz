package core.amazake.systems.coremechanics.statue;

import core.amazake.systems.coremechanics.doll.Piece;
import core.amazake.systems.coremechanics.doll.PieceTransform;
import core.amazake.systems.coremechanics.doll.Pose;
import java.util.List;
import java.util.Locale;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Ванильная команда /summon куклы в позе (перенесено из RagdollManager.Puppet.summonCommand): корень — пустой
 * block_display в точке root, части — пассажиры (item_display с головами со скином, block_display — цветные блоки,
 * если кубики скина не готовы). Пассажиры стоят в точке корня, поэтому смещение части от корня входит в translation.
 * Работает без системы (командный блок / функция датапака).
 */
public final class SummonExport {

    private SummonExport() {
    }

    public static String command(final Pose pose, final List<Piece> pieces, final double rx, final double ry, final double rz,
                                 final String tag) {
        final StringBuilder sb = new StringBuilder("summon minecraft:block_display ~ ~ ~ {Tags:[\"" + tag + "\"],Passengers:[");
        boolean first = true;
        for (final Piece pc : pieces) {
            final Transformation t = PieceTransform.of(pc, pose.pos()[pc.part()], pose.quat(pc.part()), rx, ry, rz);
            final StringBuilder p = new StringBuilder();
            if (pc.head()) {
                p.append("{id:\"minecraft:item_display\",item:{id:\"minecraft:player_head\",count:1");
                final String tex = pc.texture();
                if (tex != null && !tex.isEmpty()) {
                    p.append(",components:{\"minecraft:profile\":{properties:[{name:\"textures\",value:\"").append(tex).append("\"}]}}");
                }
                p.append("}");
            } else {
                p.append("{id:\"minecraft:block_display\",block_state:{Name:\"").append(pc.block().getKey()).append("\"}");
            }
            p.append(",transformation:{translation:").append(vec(t.getTranslation()))
                .append(",left_rotation:").append(quat(t.getLeftRotation()))
                .append(",scale:").append(vec(t.getScale()))
                .append(",right_rotation:").append(quat(t.getRightRotation())).append("}}");
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(p);
        }
        return sb.append("]}").toString();
    }

    static String f(final float v) {
        final String s = String.format(Locale.ROOT, "%.5f", v).replaceAll("0+$", "");
        return (s.endsWith(".") ? s + "0" : s) + "f";
    }

    private static String vec(final Vector3f v) {
        return "[" + f(v.x) + "," + f(v.y) + "," + f(v.z) + "]";
    }

    private static String quat(final Quaternionf q) {
        return "[" + f(q.x) + "," + f(q.y) + "," + f(q.z) + "," + f(q.w) + "]";
    }
}
