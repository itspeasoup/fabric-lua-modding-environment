package net.peasoup.luacubed.api;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
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

    // Blacklisted classes that should never be accessible
    private static final Set<String> BLACKLISTED_CLASSES = new HashSet<>(Arrays.asList(
            "java.lang.Runtime",
            "java.lang.System",
            "java.lang.ProcessBuilder",
            "java.io.File",
            "java.nio.file.Files",
            "java.lang.Class",
            "java.lang.reflect.Constructor"));

    private final boolean safetyEnabled;

    public JavaAPI(boolean safetyEnabled) {
        this.safetyEnabled = safetyEnabled; // Stores the flag quietly without logging anything
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
                    if (javaObj == null)
                        return LuaValue.FALSE;

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
                        return LuaValue
                                .error("Cannot cast " + javaObj.getClass().getName() + " to " + className.tojstring());
                    }

                    return wrapInstance(clazz.cast(javaObj), safetyEnabled);
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

            // Dynamic Mod Check: Allow if it belongs to java, minecraft, fabric, or ANY
            // installed mod
            boolean isAllowedPackage = className.startsWith("java.util.") ||
                    className.startsWith("java.lang.") ||
                    className.startsWith("net.minecraft.") ||
                    className.startsWith("net.fabricmc.");

            if (!isAllowedPackage) {
                // Search through loaded mods to see if the class package matches an active mod
                // namespace
                boolean foundInActiveMod = FabricLoader.getInstance().getAllMods().stream().anyMatch(mod -> {
                    String modId = mod.getMetadata().getId().replace("-", "."); // clean up mod id syntax
                    return className.startsWith("com." + modId) ||
                            className.startsWith("net." + modId) ||
                            className.startsWith(modId);
                });

                if (!foundInActiveMod) {
                    // Ultimate fallback safety bypass: just verify it isn't an outright system
                    // exploit
                    if (className.startsWith("java.io.") || className.startsWith("java.net.")
                            || className.startsWith("sun.")) {
                        return LuaValue
                                .error("Access denied: Class '" + className + "' is outside safe environment limits.");
                    }
                }
            }
        }

        try {
            Class<?> clazz = Class.forName(className);
            LOGGER.debug("Imported class: {}", className);
            return wrapClass(clazz, safetyEnabled);
        } catch (ClassNotFoundException e) {
            // Try with mappings (for obfuscated classes)
            try {
                String mappedName = RESOLVER.mapClassName("intermediary", className);
                Class<?> clazz = Class.forName(mappedName);
                LOGGER.debug("Imported class via mapping: {} -> {}", className, mappedName);
                return wrapClass(clazz, safetyEnabled);
            } catch (Exception ex) {
                return LuaValue.error("Could not find class: " + className);
            }
        }
    }

    public static LuaTable wrapClass(Class<?> clazz, boolean safetyEnabled) {
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
            // Target is 'null' because static methods do not have a physical instance
            // wrapper
            table.set(entry.getKey(),
                    new JavaMethodWrapper(entry.getValue(), null, clazz.getName(), entry.getKey(), safetyEnabled));
        }

        // Metatable for static fields and fallback static method queries inside
        // wrapClass
                LuaTable metatable = new LuaTable();
        metatable.set(LuaValue.INDEX, new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue tbl, LuaValue key) {
                String requestedKey = key.tojstring();

                // ⬇️ ADD THIS EXPLICIT CONSTRUCTOR ROUTER HERE ⬇️
                if (requestedKey.equals("new")) {
                    return new VarArgFunction() {
                        @Override
                        public Varargs invoke(Varargs args) {
                            try {
                                Object instance = instantiate(clazz, args);
                                return wrapInstance(instance, safetyEnabled);
                            } catch (Exception e) {
                                return LuaValue.error("Construction failed: " + e.getMessage());
                            }
                        }
                    };
                }

                // Normal check for keys already sitting in the base table
                if (!tbl.rawget(key).isnil()) return tbl.rawget(key);

                // --- INTEGRATED INNER CLASS LOOKUP PASS ---
                try {
                    // Search all declared inner classes (like NotificationManager$Type)
                    for (Class<?> inner : clazz.getDeclaredClasses()) {
                        if (inner.getSimpleName().equalsIgnoreCase(requestedKey)
                                && java.lang.reflect.Modifier.isPublic(inner.getModifiers())) {
                            // Found it! Wrap it as a class and pass the safety status forward
                            return wrapClass(inner, safetyEnabled);
                        }
                    }
                } catch (Exception ignored) {
                }

                // 1. Check if it's a known static method key (mainly for IDE/development
                // environments)
                if (methodGroups.containsKey(requestedKey)) {
                    return new JavaMethodWrapper(methodGroups.get(requestedKey), null, clazz.getName(), requestedKey,
                            safetyEnabled);
                }

                // 2. Try looking it up as a public static field
                try {
                    Field f = clazz.getField(requestedKey);
                    if (Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())) {
                        f.setAccessible(true);
                        return wrapInstance(f.get(null), safetyEnabled);
                    }
                } catch (NoSuchFieldException e) {
                    try {
                        String mappedField = RESOLVER.mapFieldName("intermediary", clazz.getName(), requestedKey, "");
                        Field f = clazz.getField(mappedField);
                        if (Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())) {
                            f.setAccessible(true);
                            return wrapInstance(f.get(null), safetyEnabled);
                        }
                    } catch (Exception ignored) {
                    }
                } catch (Exception ignored) {
                }

                // 3. Fallback for production obfuscation: check every static method's mapped
                // name
                try {
                    MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
                    String unmappedOwner = resolver.unmapClassName("intermediary", clazz.getName());

                    for (Method m : clazz.getMethods()) {
                        if (Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers())) {
                            String descriptor = org.objectweb.asm.Type.getMethodDescriptor(m);
                            String productionName = resolver.mapMethodName("intermediary", unmappedOwner, requestedKey,
                                    descriptor);

                            if (m.getName().equals(productionName)) {
                                return new JavaMethodWrapper(List.of(m), null, clazz.getName(), requestedKey,
                                        safetyEnabled);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }

                return LuaValue.NIL;
            }
        });

        metatable.set(LuaValue.NEWINDEX, new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue tbl, LuaValue key, LuaValue val) {
                String fieldName = key.tojstring();
                try {
                    Field f = clazz.getField(fieldName);
                    if (Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())) {
                        f.setAccessible(true);
                        f.set(null, luaToJavaParam(val, f.getType()));
                    }
                } catch (NoSuchFieldException e) {
                    try {
                        String mappedField = RESOLVER.mapFieldName("intermediary", clazz.getName(), fieldName, "");
                        Field f = clazz.getField(mappedField);
                        if (Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())) {
                            f.setAccessible(true);
                            f.set(null, luaToJavaParam(val, f.getType()));
                        }
                    } catch (Exception ignored) {
                    }
                } catch (Exception ignored) {
                }
                return LuaValue.NIL;
            }
        });
        table.setmetatable(metatable);

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
                    return wrapInstance(instance, safetyEnabled);
                } catch (Exception e) {
                    return LuaValue.error("Construction failed: " + e.getMessage());
                }
            }
        });

        return table;
    }

    // Add the 'final' keyword to the method argument
    // 1. ADDED safetyEnabled TO THE METHOD SIGNATURE PARAMETERS HERE
    private static LuaValue wrapInstance(final Object instance, final boolean safetyEnabled) {

        if (instance == null)
            return LuaValue.NIL;

        // Intercept arrays and lists for native Lua loop support
        if (instance.getClass().isArray()) {
            LuaTable arrayTable = new LuaTable();
            int length = java.lang.reflect.Array.getLength(instance);
            for (int i = 0; i < length; i++) {
                // 2. PASS safetyEnabled DOWN RECURSIVELY FOR ARRAYS
                arrayTable.set(i + 1, wrapInstance(java.lang.reflect.Array.get(instance, i), safetyEnabled));
            }
            return arrayTable;
        }
        if (instance instanceof Collection<?> collection) {
            LuaTable listTable = new LuaTable();
            int idx = 1;
            for (Object element : collection) {
                // 3. PASS safetyEnabled DOWN RECURSIVELY FOR LISTS
                listTable.set(idx++, wrapInstance(element, safetyEnabled));
            }
            return listTable;
        }
        if (instance instanceof String || instance instanceof Number || instance instanceof Boolean
                || instance instanceof Character) {
            return CoerceJavaToLua.coerce(instance);
        }

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
            // 4. NOW THIS WORKS! It references the method parameter directly
            table.set(entry.getKey(),
                    new JavaMethodWrapper(entry.getValue(), instance, clazz.getName(), entry.getKey(), safetyEnabled));
        }

        // METATABLE FOR FIELDS AND INNER CLASSES
        final Object rawObj = instance;
        LuaTable metatable = new LuaTable();
        metatable.set(LuaValue.INDEX, new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue tbl, LuaValue key) {
                String fieldName = key.tojstring();

                // --- INTEGRATED INNER CLASS LOOKUP PASS ---
                try {
                    // Allows an instance of an object to also query its class definition's nested
                    // types!
                    for (Class<?> inner : clazz.getDeclaredClasses()) {
                        if (inner.getSimpleName().equalsIgnoreCase(fieldName)
                                && java.lang.reflect.Modifier.isPublic(inner.getModifiers())) {
                            // Since inner nested classes/enums are static descriptors, we route back to
                            // wrapClass
                            // We need to make sure your outer class reference can call wrapClass correctly
                            // here
                            return wrapClass(inner, safetyEnabled);
                        }
                    }
                } catch (Exception ignored) {
                }
                // ------------------------------------------

                try {
                    Field f = clazz.getField(fieldName);
                    if (!Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())) {
                        f.setAccessible(true);
                        // 5. PASS safetyEnabled DOWN RECURSIVELY ON FIELD LOOKUP
                        return wrapInstance(f.get(rawObj), safetyEnabled);
                    }
                } catch (NoSuchFieldException e) {
                    try {
                        String mappedField = RESOLVER.mapFieldName("intermediary", clazz.getName(), fieldName, "");
                        Field f = clazz.getField(mappedField);
                        if (!Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())) {
                            f.setAccessible(true);
                            // 6. PASS safetyEnabled DOWN RECURSIVELY ON INTERMEDIARY FIELD LOOKUP
                            return wrapInstance(f.get(rawObj), safetyEnabled);
                        }
                    } catch (Exception ignored) {
                    }
                } catch (Exception ignored) {
                }
                return LuaValue.NIL;
            }
        });

        metatable.set(LuaValue.NEWINDEX, new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue tbl, LuaValue key, LuaValue val) {
                String fieldName = key.tojstring();
                try {
                    Field f = clazz.getField(fieldName);
                    if (!Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())) {
                        f.setAccessible(true);
                        f.set(rawObj, luaToJavaParam(val, f.getType()));
                    }
                } catch (NoSuchFieldException e) {
                    try {
                        String mappedField = RESOLVER.mapFieldName("intermediary", clazz.getName(), fieldName, "");
                        Field f = clazz.getField(mappedField);
                        if (!Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())) {
                            f.setAccessible(true);
                            f.set(rawObj, luaToJavaParam(val, f.getType()));
                        }
                    } catch (Exception ignored) {
                    }
                } catch (Exception ignored) {
                }
                return LuaValue.NIL;
            }
        });
        table.setmetatable(metatable);

        // Keep your original toString() block right below the metatable
        table.set("__tostring", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(instance.toString());
            }
        });

        return table;
    }

    private static Object instantiate(Class<?> clazz, Varargs args) throws Exception {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        int luaArgCount = args.narg();

        List<Exception> attemptedErrors = new ArrayList<>();

        // Try to find matching constructor
        for (Constructor<?> constructor : constructors) {
            // Don't skip non-public constructors! We're using setAccessible anyway
            if (constructor.getParameterCount() != luaArgCount)
                continue;

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
                if (i > 0)
                    errorMsg.append(", ");
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

    private static Object luaToJavaParam(LuaValue arg, Class<?> targetType) {
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
        private final String methodName; // FIX 1: Added field to track the actual method name called
        private final boolean safetyEnabled; // FIX 1: Pass safety status down to the static scope

        // Updated constructor to capture the method name and safety configuration
        JavaMethodWrapper(List<Method> methods, Object target, String className, String methodName,
                boolean safetyEnabled) {
            this.methods = methods;
            this.target = target;
            this.className = className;
            this.methodName = methodName;
            this.safetyEnabled = safetyEnabled;
        }

        private static final Set<String> FORBIDDEN_METHODS = Set.of(
                "getClass", "getClassLoader", "getMethods", "getDeclaredMethods",
                "getFields", "getDeclaredFields", "newInstance", "invoke");

        @Override
        public Varargs invoke(Varargs args) {
            // FIX 1: Effectively blocks reflection exploits using our tracked methodName
            if (safetyEnabled && FORBIDDEN_METHODS.contains(this.methodName)) {
                return LuaValue
                        .error("Security violation: Access to reflection method '" + this.methodName + "' is blocked.");
            }

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

            // Find matching method by parameter count (Your brilliant overload logic
            // remains untouched!)
            Method match = null;
            for (Method m : methods) {
                if (m.getParameterCount() == effectiveArgCount) {
                    Class<?>[] paramTypes = m.getParameterTypes();
                    boolean matchFound = true;
                    for (int i = 0; i < effectiveArgCount; i++) {
                        if (!canCoerce(args.arg(i + startIdx), paramTypes[i])) {
                            matchFound = false;
                            break;
                        }
                    }
                    if (matchFound) {
                        match = m;
                        break;
                    }
                }
            }

            if (match == null) {
                return LuaValue.error("Method '" + className + "." + this.methodName +
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
                    return wrapInstance(result, safetyEnabled);
                }

                return CoerceJavaToLua.coerce(result);

            } catch (InvocationTargetException e) {
                Throwable cause = e.getTargetException();
                return LuaValue.error("Java method threw exception: " +
                        cause.getClass().getSimpleName() + ": " + cause.getMessage());
            } catch (Exception e) {
                // ENHANCED DEBUGGING: Intercept type mismatch errors to show expected vs
                // received
                StringBuilder msg = new StringBuilder();
                msg.append("Method invocation failed: ").append(e.getMessage());
                msg.append("\n=== [Lua to Java Type Mismatch Debugger] ===");
                msg.append("\nJava Method: ").append(className).append(".").append(this.methodName).append("()");

                // Print what Java wanted
                Class<?>[] expectedTypes = match.getParameterTypes();
                msg.append("\nExpected Java Types (").append(expectedTypes.length).append("):");
                for (int i = 0; i < expectedTypes.length; i++) {
                    msg.append("\n  [").append(i).append("] ").append(expectedTypes[i].getName());
                }

                // Print what Lua sent
                msg.append("\nReceived Lua Values (").append(effectiveArgCount).append("):");
                for (int i = 0; i < effectiveArgCount; i++) {
                    LuaValue arg = args.arg(i + startIdx);
                    String valString = arg.isuserdata() ? "userdata"
                            : (arg.istable() && !arg.get("__raw").isnil() ? "wrapped_java" : arg.tojstring());
                    msg.append("\n  [").append(i).append("] Type: ")
                            .append(arg.typename())
                            .append(" | Value: ").append(valString);
                }
                msg.append("\n=============================================");

                return LuaValue.error(msg.toString());
            }
        }

        private String getOverloadSignatures() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < methods.size(); i++) {
                if (i > 0)
                    sb.append(", ");
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

    private static boolean canCoerce(LuaValue arg, Class<?> targetType) {
        try {
            if (arg.istable() && !arg.get("__raw").isnil()) {
                return targetType.isInstance(arg.get("__raw").checkuserdata());
            }
            if (arg.isuserdata()) {
                return targetType.isInstance(arg.checkuserdata());
            }
            if (arg.isnumber() && (targetType == int.class || targetType == double.class || targetType == float.class
                    || targetType == long.class))
                return true;
            if (arg.isboolean() && targetType == boolean.class)
                return true;
            if (arg.isstring() && targetType == String.class)
                return true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}