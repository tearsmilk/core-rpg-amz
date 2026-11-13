package core.amazake.systems.corerpg.aspect;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Неизменяемый снимок аспектов игрока. Читается из любого потока, подменяется целиком. */
public final class AspectSnapshot {

    public static final AspectSnapshot EMPTY = new AspectSnapshot(new EnumMap<>(Aspect.class), Map.of());

    private final EnumMap<Aspect, Double> values;
    private final Map<String, Double> targeted;

    private AspectSnapshot(final EnumMap<Aspect, Double> values, final Map<String, Double> targeted) {
        this.values = values;
        this.targeted = targeted;
    }

    public double get(final Aspect aspect) {
        final Double v = this.values.get(aspect);
        return v == null ? 0.0 : v;
    }

    /** Целевой аспект: «resistance:fire», «damage_vs:monster»; 0 если нет. */
    public double targeted(final String key) {
        final Double v = this.targeted.get(key);
        return v == null ? 0.0 : v;
    }

    public Map<String, Double> targeted() {
        return this.targeted;
    }

    public Map<Aspect, Double> values() {
        return java.util.Collections.unmodifiableMap(this.values);
    }

    public Builder toBuilder() {
        final Builder b = new Builder();
        b.values.putAll(this.values);
        b.targeted.putAll(this.targeted);
        return b;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Сборщик: живёт внутри одного расчёта в одном потоке. */
    public static final class Builder {
        private final EnumMap<Aspect, Double> values = new EnumMap<>(Aspect.class);
        private final Map<String, Double> targeted = new HashMap<>();

        public Builder add(final Aspect aspect, final double amount) {
            if (amount != 0.0) {
                this.values.merge(aspect, amount, Double::sum);
            }
            return this;
        }

        /** Ключ конфига: обычный аспект или целевой «resistance:fire». Неизвестные ключи игнорируются. */
        public Builder add(final String key, final double amount) {
            if (amount == 0.0 || key == null) {
                return this;
            }
            if (Aspect.isTargetedKey(key)) {
                this.targeted.merge(key, amount, Double::sum);
                return this;
            }
            final Aspect aspect = Aspect.byKey(key);
            if (aspect != null) {
                this.add(aspect, amount);
            }
            return this;
        }

        public Builder set(final Aspect aspect, final double value) {
            this.values.put(aspect, value);
            return this;
        }

        public double get(final Aspect aspect) {
            final Double v = this.values.get(aspect);
            return v == null ? 0.0 : v;
        }

        public Builder clear(final Aspect aspect) {
            this.values.remove(aspect);
            return this;
        }

        public AspectSnapshot build() {
            return new AspectSnapshot(new EnumMap<>(this.values), Map.copyOf(this.targeted));
        }
    }
}
