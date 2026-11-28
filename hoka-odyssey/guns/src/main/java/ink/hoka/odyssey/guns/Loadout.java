package ink.hoka.odyssey.guns;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minestom.server.item.ItemStack;
import net.minestom.server.tag.Tag;

/**
 * Обвес на конкретном оружии (метки custom_data предмета рядом с hoka_gun / hoka_gun_sn / hoka_gun_ammo):
 * <ul>
 *   <li>{@code hoka_att_scope}, {@code hoka_att_muzzle}, {@code hoka_att_stock}, {@code hoka_att_grip},
 *       {@code hoka_att_laser}, {@code hoka_att_mag} — id обвеса в слоте;</li>
 *   <li>{@code hoka_att_virtual} — слоты через запятую, где стоит встроенный обвес (при снятии предмет не
 *       возвращается);</li>
 *   <li>{@code hoka_att_adapter_scope} — повышающая планка в слоте прицела; {@code hoka_att_zoom} — выбранная
 *       кратность прицела (номер); {@code hoka_laser_<слот>} — свой цвет луча ЛЦУ (RGB);</li>
 *   <li>{@code hoka_att_laser2} — второй ЛЦУ (справа; только у оружия со слотом laser2);</li>
 *   <li>{@code hoka_paint} — окраска (0 / нет — стандарт, 1 — чёрный металл; только у оружия с attachments.paint);</li>
 *   <li>{@code hoka_att_init} — обвес по умолчанию уже поставлен (на новое оружие — один раз).</li>
 * </ul>
 * Неизменяемый: изменения — {@code with…}, запись в предмет — {@link #write}.
 */
final class Loadout {
    static final Tag<String> VIRTUAL = Tag.String("hoka_att_virtual");
    static final Tag<String> ADAPTER = Tag.String("hoka_att_adapter_scope");
    static final Tag<Integer> ZOOM = Tag.Integer("hoka_att_zoom");
    static final Tag<Boolean> INIT = Tag.Boolean("hoka_att_init");
    static final Tag<Integer> PAINT = Tag.Integer("hoka_paint");
    /** Окраски: 0 — стандарт, 1 — чёрный металл. */
    static final String[] PAINTS = {"Стандарт", "Чёрный металл"};
    /** Метка предмета-обвеса: id обвеса; и свой цвет луча на нём. */
    static final Tag<String> ITEM = Tag.String("hoka_att");
    static final Tag<Integer> ITEM_LASER = Tag.Integer("hoka_laser");

    private static final Map<Attachments.Slot, Tag<String>> SLOT_TAGS = new EnumMap<>(Attachments.Slot.class);
    private static final Map<Attachments.Slot, Tag<Integer>> LASER_TAGS = new EnumMap<>(Attachments.Slot.class);

    static {
        for (Attachments.Slot s : Attachments.Slot.values()) {
            SLOT_TAGS.put(s, Tag.String(s.tag));
            LASER_TAGS.put(s, Tag.Integer("hoka_laser_" + s.key));
        }
    }

    /** Обвес в слоте: id, встроенный ли, свой цвет луча (−1 — по умолчанию). */
    record Entry(String id, boolean virtual, int laser) {}

    static final Loadout EMPTY = new Loadout(new EnumMap<>(Attachments.Slot.class), "", 0, false, 0);

    private final EnumMap<Attachments.Slot, Entry> slots;
    final String adapter;
    final int zoom;
    final boolean init;
    /** Окраска (номер в {@link #PAINTS}). */
    final int paint;

    private Loadout(EnumMap<Attachments.Slot, Entry> slots, String adapter, int zoom, boolean init, int paint) {
        this.slots = slots;
        this.adapter = adapter == null ? "" : adapter;
        this.zoom = Math.max(0, zoom);
        this.init = init;
        this.paint = Math.max(0, Math.min(PAINTS.length - 1, paint));
    }

    static String paintName(int paint) {
        return PAINTS[Math.max(0, Math.min(PAINTS.length - 1, paint))];
    }

    Entry get(Attachments.Slot s) {
        return slots.get(s);
    }

    String id(Attachments.Slot s) {
        Entry e = slots.get(s);
        return e == null ? null : e.id();
    }

    boolean isEmpty() {
        return slots.isEmpty();
    }

    Map<Attachments.Slot, Entry> entries() {
        return java.util.Collections.unmodifiableMap(slots);
    }

