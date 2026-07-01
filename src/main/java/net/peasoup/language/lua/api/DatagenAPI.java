package net.peasoup.language.lua.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;

import java.nio.file.Files;
import java.nio.file.Path;
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

    // ==========================================
    // 1. LUA API INSTALLATION
    // ==========================================

    public void install(Globals globals) {
        LuaTable datagen = new LuaTable();

        // datagen.simple_block(block_name)
        datagen.set("simple_block", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue blockName) {
                if (!blockName.isstring())
                    throw new LuaError("Block name must be a string");
                generateSimpleBlock(blockName.tojstring());
                return LuaValue.NIL;
            }
        });

        // datagen.client_item(name, modelId)
        datagen.set("client_item", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue modelId) {
                generateClientItem(name.tojstring(), modelId.tojstring());
                return LuaValue.NIL;
            }
        });

        // datagen.item_model(item_name, model_table)
        datagen.set("item_model", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue itemName, LuaValue modelTable) {
                if (!itemName.isstring())
                    throw new LuaError("Item name must be a string");
                generateItemModel(itemName.tojstring(), modelTable);
                return LuaValue.NIL;
            }
        });

        // datagen.recipe(recipe_name, recipe_table)
        datagen.set("recipe", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue recipeName, LuaValue recipeTable) {
                if (!recipeName.isstring())
                    throw new LuaError("Recipe name must be a string");
                generateRecipe(recipeName.tojstring(), recipeTable);
                return LuaValue.NIL;
            }
        });

        // datagen.lang(lang_code, key, value)
        datagen.set("lang", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue langCode, LuaValue key, LuaValue value) {
                if (!langCode.isstring() || !key.isstring() || !value.isstring()) {
                    throw new LuaError("All arguments must be strings");
                }
                generateLangEntry(langCode.tojstring(), key.tojstring(), value.tojstring());
                return LuaValue.NIL;
            }
        });

        // datagen.block_drop("block_name", "item_to_drop")
        datagen.set("block_drop", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue blockName, LuaValue dropItem) {
                if (!blockName.isstring())
                    throw new LuaError("Block name must be a string");
                // If they pass nil for the second argument, it passes null to Java
                String dropId = dropItem.isnil() ? null : dropItem.tojstring();
                generateBlockDrop(blockName.tojstring(), dropId);
                return LuaValue.NIL;
            }
        });

        datagen.set("mining_level", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue blockName, LuaValue item, LuaValue level) {
                if (!blockName.isstring())
                    throw new LuaError("Block name must be a string");
                // If they pass nil for the second argument, it passes null to Java
                String dropId = item.isnil() ? null : item.tojstring();
                generateMiningTag(blockName.tojstring(), dropId, level.toint());
                return LuaValue.NIL;
            }
        });

        globals.set("datagen", datagen);
    }

    // ==========================================
    // 2. PACK METADATA
    // ==========================================

    public void generatePackMetadata(String description) {
        try {
            Path metaPath = modPath.resolve("pack.mcmeta");
            Map<String, Object> root = new HashMap<>();
            Map<String, Object> pack = new HashMap<>();

            pack.put("pack_format", 46); // 1.21.4 standard
            pack.put("description", description);
            root.put("pack", pack);

            Files.writeString(metaPath, GSON.toJson(root));
            LOGGER.info("Generated pack.mcmeta for mod: {}", modId);
        } catch (Exception e) {
            LOGGER.error("Failed to generate pack.mcmeta", e);
        }
    }

    // ==========================================
    // 3. BLOCK GENERATION
    // ==========================================

    public void generateSimpleBlock(String blockName) {
        try {
            // 1. BlockState (assets/modid/blockstates/blockName.json)
            Map<String, Object> variants = Map.of("", Map.of("model", modId + ":block/" + blockName));
            Map<String, Object> blockState = Map.of("variants", variants);

            Path statePath = modPath.resolve("assets/" + modId + "/blockstates/" + blockName + ".json");
            Files.createDirectories(statePath.getParent());
            Files.writeString(statePath, GSON.toJson(blockState));

            // 2. Block Model (assets/modid/models/block/blockName.json)
            Map<String, Object> blockModel = Map.of("parent", "minecraft:block/cube_all", "textures",
                    Map.of("all", modId + ":block/" + blockName));

            Path bModelPath = modPath.resolve("assets/" + modId + "/models/block/" + blockName + ".json");
            Files.createDirectories(bModelPath.getParent());
            Files.writeString(bModelPath, GSON.toJson(blockModel));

            // 3. Item Model (assets/modid/models/item/blockName.json)
            Map<String, Object> itemModel = Map.of("parent", modId + ":block/" + blockName);

            Path iModelPath = modPath.resolve("assets/" + modId + "/models/item/" + blockName + ".json");
            Files.createDirectories(iModelPath.getParent());
            Files.writeString(iModelPath, GSON.toJson(itemModel));

            // 4. Client Item Definition (1.21.4 Requirement for the BlockItem)
            // Inside generateSimpleBlock(...)
            generateClientItem(blockName, modId + ":block/" + blockName);

            // THE NEW AUTOMATION: Automatically make it drop itself!
            generateBlockDrop(blockName, null);

            LOGGER.info("Generated BlockState & Models for: {}", blockName);
        } catch (Exception e) {
            LOGGER.error("Failed to generate block resources for {}", blockName, e);
        }
    }

    public void generateMiningTag(String blockName, String tool, int level) {
        try {
            // 1. Tool type tag (e.g., mineable/pickaxe)
            Path toolTagPath = modPath.resolve("data/minecraft/tags/block/mineable/" + tool + ".json");
            addBlockToTag(toolTagPath, modId + ":" + blockName);

            // 2. Mining level tag (e.g., needs_iron_tool)
            String levelTag = switch (level) {
                case 1 -> "needs_stone_tool";
                case 2 -> "needs_iron_tool";
                case 3 -> "needs_diamond_tool";
                default -> null;
            };

            if (levelTag != null) {
                Path levelTagPath = modPath.resolve("data/minecraft/tags/block/" + levelTag + ".json");
                addBlockToTag(levelTagPath, modId + ":" + blockName);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to generate mining tags", e);
        }
    }

    private synchronized void addBlockToTag(Path path, String blockId) throws Exception {
        Files.createDirectories(path.getParent());
        JsonObject tagData;

        if (Files.exists(path)) {
            try {
                tagData = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            } catch (Exception e) {
                tagData = new JsonObject();
            }
        } else {
            tagData = new JsonObject();
        }

        if (!tagData.has("replace")) {
            tagData.addProperty("replace", false);
        }

        JsonArray valuesArray;
        if (tagData.has("values")) {
            valuesArray = tagData.getAsJsonArray("values");
        } else {
            valuesArray = new JsonArray();
            tagData.add("values", valuesArray);
        }

        // Check if this optional entry object structure already exists
        boolean alreadyExists = false;
        for (JsonElement el : valuesArray) {
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("id") && obj.get("id").getAsString().equals(blockId)) {
                    alreadyExists = true;
                    break;
                }
            }
        }

        if (!alreadyExists) {
            // THE FIX: Structure the tag entry as an object instead of a raw string literal
            JsonObject optionalEntry = new JsonObject();
            optionalEntry.addProperty("id", blockId);
            optionalEntry.addProperty("required", false); // Stops Minecraft from disabling the pack!
            valuesArray.add(optionalEntry);
        }

        Files.writeString(path, GSON.toJson(tagData));
    }

    // ==========================================
    // 4. ITEM GENERATION
    // ==========================================

    public void generateClientItem(String name, String modelId) {
        try {
            Path dir = modPath.resolve("assets").resolve(modId).resolve("items");
            Files.createDirectories(dir);

            Map<String, Object> root = new HashMap<>();
            Map<String, String> modelMap = new HashMap<>();

            modelMap.put("type", "minecraft:model");
            modelMap.put("model", modelId);
            root.put("model", modelMap);

            Files.writeString(dir.resolve(name + ".json"), GSON.toJson(root));
            LOGGER.info("Generated client item definition: {}", name);
        } catch (Exception e) {
            LOGGER.error("Failed to generate client item", e);
        }
    }

    public void generateItemModel(String itemName, LuaValue modelTable) {
        try {
            Object nativeModelData = convertLuaToJson(modelTable);
            Path modelPath = modPath.resolve("assets/" + modId + "/models/item/" + itemName + ".json");

            Files.createDirectories(modelPath.getParent());
            Files.writeString(modelPath, GSON.toJson(nativeModelData));
        } catch (Exception e) {
            LOGGER.error("Failed to generate item model for {}", itemName, e);
        }
    }

    // ==========================================
    // 5. DATA & RECIPES
    // ==========================================

    public void generateRecipe(String recipeName, LuaValue recipeTable) {
        try {
            // 1. Convert Lua to Java Maps/Lists using your method
            Object nativeRecipeData = convertLuaToJson(recipeTable);
            Path recipePath = modPath.resolve("data/" + modId + "/recipe/" + recipeName + ".json");

            // 2. GSON converts the native Map/List straight to clean JSON string
            Files.createDirectories(recipePath.getParent());
            Files.writeString(recipePath, GSON.toJson(nativeRecipeData));
            LOGGER.info("Generated dynamic recipe data: {}:{}", modId, recipeName);
        } catch (Exception e) {
            LOGGER.error("Failed to generate recipe for {}", recipeName, e);
        }
    }

    public synchronized void generateLangEntry(String langCode, String key, String value) {
        try {
            Path langPath = modPath.resolve("assets/" + modId + "/lang/" + langCode + ".json");
            Files.createDirectories(langPath.getParent());

            JsonObject langData = new JsonObject();
            if (Files.exists(langPath)) {
                try {
                    langData = JsonParser.parseString(Files.readString(langPath)).getAsJsonObject();
                } catch (Exception ignored) {
                }
            }

            langData.addProperty(key, value);
            Files.writeString(langPath, GSON.toJson(langData));
        } catch (Exception e) {
            LOGGER.error("Failed to append lang entry", e);
        }
    }

    public void generateBlockDrop(String blockName, String dropItem) {
        try {
            Path lootPath = modPath.resolve("data/" + modId + "/loot_table/blocks/" + blockName + ".json");
            Files.createDirectories(lootPath.getParent());

            String finalDrop = (dropItem == null) ? modId + ":" + blockName : dropItem;

            // Clean raw json injection matching standard Mojang loot tables
            String rawLootJson = """
                    {
                      "type": "minecraft:block",
                      "pools": [
                        {
                          "rolls": 1.0,
                          "bonus_rolls": 0.0,
                          "entries": [
                            {
                              "type": "minecraft:item",
                              "name": "%s"
                            }
                          ],
                          "conditions": [
                            {
                              "condition": "minecraft:survives_explosion"
                            }
                          ]
                        }
                      ]
                    }
                    """.formatted(finalDrop);

            Files.writeString(lootPath, rawLootJson);
        } catch (Exception e) {
            LOGGER.error("Failed to generate block drop loot table", e);
        }
    }
    // ==========================================
    // 6. HELPERS
    // ==========================================

    private Object convertLuaToJson(LuaValue v) {
        if (v.isboolean())
            return v.toboolean();
        if (v.isnumber()) {
            double d = v.todouble();
            if (d == (int) d)
                return (int) d;
            return d;
        }
        if (v.isstring())
            return v.tojstring();
        if (v.istable()) {
            LuaTable table = v.checktable();

            if (table.length() > 0) {
                java.util.List<Object> list = new java.util.ArrayList<>();
                for (int i = 1; i <= table.length(); i++) {
                    list.add(convertLuaToJson(table.get(i)));
                }
                return list;
            }

            Map<String, Object> map = new java.util.HashMap<>();
            LuaValue k = LuaValue.NIL;
            while (true) {
                Varargs n = table.next(k);
                if ((k = n.arg1()).isnil())
                    break;
                map.put(k.tojstring(), convertLuaToJson(n.arg(2)));
            }
            return map;
        }
        return null;
    }
}