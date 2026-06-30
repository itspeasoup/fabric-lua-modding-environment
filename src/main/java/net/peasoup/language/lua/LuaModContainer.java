package net.peasoup.language.lua;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;

import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;

public class LuaModContainer {
    private static final Logger LOGGER = LogManager.getLogger("lua-mod-container");
    private static final Gson GSON = new Gson();

    private final LuaModMetadata metadata;
    private final Path modPath;
    private final Globals parentGlobals;
    private Globals modGlobals;  // Per-mod isolated globals
    private LuaValue modTable;
    boolean hasDatagen = false;
    private final java.util.Map<String, String> itemTranslations = new java.util.HashMap<>();

    public LuaModContainer(LuaModMetadata metadata, Path modPath, Globals parentGlobals) {
        this.metadata = metadata;
        this.modPath = modPath;
        this.parentGlobals = parentGlobals;
    }

    public void loadMainScript() throws Exception {
        Path mainScript = modPath.resolve(metadata.mainScript);
        if (!Files.exists(mainScript)) {
            throw new Exception("Main script not found: " + mainScript);
        }

        // Create mod-specific isolated globals
        createModGlobals();

        // Load and execute the main script
        modGlobals.load(new FileReader(mainScript.toFile()), metadata.id + ":" + metadata.mainScript).call();

        // Get the mod table if it exists
        modTable = modGlobals.get("mod");

        LOGGER.info("Loaded main script for mod: {}", metadata.id);
    }

    public void loadLegacyScript(Path luaFile) throws Exception {
        // For legacy mods, create isolated globals
        createModGlobals();
        modGlobals.load(new FileReader(luaFile.toFile()), luaFile.getFileName().toString()).call();
        modTable = modGlobals.get("mod");
        LOGGER.info("Loaded legacy script: {}", luaFile.getFileName());
    }

    public void loadDatagenScript() throws Exception {
        if (metadata.datagen == null || !metadata.datagen.enabled) {
            return;
        }

        Path datagenScript = modPath.resolve(metadata.datagen.datagenScript);
        if (!Files.exists(datagenScript)) {
            LOGGER.warn("Datagen script not found for mod {}: {}", metadata.id, datagenScript);
            return;
        }

        createModGlobals();
        setupDatagenGlobals();

        modGlobals.load(new FileReader(datagenScript.toFile()), metadata.id + ":datagen").call();
        hasDatagen = true;

        LOGGER.info("Loaded datagen script for mod: {}", metadata.id);
    }

    /**
     * Create isolated Lua globals for this mod.
     * Prevents mods from interfering with each other's namespaces.
     */
    private void createModGlobals() {
        if (modGlobals == null) {
            modGlobals = new Globals();
            // Copy sandbox-safe libraries from parent
            modGlobals.load(new org.luaj.vm2.lib.jse.JsePlatform().standardGlobals(), false);
            setupModGlobals();
        }
    }

