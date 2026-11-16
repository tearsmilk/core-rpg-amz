package core.amazake.systems.corepython.host;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Value;

/**
 * Готовый вызов публичного метода Java ({@code target.method(args)}) как {@link Runnable} без Python: метод выбирается
 * и аргументы переводятся в объекты Java сразу, при создании. Поэтому задача выполняется и после закрытия контекста
 * Python (выгрузка системы, остановка сервера). Создаётся через {@code amazake.java_task(...)}.
 */
final class JavaCall implements Runnable {

    private static final int NO = Integer.MAX_VALUE;

    private final Object target;
    private final Method method;
    private final Object[] args;

    private JavaCall(final Object target, final Method method, final Object[] args) {
        this.target = target;
        this.method = method;
        this.args = args;
    }

    /**
     * @param target объект Java
     * @param name   имя публичного метода
     * @param args   аргументы: объекты Java, строки, числа, bool, None
     * @param floats floats[i] — args[i] это float Python (null — нет таких); 2.0 не отличить от 2 по значению
     * @param type   публичный класс/интерфейс, в котором искать метод (null — по иерархии класса объекта)
     */
    static JavaCall of(final Object target, final String name, final Value[] args, final boolean[] floats, final Class<?> type) {
        if (target == null) {
            throw new IllegalArgumentException("java_task: объект = None");
        }
        if (type != null && !type.isInstance(target)) {
            throw new IllegalArgumentException("java_task: " + target.getClass().getName() + " — не " + type.getName());
        }
        final List<Method> best = new ArrayList<>();
        final List<Object[]> bestArgs = new ArrayList<>();
        int bestScore = NO;
        for (final Method m : candidates(type != null ? type : target.getClass(), name, args.length)) {
            final Class<?>[] params = m.getParameterTypes();
            final Object[] converted = new Object[args.length];
            int score = 0;
            for (int i = 0; i < args.length && score != NO; i++) {
                final boolean fl = floats != null && i < floats.length && floats[i];
                final int s = score(args[i], fl, params[i]);
                if (s == NO) {
                    score = NO;
                } else {
                    score += s;
                    converted[i] = convert(args[i], fl, params[i]);
                }
            }
            if (score == NO || score > bestScore) {
                continue;
            }
            if (score < bestScore) {
                best.clear();
                bestArgs.clear();
                bestScore = score;
            }
            best.add(m);
            bestArgs.add(converted);
        }
        if (best.isEmpty()) {
            throw new IllegalArgumentException("java_task: у " + (type != null ? type : target.getClass()).getName()
                + " нет публичного метода " + name + " для " + args.length + " аргумент(ов) таких типов");
        }
        // Равные по счёту перегрузки — как в Java: самая специфичная (её параметры подходят ко всем остальным).
        int pick = -1;
        for (int i = 0; i < best.size() && pick < 0; i++) {
            boolean most = true;
            for (int j = 0; j < best.size() && most; j++) {
                most = i == j || moreSpecific(best.get(i), best.get(j));
            }
            if (most) {
                pick = i;
            }
        }
        if (pick < 0) {
            final StringBuilder list = new StringBuilder();
            for (final Method m : best) {
                list.append(' ').append(m.getName()).append(Arrays.toString(m.getParameterTypes()));
            }
            throw new IllegalArgumentException("java_task: неоднозначно, подходят:" + list
                + " — задайте type=... или аргументы точнее (например, Java-объект нужного типа)");
        }
        return new JavaCall(target, best.get(pick), bestArgs.get(pick));
    }

    /** Параметры a подходят к параметрам b (a не менее специфичен, чем b). */
    private static boolean moreSpecific(final Method a, final Method b) {
        final Class<?>[] pa = a.getParameterTypes();
        final Class<?>[] pb = b.getParameterTypes();
        for (int i = 0; i < pa.length; i++) {
            if (!boxed(pb[i]).isAssignableFrom(boxed(pa[i]))) {
                return false;
            }
        }
        return true;
    }

    /** Публичный тип, пакет которого модуль открывает всем (иначе invoke упадёт IllegalAccessException уже в задаче). */
    private static boolean accessible(final Class<?> c) {
        return Modifier.isPublic(c.getModifiers()) && c.getModule().isExported(c.getPackageName());
    }

