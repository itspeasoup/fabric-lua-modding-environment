package net.peasoup.language.lua;

import net.fabricmc.fabric.api.event.Event;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LuaEventBridge {
    private static final Logger LOGGER = LogManager.getLogger("LuaEventBridge");

    // Map simple class names to known event classes
    private static final Map<String, Class<?>> EVENT_CLASS_MAP = new HashMap<>();
    static {
        List<Class<?>> eventClasses = Arrays.asList(
                net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents.class,
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.class,
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.class,
                net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.class,
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.class,
                net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents.class,
                net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.class,
                net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.class,
                net.fabricmc.fabric.api.event.player.UseBlockCallback.class,
                net.fabricmc.fabric.api.event.player.UseEntityCallback.class,
                net.fabricmc.fabric.api.event.player.AttackBlockCallback.class,
                net.fabricmc.fabric.api.event.player.AttackEntityCallback.class
        );
        for (Class<?> clazz : eventClasses) {
            EVENT_CLASS_MAP.put(clazz.getSimpleName(), clazz);
        }
    }

    private static final Map<Object, List<LuaFunction>> LUA_HANDLERS = new ConcurrentHashMap<>();
    private static final Set<Event<?>> HOOKED_EVENTS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Preferred registration: by event class name and field name!
     */
    public static void registerEventByClassAndField(String className, String fieldName, LuaFunction handler) {
        Event<?> eventObj = findEventByClassAndField(className, fieldName);
        Class<?> callbackType = findCallbackTypeByClassAndField(className, fieldName);

        if (eventObj == null || callbackType == null) {
            LOGGER.warn("Could not find event or callback type for {}.{}", className, fieldName);
            return;
        }

        LUA_HANDLERS.computeIfAbsent(eventObj, k -> new ArrayList<>()).add(handler);

        if (!HOOKED_EVENTS.contains(eventObj)) {
            hookFabricEvent(eventObj, callbackType);
        }

        LOGGER.info("Registered Lua handler for event '{}.{}': {}", className, fieldName, eventObj);
    }

    private static Event<?> findEventByClassAndField(String className, String fieldName) {
        Class<?> clazz = EVENT_CLASS_MAP.get(className);
        if (clazz == null) return null;
        try {
            Field f = clazz.getField(fieldName);
            Object obj = f.get(null);
            if (obj instanceof Event<?> event) return event;
        } catch (Exception ignored) {}
        return null;
    }

    private static Class<?> findCallbackTypeByClassAndField(String className, String fieldName) {
        Class<?> clazz = EVENT_CLASS_MAP.get(className);
        if (clazz == null) return null;
        try {
            Field f = clazz.getField(fieldName);
            Type generic = f.getGenericType();
            if (generic instanceof ParameterizedType pt) {
                Type arg = pt.getActualTypeArguments()[0];
                if (arg instanceof Class<?> cls) return cls;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static void fireEvent(Object eventObj, Object... args) {
        List<LuaFunction> handlers = LUA_HANDLERS.get(eventObj);
        if (handlers != null) {
            for (LuaFunction func : handlers) {
                try {
                    LuaValue[] luaArgs = new LuaValue[args.length];
                    for (int i = 0; i < args.length; i++) {
                        luaArgs[i] = CoerceJavaToLua.coerce(args[i]);
                    }
                    func.invoke(LuaValue.varargsOf(luaArgs));
                } catch (Exception e) {
                    LOGGER.error("Error calling Lua handler for event {}: {}", eventObj, e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Lua interface: register_event(className, fieldName, handler)
     */
    public static LuaValue getRegisterEventFunction() {
        return new org.luaj.vm2.lib.ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue className, LuaValue fieldName, LuaValue handler) {
                if (!className.isstring() || !fieldName.isstring() || !handler.isfunction()) return LuaValue.NIL;
                registerEventByClassAndField(className.tojstring(), fieldName.tojstring(), (LuaFunction) handler);
                return LuaValue.NIL;
            }
        };
    }

    public static LuaValue getDescribeFunction() {
        return new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                Object obj = org.luaj.vm2.lib.jse.CoerceLuaToJava.coerce(arg, Object.class);
                if (obj == null) return LuaValue.valueOf("nil");
                Class<?> clazz = obj.getClass();
                StringBuilder sb = new StringBuilder();
                sb.append("Class: ").append(clazz.getName()).append("\nFields:\n");
                for (Field f : clazz.getFields()) {
                    sb.append("  ").append(f.getType().getSimpleName()).append(" ").append(f.getName()).append("\n");
                }
                sb.append("Methods:\n");
                for (Method m : clazz.getMethods()) {
                    sb.append("  ").append(m.getReturnType().getSimpleName()).append(" ").append(m.getName()).append("(");
                    for (int i = 0; i < m.getParameterCount(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(m.getParameterTypes()[i].getSimpleName());
                    }
                    sb.append(")\n");
                }
                return LuaValue.valueOf(sb.toString());
            }
        };
    }

    public static <T> void hookFabricEvent(Event<T> event, Class<?> callbackType) {
        if (event == null || HOOKED_EVENTS.contains(event) || callbackType == null) {
            LOGGER.info("event is null/event is already hooked/callbacktype is null");
            return;
        }
        try {
            Object proxy = Proxy.newProxyInstance(
                    callbackType.getClassLoader(),
                    new Class[]{callbackType},
                    (proxyObj, method, args) -> {
                        fireEvent(event, args == null ? new Object[0] : args);
                        Class<?> ret = method.getReturnType();
                        if (ret.isPrimitive()) {
                            if (ret == boolean.class) return false;
                            if (ret == char.class) return '\0';
                            if (ret == byte.class) return (byte)0;
                            if (ret == short.class) return (short)0;
                            if (ret == int.class) return 0;
                            if (ret == long.class) return 0L;
                            if (ret == float.class) return 0.0f;
                            if (ret == double.class) return 0.0d;
                        }
                        return null;
                    }
            );

            Method registerMethod = getRegisterMethod(event, callbackType);
            if (registerMethod == null) {
                LOGGER.warn("No suitable register method found for event {} and callback type {}", event, callbackType);
                return;
            }

            registerMethod.setAccessible(true);

            if (registerMethod.getParameterCount() == 1) {
                registerMethod.invoke(event, proxy);
            } else if (registerMethod.getParameterCount() == 2) {
                Object identifier = null;
                try {
                    Class<?> identifierClass = registerMethod.getParameterTypes()[0];
                    identifier = identifierClass.getConstructor(String.class).newInstance("lua");
                } catch (Exception ignored) {}
                registerMethod.invoke(event, identifier, proxy);
            }

            HOOKED_EVENTS.add(event);
            LOGGER.info("Hooked Fabric event '{}' with callback type {}", event.getClass().getSimpleName(), callbackType);
        } catch (Exception e) {
            LOGGER.warn("Failed to hook Fabric event {}: {}", event, e.toString());
        }
    }

    public static void clearAllHandlers() {
        LUA_HANDLERS.clear();
        LOGGER.info("Cleared all Lua event handlers!");
        // DO NOT clear HOOKED_EVENTS or REGISTERED_PROXIES!
        // DO NOT try to unregister proxies!
    }

    private static <T> @Nullable Method getRegisterMethod(Event<T> event, Class<?> callbackType) {
        Method registerMethod = null;
        for (Method m : event.getClass().getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (m.getName().equals("register")) {
                if (params.length == 1 && params[0].isAssignableFrom(callbackType))
                    registerMethod = m;
                else if (params.length == 2 && params[1].isAssignableFrom(callbackType))
                    registerMethod = m;
            }
        }
        return registerMethod;
    }
}