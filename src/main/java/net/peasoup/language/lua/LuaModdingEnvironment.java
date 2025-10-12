package net.peasoup.language.lua;

import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

public class LuaModdingEnvironment implements ModInitializer {
	private static final Logger LOGGER = LogManager.getLogger("lua-modding-environment");

	// List all known event classes to search for static fields
	private static final List<Class<?>> EVENT_CLASSES = Arrays.asList(
			net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents.class,
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.class,
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.class,
			net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.class,
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.class,
			net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents.class,
			net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.class,
			net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.class,
			net.fabricmc.fabric.api.event.player.UseBlockCallback.class,
			net.fabricmc.fabric.api.event.player.UseEntityCallback.class,
			net.fabricmc.fabric.api.event.player.AttackBlockCallback.class,
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.class
	);

	@Override
	public void onInitialize() {
		Globals globals = JsePlatform.standardGlobals();

		// redirect print
		globals.set("print", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				StringBuilder sb = new StringBuilder();
				for (int i = 1; i <= args.narg(); i++) {
					if (i > 1) sb.append(" ");
					sb.append(args.arg(i).tojstring());
				}
				LOGGER.info("[Lua print] {}", sb.toString());
				return LuaValue.NIL;
			}
		});

		// expose all fabric events properly
		exposeFabricEvents(globals);

		// register improved event bridge (now takes className, fieldName, handler)
		globals.set("register_event", LuaEventBridge.getRegisterEventFunction());
		globals.set("describe_class", LuaEventBridge.getDescribeFunction());

		// load lua mods
		loadLuaMods(globals);
		startLuaModWatcher(globals); // <-- Add this
	}

	private void startLuaModWatcher(Globals globals) {
		Path modsPath = Paths.get("mods");
		try {
			WatchService watcher = modsPath.getFileSystem().newWatchService();
			modsPath.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);

			Thread watcherThread = new Thread(() -> {
				while (true) {
					try {
						WatchKey key = watcher.take();
						for (WatchEvent<?> event : key.pollEvents()) {
							WatchEvent.Kind<?> kind = event.kind();
							Path changed = (Path) event.context();
							if (changed.toString().endsWith(".lua")) {
								LOGGER.info("Lua mod changed: {} - Reloading mods...", changed);
								// CLEAR Lua state and reload mods!
								reloadLuaMods(globals);
							}
						}
						key.reset();
					} catch (Exception e) {
						LOGGER.error("Lua mod watcher error", e);
					}
				}
			}, "LuaModWatcher");
			watcherThread.setDaemon(true);
			watcherThread.start();
		} catch (Exception e) {
			LOGGER.error("Failed to start Lua file watcher", e);
		}
	}

	public static void reloadLuaMods(Globals globals) {
		// 1. Clear all Lua event handlers
		net.peasoup.language.lua.LuaEventBridge.clearAllHandlers();

		// 2. Re-expose Fabric events (in case globals changed)
		exposeFabricEvents(globals);

		// 3. Re-register bridge functions
		globals.set("register_event", net.peasoup.language.lua.LuaEventBridge.getRegisterEventFunction());
		globals.set("describe_class", net.peasoup.language.lua.LuaEventBridge.getDescribeFunction());

		// 4. Reload all Lua mods
		File modsFolder = new File("mods");
		if (!modsFolder.exists() || !modsFolder.isDirectory()) {
			LOGGER.warn("mods folder not found!");
			return;
		}

		File[] files = modsFolder.listFiles((d, n) -> n.endsWith(".lua"));
		if (files == null) return;

		for (File luaFile : files) {
			try {
				LOGGER.info("reloading lua mod: {}", luaFile.getName());
				globals.load(new FileReader(luaFile), luaFile.getName()).call();

				LuaValue modTable = globals.get("mod");
				if (modTable.istable()) {
					LuaValue onInit = modTable.get("onInitialize");
					if (onInit.isfunction()) {
						LOGGER.info("calling onInitialize for {}", luaFile.getName());
						onInit.call();
					}
				}
			} catch (Exception e) {
				LOGGER.error("failed to reload lua mod: {}", luaFile.getName(), e);
			}
		}
	}

	private static void exposeFabricEvents(Globals globals) {
		try {
			for (Class<?> clazz : EVENT_CLASSES) {
				LuaTable classTable = new LuaTable();

				for (Field f : clazz.getFields()) {
					Object ev = f.get(null);
					if (!(ev instanceof net.fabricmc.fabric.api.event.Event<?>)) continue;
					classTable.set(f.getName(), org.luaj.vm2.lib.jse.CoerceJavaToLua.coerce(ev));
					LOGGER.info("exposed Fabric event instance: {}.{}", clazz.getSimpleName(), f.getName());
				}

				globals.set(clazz.getSimpleName(), classTable);
				LOGGER.info("exposed Fabric event: {}", clazz.getSimpleName());
			}
		} catch (Exception e) {
			LOGGER.error("error exposing fabric events", e);
		}
	}

	private static void loadLuaMods(Globals globals) {
		File modsFolder = new File("mods");
		if (!modsFolder.exists() || !modsFolder.isDirectory()) {
			LOGGER.warn("mods folder not found!");
			return;
		}

		File[] files = modsFolder.listFiles((d, n) -> n.endsWith(".lua"));
		if (files == null) return;

		for (File luaFile : files) {
			try {
				LOGGER.info("loading lua mod: {}", luaFile.getName());
				globals.load(new FileReader(luaFile), luaFile.getName()).call();

				LuaValue modTable = globals.get("mod");
				if (modTable.istable()) {
					LuaValue onInit = modTable.get("onInitialize");
					if (onInit.isfunction()) {
						LOGGER.info("calling onInitialize for {}", luaFile.getName());
						onInit.call();
					}
				}
			} catch (Exception e) {
				LOGGER.error("failed to load lua mod: {}", luaFile.getName(), e);
			}
		}
	}
}