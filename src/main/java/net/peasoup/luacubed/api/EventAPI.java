package net.peasoup.luacubed.api;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.TwoArgFunction;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.peasoup.luacubed.LuaBridge;

/**
 * Lua API for registering event handlers
 */
public class EventAPI {
    private static final Logger LOGGER = LogManager.getLogger("EventAPI");

    // Global registry of event handlers (shared across all mods)
    private static final Map<Event<?>, List<LuaFunction>> EVENT_HANDLERS = new ConcurrentHashMap<>();
    private static final Set<Event<?>> HOOKED_EVENTS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Event name -> Event object mapping
    private static final Map<String, EventInfo> EVENT_REGISTRY = new HashMap<>();

    // Tracks which Mod ID registered a specific LuaFunction instance
    private static final Map<LuaFunction, String> HANDLER_OWNERS = new ConcurrentHashMap<>();

    private final String activeModId;

    // 3. Force the constructor to require the specific mod ID context!
    public EventAPI(String modId) {
        this.activeModId = modId;
    }

    /**
     * Register all known Fabric events
     */
    public static void registerKnownEvents() {
        LOGGER.info("Scanning Fabric Loader modules for real API events...");

        // Iterate through ALL loaded mods to find any that belong to the Fabric API
        // ecosystem
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String modId = mod.getMetadata().getId();

            // Target only Fabric API core modules (they usually start with fabric- or
            // fabric-api)
            if (!modId.startsWith("fabric-"))
                continue;

            // Use Fabric's native NIO FileSystem accessor to read inside the loaded mod's
            // paths
            for (Path rootPath : mod.getRootPaths()) {
                try (Stream<Path> walk = Files.walk(rootPath)) {
                    walk.filter(path -> path.toString().endsWith(".class"))
                            .forEach(path -> {
                                // Convert file path format (/net/fabricmc/fabric/api/...) to binary class name
                                String relativePath = rootPath.relativize(path).toString();
                                String className = relativePath
                                        .replace('/', '.')
                                        .replace('\\', '.')
                                        .substring(0, relativePath.length() - 6); // Strip ".class"

                                // Only inspect the formal public API namespaces
                                if (!className.startsWith("net.fabricmc.fabric.api"))
                                    return;

                                try {
                                    Class<?> eventClass = Class.forName(className, false,
                                            JavaAPI.class.getClassLoader());

                                    // Guard against client/server side-loading crashes
                                    try {
                                        for (Field field : eventClass.getFields()) {
                                            if (!Modifier.isStatic(field.getModifiers()))
                                                continue;

                                            Object fieldValue = null;
                                            try {
                                                fieldValue = field.get(null);
                                            } catch (Exception ignored) {
                                            }

                                            if (!(fieldValue instanceof Event<?> event))
                                                continue;

                                            Type genericType = field.getGenericType();
                                            if (!(genericType instanceof ParameterizedType paramType))
                                                continue;

                                            Type[] typeArgs = paramType.getActualTypeArguments();
                                            // Fix: Reverted to your exact array index safety checks
                                            if (typeArgs.length == 0 || !(typeArgs[0] instanceof Class<?> callbackType))
                                                continue;

                                            String eventName = eventClass.getSimpleName() + "." + field.getName();

                                            if (!EVENT_REGISTRY.containsKey(eventName)) {
                                                EVENT_REGISTRY.put(eventName, new EventInfo(event, callbackType));
                                                LOGGER.debug("Dynamically discovered Fabric event: {}", eventName);
                                            }
                                        }
                                    } catch (NoClassDefFoundError | Exception ignored) {
                                        // Gracefully skip dedicated Client-only or Server-only classes
                                    }
                                } catch (ClassNotFoundException ignored) {
                                    // Skip classes that fail deep verification
                                }
                            });
                } catch (IOException e) {
                    LOGGER.warn("Could not parse files inside Fabric API module: {}", modId);
                }
            }
        }

