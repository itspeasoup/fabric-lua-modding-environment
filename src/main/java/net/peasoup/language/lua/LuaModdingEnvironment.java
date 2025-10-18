package net.peasoup.language.lua;

import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;

public class LuaModdingEnvironment implements ModInitializer {
	private static final Logger LOGGER = LogManager.getLogger("lua-modding-environment");
	private static LuaModLoader luaModLoader;
	private static Globals globals;

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

	// Static initializer - runs BEFORE mod initialization
	static {
		LOGGER.info("Pre-generating Lua mod assets before resource loading...");
		try {
			preGenerateAssets();
		} catch (Exception e) {
			LOGGER.error("Failed to pre-generate assets", e);
		}
	}

	private static void preGenerateAssets() throws Exception {
		// Create globals for pre-generation
		Globals preGenGlobals = JsePlatform.standardGlobals();

		// Redirect print
		preGenGlobals.set("print", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				StringBuilder sb = new StringBuilder();
				for (int i = 1; i <= args.narg(); i++) {
					if (i > 1) sb.append(" ");
					sb.append(args.arg(i).tojstring());
				}
				LOGGER.info("[Lua pre-gen] {}", sb.toString());
				return LuaValue.NIL;
			}
		});

		// Load mods in datagen mode to generate assets
		Path modsPath = Path.of("mods");
		if (!Files.exists(modsPath)) {
			LOGGER.warn("Mods directory not found at startup: {}", modsPath.toAbsolutePath());
			return;
		}

		try (var stream = Files.list(modsPath)) {
			stream.forEach(path -> {
				if (Files.isDirectory(path)) {
					Path metadataPath = path.resolve("lua_mod.json");
					if (!Files.exists(metadataPath)) {
						return;
					}

					try {
						String metadataJson = Files.readString(metadataPath);
						LuaModMetadata metadata = LuaModMetadata.fromJson(metadataJson);

						if (metadata.datagen != null && metadata.datagen.enabled) {
							LOGGER.info("Pre-generating assets for mod: {}", metadata.id);
							LuaModContainer container = new LuaModContainer(metadata, path, preGenGlobals);
							// Only generate asset files, DON'T register items yet
							container.generateAssetsOnly();
						}
					} catch (Exception e) {
						LOGGER.error("Failed to pre-generate assets for {}", path, e);
					}
				}
			});
		}

		LOGGER.info("Pre-generation complete - assets are ready for resource loading");
	}

	@Override
	public void onInitialize() {
		globals = JsePlatform.standardGlobals();

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

		// Initialize the new Lua mod loader
		luaModLoader = new LuaModLoader(globals);
		luaModLoader.loadAllMods();

		// Start file watcher for hot reloading
		startLuaModWatcher();
	}

	private void startLuaModWatcher() {
		Path modsPath = Paths.get("run/mods");
		try {
			// Ensure the directory exists before watching
			if (!Files.exists(modsPath)) {
				Files.createDirectories(modsPath);
			}
			WatchService watcher = modsPath.getFileSystem().newWatchService();
			modsPath.register(watcher,
				StandardWatchEventKinds.ENTRY_MODIFY,
				StandardWatchEventKinds.ENTRY_CREATE,
				StandardWatchEventKinds.ENTRY_DELETE);

			Thread watcherThread = new Thread(() -> {
				while (true) {
					try {
						WatchKey key = watcher.take();
						for (WatchEvent<?> event : key.pollEvents()) {
                            Path changed = (Path) event.context();
							if (changed.toString().endsWith(".lua") || changed.toString().equals("lua_mod.json")) {
								LOGGER.info("Lua mod file changed: {} - Reloading mods...", changed);
								luaModLoader.reloadAllMods();
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

	public static LuaModLoader getLuaModLoader() {
		return luaModLoader;
	}

	public static Globals getGlobals() {
		return globals;
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
}
