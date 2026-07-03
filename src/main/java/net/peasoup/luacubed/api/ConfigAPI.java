package net.peasoup.luacubed.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.peasoup.luacubed.LuaModMetadata;

public class ConfigAPI {
    private static final Logger LOGGER = LogManager.getLogger("ConfigAPI");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String modId;
    private final Map<String, Object> config = new HashMap<>();
    private Path configFile;

    public ConfigAPI(String modId, Path modPath) {
        this.modId = modId;
    }

    public void install(Globals globals, LuaModMetadata.ConfigSpec configSpec) {
        Path configDir = Paths.get("config").resolve("lua_mods");
        if (configSpec.path != null && !configSpec.path.isEmpty()) {
            configDir = Paths.get(configSpec.path);
        }

        this.configFile = configDir.resolve(modId + ".json");

        loadConfig(configSpec.defaultConfig);

        LuaTable configTable = new LuaTable();

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

        configTable.set("save", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override
            public LuaValue call() {
                saveConfig();
                return LuaValue.NIL;
            }
        });

        configTable.set("reload", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override
            public LuaValue call() {
                reloadConfig();
                return LuaValue.NIL;
            }
        });

        configTable.set("has", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue key) {
                if (!key.isstring()) {
                    throw new LuaError("Config key must be a string");
                }
                return LuaValue.valueOf(hasConfigKey(key.tojstring()));
            }
        });

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

        configTable.set("get_all", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return javaToLua(config);
            }
        });

        globals.set("config", configTable);

        LOGGER.info("Installed config API for mod: {}", modId);
    }

    private void loadConfig(Map<String, Object> defaults) {
        config.clear();
        if (defaults != null) {
            config.putAll(defaults);
        }

        if (Files.exists(configFile)) {
            try {
                String json = Files.readString(configFile);
                @SuppressWarnings("unchecked") Map<String, Object> loaded = GSON.fromJson(json, Map.class);

                if (loaded != null) {
                    config.putAll(loaded);
                    LOGGER.info("Loaded config for mod: {}", modId);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load config for mod {}, using defaults", modId, e);
            }
        } else {
            saveConfig();
            LOGGER.info("Created default config for mod: {}", modId);
        }
    }

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

    private boolean hasConfigKey(String key) {
        return getConfigValue(key) != null;
    }

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
                    return;
                }

                @SuppressWarnings("unchecked") Map<String, Object> nextMap = (Map<String, Object>) next;
                current = nextMap;
            }

            current.remove(parts[parts.length - 1]);
        }
    }

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

            if (table.length() > 0) {
                java.util.List<Object> list = new java.util.ArrayList<>();
                for (int i = 1; i <= table.length(); i++) {
                    list.add(luaToJava(table.get(i)));
                }
                return list;
            } else {
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