        LOGGER.info("Finished! Dynamically registered {} Fabric events", EVENT_REGISTRY.size());
    }

    /**
     * Clear all handlers (for hot reloading)
     */
    public static void clearAllHandlers() {
        EVENT_HANDLERS.clear();
        LOGGER.info("Cleared all Lua event handlers");
        // Note: We don't unhook events, they remain hooked
    }

    /**
     * Install this API into a Lua globals table
     */
    public void install(Globals globals) {
        LuaTable events = new LuaTable();

        // events.on(event_name, handler_function)
        events.set("on", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue eventName, LuaValue handler) {
                if (!eventName.isstring()) {
                    throw new LuaError("Event name must be a string");
                }
                if (!handler.isfunction()) {
                    throw new LuaError("Handler must be a function");
                }

                registerEventHandler(eventName.tojstring(), (LuaFunction) handler);
                return LuaValue.NIL;
            }
        });

        // Helper to list available events
        events.set("list", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable events = new LuaTable();
                int i = 1;
                for (String eventName : EVENT_REGISTRY.keySet()) {
                    events.set(i++, eventName);
                }
                return events;
            }
        });

        globals.set("events", events);
    }

    public static void clearHandlersForMod(String modId) {
        LOGGER.info("Purging event handlers for mod: {}", modId);

        // 1. collect all handlers belonging to this mod
        List<LuaFunction> handlersToRemove = new ArrayList<>();
        for (Map.Entry<LuaFunction, String> entry : HANDLER_OWNERS.entrySet()) {
            if (entry.getValue().equals(modId)) {
                handlersToRemove.add(entry.getKey());
            }
        }

        // 2. remove these handlers from the actual active lists
        for (List<LuaFunction> handlersList : EVENT_HANDLERS.values()) {
            handlersList.removeAll(handlersToRemove);
        }

        // 3. now clean up the ownership map
        for (LuaFunction handler : handlersToRemove) {
            HANDLER_OWNERS.remove(handler);
        }
    }

    private void registerEventHandler(String eventName, LuaFunction handler) {
        EventInfo eventInfo = EVENT_REGISTRY.get(eventName);
        if (eventInfo == null) {
            LOGGER.warn("unknown event: {}. use events.list() (table) to see available events.", eventName);
            return;
        }

        // Add handler to the list
        EVENT_HANDLERS.computeIfAbsent(eventInfo.event, k -> new ArrayList<>()).add(handler);
        HANDLER_OWNERS.put(handler, this.activeModId);

        // Hook the event if not already hooked
        if (!HOOKED_EVENTS.contains(eventInfo.event)) {
            hookEvent(eventInfo.event, eventInfo.callbackType);
            HOOKED_EVENTS.add(eventInfo.event);
        }

        LOGGER.info("registered Lua handler for event: {}", eventName);
    }

    private void hookEvent(Event<?> event, Class<?> callbackType) {
        try {
            // Create a dynamic proxy that forwards to our Lua handlers
            Object proxy = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[] { callbackType },
                    (proxy1, method, args1) -> {
                        // 1. Fire the event and get the Lua result
                        Object result = fireEvent(event, args1);

                        // 2. Check if the Minecraft event expects a return value (like ActionResult)
                        Class<?> returnType = method.getReturnType();

                        if (returnType != void.class) {
                            // If the event expects an ActionResult (most interaction events do)
                            if (returnType.equals(net.minecraft.util.ActionResult.class)) {
                                if (result instanceof String s) {
                                    return switch (s.toUpperCase()) {
                                        case "SUCCESS" -> net.minecraft.util.ActionResult.SUCCESS;
                                        case "CONSUME" -> net.minecraft.util.ActionResult.CONSUME;
                                        case "FAIL" -> net.minecraft.util.ActionResult.FAIL;
                                        default -> net.minecraft.util.ActionResult.PASS;
                                    };
                                }
                                // Default fallback: If Lua didn't return a string, return PASS
                                return net.minecraft.util.ActionResult.PASS;
                            }
                        }

                        return null; // Only return null if the method is void
                    });

            // Find and call the register method
            Method registerMethod = findRegisterMethod(event, callbackType);
            if (registerMethod == null) {
                LOGGER.warn("could not find register method for event {}", event);
                return;
            }

            registerMethod.setAccessible(true);

            // Handle both register(callback) and register(identifier, callback)
            if (registerMethod.getParameterCount() == 1) {
                registerMethod.invoke(event, proxy);
            } else if (registerMethod.getParameterCount() == 2) {
                // Try to create an identifier using Identifier.of() static method
                Class<?> identifierType = registerMethod.getParameterTypes()[0];
                try {
                    // Try the new way first (Identifier.of("namespace", "path"))
                    Method ofMethod = identifierType.getMethod("of", String.class, String.class);
                    Object identifier = ofMethod.invoke(null, "lua", "event_handler");
                    registerMethod.invoke(event, identifier, proxy);
                } catch (NoSuchMethodException e) {
                    // Fall back to old constructor if the static method doesn't exist
                    try {
                        Object identifier = identifierType.getConstructor(String.class).newInstance("lua");
                        registerMethod.invoke(event, identifier, proxy);
                    } catch (Exception ex) {
                        LOGGER.warn("failed to create identifier for event registration", ex);
                        return;
                    }
                }
            }

            LOGGER.info("hooked Fabric event: {}", event.getClass().getSimpleName());

        } catch (Exception e) {
            LOGGER.error("failed to hook event {}", event, e);
        }
    }

    private Method findRegisterMethod(Event<?> event, Class<?> callbackType) {
        for (Method method : event.getClass().getMethods()) {
            if (!method.getName().equals("register"))
                continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0].isAssignableFrom(callbackType)) {
                return method;
            } else if (params.length == 2 && params[1].isAssignableFrom(callbackType)) {
                return method;
            }
        }
        return null;
    }

    private Object fireEvent(Event<?> event, Object[] args) {
        List<LuaFunction> handlers = EVENT_HANDLERS.get(event);
        if (handlers == null)
            return null;

        Object lastResult = null;
        for (LuaFunction handler : handlers) {
            try {
                // Capture the return value from Lua
                LuaValue result = LuaBridge.safeCall(handler, args);

                // If Lua returned something (like "PASS" or "SUCCESS"), save it
                if (result != null && !result.isnil()) {
                    lastResult = result.tojstring();
                }
            } catch (Exception e) {
                LOGGER.error("error in Lua event handler: {}", e.getMessage(), e);
            }
        }
        return lastResult;
    }

    private record EventInfo(Event<?> event, Class<?> callbackType) {
    }
}