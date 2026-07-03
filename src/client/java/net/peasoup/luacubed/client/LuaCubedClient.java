package net.peasoup.luacubed.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import net.peasoup.luacubed.LuaCubed;
import net.peasoup.luacubed.LuaModContainer;

/**
 * Client-side initialization for Lua modding GUI
 */
public class LuaCubedClient implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("LuaModdingEnvironmentClient");

    private static KeyBinding openGuiKey;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Lua Modding Environment Client");

        // Register keybind
        openGuiKey = KeyBindingHelper
                .registerKeyBinding(new KeyBinding("key.lua_modding.open_gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, // Default:
                                                                                                                       // K
                                                                                                                       // key
                        "category.lua_modding"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Tick all loaded mods
            if (LuaCubed.getModLoader() != null) {
                for (LuaModContainer mod : LuaCubed.getModLoader().getLoadedMods().values()) {
                    mod.tick();
                }
            }

            while (openGuiKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new LuaModsScreen(null));
                }
            }
        });

        // Replace your old HudRenderCallback snippet with this:
        HudElementRegistry.addLast(
                Identifier.of("luacubed", "notification_overlay"),
                (context, deltaTracker) -> {
                    NotificationRenderer.render(context, NotificationRenderer.Corner.TOP_RIGHT);
                });

        // Layer 2: Renders on top of ALL inventory / menu screens AFTER they are drawn!
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.afterRender(screen).register((screenInstance, drawContext, mouseX, mouseY, tickDelta) -> {
                NotificationRenderer.render(drawContext, NotificationRenderer.Corner.TOP_RIGHT);
            });
        });

        LOGGER.info("Lua Modding Environment Client initialized");
    }
}