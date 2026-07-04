package net.peasoup.luacubed;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.nio.file.Path;

public class LuaCubedDatagen {
    private static final Logger LOGGER = LogManager.getLogger("LuaCubed-PreLaunch");

    public static void generate() {
        LOGGER.info("=== luacubed datagen starting! ===");

        try {
            Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
            Path modsPath = gameDir.resolve("mods");
            
            LuaModLoader.preGenerateAssets(modsPath);
            LOGGER.info("=== luacubed asset pre-generation complete! ===");
        } catch (Exception e) {
            LOGGER.error("=== failed to pre-generate luacubed assets during pre-launch:", e, "===");
        }
    }
}
