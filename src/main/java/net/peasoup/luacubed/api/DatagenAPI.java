package net.peasoup.luacubed.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.MinecraftVersion;
import net.minecraft.resource.ResourceType;
import net.peasoup.luacubed.resource.LuaModPackProvider;

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

public class DatagenAPI {
    private static final Logger LOGGER = LogManager.getLogger("DatagenAPI");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String modId;
    private Path modPath;

    public DatagenAPI(String modId, Path modPath) {
        this.modId = modId;
        this.modPath = modPath;
    }

    public void install(Globals globals) {
        LuaTable datagen = new LuaTable();

        datagen.set("simple_block", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue blockName) {
                if (!blockName.isstring())
                    throw new LuaError("Block name must be a string");
                generateSimpleBlock(blockName.tojstring());
                return LuaValue.NIL;
            }
        });

        datagen.set("simple_item", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue itemName) {
                if (!itemName.isstring())
                    throw new LuaError("Item name must be a string");
                generateSimpleItem(itemName.tojstring());
                return LuaValue.NIL;
            }
        });

        datagen.set("recipe", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue recipeName, LuaValue recipeTable) {
                if (!recipeName.isstring())
                    throw new LuaError("Recipe name must be a string");
                generateRecipe(recipeName.tojstring(), recipeTable);
                return LuaValue.NIL;
            }
        });

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

        datagen.set("block_drop", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue blockName, LuaValue dropItem) {
                if (!blockName.isstring())
                    throw new LuaError("Block name must be a string");
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
                String dropId = item.isnil() ? null : item.tojstring();
                generateMiningTag(blockName.tojstring(), dropId, level.toint());
                return LuaValue.NIL;
            }
        });

        globals.set("datagen", datagen);
    }

    public void generatePackMetadata(String description) {
        LOGGER.info("generating pack metadata for mod: {}", modId);
        try {
            Path metaPath = modPath.resolve("pack.mcmeta");
            Map<String, Object> root = new HashMap<>();
            Map<String, Object> pack = new HashMap<>();

            int packVersionId = MinecraftVersion.create().packVersion(ResourceType.CLIENT_RESOURCES);

            pack.put("pack_format", packVersionId);
            pack.put("description", description);

            pack.put("supported_formats", java.util.List.of(packVersionId, 999));

            root.put("pack", pack);

            String json = GSON.toJson(root);

            LuaModPackProvider.addVirtualFile(ResourceType.CLIENT_RESOURCES, modId, "pack.mcmeta", json);

            Files.writeString(metaPath, json);
            LOGGER.info("Generated clean compliant pack.mcmeta for mod: {}", modId);
        } catch (Exception e) {
            LOGGER.error("Failed to generate pack.mcmeta", e);
        }
    }

    public void generateSimpleBlock(String blockName) {
        try {
            // 1. Block Model (assets/fuck/models/block/snowscug.json)
            Map<String, Object> blockModel = Map.of(
                "parent", "minecraft:block/cube_all", 
                "textures", Map.of("all", modId + ":block/" + blockName)
            );
            saveAsset(modId, "models/block/" + blockName + ".json", GSON.toJson(blockModel), true);

            // 2. Blockstate (assets/fuck/blockstates/snowscug.json)
            Map<String, Object> variants = Map.of("", Map.of("model", modId + ":block/" + blockName));
            Map<String, Object> blockState = Map.of("variants", variants);
            saveAsset(modId, "blockstates/" + blockName + ".json", GSON.toJson(blockState), true);

            // 3. Item Model (assets/fuck/models/item/snowscug.json)
            Map<String, Object> itemModel = Map.of("parent", modId + ":block/" + blockName);
            saveAsset(modId, "models/item/" + blockName + ".json", GSON.toJson(itemModel), true);

            // 4. FIXED: Modern Client Item Definition (assets/fuck/items/snowscug.json)
            // Points straight to your models/item/ folder structure natively!
            Map<String, Object> itemDef = Map.of(
                "model", Map.of(
                    "type", "minecraft:model", 
                    "model", modId + ":item/" + blockName // FIXED: Handshake links to models/item/ structure
                )
            );
            saveAsset(modId, "items/" + blockName + ".json", GSON.toJson(itemDef), true); // FIXED: Outputs to plural items/

            // Auto loot table configuration
            generateBlockDrop(blockName, null);

            LOGGER.info("Generated BlockState, Models & Item Def for: {}", blockName);
        } catch (Exception e) {
            LOGGER.error("Failed to generate block resources for {}", blockName, e);
        }
    }

        public void generateSimpleItem(String itemName) {
        try {
            // 1. Model Layout (assets/fuck/models/item/cocaine.json)
            Map<String, Object> itemModel = Map.of(
                "parent", "item/generated", 
                "textures", Map.of("layer0", modId + ":item/" + itemName)
            );
            saveAsset(modId, "models/item/" + itemName + ".json", GSON.toJson(itemModel), true);

            // 2. FIXED: Modern Client Item Definition (assets/fuck/items/cocaine.json)
            Map<String, Object> itemDef = Map.of(
                "model", Map.of(
                    "type", "minecraft:model", 
                    "model", modId + ":item/" + itemName // FIXED: Corrected path maps to models/item/
                )
            );
            saveAsset(modId, "items/" + itemName + ".json", GSON.toJson(itemDef), true); // FIXED: Matches your exact plural tree output

            LOGGER.info("Generated simple item model and def for: {}", itemName);
        } catch (Exception e) {
            LOGGER.error("Failed to generate item model for {}", itemName, e);
        }
    }

    public void generateRecipe(String recipeName, LuaValue recipeTable) {
        try {
            Object nativeRecipeData = convertLuaToJson(recipeTable);
            saveAsset(modId, "recipe/" + recipeName + ".json", GSON.toJson(nativeRecipeData), false);
            LOGGER.info("Generated dynamic recipe data: {}:{}", modId, recipeName);
        } catch (Exception e) {
            LOGGER.error("Failed to generate recipe for {}", recipeName, e);
        }
    }

    public void generateMiningTag(String blockName, String tool, int level) {
        try {
            Path toolTagPath = modPath.resolve("data/minecraft/tags/block/mineable/" + tool + ".json");
            addBlockToTag(toolTagPath, modId + ":" + blockName);

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
            JsonObject optionalEntry = new JsonObject();
            optionalEntry.addProperty("id", blockId);
            optionalEntry.addProperty("required", false);
            valuesArray.add(optionalEntry);
        }

        Files.writeString(path, GSON.toJson(tagData));
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
            String finalDrop = (dropItem == null) ? modId + ":" + blockName : dropItem;
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

            saveAsset(modId, "loot_table/blocks/" + blockName + ".json", rawLootJson, false);
        } catch (Exception e) {
            LOGGER.error("Failed to generate block drop loot table", e);
        }
    }

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

    private void saveAsset(String namespace, String path, String json, boolean isClient) {
        try {
            net.minecraft.resource.ResourceType type = isClient ? net.minecraft.resource.ResourceType.CLIENT_RESOURCES
                    : net.minecraft.resource.ResourceType.SERVER_DATA;

            LuaModPackProvider.addVirtualFile(type, namespace, path, json);
        } catch (Throwable t) {
            LOGGER.error("Failed to add to virtual cache", t);
        }

        try {
            String dirKey = isClient ? "assets" : "data";
            Path fullPath = modPath.resolve(dirKey + "/" + namespace + "/" + path);
            Files.createDirectories(fullPath.getParent());
            Files.writeString(fullPath, json);
        } catch (Exception e) {
            LOGGER.error("Failed to write {} to disk", path, e);
        }
    }

    public void setModPath(Path newPath) {
        this.modPath = newPath;
    }

}
