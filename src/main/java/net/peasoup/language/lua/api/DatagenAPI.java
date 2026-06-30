package net.peasoup.language.lua.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
                if (!blockName.isstring()) throw new LuaError("Block name must be a string");
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
                if (!itemName.isstring()) throw new LuaError("Item name must be a string");
                generateItemModel(itemName.tojstring(), modelTable);
                return LuaValue.NIL;
            }
        });

        // datagen.recipe(recipe_name, recipe_table)
        datagen.set("recipe", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue recipeName, LuaValue recipeTable) {
                if (!recipeName.isstring()) throw new LuaError("Recipe name must be a string");
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
                if (!blockName.isstring()) throw new LuaError("Block name must be a string");
                // If they pass nil for the second argument, it passes null to Java
                String dropId = dropItem.isnil() ? null : dropItem.tojstring();
                generateBlockDrop(blockName.tojstring(), dropId);
                return LuaValue.NIL;
            }
        });

        datagen.set("mining_level", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue blockName, LuaValue item, LuaValue level) {
                if (!blockName.isstring()) throw new LuaError("Block name must be a string");
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
            Map<String, Object> blockModel = Map.of("parent", "minecraft:block/cube_all", "textures", Map.of("all", modId + ":block/" + blockName));

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

    private void addBlockToTag(Path path, String blockId) throws Exception {
        Files.createDirectories(path.getParent());
        Map<String, Object> tagData = new HashMap<>();

        // In a real scenario, we'd load the existing file and add to the list
        // For now, we'll create/overwrite for simplicity
        tagData.put("replace", false);
        tagData.put("values", java.util.List.of(blockId));

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

    private void generateItemModel(String itemName, LuaValue modelTable) {
        try {
            Map<String, Object> model = new HashMap<>();

            if (modelTable.istable()) {
                LuaTable table = modelTable.checktable();

                LuaValue parent = table.get("parent");
                model.put("parent", parent.isstring() ? parent.tojstring() : "minecraft:item/generated");

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

                    if (textureMap.isEmpty()) {
                        textureMap.put("layer0", modId + ":item/" + itemName);
                    }
                    model.put("textures", textureMap);
                } else {
                    Map<String, String> textureMap = new HashMap<>();
                    textureMap.put("layer0", modId + ":item/" + itemName);
                    model.put("textures", textureMap);
                }
            } else {
                model.put("parent", "minecraft:item/generated");
                Map<String, String> textureMap = new HashMap<>();
                textureMap.put("layer0", modId + ":item/" + itemName);
                model.put("textures", textureMap);
            }

            Path modelPath = modPath.resolve("assets/" + modId + "/models/item/" + itemName + ".json");
            Files.createDirectories(modelPath.getParent());
            Files.writeString(modelPath, GSON.toJson(model));

            LOGGER.info("Generated item model: {}", itemName);
        } catch (Exception e) {
            LOGGER.error("Failed to generate item model for {}", itemName, e);
        }
    }

    // ==========================================
    // 5. DATA & RECIPES
    // ==========================================

    public void generateRecipe(String recipeName, LuaValue recipeTable) {
        try {
            Object recipe = luaValueToNative(recipeTable);
            Path recipePath = modPath.resolve("data").resolve(modId).resolve("recipe").resolve(recipeName + ".json");

            Files.createDirectories(recipePath.getParent());
            Files.writeString(recipePath, GSON.toJson(recipe));
            LOGGER.info("Generated recipe: {}", recipeName);
        } catch (Exception e) {
            LOGGER.error("Failed to generate recipe", e);
        }
    }

    private void generateLangEntry(String langCode, String key, String value) {
        try {
            Path langPath = modPath.resolve("assets/" + modId + "/lang/" + langCode + ".json");
            Files.createDirectories(langPath.getParent());

            HashMap<Object, Object> entries = new HashMap<>();
            if (Files.exists(langPath)) {
                String json = Files.readString(langPath);
                entries = GSON.fromJson(json, HashMap.class);
            }

            entries.put(key, value);
            Files.writeString(langPath, GSON.toJson(entries));

            LOGGER.debug("Added lang entry: {} -> {} ({})", key, value, langCode);
        } catch (Exception e) {
            LOGGER.error("Failed to add lang entry", e);
        }
    }

    public void generateBlockDrop(String blockName, String dropItemId) {
        try {
            // If no drop item is specified, the block drops itself
            String finalDrop = dropItemId;
            if (finalDrop == null || finalDrop.isEmpty()) {
                finalDrop = modId + ":" + blockName;
            } else if (!finalDrop.contains(":")) {
                // If they just typed "cheese_slice", format it to "mymod:cheese_slice"
                finalDrop = modId + ":" + finalDrop;
            }

            // Build the Loot Table JSON Structure
            Map<String, Object> pool = Map.of("rolls", 1, "entries", java.util.List.of(Map.of("type", "minecraft:item", "name", finalDrop)), "conditions", java.util.List.of(Map.of("condition", "minecraft:survives_explosion")));

            Map<String, Object> root = Map.of("type", "minecraft:block", "pools", java.util.List.of(pool));

            // In 1.21.4, the folder must be exactly 'loot_table/blocks'
            Path path = modPath.resolve("data/" + modId + "/loot_table/blocks/" + blockName + ".json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));

            LOGGER.info("Generated Loot Table for {}: drops {}", blockName, finalDrop);
        } catch (Exception e) {
            LOGGER.error("Failed to generate loot table for {}", blockName, e);
        }
    }

    // ==========================================
    // 6. HELPERS
    // ==========================================

    private Object luaValueToNative(LuaValue v) {
        if (v.isboolean()) return v.toboolean();
        if (v.isnumber()) {
            double d = v.todouble();
            if (d == (int) d) return (int) d;
            return d;
        }
        if (v.isstring()) return v.tojstring();
        if (v.istable()) {
            LuaTable table = v.checktable();

            if (table.length() > 0) {
                java.util.List<Object> list = new java.util.ArrayList<>();
                for (int i = 1; i <= table.length(); i++) {
                    list.add(luaValueToNative(table.get(i)));
                }
                return list;
            }

            Map<String, Object> map = new java.util.HashMap<>();
            LuaValue k = LuaValue.NIL;
            while (true) {
                Varargs n = table.next(k);
                if ((k = n.arg1()).isnil()) break;
                map.put(k.tojstring(), luaValueToNative(n.arg(2)));
            }
            return map;
        }
        return null;
    }
}