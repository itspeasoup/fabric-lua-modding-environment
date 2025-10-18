package net.peasoup.language.lua;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

public class LuaModdingEnvironmentDatagen implements DataGeneratorEntrypoint {
    @Override
    public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
        // Get the Lua mod loader and generate data from all Lua mods that support datagen
        LuaModLoader luaModLoader = LuaModdingEnvironment.getLuaModLoader();
        if (luaModLoader != null) {
            luaModLoader.generateData();
        }
    }
}
