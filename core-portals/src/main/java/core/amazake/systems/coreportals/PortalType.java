package core.amazake.systems.coreportals;

import java.util.Locale;
import java.util.Optional;

/** Вид портала: свой предмет, своё место, своя анимация. Действие при входе — общее или своё (config.yml). */
public enum PortalType {
    /** «Портал» из смолы: рама 2×3,5 поднимается из земли, внутри — малиновое небо. */
    RESIN("resin"),
    /** «Изумрудный портал»: вертикальное кольцо-воронка с чёрной пустотой и звёздами внутри. */
    EMERALD("emerald");

    private final String id;

    PortalType(final String id) {
        this.id = id;
    }

    /** Имя для команд и config.yml. */
    public String id() {
        return this.id;
    }

    public static Optional<PortalType> parse(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        final String s = raw.strip().toLowerCase(Locale.ROOT);
        for (final PortalType t : values()) {
            if (t.id.equals(s)) {
                return Optional.of(t);
            }
        }
        return switch (s) {
            case "смола", "red", "fire" -> Optional.of(RESIN);
            case "изумруд", "green", "ring" -> Optional.of(EMERALD);
            default -> Optional.empty();
        };
    }
}
