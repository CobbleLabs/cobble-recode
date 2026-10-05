package cobble.v1_21.legacy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks Lunar's cloaks out of the cosmetics it says a player wears. Lunar's
 * classes are obfuscated anew with each release, but a worn cosmetic still
 * leads, through a getter or two, to the enum of cosmetic kinds, whose
 * constants keep their names, so the route there is found once per class by
 * the getters' return types.
 */
final class LunarCloaks {
    private static final String CLOAK = "CLOAK";
    private static final int DEPTH = 2;
    private static final Map<Class<?>, List<Method>> ROUTES = new ConcurrentHashMap<>();

    private LunarCloaks() {}

    static List<?> withoutCloaks(List<?> worn) {
        List<Object> kept = new ArrayList<>(worn.size());
        for (Object cosmetic : worn) {
            if (!isCloak(cosmetic)) {
                kept.add(cosmetic);
            }
        }
        return kept;
    }

    private static boolean isCloak(Object cosmetic) {
        List<Method> route = ROUTES.computeIfAbsent(cosmetic.getClass(), type -> route(type, DEPTH));
        if (route.isEmpty()) {
            return false;
        }
        Object at = cosmetic;
        try {
            for (Method getter : route) {
                at = getter.invoke(at);
                if (at == null) {
                    return false;
                }
            }
        } catch (ReflectiveOperationException e) {
            return false;
        }
        return ((Enum<?>) at).name().equals(CLOAK);
    }

    /* The getters from a class to the kinds enum, or none when it is not within reach. */
    private static List<Method> route(Class<?> type, int depth) {
        for (Method getter : type.getMethods()) {
            Class<?> returned = getter.getReturnType();
            if (getter.getParameterCount() != 0 || Modifier.isStatic(getter.getModifiers()) || returned.isPrimitive()
                    || returned.getName().startsWith("java.")) {
                continue;
            }
            if (returned.isEnum() && hasCloak(returned)) {
                getter.setAccessible(true);
                return List.of(getter);
            }
            if (depth > 1) {
                List<Method> rest = route(returned, depth - 1);
                if (!rest.isEmpty()) {
                    getter.setAccessible(true);
                    List<Method> whole = new ArrayList<>();
                    whole.add(getter);
                    whole.addAll(rest);
                    return whole;
                }
            }
        }
        return List.of();
    }

    private static boolean hasCloak(Class<?> kinds) {
        for (Object constant : kinds.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(CLOAK)) {
                return true;
            }
        }
        return false;
    }
}
