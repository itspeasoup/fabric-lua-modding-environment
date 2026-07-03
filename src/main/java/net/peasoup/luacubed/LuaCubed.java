package net.peasoup.luacubed;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.peasoup.luacubed.api.DatagenAPI;
import net.peasoup.luacubed.api.EventAPI;
import net.peasoup.luacubed.api.JavaAPI;
import net.peasoup.luacubed.api.RegistryAPI;

public class LuaCubed implements ModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("LuaCubed");
    private static final Globals CMD_GLOBALS = JsePlatform.standardGlobals();
    private static final String CONSOLE_MOD_ID = "lua_console";
    private static LuaModLoader modLoader;

    public static LuaModLoader getModLoader() {
        return modLoader;
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Lua Modding Environment");

        EventAPI.registerKnownEvents();

        modLoader = new LuaModLoader();
        modLoader.discoverAndLoadMods();
        modLoader.initializeAllMods();

        setupConsoleEnvironment();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommandsShared(dispatcher);
        });

        printCompletionLog();
    }

    public static void printCompletionLog() {
        LOGGER.info("========================");
        LOGGER.info("  LuaCubed initialized");
        LOGGER.info("  Loaded Mods: {}", modLoader.getLoadedMods().size());
        LOGGER.info("========================");
    }

    private static void setupConsoleEnvironment() {
        Path consolePath = Paths.get("mods", CONSOLE_MOD_ID);

        CMD_GLOBALS.set("MOD_ID", LuaValue.valueOf(CONSOLE_MOD_ID));
        CMD_GLOBALS.set("MOD_PATH", LuaValue.valueOf(consolePath.toString()));

        new JavaAPI(true).install(CMD_GLOBALS);
        new RegistryAPI(CONSOLE_MOD_ID).install(CMD_GLOBALS);
        new DatagenAPI(CONSOLE_MOD_ID, consolePath).install(CMD_GLOBALS);
        new EventAPI(CONSOLE_MOD_ID).install(CMD_GLOBALS);

        for (Map.Entry<String, String> entry : LuaGlobalRegistry.getShortcuts().entrySet()) {
            try {
                Class<?> clazz = Class.forName(entry.getValue());
                CMD_GLOBALS.set(entry.getKey(), JavaAPI.wrapClass(clazz, true));
            } catch (ClassNotFoundException ignored) {
            }
        }

        LOGGER.debug("Console environment configured");
    }

    public static void registerCommandsShared(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("lua")
                .requires(source -> source.hasPermissionLevel(4))
                .then(CommandManager.argument("code", StringArgumentType.greedyString())
                        .executes(LuaCubed::executeLuaCode)));

        dispatcher.register(CommandManager.literal("luamods")
                .requires(source -> source.hasPermissionLevel(0))
                .executes(LuaCubed::listMods));

        dispatcher.register(CommandManager.literal("luamods")
                .requires(source -> source.hasPermissionLevel(3))
                .then(CommandManager.literal("reload")
                        .executes(LuaCubed::reloadAllMods)
                        .then(CommandManager.argument("modid", StringArgumentType.word())
                                .executes(LuaCubed::reloadSpecificMod))));

        dispatcher.register(CommandManager.literal("luamods")
                .requires(source -> source.hasPermissionLevel(0))
                .then(CommandManager.literal("info")
                        .then(CommandManager.argument("modid", StringArgumentType.word())
                                .executes(LuaCubed::showModInfo))));

        dispatcher.register(CommandManager.literal("luamods")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("diagnostics")
                        .executes(LuaCubed::showDiagnostics)));

        dispatcher.register(CommandManager.literal("luamods")
                .requires(source -> source.hasPermissionLevel(3))
                .then(CommandManager.literal("disable")
                        .then(CommandManager.argument("modid", StringArgumentType.word())
                                .executes(LuaCubed::disableMod))));

        LOGGER.debug("Registered Lua commands universally");
    }

    private static int executeLuaCode(CommandContext<ServerCommandSource> context) {
        String code = StringArgumentType.getString(context, "code");
        try {
            LuaValue chunk;
            try {
                chunk = CMD_GLOBALS.load("return " + code);
            } catch (Exception e) {
                chunk = CMD_GLOBALS.load(code);
            }

            LuaValue result = chunk.call();
            String output = result.isnil() ? "Execution successful (no return value)" : result.tojstring();
            context.getSource().sendFeedback(() -> Text.literal("§a[Lua Result]: §f" + output), false);
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("§c[Lua Error]: §f" + e.getMessage()));
            LOGGER.error("Error executing Lua command", e);
        }
        return 1;
    }

    private static int listMods(CommandContext<ServerCommandSource> context) {
        if (modLoader.getLoadedMods().isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("§eNo Lua mods loaded"), false);
            return 0;
        }

        context.getSource().sendFeedback(
                () -> Text.literal("§a=== Loaded Lua Mods (" + modLoader.getLoadedMods().size() + ") ==="), false);
        for (LuaModContainer mod : modLoader.getLoadedMods().values()) {
            LuaModMetadata meta = mod.getMetadata();
            String status = mod.hasCrashed() ? "§c[CRASHED]" : "§a[OK]";
            context.getSource().sendFeedback(
                    () -> Text.literal(String.format("%s §f%s v%s §7(%s)", status, meta.name, meta.version, meta.id)),
                    false);
        }
        return 1;
    }

    private static int reloadAllMods(CommandContext<ServerCommandSource> context) {
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

    private static int reloadSpecificMod(CommandContext<ServerCommandSource> context) {
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

    private static int showModInfo(CommandContext<ServerCommandSource> context) {
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
            context.getSource().sendFeedback(() -> Text.literal("§7authors: §f" + String.join(", ", meta.authors)),
                    false);
        }

        context.getSource().sendFeedback(() -> Text.literal("§7status: §f" + mod.getState()), false);

        if (mod.hasCrashed()) {
            context.getSource().sendFeedback(
                    () -> Text.literal("§c⚠ mod has crashed with " + mod.getErrors().size() + " error(s)"), false);
            for (LuaModContainer.ModError error : mod.getErrors()) {
                context.getSource().sendFeedback(() -> Text.literal("§c  - " + error.toString()), false);
            }
        }

        if (!meta.dependencies.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("§7dependencies:"), false);
            for (LuaModMetadata.Dependency dep : meta.dependencies) {
                String depInfo = String.format("  - %s %s %s", dep.id, dep.version,
                        dep.required ? "(required)" : "(optional)");
                context.getSource().sendFeedback(() -> Text.literal("§7" + depInfo), false);
            }
        }
        return 1;
    }

    private static int showDiagnostics(CommandContext<ServerCommandSource> context) {
        String diagnostics = modLoader.getDiagnostics();
        for (String line : diagnostics.split("\n")) {
            context.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    private static int disableMod(CommandContext<ServerCommandSource> context) {
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