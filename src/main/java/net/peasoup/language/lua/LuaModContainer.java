package net.peasoup.language.lua;

import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;

import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;

public class LuaModContainer {
    private static final Logger LOGGER = LogManager.getLogger("lua-mod-container");

    private final LuaModMetadata metadata;
    private final Path modPath;
    private final Globals globals;
    private LuaValue modTable;
    boolean hasDatagen = false;
    private final java.util.Map<String, String> itemTranslations = new java.util.HashMap<>();

    public LuaModContainer(LuaModMetadata metadata, Path modPath, Globals globals) {
        this.metadata = metadata;
        this.modPath = modPath;
        this.globals = globals;
    }

    public void loadMainScript() throws Exception {
        Path mainScript = modPath.resolve(metadata.mainScript);
        if (!Files.exists(mainScript)) {
            throw new Exception("Main script not found: " + mainScript);
        }

        // Create mod-specific globals with mod info
        setupModGlobals();

        // Load and execute the main script
        globals.load(new FileReader(mainScript.toFile()), metadata.id + ":" + metadata.mainScript).call();

        // Get the mod table if it exists
        modTable = globals.get("mod");

        LOGGER.info("Loaded main script for mod: {}", metadata.id);
    }

    public void loadLegacyScript(Path luaFile) throws Exception {
        // For legacy mods, just load the file directly
        globals.load(new FileReader(luaFile.toFile()), luaFile.getFileName().toString()).call();
        modTable = globals.get("mod");
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

        setupModGlobals();
        setupDatagenGlobals();

        globals.load(new FileReader(datagenScript.toFile()), metadata.id + ":datagen").call();
        hasDatagen = true;

        LOGGER.info("Loaded datagen script for mod: {}", metadata.id);
    }

