package net.peasoup.language.lua.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side initialization for Lua modding GUI
 */
public class LuaModdingEnvironmentClient implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("LuaModdingEnvironmentClient");

    private static KeyBinding openGuiKey;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Lua Modding Environment Client");

        // Register keybind
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lua_modding.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_L,  // Default: L key
                "category.lua_modding"
        ));

        // Register keybind handler
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new LuaModsScreen(null));
                }
            }
        });

        LOGGER.info("Lua Modding Environment Client initialized");
    }
}