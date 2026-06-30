package net.peasoup.language.lua.resource;

import net.minecraft.resource.ResourceType;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.DirectoryResourcePack;
import net.minecraft.text.Text;
import net.peasoup.language.lua.LuaModdingEnvironment;
import net.peasoup.language.lua.LuaModContainer;

import java.util.function.Consumer;

public class LuaModPackProvider implements ResourcePackProvider {
    private final ResourceType type;

    // ResourceType tells us if Minecraft is currently asking for CLIENT_RESOURCES (assets) or SERVER_DATA (data)
    public LuaModPackProvider(ResourceType type) {
        this.type = type;
    }

    @Override
    public void register(Consumer<ResourcePackProfile> profileAdder) {
        var modLoader = LuaModdingEnvironment.getModLoader();
        if (modLoader == null) return;

        for (LuaModContainer mod : modLoader.getLoadedMods().values()) {
            if (!mod.hasDatagen()) continue;

            String packId = "lua_mod_" + mod.getMetadata().id;
            Text packName = Text.literal("Lua Mod: " + mod.getMetadata().name);

            // Create a virtual profile for this folder
            ResourcePackProfile profile = ResourcePackProfile.create(
                    packId,
                    packName,
                    true, // Always enabled by default
                    (name) -> new DirectoryResourcePack(name, mod.getModPath(), true), // Wraps the folder
                    this.type,
                    ResourcePackProfile.InsertionPosition.TOP, // Load on top of vanilla files
                    ResourcePackSource.BUILTIN // Treat it like a built-in mod
            );

            if (profile != null) {
                profileAdder.accept(profile);
            }
        }
    }
}