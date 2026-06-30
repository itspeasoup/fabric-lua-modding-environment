package net.peasoup.language.lua.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.peasoup.language.lua.LuaModMetadata;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Lua API for mod configuration
 * Allows mods to have user-configurable settings
 */
public class ConfigAPI {
    private static final Logger LOGGER = LogManager.getLogger("ConfigAPI");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String modId;
    private final Map<String, Object> config = new HashMap<>();
    private Path configFile;

    public ConfigAPI(String modId, Path modPath) {
        this.modId = modId;
    }

    /**
     * Install the config API into Lua globals
     */
    public void install(Globals globals, LuaModMetadata.ConfigSpec configSpec) {
        // Determine config file location
        Path configDir = Paths.get("config").resolve("lua_mods");
        if (configSpec.path != null && !configSpec.path.isEmpty()) {
            configDir = Paths.get(configSpec.path);
        }

        this.configFile = configDir.resolve(modId + ".json");

        // Load existing config or create with defaults
        loadConfig(configSpec.defaultConfig);

        LuaTable configTable = new LuaTable();

        // config.get(key, default_value)
        configTable.set("get", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue key, LuaValue defaultValue) {
                if (!key.isstring()) {
                    throw new LuaError("Config key must be a string");
                }

                Object value = getConfigValue(key.tojstring());
                if (value == null) {
                    return defaultValue;
                }

                return javaToLua(value);
            }
        });

        // config.set(key, value)
        configTable.set("set", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue key, LuaValue value) {
                if (!key.isstring()) {
                    throw new LuaError("Config key must be a string");
                }

                setConfigValue(key.tojstring(), luaToJava(value));
                return LuaValue.NIL;
            }
        });

        // config.save()
        configTable.set("save", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override
            public LuaValue call() {
                saveConfig();
                return LuaValue.NIL;
            }
        });

        // config.reload()
        configTable.set("reload", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override
            public LuaValue call() {
                reloadConfig();
                return LuaValue.NIL;
            }
        });

        // config.has(key)
        configTable.set("has", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue key) {
                if (!key.isstring()) {
                    throw new LuaError("Config key must be a string");
                }
                return LuaValue.valueOf(hasConfigKey(key.tojstring()));
            }
        });

        // config.delete(key)
        configTable.set("delete", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue key) {
                if (!key.isstring()) {
                    throw new LuaError("Config key must be a string");
                }
                deleteConfigKey(key.tojstring());
                return LuaValue.NIL;
            }
        });

        // config.get_all()
        configTable.set("get_all", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return javaToLua(config);
            }
        });

        globals.set("config", configTable);

        LOGGER.info("Installed config API for mod: {}", modId);
    }

    /**
     * Load config from file, merging with defaults
     */
    private void loadConfig(Map<String, Object> defaults) {
        // Start with defaults
        config.clear();
        if (defaults != null) {
            config.putAll(defaults);
        }

        // Try to load from file
        if (Files.exists(configFile)) {
            try {
                String json = Files.readString(configFile);
                @SuppressWarnings("unchecked") Map<String, Object> loaded = GSON.fromJson(json, Map.class);

                if (loaded != null) {
                    // Merge loaded config with defaults (loaded values override defaults)
                    config.putAll(loaded);
                    LOGGER.info("Loaded config for mod: {}", modId);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load config for mod {}, using defaults", modId, e);
            }
        } else {
            // Create config file with defaults
            saveConfig();
            LOGGER.info("Created default config for mod: {}", modId);
        }
    }

    /**
     * Save current config to file
     */
    private void saveConfig() {
        try {
            Files.createDirectories(configFile.getParent());
            String json = GSON.toJson(config);
            Files.writeString(configFile, json);
            LOGGER.debug("Saved config for mod: {}", modId);
        } catch (Exception e) {
            LOGGER.error("Failed to save config for mod {}", modId, e);
        }
    }

    /**
     * Reload config from file
     */
    private void reloadConfig() {
        if (Files.exists(configFile)) {
            try {
                String json = Files.readString(configFile);
                @SuppressWarnings("unchecked") Map<String, Object> loaded = GSON.fromJson(json, Map.class);

                if (loaded != null) {
                    config.clear();
                    config.putAll(loaded);
                    LOGGER.info("Reloaded config for mod: {}", modId);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to reload config for mod {}", modId, e);
            }
        }
    }

    /**
     * Get a config value by key (supports nested keys with dot notation)
     */
    private Object getConfigValue(String key) {
        String[] parts = key.split("\\.");
        Object current = config;

        for (String part : parts) {
            if (current instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) current;
                current = map.get(part);
                if (current == null) {
                    return null;
                }
            } else {
                return null;
            }
        }

        return current;
    }

    /**
     * Set a config value by key (supports nested keys with dot notation)
     */
    private void setConfigValue(String key, Object value) {
        String[] parts = key.split("\\.");

        if (parts.length == 1) {
            config.put(key, value);
        } else {
            Map<String, Object> current = config;

            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                Object next = current.get(part);

                if (!(next instanceof Map)) {
                    Map<String, Object> newMap = new HashMap<>();
                    current.put(part, newMap);
                    current = newMap;
                } else {
                    @SuppressWarnings("unchecked") Map<String, Object> nextMap = (Map<String, Object>) next;
                    current = nextMap;
                }
            }

            current.put(parts[parts.length - 1], value);
        }
    }

    /**
     * Check if a config key exists
     */
    private boolean hasConfigKey(String key) {
        return getConfigValue(key) != null;
    }

    /**
     * Delete a config key
     */
    private void deleteConfigKey(String key) {
        String[] parts = key.split("\\.");

        if (parts.length == 1) {
            config.remove(key);
        } else {
            Map<String, Object> current = config;

            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                Object next = current.get(part);

                if (!(next instanceof Map)) {
                    return; // Key doesn't exist
                }

                @SuppressWarnings("unchecked") Map<String, Object> nextMap = (Map<String, Object>) next;
                current = nextMap;
            }

            current.remove(parts[parts.length - 1]);
        }
    }

    /**
     * Convert Java object to Lua value
     */
    private LuaValue javaToLua(Object obj) {
        if (obj == null) {
            return LuaValue.NIL;
        } else if (obj instanceof Boolean) {
            return LuaValue.valueOf((Boolean) obj);
        } else if (obj instanceof Number) {
            return LuaValue.valueOf(((Number) obj).doubleValue());
        } else if (obj instanceof String) {
            return LuaValue.valueOf((String) obj);
        } else if (obj instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) obj;
            LuaTable table = new LuaTable();

            for (Map.Entry<String, Object> entry : map.entrySet()) {
                table.set(entry.getKey(), javaToLua(entry.getValue()));
            }

            return table;
        } else if (obj instanceof Iterable) {
            LuaTable table = new LuaTable();
            int i = 1;

            for (Object item : (Iterable<?>) obj) {
                table.set(i++, javaToLua(item));
            }

            return table;
        }

        return LuaValue.NIL;
    }

    /**
     * Convert Lua value to Java object
     */
    private Object luaToJava(LuaValue value) {
        if (value.isnil()) {
            return null;
        } else if (value.isboolean()) {
            return value.toboolean();
        } else if (value.isnumber()) {
            double d = value.todouble();
            if (d == (long) d) {
                return (long) d;
            }
            return d;
        } else if (value.isstring()) {
            return value.tojstring();
        } else if (value.istable()) {
            LuaTable table = value.checktable();

            // Check if it's an array or a map
            if (table.length() > 0) {
                // It's an array
                java.util.List<Object> list = new java.util.ArrayList<>();
                for (int i = 1; i <= table.length(); i++) {
                    list.add(luaToJava(table.get(i)));
                }
                return list;
            } else {
                // It's a map
                Map<String, Object> map = new HashMap<>();
                LuaValue key = LuaValue.NIL;

                while (true) {
                    Varargs next = table.next(key);
                    if ((key = next.arg1()).isnil()) break;

                    map.put(key.tojstring(), luaToJava(next.arg(2)));
                }

                return map;
            }
        }

        return null;
    }
}