    /** Публичные методы публичных типов иерархии (вызов через непубличный класс реализации не пройдёт проверку доступа). */
    private static Iterable<Method> candidates(final Class<?> start, final String name, final int arity) {
        final Map<String, Method> found = new LinkedHashMap<>();
        final Deque<Class<?>> queue = new ArrayDeque<>();
        final Set<Class<?>> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            final Class<?> c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            if (accessible(c)) {
                for (final Method m : c.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == arity && accessible(m.getDeclaringClass())) {
                        found.putIfAbsent(Arrays.toString(m.getParameterTypes()), m);
                    }
                }
            }
            if (c.getSuperclass() != null) {
                queue.add(c.getSuperclass());
            }
            queue.addAll(Arrays.asList(c.getInterfaces()));
        }
        return found.values();
    }

    private static Class<?> boxed(final Class<?> p) {
        if (!p.isPrimitive()) {
            return p;
        }
        return switch (p.getName()) {
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "boolean" -> Boolean.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "char" -> Character.class;
            default -> Void.class;
        };
    }

    /** Насколько аргумент подходит к параметру: 0 — точно, больше — хуже, NO — не подходит. */
    private static int score(final Value v, final boolean isFloat, final Class<?> p) {
        final Class<?> b = boxed(p);
        if (v == null || v.isNull()) {
            return p.isPrimitive() ? NO : 2;
        }
        if (v.isHostObject()) {
            final Object o = v.asHostObject();
            if (o != null && o.getClass() == b) {
                return 0;
            }
            return b.isInstance(o) ? (b == Object.class ? 4 : 1) : NO;
        }
        if (v.isBoolean()) {
            return b == Boolean.class ? 0 : b == Object.class ? 4 : NO;
        }
        if (v.isString()) {
            if (b == String.class) {
                return 0;
            }
            if (b == Character.class) {
                return v.asString().length() == 1 ? 1 : NO;
            }
            return b.isAssignableFrom(String.class) ? (b == Object.class ? 4 : 1) : NO;
        }
        if (v.isNumber()) {
            // float Python с целым значением (2.0) тоже fitsInInt — поэтому смотрим на тип, а не на значение.
            final boolean integral = !isFloat && v.fitsInLong();
            if (b == Integer.class) {
                return integral && v.fitsInInt() ? 0 : NO;
            }
            if (b == Long.class) {
                return integral && v.fitsInLong() ? 1 : NO;
            }
            if (b == Double.class) {
                return integral ? 2 : 0;
            }
            if (b == Float.class) {
                return integral ? 3 : 1;
            }
            if (b == Short.class) {
                return integral && v.fitsInShort() ? 3 : NO;
            }
            if (b == Byte.class) {
                return integral && v.fitsInByte() ? 3 : NO;
            }
            if (b == Number.class || b == Object.class) {
                return 4;
            }
            return NO;
        }
        // Списки, словари, функции Python: после закрытия контекста они мертвы — не берём.
        return NO;
    }

    private static Object convert(final Value v, final boolean isFloat, final Class<?> p) {
        final Class<?> b = boxed(p);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isHostObject()) {
            return v.asHostObject();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isString()) {
            return b == Character.class ? v.asString().charAt(0) : v.asString();
        }
        // число
        if (b == Integer.class) {
            return v.asInt();
        }
        if (b == Long.class) {
            return v.asLong();
        }
        if (b == Double.class) {
            return v.asDouble();
        }
        if (b == Float.class) {
            return (float) v.asDouble();
        }
        if (b == Short.class) {
            return v.asShort();
        }
        if (b == Byte.class) {
            return v.asByte();
        }
        if (isFloat || !v.fitsInLong()) {
            return v.asDouble();
        }
        return v.fitsInInt() ? (Object) v.asInt() : v.fitsInLong() ? (Object) v.asLong() : (Object) v.asDouble();
    }

    @Override
    public void run() {
        try {
            this.method.invoke(this.target, this.args);
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return this.method.getDeclaringClass().getSimpleName() + "." + this.method.getName() + Arrays.toString(this.args);
    }
}
