package net.peasoup.luacubed;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import net.minecraft.text.Text;
import net.peasoup.luacubed.api.ConfigAPI;
import net.peasoup.luacubed.api.DatagenAPI;
import net.peasoup.luacubed.api.EventAPI;
import net.peasoup.luacubed.api.JavaAPI;
import net.peasoup.luacubed.api.RegistryAPI;

public class LuaModContainer {
    private static final Logger LOGGER = LogManager.getLogger("LuaModContainer");

    private final LuaModMetadata metadata;
    private Path modPath;
    private final Globals globals;

    private final DatagenAPI datagenAPI;
    private final List<ModError> errors = new ArrayList<>();

    private LuaValue mainChunk;
    private LuaValue clientChunk;
    private LuaValue datagenChunk;
    private boolean crashed = false;
    private ModState state = ModState.loading;

    private final List<SleepingThread> sleepingThreads = new ArrayList<>();

    private static class SleepingThread {
        int ticksRemaining;
        final LuaThread thread;

        SleepingThread(int ticksRemaining, LuaThread thread) {
            this.ticksRemaining = ticksRemaining;
            this.thread = thread;
        }
    }

    private final boolean sandboxed;

    public LuaModContainer(LuaModMetadata metadata, Path modPath, boolean isDownloadedFromRepo) {
        this(metadata, modPath, isDownloadedFromRepo, false);
    }