    Loadout with(Attachments.Slot s, Entry e) {
        EnumMap<Attachments.Slot, Entry> m = new EnumMap<>(Attachments.Slot.class);
        m.putAll(slots);
        if (e == null) {
            m.remove(s);
        } else {
            m.put(s, e);
        }
        return new Loadout(m, adapter, s == Attachments.Slot.SCOPE ? 0 : zoom, init, paint);
    }

    Loadout withAdapter(String a) {
        return new Loadout(new EnumMap<>(slots.isEmpty() ? new EnumMap<>(Attachments.Slot.class) : slots), a, zoom, init, paint);
    }

    Loadout withZoom(int z) {
        return new Loadout(new EnumMap<>(slots.isEmpty() ? new EnumMap<>(Attachments.Slot.class) : slots), adapter, z, init, paint);
    }

    Loadout withInit() {
        return new Loadout(new EnumMap<>(slots.isEmpty() ? new EnumMap<>(Attachments.Slot.class) : slots), adapter, zoom, true, paint);
    }

    Loadout withPaint(int p) {
        return new Loadout(new EnumMap<>(slots.isEmpty() ? new EnumMap<>(Attachments.Slot.class) : slots), adapter, zoom, init, p);
    }

    /** Прочитать с предмета оружия. */
    static Loadout read(ItemStack s) {
        if (s == null || s.isAir()) {
            return EMPTY;
        }
        Set<String> virt = csv(s.getTag(VIRTUAL));
        EnumMap<Attachments.Slot, Entry> m = new EnumMap<>(Attachments.Slot.class);
        for (Attachments.Slot sl : Attachments.Slot.values()) {
            String id = s.getTag(SLOT_TAGS.get(sl));
            if (id != null && !id.isEmpty()) {
                Integer c = s.getTag(LASER_TAGS.get(sl));
                m.put(sl, new Entry(id, virt.contains(sl.key), c == null ? -1 : c & 0xFFFFFF));
            }
        }
        Integer z = s.getTag(ZOOM);
        Integer pt = s.getTag(PAINT);
        return new Loadout(m, s.getTag(ADAPTER), z == null ? 0 : z, Boolean.TRUE.equals(s.getTag(INIT)), pt == null ? 0 : pt);
    }

    /** Записать в предмет оружия (лишние метки убрать). */
    ItemStack write(ItemStack s) {
        List<String> virt = new ArrayList<>();
        for (Attachments.Slot sl : Attachments.Slot.values()) {
            Entry e = slots.get(sl);
            s = s.withTag(SLOT_TAGS.get(sl), e == null ? null : e.id());
            s = s.withTag(LASER_TAGS.get(sl), e == null || e.laser() < 0 ? null : e.laser());
            if (e != null && e.virtual()) {
                virt.add(sl.key);
            }
        }
        return s.withTag(VIRTUAL, virt.isEmpty() ? null : String.join(",", virt))
                .withTag(ADAPTER, adapter.isEmpty() ? null : adapter)
                .withTag(ZOOM, zoom == 0 ? null : zoom)
                .withTag(INIT, init ? Boolean.TRUE : null)
                .withTag(PAINT, paint == 0 ? null : paint);
    }

    /** Ключ для «изменилось ли» (и для рассылки GEAR). */
    String key() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Attachments.Slot, Entry> e : slots.entrySet()) {
            b.append(e.getKey().key).append('=').append(e.getValue().id());
            if (e.getValue().laser() >= 0) {
                b.append(':').append(String.format("%06x", e.getValue().laser()));
            }
            b.append(',');
        }
        b.append("@").append(adapter).append('#').append(zoom).append('~').append(paint);
        return b.toString();
    }

    /** Для GEAR: «слот=id[:rrggbb]» через запятую, у планки — «adapter=id», окраска — «paint=n» (не стандарт). */
    String gearCsv() {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Attachments.Slot, Entry> e : slots.entrySet()) {
            String p = e.getKey().key + "=" + e.getValue().id();
            if (e.getValue().laser() >= 0) {
                p += ":" + String.format("%06x", e.getValue().laser());
            }
            parts.add(p);
        }
        if (!adapter.isEmpty()) {
            parts.add("adapter=" + adapter);
        }
        if (paint != 0) {
            parts.add("paint=" + paint);
        }
        return String.join(",", parts);
    }

    private static Set<String> csv(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s != null) {
            for (String x : s.split(",")) {
                if (!x.isBlank()) {
                    out.add(x.trim());
                }
            }
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Loadout l && l.key().equals(key()) && l.init == init;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key(), init);
    }
}