    private void setupModGlobals() {
        // Create mod info table
        LuaTable modInfo = new LuaTable();
        modInfo.set("id", metadata.id);
        modInfo.set("name", metadata.name);
        modInfo.set("version", metadata.version);
        modInfo.set("description", metadata.description);

        globals.set("MOD_INFO", modInfo);

        // Add mod-specific resource helper
        globals.set("mod_resource", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String path = args.arg(1).tojstring();
                return LuaValue.valueOf(metadata.id + ":" + path);
            }
        });

        // Add mod path helper
        globals.set("mod_path", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String path = args.arg(1).tojstring();
                return LuaValue.valueOf(modPath.resolve(path).toString());
            }
        });
    }

    private void setupDatagenGlobals() {
        // Add datagen-specific functions
        globals.set("add_recipe", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                // TODO: Implement recipe generation
                LOGGER.info("Recipe generation requested from Lua mod: {}", metadata.id);
                return LuaValue.NIL;
            }
        });

        globals.set("add_loot_table", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                // TODO: Implement loot table generation
                LOGGER.info("Loot table generation requested from Lua mod: {}", metadata.id);
                return LuaValue.NIL;
            }
        });

        globals.set("add_tag", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                // TODO: Implement tag generation
                LOGGER.info("Tag generation requested from Lua mod: {}", metadata.id);
                return LuaValue.NIL;
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

        globals.load(new FileReader(datagenScript.toFile()), metadata.id + ":autodatagen").call();

        // Call onDatagen if it exists
        LuaValue datagenModTable = globals.get("mod");
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
        globals.set("register_item", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
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

                    // Register the item
                    net.minecraft.item.Item item = LuaItemRegistry.register(metadata.id, itemName, itemSettings);

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
                    LOGGER.error("Error registering item for mod: {}", metadata.id, e);
                }
                return LuaValue.NIL;
            }
        });

        globals.set("add_item_to_group", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    String itemName = args.arg(1).tojstring();
                    String groupName = args.arg(2).tojstring();

                    String fullItemId = metadata.id + ":" + itemName;
                    LuaItemRegistry.addToItemGroup(fullItemId, groupName);

                    LOGGER.info("Added item {} to group {}", fullItemId, groupName);
                    return LuaValue.NIL;
                } catch (Exception e) {
                    LOGGER.error("Error adding item to group for mod: {}", metadata.id, e);
                }
                return LuaValue.NIL;
            }
        });

        // Keep recipe generation for actual recipes (not items)
        globals.set("add_recipe", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    LuaTable recipe = args.arg(1).checktable();
                    writeRecipeFile(recipe, outputDir);
                } catch (Exception e) {
                    LOGGER.error("Error generating recipe for mod: {}", metadata.id, e);
                }
                return LuaValue.NIL;
            }
        });

        globals.set("add_loot_table", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    String id = args.arg(1).tojstring();
                    LuaTable lootTable = args.arg(2).checktable();
                    writeLootTableFile(id, lootTable, outputDir);
                } catch (Exception e) {
                    LOGGER.error("Error generating loot table for mod: {}", metadata.id, e);
                }
                return LuaValue.NIL;
            }
        });

        globals.set("add_tag", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    String type = args.arg(1).tojstring();
                    String id = args.arg(2).tojstring();
                    LuaTable items = args.arg(3).checktable();
                    writeTagFile(type, id, items, outputDir);
                } catch (Exception e) {
                    LOGGER.error("Error generating tag for mod: {}", metadata.id, e);
                }
                return LuaValue.NIL;
            }
        });
    }

    private void generateItemModel(String itemName, Path ignoredOutputDir) throws Exception {
        Path assetsDir = getAssetsBase();
        Path modelsDir = assetsDir.resolve("models").resolve("item");
        Files.createDirectories(modelsDir);

        // Generate item model JSON
        String modelJson = String.format("""
            {
              "parent": "minecraft:item/generated",
              "textures": {
                "layer0": "%s:item/%s"
              }
            }
            """, metadata.id, itemName);

        Path modelFile = modelsDir.resolve(itemName + ".json");
        Files.writeString(modelFile, modelJson);

        LOGGER.info("Generated item model at {}", modelFile);
    }

    private void generateItemModelDescription(String itemName, Path ignoredOutputDir) throws Exception {
        Path assetsDir = getAssetsBase();
        Path itemsDir = assetsDir.resolve("items");
        Files.createDirectories(itemsDir);

        // Generate item model description JSON
        String descriptionJson = String.format("""
            {
              "model": {
                "type": "minecraft:model",
                "model": "%s:item/%s"
              }
            }
            """, metadata.id, itemName);

        Path descriptionFile = itemsDir.resolve(itemName + ".json");
        Files.writeString(descriptionFile, descriptionJson);

        LOGGER.info("Generated item model description at {}", descriptionFile);
    }

    private void writeRecipeFile(LuaTable recipe, Path outputDir) throws Exception {
        // Extract recipe ID
        String recipeId = recipe.get("id").tojstring();
        String[] parts = recipeId.split(":");
        String namespace = parts[0];
        String name = parts[1];

        // Create recipe directory structure
        Path recipesDir = outputDir.resolve(namespace).resolve("recipes");
        Files.createDirectories(recipesDir);

        // Convert LuaTable to proper Minecraft recipe JSON
        String json = luaRecipeToMinecraftJson(recipe);
        Path recipeFile = recipesDir.resolve(name + ".json");
        Files.writeString(recipeFile, json);

        LOGGER.info("Generated recipe file: {}", recipeFile);
    }

    private String luaRecipeToMinecraftJson(LuaTable recipe) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");

        boolean first = true;

        // Handle each field specifically for Minecraft format
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = recipe.next(key);
            if ((key = next.arg1()).isnil()) break;

            String keyStr = key.tojstring();
            LuaValue value = next.arg(2);

            // Skip the 'id' field - it shouldn't be in the recipe JSON itself
            if (keyStr.equals("id")) continue;

            if (!first) json.append(",\n");

            json.append("  \"").append(keyStr).append("\": ");

            // Special handling for different recipe fields
            if (keyStr.equals("pattern") && value.istable()) {
                // Convert pattern table to array format
                json.append(luaTableToJsonArray(value.checktable()));
            } else if (keyStr.equals("result") && value.istable()) {
                // Handle result object with proper count formatting
                json.append(luaTableToJsonObjectWithNumbers(value.checktable()));
            } else {
                json.append(luaValueToJsonValue(value));
            }

            first = false;
        }

        json.append("\n}");
        return json.toString();
    }

    private String luaTableToJsonArray(LuaTable table) {
        StringBuilder json = new StringBuilder();
        json.append("[\n");

        boolean first = true;
        for (int i = 1; i <= table.length(); i++) {
            if (!first) json.append(",\n");
            json.append("    \"").append(table.get(i).tojstring()).append("\"");
            first = false;
        }

        json.append("\n  ]");
        return json.toString();
    }

    private String luaTableToJsonObjectWithNumbers(LuaTable table) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");

        boolean first = true;
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            if ((key = next.arg1()).isnil()) break;

            if (!first) json.append(",\n");

            String keyStr = key.tojstring();
            LuaValue value = next.arg(2);

            json.append("    \"").append(keyStr).append("\": ");

            // Special handling for count - should be a number, not string
            if (keyStr.equals("count") && value.isnumber()) {
                json.append(value.toint());
            } else {
                json.append(luaValueToJsonValue(value));
            }

            first = false;
        }

        json.append("\n  }");
        return json.toString();
    }

    private void writeLootTableFile(String id, LuaTable lootTable, Path outputDir) throws Exception {
        String[] parts = id.split(":");
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
        String json = luaTableToJson(lootTable);
        Path lootTableFile = lootTablesDir.resolve(pathParts[pathParts.length - 1] + ".json");
        Files.writeString(lootTableFile, json);

        LOGGER.info("Generated loot table file: {}", lootTableFile);
    }

    private void writeTagFile(String type, String id, LuaTable items, Path outputDir) throws Exception {
        String[] parts = id.split(":");
        String namespace = parts[0];
        String name = parts[1];

        // Create tag directory structure
        Path tagsDir = outputDir.resolve(namespace).resolve("tags").resolve(type);
        Files.createDirectories(tagsDir);

        // Create tag JSON structure
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"values\": [\n");

        boolean first = true;
        for (int i = 1; i <= items.length(); i++) {
            if (!first) json.append(",\n");
            json.append("    \"").append(items.get(i).tojstring()).append("\"");
            first = false;
        }

        json.append("\n  ]\n");
        json.append("}");

        Path tagFile = tagsDir.resolve(name + ".json");
        Files.writeString(tagFile, json.toString());

        LOGGER.info("Generated tag file: {}", tagFile);
    }

    private String luaTableToJson(LuaTable table) {
        // Simple Lua table to JSON converter
        StringBuilder json = new StringBuilder();
        json.append("{\n");

        boolean first = true;
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            if ((key = next.arg1()).isnil()) break;

            if (!first) json.append(",\n");

            String keyStr = key.tojstring();
            LuaValue value = next.arg(2);

            json.append("  \"").append(keyStr).append("\": ");
            json.append(luaValueToJsonValue(value));

            first = false;
        }

        json.append("\n}");
        return json.toString();
    }

    private String luaValueToJsonValue(LuaValue value) {
        if (value.isstring()) {
            return "\"" + value.tojstring() + "\"";
        } else if (value.isnumber()) {
            return value.tojstring();
        } else if (value.istable()) {
            return luaTableToJson(value.checktable());
        } else if (value.isboolean()) {
            return value.toboolean() ? "true" : "false";
        } else {
            return "null";
        }
    }

    public void generateData(FabricDataGenerator.Pack pack) {
        if (hasDatagen && modTable != null && modTable.istable()) {
            LuaValue onDatagen = modTable.get("onDatagen");
            if (onDatagen.isfunction()) {
                try {
                    LOGGER.info("Calling onDatagen for mod: {}", metadata.id);
                    // Pass pack info to Lua if needed
                    onDatagen.call();
                } catch (Exception e) {
                    LOGGER.error("Error calling onDatagen for mod: {}", metadata.id, e);
                }
            }
        }
    }

    public LuaModMetadata getMetadata() {
        return metadata;
    }

    public Path getModPath() {
        return modPath;
    }

    public boolean hasDatagen() {
        return hasDatagen || (metadata.datagen != null && metadata.datagen.enabled);
    }

    private void copyItemTexture(String itemName, Path ignoredOutputDir) throws Exception {
        Path sourceTexture = modPath.resolve("assets").resolve(metadata.id).resolve("textures").resolve("item").resolve(itemName + ".png");

        if (!Files.exists(sourceTexture)) {
            LOGGER.warn("Texture missing for {} expected at {}", itemName, sourceTexture);
            return;
        }

        Path targetDir = getAssetsBase().resolve("textures").resolve("item");
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(itemName + ".png");
        Files.copy(sourceTexture, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        LOGGER.info("Copied texture {} -> {}", sourceTexture, target);
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
            LOGGER.info("No translations to write for {}", metadata.id);
            return;
        }

        Path langDir = getAssetsBase().resolve("lang");
        Files.createDirectories(langDir);

        Path translationFile = langDir.resolve("en_us.json");
        StringBuilder json = new StringBuilder();
        json.append("{\n");

        boolean first = true;
        for (var e : itemTranslations.entrySet()) {
            if (!first) json.append(",\n");
            json.append("  \"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"");
            first = false;
        }

        json.append("\n}");
        Files.writeString(translationFile, json.toString());

        LOGGER.info("Generated translation file with {} entries: {}", itemTranslations.size(), translationFile);
    }

    private Path getProjectRoot() {
        // Working directory is 'run' when game is running, need to go up one level
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
                LOGGER.info("Migrated legacy assets folder: {} -> {}", from, to);
            }
        } catch (Exception e) {
            LOGGER.error("Error migrating legacy run/src assets for mod {}", metadata.id, e);
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
            LOGGER.warn("Datagen script not found for mod {}: {}", metadata.id, datagenScript);
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

        globals.load(new FileReader(datagenScript.toFile()), metadata.id + ":assetgen").call();

        // Call onDatagen if it exists
        LuaValue datagenModTable = globals.get("mod");
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
        globals.set("register_item", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    String itemName = args.arg(1).tojstring();
                    LuaTable settings = args.arg(2).checktable();

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

                    LOGGER.info("Pre-generated assets for item: {}:{}", metadata.id, itemName);
                    return LuaValue.NIL;
                } catch (Exception e) {
                    LOGGER.error("Error pre-generating assets for item in mod: {}", metadata.id, e);
                }
                return LuaValue.NIL;
            }
        });

        // Mock add_item_to_group - does nothing during asset generation
        globals.set("add_item_to_group", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                // Skip during asset-only generation
                return LuaValue.NIL;
            }
        });

        // Keep recipe generation
        globals.set("add_recipe", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    LuaTable recipe = args.arg(1).checktable();
                    writeRecipeFile(recipe, outputDir);
                } catch (Exception e) {
                    LOGGER.error("Error generating recipe for mod: {}", metadata.id, e);
                }
                return LuaValue.NIL;
            }
        });

        globals.set("add_loot_table", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    String id = args.arg(1).tojstring();
                    LuaTable lootTable = args.arg(2).checktable();
                    writeLootTableFile(id, lootTable, outputDir);
                } catch (Exception e) {
                    LOGGER.error("Error generating loot table for mod: {}", metadata.id, e);
                }
                return LuaValue.NIL;
            }
        });

        globals.set("add_tag", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    String type = args.arg(1).tojstring();
                    String id = args.arg(2).tojstring();
                    LuaTable items = args.arg(3).checktable();
                    writeTagFile(type, id, items, outputDir);
                } catch (Exception e) {
                    LOGGER.error("Error generating tag for mod: {}", metadata.id, e);
                }
                return LuaValue.NIL;
            }
        });
    }
}
