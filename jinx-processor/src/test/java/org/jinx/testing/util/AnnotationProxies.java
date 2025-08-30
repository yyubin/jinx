package org.jinx.testing.util;

import jakarta.persistence.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

public final class AnnotationProxies {
    private AnnotationProxies() {}

    // 공용 프록시 팩토리
    @SuppressWarnings("unchecked")
    public static <A extends Annotation> A of(Class<A> type, Map<String, Object> members) {
        InvocationHandler h = new AnnotationIH(type, members);
        return (A) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                h
        );
    }

    // 개별 편의 메서드들
    public static Inheritance inheritance(InheritanceType strategy) {
        return of(Inheritance.class, Map.of("strategy", strategy));
    }

    public static DiscriminatorColumn discriminatorColumn(String name) {
        return of(DiscriminatorColumn.class, Map.of("name", name == null ? "" : name));
    }

    public static DiscriminatorValue discriminatorValue(String value) {
        return of(DiscriminatorValue.class, Map.of("value", value));
    }

    public static PrimaryKeyJoinColumn pkJoin(String name, String referenced) {
        return of(PrimaryKeyJoinColumn.class, Map.of(
                "name", name == null ? "" : name,
                "referencedColumnName", referenced == null ? "" : referenced
        ));
    }

    public static PrimaryKeyJoinColumns pkJoins(PrimaryKeyJoinColumn... items) {
        return of(PrimaryKeyJoinColumns.class, Map.of("value", items));
    }

    public static GeneratedValue generated(GenerationType strategy) {
        return of(GeneratedValue.class, Map.of("strategy", strategy));
    }

    // --- InvocationHandler 구현: annotation 규약 준수 ---
    private static final class AnnotationIH implements InvocationHandler {
        private final Class<? extends Annotation> type;
        private final Map<String, Object> members;

        AnnotationIH(Class<? extends Annotation> type, Map<String, Object> members) {
            this.type = type;
            this.members = new HashMap<>(members);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("annotationType") && method.getParameterCount() == 0) {
                return type;
            }
            if (members.containsKey(name) && method.getParameterCount() == 0) {
                return members.get(name);
            }
            if (name.equals("toString") && method.getParameterCount() == 0) {
                return "@" + type.getName() + members.toString();
            }
            if (name.equals("hashCode") && method.getParameterCount() == 0) {
                return annotationHashCode();
            }
            if (name.equals("equals") && method.getParameterCount() == 1) {
                return annotationEquals(args[0]);
            }
            // 디폴트 멤버 값이 있는 경우 처리
            Object def = defaultValue(method);
            if (def != null && method.getParameterCount() == 0) {
                return def;
            }
            throw new AbstractMethodError("Unhandled method: " + method);
        }

        private Object defaultValue(Method m) {
            return m.getDefaultValue();
        }

        private boolean annotationEquals(Object other) {
            if (other == this) return true;
            if (!(other instanceof Annotation a)) return false;
            if (!a.annotationType().equals(this.type)) return false;

            for (Method m : type.getDeclaredMethods()) {
                try {
                    Object v1 = members.containsKey(m.getName()) ? members.get(m.getName())
                            : (m.getDefaultValue() != null ? m.getDefaultValue() : null);
                    Object v2 = m.invoke(a);
                    if (!deepEqualsNormalized(v1, v2)) return false;
                } catch (Exception e) {
                    return false;
                }
            }
            return true;
        }

        private int annotationHashCode() {
            int result = 0;
            for (Method m : type.getDeclaredMethods()) {
                Object v = members.containsKey(m.getName()) ? members.get(m.getName()) : m.getDefaultValue();
                int nameHash = 127 * m.getName().hashCode();
                result += nameHash ^ (v == null ? 0 : deepHashCode(v));
            }
            return result;
        }

        private static boolean deepEqualsNormalized(Object a, Object b) {
            if (a == b) return true;
            if (a == null || b == null) return false;

            Class<?> ca = a.getClass(), cb = b.getClass();
            if (ca.isArray() && cb.isArray()) {
                if (a instanceof Object[] ao && b instanceof Object[] bo) return Arrays.deepEquals(ao, bo);
                if (a instanceof int[] ai && b instanceof int[] bi) return Arrays.equals(ai, bi);
                if (a instanceof long[] al && b instanceof long[] bl) return Arrays.equals(al, bl);
                if (a instanceof short[] as && b instanceof short[] bs) return Arrays.equals(as, bs);
                if (a instanceof byte[] ab && b instanceof byte[] bb) return Arrays.equals(ab, bb);
                if (a instanceof char[] ac && b instanceof char[] bc) return Arrays.equals(ac, bc);
                if (a instanceof boolean[] ab1 && b instanceof boolean[] bb1) return Arrays.equals(ab1, bb1);
                if (a instanceof float[] af && b instanceof float[] bf) return Arrays.equals(af, bf);
                if (a instanceof double[] ad && b instanceof double[] bd) return Arrays.equals(ad, bd);
                return false;
            }
            return Objects.equals(a, b);
        }

        private static int deepHashCode(Object value) {
            if (value == null) return 0;
            Class<?> c = value.getClass();
            if (!c.isArray()) return value.hashCode();
            if (value instanceof Object[]) return Arrays.deepHashCode((Object[]) value);
            if (value instanceof int[]) return Arrays.hashCode((int[]) value);
            if (value instanceof long[]) return Arrays.hashCode((long[]) value);
            if (value instanceof byte[]) return Arrays.hashCode((byte[]) value);
            if (value instanceof short[]) return Arrays.hashCode((short[]) value);
            if (value instanceof char[]) return Arrays.hashCode((char[]) value);
            if (value instanceof boolean[]) return Arrays.hashCode((boolean[]) value);
            if (value instanceof float[]) return Arrays.hashCode((float[]) value);
            if (value instanceof double[]) return Arrays.hashCode((double[]) value);
            return 0;
        }
    }

    public static DiscriminatorColumn discriminatorColumnFull(
            String name,
            DiscriminatorType type,
            int length,
            String columnDefinition,
            String options
    ) {
        class H implements InvocationHandler {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                return switch (method.getName()) {
                    case "annotationType" -> DiscriminatorColumn.class;
                    case "name" -> name;
                    case "discriminatorType" -> type;
                    case "length" -> length;
                    case "columnDefinition" -> columnDefinition;
                    case "options" -> options;
                    default -> method.getDefaultValue();
                };
            }
        }
        return (DiscriminatorColumn) Proxy.newProxyInstance(
                DiscriminatorColumn.class.getClassLoader(),
                new Class[]{DiscriminatorColumn.class},
                new H()
        );
    }
}
