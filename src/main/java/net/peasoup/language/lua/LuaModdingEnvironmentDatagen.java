package net.peasoup.language.lua;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Datagen entry point for Lua mods
 */
public class LuaModdingEnvironmentDatagen implements DataGeneratorEntrypoint {
    private static final Logger LOGGER = LogManager.getLogger("LuaModdingEnvironmentDatagen");

    @Override
    public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
        LOGGER.info("Running Lua mod datagen");

        // Get or create the mod loader
        LuaModLoader modLoader = LuaModdingEnvironment.getModLoader();

        if (modLoader == null) {
            // If the main mod hasn't initialized yet, create a temporary loader
            modLoader = new LuaModLoader();
            modLoader.discoverAndLoadMods();
        }

        // Run datagen for all mods
        modLoader.runDatagen();

        LOGGER.info("Lua mod datagen complete");
    }
}