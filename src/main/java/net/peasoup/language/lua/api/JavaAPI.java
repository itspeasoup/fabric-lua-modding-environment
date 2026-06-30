package net.peasoup.language.lua.api;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.CoerceLuaToJava;

import java.lang.reflect.*;
import java.util.*;

/**
 * Advanced Java interop API for power users
 * Allows direct Java class/method access from Lua
 * <p>
 * WARNING: This bypasses the safe Lua API and gives direct Java access.
 * Use with caution!
 */
public class JavaAPI {
    private static final Logger LOGGER = LogManager.getLogger("JavaAPI");
    private static final MappingResolver RESOLVER = FabricLoader.getInstance().getMappingResolver();

    // Security: Whitelist of allowed packages (can be configured)
    private static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
            "net.minecraft",
            "net.fabricmc.fabric.api",
            "java.util",
            "java.lang"
    ));

    // Blacklisted classes that should never be accessible
    private static final Set<String> BLACKLISTED_CLASSES = new HashSet<>(Arrays.asList(
            "java.lang.Runtime",
            "java.lang.System",
            "java.lang.ProcessBuilder",
            "java.io.File",
            "java.nio.file.Files",
            "java.lang.Class",
            "java.lang.reflect.Constructor"
    ));

    private final boolean safetyEnabled;

    public JavaAPI(boolean safetyEnabled) {
        this.safetyEnabled = safetyEnabled;

        if (!safetyEnabled) {
            LOGGER.warn("JavaAPI initialized with safety checks DISABLED! This allows unrestricted Java access.");
        }
    }

    public void install(Globals globals) {
        LuaTable javaTable = new LuaTable();

        // import(className) - Import a Java class
        javaTable.set("import", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue className) {
                return importClass(className.tojstring());
            }
        });

        // instanceof(object, className) - Check if object is instance of class
        javaTable.set("instanceof", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue obj, LuaValue className) {
                try {
                    Object javaObj = extractJavaObject(obj);
                    if (javaObj == null) return LuaValue.FALSE;

                    Class<?> clazz = Class.forName(className.tojstring());
                    return LuaValue.valueOf(clazz.isInstance(javaObj));
                } catch (Exception e) {
                    return LuaValue.FALSE;
                }
            }
        });

        // cast(object, className) - Cast object to class
        javaTable.set("cast", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue obj, LuaValue className) {
                try {
                    Object javaObj = extractJavaObject(obj);
                    Class<?> clazz = Class.forName(className.tojstring());

                    if (!clazz.isInstance(javaObj)) {
                        assert javaObj != null;
                        return LuaValue.error("Cannot cast " + javaObj.getClass().getName() + " to " + className.tojstring());
                    }

                    return wrapInstance(clazz.cast(javaObj));
                } catch (Exception e) {
                    return LuaValue.error("Cast failed: " + e.getMessage());
                }
            }
        });

        // array(size, [initialValue]) - Create Java array
        javaTable.set("array", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int size = args.arg(1).toint();
                LuaValue initial = args.arg(2);

                if (initial.isnil()) {
                    return CoerceJavaToLua.coerce(new Object[size]);
                } else {
                    Object[] array = new Object[size];
                    Object initialObj = extractJavaObject(initial);
                    Arrays.fill(array, initialObj);
                    return CoerceJavaToLua.coerce(array);
                }
            }
        });

        // null() - Return Java null
        javaTable.set("null", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return CoerceJavaToLua.coerce(null);
            }
        });

        globals.set("java", javaTable);

        LOGGER.info("Installed JavaAPI (safety: {})", safetyEnabled ? "enabled" : "DISABLED");
    }

    private LuaValue importClass(String className) {
        // Security check
        if (safetyEnabled) {
            if (BLACKLISTED_CLASSES.contains(className)) {
                return LuaValue.error("Class '" + className + "' is blacklisted for security reasons");
            }

            boolean allowed = ALLOWED_PACKAGES.stream()
                    .anyMatch(pkg -> className.startsWith(pkg + ".") || className.equals(pkg));

            if (!allowed) {
                return LuaValue.error("Class '" + className + "' is not in allowed packages. " +
                        "Allowed: " + String.join(", ", ALLOWED_PACKAGES));
            }
        }

        try {
            Class<?> clazz = Class.forName(className);
            LOGGER.debug("Imported class: {}", className);
            return wrapClass(clazz);
        } catch (ClassNotFoundException e) {
            // Try with mappings (for obfuscated classes)
            try {
                String mappedName = RESOLVER.mapClassName("intermediary", className);
                Class<?> clazz = Class.forName(mappedName);
                LOGGER.debug("Imported class via mapping: {} -> {}", className, mappedName);
                return wrapClass(clazz);
            } catch (Exception ex) {
                return LuaValue.error("Could not find class: " + className);
            }
        }
    }

    private LuaTable wrapClass(Class<?> clazz) {
        LuaTable table = new LuaTable();

        // Add metadata
        table.set("__class_name", clazz.getName());
        table.set("__simple_name", clazz.getSimpleName());
        table.set("__is_interface", String.valueOf(clazz.isInterface()));
        table.set("__is_enum", String.valueOf(clazz.isEnum()));

        // Group Static Methods by name
        Map<String, List<Method>> methodGroups = new HashMap<>();
        for (Method m : clazz.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers())) {
                methodGroups.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
            }
        }

        for (Map.Entry<String, List<Method>> entry : methodGroups.entrySet()) {
            table.set(entry.getKey(), new JavaMethodWrapper(entry.getValue(), null, clazz.getName()));
        }

        // Map Static Fields
        for (Field f : clazz.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())) {
                try {
                    f.setAccessible(true);
                    table.set(f.getName(), CoerceJavaToLua.coerce(f.get(null)));
                } catch (Exception e) {
                    LOGGER.debug("Failed to access static field {}.{}", clazz.getName(), f.getName());
                }
            }
        }

        // Enum constants
        if (clazz.isEnum()) {
            Object[] constants = clazz.getEnumConstants();
            LuaTable enumTable = new LuaTable();
            for (Object constant : constants) {
                Enum<?> e = (Enum<?>) constant;
                enumTable.set(e.name(), CoerceJavaToLua.coerce(constant));
            }
            table.set("values", enumTable);
        }

        // Constructor
        table.set("new", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    Object instance = instantiate(clazz, args);
                    return wrapInstance(instance);
                } catch (Exception e) {
                    return LuaValue.error("Construction failed: " + e.getMessage());
                }
            }
        });

        return table;
    }

    private static LuaTable wrapInstance(Object instance) {
        if (instance == null) return (LuaTable) LuaValue.NIL;

        LuaTable table = new LuaTable();
        Class<?> clazz = instance.getClass();

        // Add metadata
        table.set("__class_name", clazz.getName());
        table.set("__simple_name", clazz.getSimpleName());
        table.set("__raw", CoerceJavaToLua.coerce(instance));

        // Group Instance Methods
        Map<String, List<Method>> methodGroups = new HashMap<>();
        for (Method m : clazz.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers())) {
                methodGroups.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
            }
        }

        for (Map.Entry<String, List<Method>> entry : methodGroups.entrySet()) {
            table.set(entry.getKey(), new JavaMethodWrapper(entry.getValue(), instance, clazz.getName()));
        }

        // Instance Fields (read-only)
        for (Field f : clazz.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())) {
                try {
                    f.setAccessible(true);
                    table.set(f.getName(), CoerceJavaToLua.coerce(f.get(instance)));
                } catch (Exception e) {
                    // Skip inaccessible fields
                }
            }
        }

        // toString()
        table.set("__tostring", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(instance.toString());
            }
        });

        return table;
    }

    private Object instantiate(Class<?> clazz, Varargs args) throws Exception {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        int luaArgCount = args.narg();

        List<Exception> attemptedErrors = new ArrayList<>();

        // Try to find matching constructor
        for (Constructor<?> constructor : constructors) {
            // Don't skip non-public constructors! We're using setAccessible anyway
            if (constructor.getParameterCount() != luaArgCount) continue;

            try {
                // CRITICAL: Must set accessible BEFORE trying to use it
                constructor.setAccessible(true);

                Object[] params = new Object[luaArgCount];
                Class<?>[] paramTypes = constructor.getParameterTypes();

                for (int i = 0; i < luaArgCount; i++) {
                    LuaValue arg = args.arg(i + 1);
                    params[i] = luaToJavaParam(arg, paramTypes[i]);
                }

                Object result = constructor.newInstance(params);
                LOGGER.debug("Successfully instantiated {} with {} args", clazz.getName(), luaArgCount);
                return result;

            } catch (Exception e) {
                // Save error for debugging
                attemptedErrors.add(e);
                LOGGER.debug("Constructor attempt failed: {}", e.getMessage());
            }
        }

        // Build detailed error message
        StringBuilder errorMsg = new StringBuilder();
        errorMsg.append("No matching constructor found for ").append(clazz.getName())
                .append(" with ").append(luaArgCount).append(" argument(s).\n");
        errorMsg.append("Available constructors:\n");

        for (Constructor<?> c : constructors) {
            errorMsg.append("  - ").append(c.getParameterCount()).append(" parameters: ");
            Class<?>[] types = c.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (i > 0) errorMsg.append(", ");
                errorMsg.append(types[i].getSimpleName());
            }
            errorMsg.append("\n");
        }

        if (!attemptedErrors.isEmpty()) {
            errorMsg.append("Errors encountered:\n");
            for (Exception e : attemptedErrors) {
                errorMsg.append("  - ").append(e.getClass().getSimpleName())
                        .append(": ").append(e.getMessage()).append("\n");
            }
        }

        throw new RuntimeException(errorMsg.toString());
    }

    private Object luaToJavaParam(LuaValue arg, Class<?> targetType) {
        // If it's a wrapped table, grab the raw object
        if (arg.istable() && !arg.get("__raw").isnil()) {
            Object raw = arg.get("__raw").touserdata();
            if (targetType.isInstance(raw)) {
                return raw;
            }
        }

        // If it's already userdata, extract it
        if (arg.isuserdata()) {
            Object userData = arg.touserdata();
            if (targetType.isInstance(userData)) {
                return userData;
            }
        }

        // Let Luaj handle primitives and basic types
        return CoerceLuaToJava.coerce(arg, targetType);
    }

    private Object extractJavaObject(LuaValue value) {
        if (value.istable() && !value.get("__raw").isnil()) {
            return value.get("__raw").touserdata();
        }
        if (value.isuserdata()) {
            return value.touserdata();
        }
        return null;
    }

    private static class JavaMethodWrapper extends VarArgFunction {
        private final List<Method> methods;
        private final Object target;
        private final String className;

        JavaMethodWrapper(List<Method> methods, Object target, String className) {
            this.methods = methods;
            this.target = target;
            this.className = className;
        }

        @Override
        public Varargs invoke(Varargs args) {
            int totalArgs = args.narg();
            int startIdx = 1;
            int effectiveArgCount = totalArgs;

            // Detect ":" syntax (method call with self)
            if (target != null && totalArgs > 0 && args.arg1().istable()) {
                LuaValue raw = args.arg1().get("__raw");
                if (raw.isuserdata() && raw.touserdata() == target) {
                    // It's a ':' call! Skip the 'self' table.
                    startIdx = 2;
                    effectiveArgCount--;
                }
            }

            // Find matching method by parameter count
            Method match = null;
            for (Method m : methods) {
                if (m.getParameterCount() == effectiveArgCount) {
                    match = m;
                    break;
                }
            }

            if (match == null) {
                String methodName = methods.isEmpty() ? "unknown" : methods.getFirst().getName();
                return LuaValue.error("Method '" + className + "." + methodName +
                        "' does not accept " + effectiveArgCount + " arguments. " +
                        "Available overloads: " + getOverloadSignatures());
            }

            try {
                Object[] params = new Object[effectiveArgCount];
                Class<?>[] paramTypes = match.getParameterTypes();

                for (int i = 0; i < effectiveArgCount; i++) {
                    LuaValue arg = args.arg(i + startIdx);

                    // Extract raw Java object if wrapped
                    if (arg.istable() && !arg.get("__raw").isnil()) {
                        params[i] = arg.get("__raw").touserdata();
                    } else if (arg.isuserdata()) {
                        params[i] = arg.touserdata();
                    } else {
                        params[i] = CoerceLuaToJava.coerce(arg, paramTypes[i]);
                    }
                }

                Object result = match.invoke(target, params);

                // Fluent API: if method returns 'this', return the same Lua table
                if (result == target && startIdx == 2) {
                    return args.arg1();
                }

                // Wrap result if it's a Java object
                if (result != null && !isPrimitive(result)) {
                    return wrapInstance(result);
                }

                return CoerceJavaToLua.coerce(result);

            } catch (InvocationTargetException e) {
                Throwable cause = e.getTargetException();
                return LuaValue.error("Java method threw exception: " +
                        cause.getClass().getSimpleName() + ": " + cause.getMessage());
            } catch (Exception e) {
                return LuaValue.error("Method invocation failed: " + e.getMessage());
            }
        }

        private String getOverloadSignatures() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < methods.size(); i++) {
                if (i > 0) sb.append(", ");
                Method m = methods.get(i);
                sb.append(m.getParameterCount()).append(" args");
            }
            return sb.toString();
        }

        private boolean isPrimitive(Object obj) {
            return obj instanceof String ||
                    obj instanceof Number ||
                    obj instanceof Boolean ||
                    obj instanceof Character;
        }
    }
}