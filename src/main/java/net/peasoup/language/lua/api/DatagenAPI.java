package net.peasoup.language.lua.api;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.ThreeArgFunction;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Lua API for generating data (recipes, loot tables, models, etc.)
 */
public class DatagenAPI {
    private static final Logger LOGGER = LogManager.getLogger("DatagenAPI");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String modId;
    private final Path modPath;

    public DatagenAPI(String modId, Path modPath) {
        this.modId = modId;
        this.modPath = modPath;
    }

    /**
     * Install this API into a Lua globals table
     */
    public void install(Globals globals) {
        LuaTable datagen = new LuaTable();

        // datagen.item_model(item_name, model_table)
        datagen.set("item_model", new org.luaj.vm2.lib.TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue itemName, LuaValue modelTable) {
                if (!itemName.isstring()) {
                    throw new LuaError("Item name must be a string");
                }
                generateItemModel(itemName.tojstring(), modelTable);
                return LuaValue.NIL;
            }
        });

        // datagen.recipe(recipe_name, recipe_table)
        datagen.set("recipe", new org.luaj.vm2.lib.TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue recipeName, LuaValue recipeTable) {
                if (!recipeName.isstring()) {
                    throw new LuaError("Recipe name must be a string");
                }
                generateRecipe(recipeName.tojstring(), recipeTable);
                return LuaValue.NIL;
            }
        });

        // datagen.lang_entry(lang_code, key, value)
        datagen.set("lang_entry", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue langCode, LuaValue key, LuaValue value) {
                if (!langCode.isstring() || !key.isstring() || !value.isstring()) {
                    throw new LuaError("All arguments must be strings");
                }
                addLangEntry(langCode.tojstring(), key.tojstring(), value.tojstring());
                return LuaValue.NIL;
            }
        });

        globals.set("datagen", datagen);
    }

    private void generateItemModel(String itemName, LuaValue modelTable) {
        try {
            // Build the model JSON
            Map<String, Object> model = new HashMap<>();

            if (modelTable.istable()) {
                LuaTable table = modelTable.checktable();

                // Get parent (default to "item/generated")
                LuaValue parent = table.get("parent");
                model.put("parent", parent.isstring() ? parent.tojstring() : "minecraft:item/generated");

                // Get textures
                LuaValue textures = table.get("textures");
                if (textures.istable()) {
                    Map<String, String> textureMap = new HashMap<>();
                    LuaTable textureTable = textures.checktable();

                    LuaValue k = LuaValue.NIL;
                    while (true) {
                        Varargs n = textureTable.next(k);
                        if ((k = n.arg1()).isnil()) break;
                        LuaValue v = n.arg(2);
                        if (k.isstring() && v.isstring()) {
                            textureMap.put(k.tojstring(), v.tojstring());
                        }
                    }

                    // Default to layer0 if no textures specified
                    if (textureMap.isEmpty()) {
                        textureMap.put("layer0", modId + ":item/" + itemName);
                    }

                    model.put("textures", textureMap);
                } else {
                    // Default texture
                    Map<String, String> textureMap = new HashMap<>();
                    textureMap.put("layer0", modId + ":item/" + itemName);
                    model.put("textures", textureMap);
                }
            } else {
                // Default model
                model.put("parent", "minecraft:item/generated");
                Map<String, String> textureMap = new HashMap<>();
                textureMap.put("layer0", modId + ":item/" + itemName);
                model.put("textures", textureMap);
            }

            // Write the model file
            Path modelPath = modPath.resolve("assets/" + modId + "/models/item/" + itemName + ".json");
            Files.createDirectories(modelPath.getParent());
            Files.writeString(modelPath, GSON.toJson(model));

            LOGGER.info("Generated item model: {}", itemName);

        } catch (Exception e) {
            LOGGER.error("Failed to generate item model for {}", itemName, e);
        }
    }

    private void generateRecipe(String recipeName, LuaValue recipeTable) {
        try {
            if (!recipeTable.istable()) {
                LOGGER.error("Recipe data must be a table");
                return;
            }

            // Convert Lua table to Map
            Map<String, Object> recipe = luaTableToMap(recipeTable.checktable());

            // Write the recipe file
            Path recipePath = modPath.resolve("data/" + modId + "/recipe/" + recipeName + ".json");
            Files.createDirectories(recipePath.getParent());
            Files.writeString(recipePath, GSON.toJson(recipe));

            LOGGER.info("Generated recipe: {}", recipeName);

        } catch (Exception e) {
            LOGGER.error("Failed to generate recipe {}", recipeName, e);
        }
    }

    private void addLangEntry(String langCode, String key, String value) {
        try {
            Path langPath = modPath.resolve("assets/" + modId + "/lang/" + langCode + ".json");
            Files.createDirectories(langPath.getParent());

            // Load existing entries or create new
            Map<String, String> entries = new HashMap<>();
            if (Files.exists(langPath)) {
                String json = Files.readString(langPath);
                entries = GSON.fromJson(json, HashMap.class);
            }

            // Add new entry
            entries.put(key, value);

            // Write back
            Files.writeString(langPath, GSON.toJson(entries));

            LOGGER.debug("Added lang entry: {} -> {} ({})", key, value, langCode);

        } catch (Exception e) {
            LOGGER.error("Failed to add lang entry", e);
        }
    }

    private Map<String, Object> luaTableToMap(LuaTable table) {
        Map<String, Object> map = new HashMap<>();

        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs n = table.next(k);
            if ((k = n.arg1()).isnil()) break;
            LuaValue v = n.arg(2);

            String key = k.tojstring();
            Object value;

            if (v.istable()) {
                value = luaTableToMap(v.checktable());
            } else if (v.isstring()) {
                value = v.tojstring();
            } else if (v.isnumber()) {
                value = v.todouble();
            } else if (v.isboolean()) {
                value = v.toboolean();
            } else {
                value = v.tojstring();
            }

            map.put(key, value);
        }

        return map;
    }
}