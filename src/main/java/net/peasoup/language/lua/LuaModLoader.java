package net.peasoup.language.lua;

import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class LuaModLoader {
    private static final Logger LOGGER = LogManager.getLogger("lua-mod-loader");
    private final Map<String, LuaModContainer> loadedMods = new HashMap<>();
    private final Globals globals;
    
    public LuaModLoader(Globals globals) {
        this.globals = globals;
    }
    
    public void loadAllMods() {
        loadAllMods(false);
    }
    
    public void loadAllMods(boolean isDatagen) {
        Path modsPath = Paths.get("mods");
        if (!Files.exists(modsPath)) {
            LOGGER.warn("Mods directory not found: {}", modsPath.toAbsolutePath());
            return;
        }
        
        // Find all lua mod directories and standalone lua files
        try {
            Files.list(modsPath).forEach(path -> {
                if (Files.isDirectory(path)) {
                    loadStructuredMod(path, isDatagen);
                } else if (path.toString().endsWith(".lua")) {
                    loadLegacyMod(path);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to scan mods directory", e);
        }
        
        // Call onInitialize for all loaded mods
        if (!isDatagen) {
            for (LuaModContainer mod : loadedMods.values()) {
                mod.callInitialize();
            }
        }
    }
    
    private void loadStructuredMod(Path modPath, boolean isDatagen) {
        Path metadataPath = modPath.resolve("lua_mod.json");
        if (!Files.exists(metadataPath)) {
            LOGGER.debug("Skipping directory {} - no lua_mod.json found", modPath.getFileName());
            return;
        }
        
        try {
            String metadataJson = Files.readString(metadataPath);
            LuaModMetadata metadata = LuaModMetadata.fromJson(metadataJson);
            
            if (loadedMods.containsKey(metadata.id)) {
                LOGGER.warn("Duplicate mod ID: {}", metadata.id);
                return;
            }
            
            LuaModContainer container = new LuaModContainer(metadata, modPath, globals);
            
            if (isDatagen) {
                if (metadata.datagen != null && metadata.datagen.enabled) {
                    container.loadDatagenScript();
                }
            } else {
                container.loadMainScript();
            }
            
            loadedMods.put(metadata.id, container);
            LOGGER.info("Loaded structured Lua mod: {} v{}", metadata.name, metadata.version);
            
        } catch (Exception e) {
            LOGGER.error("Failed to load structured mod from {}", modPath, e);
        }
    }
    
    private void loadLegacyMod(Path luaFile) {
        try {
            String modId = luaFile.getFileName().toString().replace(".lua", "");
            
            // Create a legacy metadata
            LuaModMetadata metadata = new LuaModMetadata();
            metadata.id = modId;
            metadata.name = modId;
            metadata.version = "1.0.0";
            metadata.description = "Legacy Lua mod";
            
            LuaModContainer container = new LuaModContainer(metadata, luaFile.getParent(), globals);
            container.loadLegacyScript(luaFile);
            
            loadedMods.put(metadata.id, container);
            LOGGER.info("Loaded legacy Lua mod: {}", luaFile.getFileName());
            
        } catch (Exception e) {
            LOGGER.error("Failed to load legacy mod {}", luaFile, e);
        }
    }
    
    public void reloadAllMods() {
        // Clear existing mods
        loadedMods.clear();
        LuaEventBridge.clearAllHandlers();
        
        // Reload all mods
        loadAllMods();
    }
    
    public void generateData(FabricDataGenerator.Pack pack) {
        loadAllMods(true);

        for (LuaModContainer mod : loadedMods.values()) {
            if (mod.hasDatagen()) {
                mod.generateData();
            }
        }
    }
}
