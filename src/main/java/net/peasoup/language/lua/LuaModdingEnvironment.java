package net.peasoup.language.lua;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.peasoup.language.lua.api.DatagenAPI;
import net.peasoup.language.lua.api.EventAPI;
import net.peasoup.language.lua.api.JavaAPI;
import net.peasoup.language.lua.api.RegistryAPI;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main entry point for the Lua modding environment
 * Enhanced with better commands, diagnostics, and error handling
 */
public class LuaModdingEnvironment implements ModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("LuaModdingEnvironment");
    private static final Globals CMD_GLOBALS = JsePlatform.standardGlobals();
    private static final String CONSOLE_MOD_ID = "lua_console";
    private static LuaModLoader modLoader;

    // Static initializer - runs before mod initialization
    static {
        LOGGER.info("===========================================");
        LOGGER.info("  Lua Modding Environment Starting Up");
        LOGGER.info("===========================================");

        LOGGER.info("Pre-generating Lua mod assets...");
        try {
            Path modsPath = Paths.get("mods");
            LuaModLoader.preGenerateAssets(modsPath);
            LOGGER.info("Asset pre-generation complete");
        } catch (Exception e) {
            LOGGER.error("Failed to pre-generate assets", e);
        }
    }

    public static LuaModLoader getModLoader() {
        return modLoader;
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Lua Modding Environment");

        // Register all known Fabric events
        EventAPI.registerKnownEvents();

        // Create mod loader and discover mods
        modLoader = new LuaModLoader();
        modLoader.discoverAndLoadMods();

        // Initialize all mods
        modLoader.initializeAllMods();

        // Setup console environment
        setupConsoleEnvironment();

        // Register commands
        registerCommands();

        LOGGER.info("===========================================");
        LOGGER.info("  Lua Modding Environment Initialized");
        LOGGER.info("  Loaded Mods: {}", modLoader.getLoadedMods().size());
        LOGGER.info("===========================================");
    }

    /**
     * Setup the Lua console environment with APIs
     */
    private void setupConsoleEnvironment() {
        Path consolePath = Paths.get("mods", CONSOLE_MOD_ID);

        // Inject standard variables
        CMD_GLOBALS.set("MOD_ID", LuaValue.valueOf(CONSOLE_MOD_ID));
        CMD_GLOBALS.set("MOD_PATH", LuaValue.valueOf(consolePath.toString()));

        // Inject APIs
        new JavaAPI(true).install(CMD_GLOBALS);
        new RegistryAPI(CONSOLE_MOD_ID).install(CMD_GLOBALS);
        new DatagenAPI(CONSOLE_MOD_ID, consolePath).install(CMD_GLOBALS);
        new EventAPI().install(CMD_GLOBALS);

        LOGGER.debug("Console environment configured");
    }

    /**
     * Register all Lua-related commands
     */
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // /lua <code> - Execute Lua code
            dispatcher.register(CommandManager.literal("lua").requires(source -> source.hasPermissionLevel(2)).then(CommandManager.argument("code", StringArgumentType.greedyString()).executes(this::executeLuaCode)));

            // /luamods - List loaded mods
            dispatcher.register(CommandManager.literal("luamods").requires(source -> source.hasPermissionLevel(0)).executes(this::listMods));

            // /luamods reload - Reload all mods
            dispatcher.register(CommandManager.literal("luamods").requires(source -> source.hasPermissionLevel(3)).then(CommandManager.literal("reload").executes(this::reloadAllMods)));

            // /luamods reload <modid> - Reload specific mod
            dispatcher.register(CommandManager.literal("luamods").requires(source -> source.hasPermissionLevel(3)).then(CommandManager.literal("reload").then(CommandManager.argument("modid", StringArgumentType.word()).executes(this::reloadSpecificMod))));

            // /luamods info <modid> - Show mod info
            dispatcher.register(CommandManager.literal("luamods").requires(source -> source.hasPermissionLevel(0)).then(CommandManager.literal("info").then(CommandManager.argument("modid", StringArgumentType.word()).executes(this::showModInfo))));

            // /luamods diagnostics - Show diagnostic information
            dispatcher.register(CommandManager.literal("luamods").requires(source -> source.hasPermissionLevel(2)).then(CommandManager.literal("diagnostics").executes(this::showDiagnostics)));

            // /luamods disable <modid> - Disable a mod
            dispatcher.register(CommandManager.literal("luamods").requires(source -> source.hasPermissionLevel(3)).then(CommandManager.literal("disable").then(CommandManager.argument("modid", StringArgumentType.word()).executes(this::disableMod))));
        });

        LOGGER.debug("Registered Lua commands");
    }

    /**
     * Execute Lua code from command
     */
    private int executeLuaCode(CommandContext<ServerCommandSource> context) {
        String code = StringArgumentType.getString(context, "code");

        try {
            LuaValue chunk;
            try {
                // Try evaluating it as an expression first
                chunk = CMD_GLOBALS.load("return " + code);
            } catch (Exception e) {
                // If that fails, load normally
                chunk = CMD_GLOBALS.load(code);
            }

            // Execute and get the result
            LuaValue result = chunk.call();

            // Format the result nicely
            String output = result.isnil() ? "Execution successful (no return value)" : result.tojstring();

            context.getSource().sendFeedback(() -> Text.literal("§a[Lua Result]: §f" + output), false);

        } catch (Exception e) {
            context.getSource().sendError(Text.literal("§c[Lua Error]: §f" + e.getMessage()));
            LOGGER.error("Error executing Lua command", e);
        }

        return 1;
    }

    /**
     * List all loaded mods
     */
    private int listMods(CommandContext<ServerCommandSource> context) {
        if (modLoader.getLoadedMods().isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("§eNo Lua mods loaded"), false);
            return 0;
        }

        context.getSource().sendFeedback(() -> Text.literal("§a=== Loaded Lua Mods (" + modLoader.getLoadedMods().size() + ") ==="), false);

        for (LuaModContainer mod : modLoader.getLoadedMods().values()) {
            LuaModMetadata meta = mod.getMetadata();
            String status = mod.hasCrashed() ? "§c[CRASHED]" : "§a[OK]";

            context.getSource().sendFeedback(() -> Text.literal(String.format("%s §f%s v%s §7(%s)", status, meta.name, meta.version, meta.id)), false);
        }

        return 1;
    }

    /**
     * Reload all mods
     */
    private int reloadAllMods(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(() -> Text.literal("§eReloading all Lua mods..."), true);

        try {
            modLoader.reloadAllMods();

            context.getSource().sendFeedback(() -> Text.literal("§aSuccessfully reloaded all mods"), true);
            return 1;

        } catch (Exception e) {
            context.getSource().sendError(Text.literal("§cFailed to reload mods: " + e.getMessage()));
            LOGGER.error("Failed to reload mods", e);
            return 0;
        }
    }

    /**
     * Reload a specific mod
     */
    private int reloadSpecificMod(CommandContext<ServerCommandSource> context) {
        String modId = StringArgumentType.getString(context, "modid");

        context.getSource().sendFeedback(() -> Text.literal("§ereloading mod: " + modId), true);

        if (modLoader.reloadMod(modId)) {
            context.getSource().sendFeedback(() -> Text.literal("§asuccessfully reloaded mod: " + modId), true);
            return 1;
        } else {
            context.getSource().sendError(Text.literal("§cfailed to reload mod: " + modId));
            return 0;
        }
    }

    /**
     * Show information about a specific mod
     */
    private int showModInfo(CommandContext<ServerCommandSource> context) {
        String modId = StringArgumentType.getString(context, "modid");
        LuaModContainer mod = modLoader.getMod(modId);

        if (mod == null) {
            context.getSource().sendError(Text.literal("§cmod not found: " + modId));
            return 0;
        }

        LuaModMetadata meta = mod.getMetadata();

        context.getSource().sendFeedback(() -> Text.literal("§a=== mod Info: " + meta.name + " ==="), false);

        context.getSource().sendFeedback(() -> Text.literal("§7ID: §f" + meta.id), false);

        context.getSource().sendFeedback(() -> Text.literal("§7version: §f" + meta.version), false);

        context.getSource().sendFeedback(() -> Text.literal("§7description: §f" + meta.description), false);

        if (meta.authors.length > 0) {
            context.getSource().sendFeedback(() -> Text.literal("§7authors: §f" + String.join(", ", meta.authors)), false);
        }

        context.getSource().sendFeedback(() -> Text.literal("§7status: §f" + mod.getState()), false);

        if (mod.hasCrashed()) {
            context.getSource().sendFeedback(() -> Text.literal("§c⚠ mod has crashed with " + mod.getErrors().size() + " error(s)"), false);

            for (LuaModContainer.ModError error : mod.getErrors()) {
                context.getSource().sendFeedback(() -> Text.literal("§c  - " + error.toString()), false);
            }
        }

        if (!meta.dependencies.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("§7dependencies:"), false);

            for (LuaModMetadata.Dependency dep : meta.dependencies) {
                String depInfo = String.format("  - %s %s %s", dep.id, dep.version, dep.required ? "(required)" : "(optional)");
                context.getSource().sendFeedback(() -> Text.literal("§7" + depInfo), false);
            }
        }

        return 1;
    }

    /**
     * Show diagnostic information
     */
    private int showDiagnostics(CommandContext<ServerCommandSource> context) {
        String diagnostics = modLoader.getDiagnostics();

        for (String line : diagnostics.split("\n")) {
            context.getSource().sendFeedback(() -> Text.literal(line), false);
        }

        return 1;
    }

    /**
     * Disable a mod
     */
    private int disableMod(CommandContext<ServerCommandSource> context) {
        String modId = StringArgumentType.getString(context, "modid");
        LuaModContainer mod = modLoader.getMod(modId);

        if (mod == null) {
            context.getSource().sendError(Text.literal("§cmod not found: " + modId));
            return 0;
        }

        mod.disable();

        context.getSource().sendFeedback(() -> Text.literal("§edisabled mod: " + modId), true);

        return 1;
    }
}