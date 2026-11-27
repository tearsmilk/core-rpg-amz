package ink.hoka.odyssey.guns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Характеристики оружия с обвесом (как TACZ складывает модификаторы обвесов, своя реализация): значение = (база +
 * сумма add) × max(0, 1 + сумма percent) × произведение max(0, mul), потом операции op по порядку. Результат —
 * {@link Effective}: то же оружие ({@link GunDef#tuned}) с новыми числами и то, чего в GunDef нет (глушитель, взрыв,
 * поджог, пробитие брони). Без Minestom.
 */
final class AttachmentMath {

    private AttachmentMath() {
    }

    /**
     * Оружие с обвесом.
     *
     * @param gun          оружие как в config.yml
     * @param silenced     стоит глушитель (звук «с глушителем», вспышки у других нет)
     * @param soundAdd     на сколько блоков меняется дальность звука выстрела (глушитель — меньше нуля)
     * @param armorIgnore  доля брони, которую пробивает пуля (брони пока нет — только хранится и показывается)
     * @param explosion    взрыв при попадании (фугасные патроны и у оружия есть раздел explosion), иначе null
     * @param ignite       поджигает цель
     * @param zooms        кратности установленного прицела (пусто — прицела нет или он часть оружия)
     * @param xmagLevel    уровень удлинённого магазина (0 — обычный)
     * @param weight       вес обвеса (кг, как в TACZ; пока только показывается)
     */
    record Effective(GunDef gun, boolean silenced, double soundAdd, double armorIgnore, Attachments.Explosion explosion,
                     boolean ignite, double[] zooms, int xmagLevel, double weight) {

        static Effective plain(GunDef g, Attachments.GunAtt ga) {
            return new Effective(g, false, 0, ga == null ? 0 : ga.armorIgnore(), null, false, new double[0], 0, 0);
        }
    }

    /** Сложенный модификатор одной характеристики. */
    private static final class Acc {
        double add;
        double percent;
        double mul = 1;
        final List<Attachments.Op> ops = new ArrayList<>();
        boolean any;

        double apply(double base) {
            if (!any) {
                return base;
            }
            double v = (base + add) * Math.max(0, 1 + percent) * mul;
            for (Attachments.Op o : ops) {
                v = o.apply(v);
            }
            return v;
        }
    }

    /**
     * Оружие с обвесом lo. ammoModsExtendMag false — патроны (ammo_mod_*) магазин не удлиняют (в TACZ удлиняют: они
     * стоят в слоте магазина с уровнем).
     */
    static Effective apply(GunDef g, Attachments.GunAtt ga, Loadout lo, Attachments cat, boolean ammoModsExtendMag) {
        if (ga == null || lo == null || lo.isEmpty() || cat == null) {
            return Effective.plain(g, ga);
        }
        Map<String, Acc> acc = new LinkedHashMap<>();
        boolean silenced = false;
        boolean silenceSound = false;
        double soundAdd = 0;
        boolean explosive = false;
        boolean ignite = false;
        boolean single = false;
        int xmag = 0;
        double flash = Double.NaN;
        double weight = 0;
        double[] zooms = new double[0];
        for (Map.Entry<Attachments.Slot, Loadout.Entry> e : lo.entries().entrySet()) {
            Attachments.Def d = cat.get(e.getValue().id());
            if (d == null) {
                continue;
            }
            weight += d.weight();
            for (Map.Entry<String, Attachments.Mod> m : d.mods().entrySet()) {
                Acc a = acc.computeIfAbsent(m.getKey(), k -> new Acc());
                Attachments.Mod mod = m.getValue();
                a.any = true;
                a.add += mod.add();
                a.percent += mod.percent();
                a.mul *= Math.max(0, mod.mul());
                if (mod.op() != null) {
                    a.ops.add(mod.op());
                }
            }
            if (d.silence()) {
                silenced = true;
                silenceSound |= d.silenceSound();
                soundAdd += d.silenceDistance();
            }
            explosive |= d.explosion();
            ignite |= d.ignite();
            single |= d.singleProjectile();
            if (d.xmagLevel() > 0 && (ammoModsExtendMag || !d.id().startsWith("ammo_mod"))) {
                xmag = Math.max(xmag, d.xmagLevel());
            }
            if (!Double.isNaN(d.flashHider())) {
                flash = Double.isNaN(flash) ? d.flashHider() : Math.min(flash, d.flashHider());
            }
            if (e.getKey() == Attachments.Slot.SCOPE && d.zoom().length > 0 && !ga.locked(Attachments.Slot.SCOPE)) {
                zooms = d.zoom().clone();
            }
        }
        Acc none = new Acc();
        Acc hip = acc.getOrDefault("inaccuracy", none);
        int magazine = g.magazine();
        int[] xm = ga.extendedMag();
        if (xmag > 0 && xm.length > 0) {
            magazine = xm[Math.min(xm.length, xmag) - 1];
        }
        Acc er = acc.get("effective-range");
        double[] dd = g.damageDistance().clone();
        if (er != null) {
            for (int i = 0; i < dd.length; i++) {
                if (Double.isFinite(dd[i])) {
                    dd[i] = Math.max(0, er.apply(dd[i]));
                }
            }
        }
        Acc dm = acc.getOrDefault("damage", none);
        double[] dv = g.damage().clone();
        for (int i = 0; i < dv.length; i++) {
            dv[i] = Math.max(0, dm.apply(dv[i]));
        }
        double rpm = GunDef.clamp(acc.getOrDefault("rpm", none).apply(g.rpm()), 1, 3000);
        double headshot = Math.max(0, acc.getOrDefault("head-shot", none).apply(g.headshot()));
        double speed = GunDef.clamp(acc.getOrDefault("ammo-speed", none).apply(g.speed()), 1, 5000);
        int pierce = GunDef.clamp((int) Math.round(acc.getOrDefault("pierce", none).apply(g.pierce())), 1, 16);
        double knock = Math.max(0, acc.getOrDefault("knockback", none).apply(g.knockback()));
        double stand = Math.max(0, hip.apply(g.spreadStand()));
        double move = Math.max(0, hip.apply(g.spreadMove()));
        double vac = Math.max(0, hip.apply(g.spreadVacuum()));
        double sneak = Math.max(0, acc.getOrDefault("sneak-inaccuracy", none).apply(g.spreadSneak()));
        double aim = Math.max(0, acc.getOrDefault("aim-inaccuracy", none).apply(g.spreadAim()));
        double ads = GunDef.clamp(acc.getOrDefault("ads", none).apply(g.aimTime()), 0.01, 5);
        double pitchMul = acc.getOrDefault("recoil-pitch", none).apply(1);
        double yawMul = acc.getOrDefault("recoil-yaw", none).apply(1);
        double armor = Math.max(0, acc.getOrDefault("armor-ignore", none).apply(ga.armorIgnore()));
        GunDef.Reload tac = g.tactical();
        GunDef.Reload emp = g.empty();
        if (xmag > 0 && magazine != g.magazine() && ga.xmagTactical() != null && ga.xmagEmpty() != null) {
            tac = ga.xmagTactical();
            emp = ga.xmagEmpty();
        }
        double zoom = g.zoom();
        if (zooms.length > 0) {
            zoom = GunDef.clamp(zooms[Math.max(0, Math.min(zooms.length - 1, lo.zoom))], 1, 10);
        }
        Map<String, String> sounds = g.sounds();
        if (silenced && silenceSound) {
            Map<String, String> s = new LinkedHashMap<>(sounds);
            String sil = sounds.get("silence");
            String sil3 = sounds.get("silence3p");
            if (sil != null && !sil.isEmpty()) {
                s.put("shoot", sil);
            }
            if (sil3 != null && !sil3.isEmpty()) {
                s.put("shoot3p", sil3);
            }
            sounds = Map.copyOf(s);
        }
        double flashScale = Double.isNaN(flash) ? g.flashScale() : g.flashScale() * flash;
        int pellets = single ? 1 : g.pellets();
        GunDef t = g.tuned(magazine, rpm, dd, dv, headshot, speed, pierce, knock, stand, move, sneak, aim, vac,
                scale(g.recoilPitch(), pitchMul), scale(g.recoilYaw(), yawMul), tac, emp, ads, zoom, sounds,
                flashScale, pellets);
        return new Effective(t, silenced, soundAdd, armor, explosive ? ga.explosion() : null, ignite, zooms, xmag,
                weight);
    }

    /** Множитель отдачи (вертикальной/горизонтальной) как он вышел у оружия t относительно g. */
    static double recoilMul(GunDef g, GunDef t, boolean pitch) {
        List<GunDef.RecoilKey> a = pitch ? g.recoilPitch() : g.recoilYaw();
        List<GunDef.RecoilKey> b = pitch ? t.recoilPitch() : t.recoilYaw();
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            double x = Math.max(Math.abs(a.get(i).min()), Math.abs(a.get(i).max()));
            if (x > 1e-9) {
                return Math.max(Math.abs(b.get(i).min()), Math.abs(b.get(i).max())) / x;
            }
        }
        return 1;
    }

    private static List<GunDef.RecoilKey> scale(List<GunDef.RecoilKey> keys, double k) {
        if (Math.abs(k - 1) < 1e-9) {
            return keys;
        }
        List<GunDef.RecoilKey> out = new ArrayList<>(keys.size());
        for (GunDef.RecoilKey r : keys) {
            out.add(new GunDef.RecoilKey(r.time(), r.min() * k, r.max() * k));
        }
        return List.copyOf(out);
    }
}
