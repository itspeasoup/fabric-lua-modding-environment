package net.peasoup.luacubed;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.nio.file.Path;

public class LuaCubedDatagen implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LogManager.getLogger("LuaCubed-PreLaunch");

    @Override
    public void onPreLaunch() {
        LOGGER.info("=== luacubed datagen starting! ===");

        try {
            // Fabric Loader provides a reliable way to get the game directory
            Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
            Path modsPath = gameDir.resolve("mods");
            
            LuaModLoader.preGenerateAssets(modsPath);
            LOGGER.info("=== luacubed asset pre-generation complete! ===");
        } catch (Exception e) {
            LOGGER.error("=== failed to pre-generate luacubed assets during pre-launch:", e, "===");
        }
    }
}