    public LuaModContainer(LuaModMetadata metadata, Path modPath, boolean isDownloadedFromRepo, boolean isDatagen) {
        this.metadata = metadata;
        this.modPath = modPath;
        this.sandboxed = isDownloadedFromRepo;

        this.globals = JsePlatform.standardGlobals();

        if (this.sandboxed) {
            this.globals.set("io", org.luaj.vm2.LuaValue.NIL);
            this.globals.set("os", org.luaj.vm2.LuaValue.NIL);
        }

        this.globals.set("luajava", org.luaj.vm2.LuaValue.NIL);

        this.globals.set("print", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= args.narg(); i++) {
                    if (i > 1)
                        sb.append(" ");
                    sb.append(args.arg(i).tojstring());
                }
                LOGGER.info("[{}] {}", metadata.id, sb.toString());
                return LuaValue.NIL;
            }
        });

        globals.set("start_thread", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue func) {
                if (!func.isfunction())
                    throw new LuaError("start_thread requires a function");

                LuaThread thread = new LuaThread(globals, func);
                thread.resume(LuaValue.NONE);
                return thread;
            }
        });

        globals.set("wait", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                int ticks = arg.checkint();
                LuaThread thread = globals.running;

                if (thread == null || thread.state.status == LuaThread.STATUS_DEAD) {
                    throw new LuaError(
                            "wait() can only be called inside a thread! Wrap your code in start_thread(function() ... end)");
                }

                sleepingThreads.add(new SleepingThread(ticks, thread));
                return globals.yield(LuaValue.NONE).arg1();
            }
        });

        this.globals.set("error", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String msg = args.arg(1).tojstring();
                LOGGER.error("[{}] lua error: {}", metadata.id, msg);
                recordError("runtime", msg, null);
                return LuaValue.NIL;
            }
        });

        this.datagenAPI = new DatagenAPI(metadata.id, modPath);
        this.datagenAPI.install(globals);

        if (!isDatagen) {
            RegistryAPI registryAPI = new RegistryAPI(metadata.id);
            EventAPI eventAPI = new EventAPI(metadata.id);
            ConfigAPI configAPI = new ConfigAPI(metadata.id, modPath);

            JavaAPI javaAPI = new JavaAPI(this.sandboxed);
            javaAPI.install(globals);
            registryAPI.install(globals);
            eventAPI.install(globals);

            for (Map.Entry<String, String> entry : LuaGlobalRegistry.getShortcuts().entrySet()) {
                try {
                    Class<?> clazz = Class.forName(entry.getValue());
                    globals.set(entry.getKey(), JavaAPI.wrapClass(clazz, this.sandboxed));
                } catch (ClassNotFoundException ignored) {
                }
            }

            if (metadata.hasConfig()) {
                configAPI.install(globals, metadata.config);
            }

            if (metadata.hasConfig()) {
                configAPI.install(globals, metadata.config);
            }

        }

        LuaTable modInfo = new LuaTable();
        modInfo.set("id", metadata.id);
        modInfo.set("name", metadata.name);
        modInfo.set("version", metadata.version);
        modInfo.set("description", metadata.description);
        modInfo.set("authors", arrayToLuaTable(metadata.authors));
        modInfo.set("environment", metadata.environment.toString().toLowerCase());
        globals.set("MOD_INFO", modInfo);

        globals.set("MOD_PATH", modPath.toString());
        globals.set("resolve_path", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String relativePath = args.arg(1).tojstring();
                return LuaValue.valueOf(modPath.resolve(relativePath).toString());
            }
        });
    }

    public boolean loadMainScript() {
        if (!metadata.isServerSide() && !metadata.isClientSide()) {
            return true;
        }

        try {
            Path scriptPath = modPath.resolve(metadata.mainScript);
            if (!Files.exists(scriptPath)) {
                recordError("loading", "main script not found: " + metadata.mainScript, null);
                return false;
            }

            String script = Files.readString(scriptPath);
            mainChunk = safeLoadScript(script, metadata.id + ":" + metadata.mainScript);

            if (mainChunk == null) {
                return false;
            }

            LOGGER.debug("loaded main script for mod: {}", metadata.id);
            return true;

        } catch (Exception e) {
            recordError("loading", "failed to load main script", e);
            return false;
        }
    }

    /**
     * Load client-side script (if present)
     */
    public boolean loadClientScript() {
        if (metadata.clientScript == null || !metadata.isClientSide()) {
            return true;
        }

        try {
            Path scriptPath = modPath.resolve(metadata.clientScript);
            if (!Files.exists(scriptPath)) {
                LOGGER.debug("optional client script not found: {}", metadata.clientScript);
                return true;
            }

            String script = Files.readString(scriptPath);
            clientChunk = safeLoadScript(script, metadata.id + ":" + metadata.clientScript);

            if (clientChunk != null) {
                LOGGER.debug("loaded client script for mod: {}", metadata.id);
            }
            return clientChunk != null;

        } catch (Exception e) {
            recordError("loading", "failed to load client script", e);
            return false;
        }
    }

    /**
     * Load server-side script (if present)
     */
    public boolean loadServerScript() {
        if (metadata.serverScript == null || !metadata.isServerSide()) {
            return true;
        }

        try {
            Path scriptPath = modPath.resolve(metadata.serverScript);
            if (!Files.exists(scriptPath)) {
                LOGGER.debug("optional server script not found: {}", metadata.serverScript);
                return true;
            }

            String script = Files.readString(scriptPath);
            LuaValue serverChunk = safeLoadScript(script, metadata.id + ":" + metadata.serverScript);

            if (serverChunk != null) {
                LOGGER.debug("loaded server script for mod: {}", metadata.id);
            }
            return serverChunk != null;

        } catch (Exception e) {
            recordError("loading", "failed to load server script", e);
            return false;
        }
    }

    /**
     * Load a legacy standalone .lua file
     */
    public boolean loadLegacyScript(Path luaFile) {
        try {
            String script = Files.readString(luaFile);
            mainChunk = safeLoadScript(script, luaFile.getFileName().toString());

            if (mainChunk == null) {
                return false;
            }

            LOGGER.debug("loaded legacy script: {}", luaFile.getFileName());
            return true;

        } catch (Exception e) {
            recordError("loading", "failed to load legacy script", e);
            return false;
        }
    }

    /**
     * Call the mod's onInitialize function if it exists
     */
    public boolean callInitialize() {
        if (crashed || state == ModState.running) {
            LOGGER.warn("skipping initialization of {} mod: {}", state.name(), metadata.id);
            return false;
        }

        if (mainChunk == null) {
            recordError("initializing", "cannot initialize - main script not loaded", null);
            return false;
        }

        try {
            state = ModState.initializing;

            safeExecute(mainChunk, "main script");

            LuaValue initFunc = globals.get("onInitialize");
            if (initFunc.isfunction()) {
                safeExecute(initFunc, "onInitialize");
                LOGGER.info("initialized mod: {} v{}", metadata.name, metadata.version);
            } else {
                LOGGER.debug("no onInitialize function found for mod: {}", metadata.id);
            }

            state = ModState.running;
            return true;

        } catch (Exception e) {
            recordError("initializing", "failed to initialize mod", e);
            state = ModState.crashed;
            crashed = true;
            return false;
        }
    }

    public boolean callClientInitialize() {
        if (crashed || clientChunk == null) {
            return true;
        }

        try {
            safeExecute(clientChunk, "client script");

            LuaValue initFunc = globals.get("onClientInitialize");
            if (initFunc.isfunction()) {
                safeExecute(initFunc, "onClientInitialize");
                LOGGER.info("client-initialized mod: {}", metadata.id);
            }
            return true;

        } catch (Exception e) {
            recordError("client_init", "failed to initialize client-side", e);
            return false;
        }
    }

    public boolean loadDatagenScript() {
        if (!metadata.hasDatagen()) {
            LOGGER.info("{} claims it has no datagen.", metadata.id);
            return true;
        }

        try {
            Path scriptPath = modPath.resolve(metadata.datagen.datagenScript);
            LOGGER.info("[{}] path of datagen script: {}", metadata.id, scriptPath);
            if (!Files.exists(scriptPath)) {
                recordError("datagen", "datagen script not found: " + metadata.datagen.datagenScript, null);
                return false;
            }

            String script = Files.readString(scriptPath);
            datagenChunk = safeLoadScript(script, metadata.id + ":" + metadata.datagen.datagenScript);

            if (datagenChunk != null) {
                LOGGER.debug("loaded datagen script for mod: {}", metadata.id);
            }
            return datagenChunk != null;

        } catch (Exception e) {
            recordError("datagen", "failed to load datagen script", e);
            return false;
        }
    }

    /**
     * Generate data (recipes, models, etc.)
     */
    public boolean generateData() {
        if (datagenChunk == null) {
            LOGGER.warn("Cannot generate data for mod {} - datagen script not loaded", metadata.id);
            return false;
        }

        try {
            safeExecute(datagenChunk, "datagen script");

            LuaValue genFunc = globals.get("onGenerateData");
            if (genFunc.isfunction()) {
                safeExecute(genFunc, "onGenerateData");
                LOGGER.info("generated data for mod: {}", metadata.id);
                return true;
            }
            return false;

        } catch (Exception e) {
            recordError("datagen", "failed to generate data", e);
            return false;
        }
    }

    public void generateAssetsOnly(Path tmpModPath) {
        LOGGER.info("pregenerating assets!");

        if (!loadDatagenScript()) {
            LOGGER.info("no datagen script!");
            return;
        }

        Path realModPath = this.modPath;

        try {
            this.modPath = tmpModPath;
            datagenAPI.setModPath(tmpModPath);

            safeExecute(datagenChunk, "datagen script");
            datagenAPI.generatePackMetadata("resources for " + metadata.name);

            LuaValue genFunc = globals.get("onGenerateAssets");
            if (genFunc.isfunction()) {
                safeExecute(genFunc, "onGenerateAssets");
            } else {
                genFunc = globals.get("onGenerateData");
                if (genFunc.isfunction()) {
                    safeExecute(genFunc, "onGenerateData");
                }
            }

            Path realAssets = realModPath.resolve("assets");
            Path tmpAssets = tmpModPath.resolve("assets");
            if (Files.exists(tmpAssets)) {
                overlayMergeDirectories(tmpAssets, realAssets);
            }

            Path realData = realModPath.resolve("data");
            Path tmpData = tmpModPath.resolve("data");
            if (Files.exists(tmpData)) {
                overlayMergeDirectories(tmpData, realData);
            }

            Path realMcmeta = realModPath.resolve("pack.mcmeta");
            Path tmpMcmeta = tmpModPath.resolve("pack.mcmeta");
            if (Files.exists(tmpMcmeta)) {
                Files.deleteIfExists(realMcmeta);
                Files.move(tmpMcmeta, realMcmeta);
            }

        } catch (Exception e) {
            recordError("datagen", "failed to pre-generate assets", e);
        } finally {
            this.modPath = realModPath;
            datagenAPI.setModPath(realModPath);
            deleteDirectorySilently(tmpModPath);
        }
    }

    /**
     * Safely load a Lua script with error handling
     */
    private LuaValue safeLoadScript(String script, String chunkName) {
        try {
            return globals.load(script, chunkName);
        } catch (LuaError e) {
            recordError("loading", "syntax error in " + chunkName, e);
            LOGGER.error("[{}] syntax error in {}: {}", metadata.id, chunkName, e.getMessage());
            return null;
        } catch (Exception e) {
            recordError("loading", "failed to load " + chunkName, e);
            LOGGER.error("[{}] failed to load {}", metadata.id, chunkName, e);
            return null;
        }
    }

    /**
     * Safely execute Lua code with error handling
     */
    private void safeExecute(LuaValue chunk, String contextName) throws Exception {
        try {
            chunk.call();
        } catch (LuaError e) {
            String msg = "runtime error in " + contextName + ": " + e.getMessage();
            recordError("runtime", msg, e);
            throw new Exception(msg, e);
        }
    }

    /**
     * Record an error for this mod
     */
    private void recordError(String state, String message, Throwable cause) {
        LOGGER.error("[{}] {} error: {} - {}", metadata.id, state, message, cause);
        errors.add(new ModError(state, message, cause));

        if (errors.size() >= 10) {
            crashed = true;
            this.state = ModState.crashed;
            LOGGER.error("[{}] mod has crashed due to excessive errors ({})", metadata.id, errors.size());
            NotificationManager.show(Text.literal("✗ mod " + metadata.name + " has crashed due to excessive errors"),
                    NotificationManager.Type.ERROR, 5000);
        }
    }

    /**
     * Helper to convert String array to Lua table
     */
    private LuaTable arrayToLuaTable(String[] array) {
        LuaTable table = new LuaTable();
        for (int i = 0; i < array.length; i++) {
            table.set(i + 1, array[i]);
        }
        return table;
    }

    public LuaModMetadata getMetadata() {
        return metadata;
    }

    public boolean hasDatagen() {
        return metadata.hasDatagen();
    }


    public Path getModPath() {
        return modPath;
    }

    public List<ModError> getErrors() {
        return new ArrayList<>(errors);
    }

    public boolean hasCrashed() {
        return crashed;
    }

    public ModState getState() {
        return state;
    }

    public Globals getGlobals() {
        return globals;
    }

    public void disable() {
        EventAPI.clearHandlersForMod(metadata.id);
        this.state = ModState.disabled;
        LOGGER.info("disabled mod: {}", metadata.id);
    }

    public enum ModState {
        loading, initializing, running, crashed, disabled
    }

    public static class ModError {
        public final String state;
        public final String message;
        public final Throwable cause;
        public final long timestamp;

        public ModError(String state, String message, Throwable cause) {
            this.state = state;
            this.message = message;
            this.cause = cause;
            this.timestamp = System.currentTimeMillis();

            NotificationManager.show(Text.literal("✗ mod error in " + state + ": " + message),
                    NotificationManager.Type.ERROR, 5000);
        }

        @Override
        public String toString() {
            return "[" + state + "] " + message + (cause != null ? ": " + cause.getMessage() : "");
        }
    }

    public void tick() {
        if (crashed || state != ModState.running)
            return;

        List<SleepingThread> currentThreads = new ArrayList<>(sleepingThreads);
        sleepingThreads.clear();

        for (SleepingThread st : currentThreads) {
            st.ticksRemaining--;

            if (st.ticksRemaining <= 0) {
                try {
                    st.thread.resume(LuaValue.NONE);
                } catch (Exception e) {
                    LOGGER.error("[{}] error resuming lua thread", metadata.id, e);
                    NotificationManager.show(Text.literal("✗ error resuming lua thread " + metadata.id + ", " + e),
                            NotificationManager.Type.ERROR, 5000);
                }
            } else {
                sleepingThreads.add(st);
            }
        }
    }

    private static void deleteDirectorySilently(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("failed to silently clean up directory profile: {}", path, e);
        }
    }

    private static void overlayMergeDirectories(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                Files.move(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}