    private void setupModGlobals() {
        // Create mod info table
        LuaTable modInfo = new LuaTable();
        modInfo.set("id", metadata.id);
        modInfo.set("name", metadata.name);
        modInfo.set("version", metadata.version);
        modInfo.set("description", metadata.description);

        modGlobals.set("MOD_INFO", modInfo);

        // Add mod-specific resource helper
        modGlobals.set("mod_resource", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 1) {
                        LOGGER.error("mod_resource requires 1 argument, got {}", args.narg());
                        return LuaValue.NIL;
                    }
                    String path = args.arg(1).tojstring();
                    return LuaValue.valueOf(metadata.id + ":" + path);
                } catch (Exception e) {
                    LOGGER.error("Error in mod_resource: {}", e.getMessage());
                    return LuaValue.NIL;
                }
            }
        });

        // Add mod path helper
        modGlobals.set("mod_path", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 1) {
                        LOGGER.error("mod_path requires 1 argument, got {}", args.narg());
                        return LuaValue.NIL;
                    }
                    String path = args.arg(1).tojstring();
                    return LuaValue.valueOf(modPath.resolve(path).toString());
                } catch (Exception e) {
                    LOGGER.error("Error in mod_path: {}", e.getMessage());
                    return LuaValue.NIL;
                }
            }
        });

        // Redirect print to logger
        modGlobals.set("print", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= args.narg(); i++) {
                    if (i > 1) sb.append(" ");
                    try {
                        sb.append(args.arg(i).tojstring());
                    } catch (Exception e) {
                        sb.append("<error>");
                    }
                }
                LOGGER.info("[{}] {}", metadata.id, sb.toString());
                return LuaValue.NIL;
            }
        });
    }

    private void setupDatagenGlobals() {
        // Add datagen-specific functions
        modGlobals.set("add_recipe", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 1) {
                        LOGGER.error("[{}] add_recipe requires 1 argument", metadata.id);
                        return LuaValue.NIL;
                    }
                    LuaTable recipe = args.arg(1).checktable();
                    LOGGER.info("[{}] Recipe generation requested", metadata.id);
                    return LuaValue.NIL;
                } catch (Exception e) {
                    LOGGER.error("[{}] Error in add_recipe: {}", metadata.id, e.getMessage());
                    return LuaValue.NIL;
                }
            }
        });

        modGlobals.set("add_loot_table", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 1) {
                        LOGGER.error("[{}] add_loot_table requires 1 argument", metadata.id);
                        return LuaValue.NIL;
                    }
                    LOGGER.info("[{}] Loot table generation requested", metadata.id);
                    return LuaValue.NIL;
                } catch (Exception e) {
                    LOGGER.error("[{}] Error in add_loot_table: {}", metadata.id, e.getMessage());
                    return LuaValue.NIL;
                }
            }
        });

        modGlobals.set("add_tag", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 1) {
                        LOGGER.error("[{}] add_tag requires 1 argument", metadata.id);
                        return LuaValue.NIL;
                    }
                    LOGGER.info("[{}] Tag generation requested", metadata.id);
                    return LuaValue.NIL;
                } catch (Exception e) {
                    LOGGER.error("[{}] Error in add_tag: {}", metadata.id, e.getMessage());
                    return LuaValue.NIL;
                }
            }
        });
    }

    public void callInitialize() {
        if (modTable != null && modTable.istable()) {
            LuaValue onInit = modTable.get("onInitialize");
            if (onInit.isfunction()) {
                try {
                    LOGGER.info("Calling onInitialize for mod: {}", metadata.id);
                    onInit.call();
                } catch (Exception e) {
                    LOGGER.error("Error calling onInitialize for mod: {}", metadata.id, e);
                }
            }
        }

        // Automatically run datagen if enabled
        if (metadata.datagen != null && metadata.datagen.enabled) {
            try {
                autoGenerateData();
            } catch (Exception e) {
                LOGGER.error("Error running auto-datagen for mod: {}", metadata.id, e);
            }
        }
    }

    private void autoGenerateData() throws Exception {
        LOGGER.info("Auto-generating data for mod: {}", metadata.id);
        // Migrate any old assets first
        migrateLegacyRunSrcAssets();

        Path datagenScript = modPath.resolve(metadata.datagen.datagenScript);
        if (!Files.exists(datagenScript)) {
            LOGGER.warn("Datagen script not found for mod {}: {}", metadata.id, datagenScript);
            return;
        }

        Path projectRoot = getProjectRoot();
        LOGGER.info("Resolved project root for {} as {}", metadata.id, projectRoot);

        // Data goes to src/main/generated/data (NOT inside resources)
        Path outputDir = projectRoot.resolve("src").resolve("main").resolve("generated").resolve("data");
        Files.createDirectories(outputDir);
        LOGGER.info("Data generation output dir for {}: {}", metadata.id, outputDir);

        setupModGlobals();
        setupAutoDatagenGlobals(outputDir);

        modGlobals.load(new FileReader(datagenScript.toFile()), metadata.id + ":autodatagen").call();

        // Call onDatagen if it exists
        LuaValue datagenModTable = modGlobals.get("mod");
        if (datagenModTable != null && datagenModTable.istable()) {
            LuaValue onDatagen = datagenModTable.get("onDatagen");
            if (onDatagen.isfunction()) {
                LOGGER.info("Calling onDatagen for mod: {}", metadata.id);
                onDatagen.call();
            }
        }

        // Generate translation file after all items are registered
        generateTranslations(outputDir);

        LOGGER.info("Auto-datagen completed for mod: {} - files written to {}", metadata.id, outputDir);
    }

    private void setupAutoDatagenGlobals(Path outputDir) {
        // Add actual item registration functions
        modGlobals.set("register_item", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 2) {
                        LOGGER.error("[{}] register_item requires 2 arguments, got {}", metadata.id, args.narg());
                        return LuaValue.NIL;
                    }

                    String itemName = args.arg(1).tojstring();
                    LuaTable settings = args.arg(2).checktable();

                    // Create item settings
                    net.minecraft.item.Item.Settings itemSettings = new net.minecraft.item.Item.Settings();

                    // Apply settings from Lua table
                    if (settings.get("max_count") != LuaValue.NIL) {
                        itemSettings.maxCount(settings.get("max_count").toint());
                    }
                    if (settings.get("fireproof") != LuaValue.NIL && settings.get("fireproof").toboolean()) {
                        itemSettings.fireproof();
                    }

                    // Copy texture from Lua mod assets to main mod resources
                    copyItemTexture(itemName, outputDir);

                    // Automatically generate model and model description files
                    generateItemModel(itemName, outputDir);
                    generateItemModelDescription(itemName, outputDir);

                    // Generate translation key and display name
                    String translationKey = "item." + metadata.id + "." + itemName;
                    String displayName = generateDisplayName(itemName);
                    itemTranslations.put(translationKey, displayName);

                    LOGGER.info("Registered item: {}:{}", metadata.id, itemName);
                    return LuaValue.NIL;
                } catch (Exception e) {
                    LOGGER.error("[{}] Error registering item: {}", metadata.id, e.getMessage());
                }
                return LuaValue.NIL;
            }
        });

        modGlobals.set("add_item_to_group", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 2) {
                        LOGGER.error("[{}] add_item_to_group requires 2 arguments", metadata.id);
                        return LuaValue.NIL;
                    }

                    String itemName = args.arg(1).tojstring();
                    String groupName = args.arg(2).tojstring();

                    String fullItemId = metadata.id + ":" + itemName;
                    LuaItemRegistry.addToItemGroup(fullItemId, groupName);

                    LOGGER.info("[{}] Added item {} to group {}", metadata.id, fullItemId, groupName);
                    return LuaValue.NIL;
                } catch (Exception e) {
                    LOGGER.error("[{}] Error adding item to group: {}", metadata.id, e.getMessage());
                }
                return LuaValue.NIL;
            }
        });

        // Keep recipe generation for actual recipes (not items)
        modGlobals.set("add_recipe", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 1) {
                        LOGGER.error("[{}] add_recipe requires 1 argument", metadata.id);
                        return LuaValue.NIL;
                    }

                    LuaTable recipe = args.arg(1).checktable();
                    writeRecipeFile(recipe, outputDir);
                } catch (Exception e) {
                    LOGGER.error("[{}] Error generating recipe: {}", metadata.id, e.getMessage());
                }
                return LuaValue.NIL;
            }
        });

        modGlobals.set("add_loot_table", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 2) {
                        LOGGER.error("[{}] add_loot_table requires 2 arguments", metadata.id);
                        return LuaValue.NIL;
                    }

                    String id = args.arg(1).tojstring();
                    LuaTable lootTable = args.arg(2).checktable();
                    writeLootTableFile(id, lootTable, outputDir);
                } catch (Exception e) {
                    LOGGER.error("[{}] Error generating loot table: {}", metadata.id, e.getMessage());
                }
                return LuaValue.NIL;
            }
        });

        modGlobals.set("add_tag", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 3) {
                        LOGGER.error("[{}] add_tag requires 3 arguments", metadata.id);
                        return LuaValue.NIL;
                    }

                    String type = args.arg(1).tojstring();
                    String id = args.arg(2).tojstring();
                    LuaTable items = args.arg(3).checktable();
                    writeTagFile(type, id, items, outputDir);
                } catch (Exception e) {
                    LOGGER.error("[{}] Error generating tag: {}", metadata.id, e.getMessage());
                }
                return LuaValue.NIL;
            }
        });
    }

    private void generateItemModel(String itemName, Path ignoredOutputDir) throws Exception {
        Path assetsDir = getAssetsBase();
        Path modelsDir = assetsDir.resolve("models").resolve("item");
        Files.createDirectories(modelsDir);

        // Generate item model JSON using Gson
        JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:item/generated");
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", metadata.id + ":item/" + itemName);
        model.add("textures", textures);

        Path modelFile = modelsDir.resolve(itemName + ".json");
        Files.writeString(modelFile, GSON.toJson(model));

        LOGGER.info("Generated item model at {}", modelFile);
    }

    private void generateItemModelDescription(String itemName, Path ignoredOutputDir) throws Exception {
        Path assetsDir = getAssetsBase();
        Path itemsDir = assetsDir.resolve("items");
        Files.createDirectories(itemsDir);

        // Generate item model description JSON using Gson
        JsonObject root = new JsonObject();
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", metadata.id + ":item/" + itemName);
        root.add("model", model);

        Path descriptionFile = itemsDir.resolve(itemName + ".json");
        Files.writeString(descriptionFile, GSON.toJson(root));

        LOGGER.info("Generated item model description at {}", descriptionFile);
    }

    private void writeRecipeFile(LuaTable recipe, Path outputDir) throws Exception {
        try {
            // Extract recipe ID
            LuaValue idValue = recipe.get("id");
            if (idValue.isnil()) {
                LOGGER.error("[{}] Recipe missing 'id' field", metadata.id);
                return;
            }

            String recipeId = idValue.tojstring();
            String[] parts = recipeId.split(":");
            if (parts.length != 2) {
                LOGGER.error("[{}] Invalid recipe ID format: {}", metadata.id, recipeId);
                return;
            }

            String namespace = parts[0];
            String name = parts[1];

            // Create recipe directory structure
            Path recipesDir = outputDir.resolve(namespace).resolve("recipes");
            Files.createDirectories(recipesDir);

            // Convert LuaTable to proper Minecraft recipe JSON
            String json = luaRecipeToMinecraftJson(recipe);
            Path recipeFile = recipesDir.resolve(name + ".json");
            Files.writeString(recipeFile, json);

            LOGGER.info("[{}] Generated recipe file: {}", metadata.id, recipeFile);
        } catch (Exception e) {
            LOGGER.error("[{}] Error writing recipe file: {}", metadata.id, e.getMessage());
        }
    }

    private String luaRecipeToMinecraftJson(LuaTable recipe) {
        JsonObject json = new JsonObject();

        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = recipe.next(key);
            if ((key = next.arg1()).isnil()) break;

            String keyStr = key.tojstring();
            LuaValue value = next.arg(2);

            // Skip the 'id' field - it shouldn't be in the recipe JSON itself
            if (keyStr.equals("id")) continue;

            // Special handling for different recipe fields
            if (keyStr.equals("pattern") && value.istable()) {
                json.add(keyStr, luaTableToJsonArray(value.checktable()));
            } else if (keyStr.equals("result") && value.istable()) {
                json.add(keyStr, luaTableToJsonObjectWithNumbers(value.checktable()));
            } else {
                json.add(keyStr, luaValueToJsonElement(value));
            }
        }

        return GSON.toJson(json);
    }

    private JsonArray luaTableToJsonArray(LuaTable table) {
        JsonArray arr = new JsonArray();
        for (int i = 1; i <= table.length(); i++) {
            LuaValue val = table.get(i);
            arr.add(val.tojstring());
        }
        return arr;
    }

    private JsonObject luaTableToJsonObjectWithNumbers(LuaTable table) {
        JsonObject obj = new JsonObject();

        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            if ((key = next.arg1()).isnil()) break;

            String keyStr = key.tojstring();
            LuaValue value = next.arg(2);

            // Special handling for count - should be a number, not string
            if (keyStr.equals("count") && value.isnumber()) {
                obj.addProperty(keyStr, value.toint());
            } else {
                obj.add(keyStr, luaValueToJsonElement(value));
            }
        }

        return obj;
    }

    private com.google.gson.JsonElement luaValueToJsonElement(LuaValue value) {
        if (value.isstring()) {
            return new com.google.gson.JsonPrimitive(value.tojstring());
        } else if (value.isnumber()) {
            return new com.google.gson.JsonPrimitive(value.tonumber());
        } else if (value.istable()) {
            return jsonObjectFromLuaTable(value.checktable());
        } else if (value.isboolean()) {
            return new com.google.gson.JsonPrimitive(value.toboolean());
        } else {
            return com.google.gson.JsonNull.INSTANCE;
        }
    }

    private JsonObject jsonObjectFromLuaTable(LuaTable table) {
        JsonObject obj = new JsonObject();

        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            if ((key = next.arg1()).isnil()) break;
            obj.add(key.tojstring(), luaValueToJsonElement(next.arg(2)));
        }

        return obj;
    }

    private void writeLootTableFile(String id, LuaTable lootTable, Path outputDir) throws Exception {
        try {
            String[] parts = id.split(":");
            if (parts.length != 2) {
                LOGGER.error("[{}] Invalid loot table ID format: {}", metadata.id, id);
                return;
            }

            String namespace = parts[0];
            String path = parts[1];

            // Create loot table directory structure (note: loot_tables is plural)
            Path lootTablesDir = outputDir.resolve(namespace).resolve("loot_tables");
            String[] pathParts = path.split("/");
            for (int i = 0; i < pathParts.length - 1; i++) {
                lootTablesDir = lootTablesDir.resolve(pathParts[i]);
            }
            Files.createDirectories(lootTablesDir);

            // Convert LuaTable to JSON and write file
            String json = GSON.toJson(jsonObjectFromLuaTable(lootTable));
            Path lootTableFile = lootTablesDir.resolve(pathParts[pathParts.length - 1] + ".json");
            Files.writeString(lootTableFile, json);

            LOGGER.info("[{}] Generated loot table file: {}", metadata.id, lootTableFile);
        } catch (Exception e) {
            LOGGER.error("[{}] Error writing loot table file: {}", metadata.id, e.getMessage());
        }
    }

    private void writeTagFile(String type, String id, LuaTable items, Path outputDir) throws Exception {
        try {
            String[] parts = id.split(":");
            if (parts.length != 2) {
                LOGGER.error("[{}] Invalid tag ID format: {}", metadata.id, id);
                return;
            }

            String namespace = parts[0];
            String name = parts[1];

            // Create tag directory structure
            Path tagsDir = outputDir.resolve(namespace).resolve("tags").resolve(type);
            Files.createDirectories(tagsDir);

            // Create tag JSON structure
            JsonObject tagJson = new JsonObject();
            JsonArray values = new JsonArray();

            for (int i = 1; i <= items.length(); i++) {
                values.add(items.get(i).tojstring());
            }

            tagJson.add("values", values);

            Path tagFile = tagsDir.resolve(name + ".json");
            Files.writeString(tagFile, GSON.toJson(tagJson));

            LOGGER.info("[{}] Generated tag file: {}", metadata.id, tagFile);
        } catch (Exception e) {
            LOGGER.error("[{}] Error writing tag file: {}", metadata.id, e.getMessage());
        }
    }

    public void generateData() {
        if (hasDatagen && modTable != null && modTable.istable()) {
            LuaValue onDatagen = modTable.get("onDatagen");
            if (onDatagen.isfunction()) {
                try {
                    LOGGER.info("Calling onDatagen for mod: {}", metadata.id);
                    onDatagen.call();
                } catch (Exception e) {
                    LOGGER.error("Error calling onDatagen for mod: {}", metadata.id, e);
                }
            }
        }
    }

    public boolean hasDatagen() {
        return hasDatagen || (metadata.datagen != null && metadata.datagen.enabled);
    }

    private void copyItemTexture(String itemName, Path ignoredOutputDir) throws Exception {
        Path sourceTexture = modPath.resolve("assets").resolve(metadata.id).resolve("textures").resolve("item").resolve(itemName + ".png");

        if (!Files.exists(sourceTexture)) {
            LOGGER.warn("[{}] Texture missing, expected at {}", metadata.id, sourceTexture);
            return;
        }

        Path targetDir = getAssetsBase().resolve("textures").resolve("item");
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(itemName + ".png");
        Files.copy(sourceTexture, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        LOGGER.info("[{}] Copied texture {} -> {}", metadata.id, sourceTexture, target);
    }

    private String generateDisplayName(String itemName) {
        // Convert snake_case to Title Case
        // e.g., "super_coal" -> "Super Coal"
        String[] words = itemName.split("_");
        StringBuilder displayName = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            if (i > 0) displayName.append(" ");
            String word = words[i];
            if (!word.isEmpty()) {
                displayName.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    displayName.append(word.substring(1).toLowerCase());
                }
            }
        }

        return displayName.toString();
    }

    private void generateTranslations(Path ignoredOutputDir) throws Exception {
        if (itemTranslations.isEmpty()) {
            LOGGER.info("[{}] No translations to write", metadata.id);
            return;
        }

        Path langDir = getAssetsBase().resolve("lang");
        Files.createDirectories(langDir);

        JsonObject translationObj = new JsonObject();
        for (var e : itemTranslations.entrySet()) {
            translationObj.addProperty(e.getKey(), e.getValue());
        }

        Path translationFile = langDir.resolve("en_us.json");
        Files.writeString(translationFile, GSON.toJson(translationObj));

        LOGGER.info("[{}] Generated translation file with {} entries: {}", metadata.id, itemTranslations.size(), translationFile);
    }

    /**
     * Get the project root directory using FabricLoader's game directory.
     * Falls back to build.gradle detection if needed.
     */
    private Path getProjectRoot() {
        try {
            // Try using FabricLoader first (most reliable)
            Path gameDir = FabricLoader.getInstance().getGameDir();
            if (gameDir != null && Files.exists(gameDir.resolve("build.gradle"))) {
                LOGGER.info("Using FabricLoader game directory as project root: {}", gameDir);
                return gameDir;
            }
        } catch (Exception e) {
            LOGGER.debug("FabricLoader game dir unavailable: {}", e.getMessage());
        }

        // Fallback: Working directory detection
        Path cwd = Path.of(System.getProperty("user.dir"));

        // If we're in the 'run' folder, go up to parent
        if (cwd.getFileName() != null && cwd.getFileName().toString().equals("run")) {
            Path parent = cwd.getParent();
            if (parent != null && Files.exists(parent.resolve("build.gradle"))) {
                LOGGER.info("Detected we're in run folder, using parent as project root: {}", parent);
                return parent;
            }
        }

        // Check if current dir has build.gradle
        if (Files.exists(cwd.resolve("build.gradle"))) {
            LOGGER.info("Using current directory as project root: {}", cwd);
            return cwd;
        }

        // Fallback: walk up from modPath
        Path current = modPath;
        while (current != null) {
            if (Files.exists(current.resolve("build.gradle"))) {
                LOGGER.info("Found project root by walking up from modPath: {}", current);
                return current;
            }
            current = current.getParent();
        }

        LOGGER.warn("Could not find project root, falling back to cwd: {}", cwd);
        return cwd; // ultimate fallback
    }

    private Path getAssetsBase() {
        Path root = getProjectRoot();
        Path assetsBase = root.resolve("src").resolve("main").resolve("resources").resolve("assets").resolve(metadata.id);
        LOGGER.debug("[{}] assetsBase resolved to: {}", metadata.id, assetsBase);
        return assetsBase;
    }

    private void migrateLegacyRunSrcAssets() {
        try {
            Path legacyAssets = getProjectRoot()
                    .resolve("run")
                    .resolve("src")
                    .resolve("main")
                    .resolve("resources")
                    .resolve("assets")
                    .resolve(metadata.id);
            if (!Files.exists(legacyAssets)) {
                return; // nothing to migrate
            }
            Path targetAssets = getAssetsBase();
            Files.createDirectories(targetAssets);
            // Copy selected subfolders
            String[] sub = {"models", "items", "textures", "lang"};
            for (String s : sub) {
                Path from = legacyAssets.resolve(s);
                if (!Files.exists(from)) continue;
                Path to = targetAssets.resolve(s);
                Files.createDirectories(to);
                try (var stream = Files.walk(from)) {
                    stream.filter(Files::isRegularFile).forEach(src -> {
                        try {
                            Path rel = from.relativize(src);
                            Path dest = to.resolve(rel);
                            Files.createDirectories(dest.getParent());
                            Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e) {
                            LOGGER.error("Failed copying legacy asset {}", src, e);
                        }
                    });
                }
                LOGGER.info("[{}] Migrated legacy assets folder: {} -> {}", metadata.id, from, to);
            }
        } catch (Exception e) {
            LOGGER.error("[{}] Error migrating legacy run/src assets", metadata.id, e);
        }
    }

    /**
     * Generate ONLY asset files (textures, models, translations) without registering items.
     * This is used during pre-initialization to ensure assets are available before resource loading.
     */
    public void generateAssetsOnly() throws Exception {
        LOGGER.info("Pre-generating assets for mod: {}", metadata.id);

        // Migrate any old assets first
        migrateLegacyRunSrcAssets();

        Path datagenScript = modPath.resolve(metadata.datagen.datagenScript);
        if (!Files.exists(datagenScript)) {
            LOGGER.warn("[{}] Datagen script not found", metadata.id);
            return;
        }

        Path projectRoot = getProjectRoot();
        LOGGER.info("Resolved project root for {} as {}", metadata.id, projectRoot);

        // Data goes to src/main/generated/data (NOT inside resources)
        Path outputDir = projectRoot.resolve("src").resolve("main").resolve("generated").resolve("data");
        Files.createDirectories(outputDir);
        LOGGER.info("Asset generation output dir for {}: {}", metadata.id, outputDir);

        setupModGlobals();
        setupAssetOnlyDatagenGlobals(outputDir);

        modGlobals.load(new FileReader(datagenScript.toFile()), metadata.id + ":assetgen").call();

        // Call onDatagen if it exists
        LuaValue datagenModTable = modGlobals.get("mod");
        if (datagenModTable != null && datagenModTable.istable()) {
            LuaValue onDatagen = datagenModTable.get("onDatagen");
            if (onDatagen.isfunction()) {
                LOGGER.info("Calling onDatagen (asset-only mode) for mod: {}", metadata.id);
                onDatagen.call();
            }
        }

        // Generate translation file after all items are processed
        generateTranslations(outputDir);

        LOGGER.info("Asset pre-generation completed for mod: {} - files written to {}", metadata.id, outputDir);
    }

    /**
     * Setup globals for asset-only generation - this SKIPS actual item registration
     */
    private void setupAssetOnlyDatagenGlobals(Path outputDir) {
        // Add mock item registration that only generates assets
        modGlobals.set("register_item", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 1) {
                        LOGGER.error("[{}] register_item requires 1 argument", metadata.id);
                        return LuaValue.NIL;
                    }

                    String itemName = args.arg(1).tojstring();

                    // DON'T register the item - just generate assets
                    // Copy texture from Lua mod assets to main mod resources
                    copyItemTexture(itemName, outputDir);

                    // Automatically generate model and model description files
                    generateItemModel(itemName, outputDir);
                    generateItemModelDescription(itemName, outputDir);

                    // Generate translation key and display name
                    String translationKey = "item." + metadata.id + "." + itemName;
                    String displayName = generateDisplayName(itemName);
                    itemTranslations.put(translationKey, displayName);

                    LOGGER.info("[{}] Pre-generated assets for item", metadata.id);
                    return LuaValue.NIL;
                } catch (Exception e) {
                    LOGGER.error("[{}] Error pre-generating assets: {}", metadata.id, e.getMessage());
                }
                return LuaValue.NIL;
            }
        });

        // Mock add_item_to_group - does nothing during asset generation
        modGlobals.set("add_item_to_group", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                // Skip during asset-only generation
                return LuaValue.NIL;
            }
        });

        // Keep recipe generation
        modGlobals.set("add_recipe", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 1) {
                        LOGGER.error("[{}] add_recipe requires 1 argument", metadata.id);
                        return LuaValue.NIL;
                    }

                    LuaTable recipe = args.arg(1).checktable();
                    writeRecipeFile(recipe, outputDir);
                } catch (Exception e) {
                    LOGGER.error("[{}] Error generating recipe: {}", metadata.id, e.getMessage());
                }
                return LuaValue.NIL;
            }
        });

        modGlobals.set("add_loot_table", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 2) {
                        LOGGER.error("[{}] add_loot_table requires 2 arguments", metadata.id);
                        return LuaValue.NIL;
                    }

                    String id = args.arg(1).tojstring();
                    LuaTable lootTable = args.arg(2).checktable();
                    writeLootTableFile(id, lootTable, outputDir);
                } catch (Exception e) {
                    LOGGER.error("[{}] Error generating loot table: {}", metadata.id, e.getMessage());
                }
                return LuaValue.NIL;
            }
        });

        modGlobals.set("add_tag", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    if (args.narg() < 3) {
                        LOGGER.error("[{}] add_tag requires 3 arguments", metadata.id);
                        return LuaValue.NIL;
                    }

                    String type = args.arg(1).tojstring();
                    String id = args.arg(2).tojstring();
                    LuaTable items = args.arg(3).checktable();
                    writeTagFile(type, id, items, outputDir);
                } catch (Exception e) {
                    LOGGER.error("[{}] Error generating tag: {}", metadata.id, e.getMessage());
                }
                return LuaValue.NIL;
            }
        });
    }
}
