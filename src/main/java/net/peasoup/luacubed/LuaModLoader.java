package net.peasoup.luacubed;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.peasoup.luacubed.api.EventAPI;

public class LuaModLoader {
    private static final Logger LOGGER = LogManager.getLogger("LuaModLoader");

    private final Map<String, LuaModContainer> loadedMods = new LinkedHashMap<>();
    private final Map<String, LuaModMetadata> discoveredMetadata = new HashMap<>();
    private final List<LoadError> loadErrors = new ArrayList<>();

    public static class LoadError {
        public final String modId;
        public final String state;
        public final String message;
        public final Throwable cause;

        public LoadError(String modId, String state, String message, Throwable cause) {
            this.modId = modId;
            this.state = state;
            this.message = message;
            this.cause = cause;
        }

        @Override
        public String toString() {
            return "[" + modId + "/" + state + "] " + message;
        }
    }

    public void discoverAndLoadMods() {
        Path modsPath = Paths.get("mods");

        if (!Files.exists(modsPath)) {
            LOGGER.warn("mods directory not found ???: {}", modsPath.toAbsolutePath());
            return;
        }

        discoverMods(modsPath);

        validateDependencies();

        List<String> loadOrder = calculateLoadOrder();

        for (String modId : loadOrder) {
            loadMod(modId);
        }

        LOGGER.info("discovered {} Lua mod(s), loaded {} successfully",
                discoveredMetadata.size(), loadedMods.size());

        if (!loadErrors.isEmpty()) {
            LOGGER.warn("encountered {} error(s) during mod loading:", loadErrors.size());
            for (LoadError error : loadErrors) {
                LOGGER.warn("  {}", error);
            }
        }
    }

    private void discoverMods(Path modsPath) {
        try (Stream<Path> paths = Files.list(modsPath)) {
            paths.forEach(path -> {
                if (Files.isDirectory(path)) {
                    discoverStructuredMod(path);
                } else if (path.toString().endsWith(".lua")) {
                    discoverLegacyMod(path);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to scan mods directory", e);
        }
    }

    private void discoverStructuredMod(Path modPath) {
        Path metadataPath = modPath.resolve("lua_mod.json");
        if (!Files.exists(metadataPath)) {
            LOGGER.debug("Skipping directory {} - no lua_mod.json found", modPath.getFileName());
            return;
        }

        try {
            String metadataJson = Files.readString(metadataPath);
            LuaModMetadata metadata = LuaModMetadata.fromJson(metadataJson);

            if (discoveredMetadata.containsKey(metadata.id)) {
                recordError(metadata.id, "discovery",
                        "Duplicate mod ID found in " + modPath, null);
                return;
            }

            discoveredMetadata.put(metadata.id, metadata);
            LOGGER.debug("Discovered mod: {} v{} at {}",
                    metadata.name, metadata.version, modPath.getFileName());

        } catch (LuaModMetadata.ValidationException e) {
            recordError(modPath.getFileName().toString(), "discovery",
                    "Invalid metadata: " + e.getMessage(), e);
        } catch (Exception e) {
            recordError(modPath.getFileName().toString(), "discovery",
                    "Failed to parse lua_mod.json", e);
        }
    }

    private void discoverLegacyMod(Path luaFile) {
        String modId = luaFile.getFileName().toString().replace(".lua", "");

        if (discoveredMetadata.containsKey(modId)) {
            recordError(modId, "discovery", "Duplicate mod ID (legacy file)", null);
            return;
        }

        LuaModMetadata metadata = LuaModMetadata.createLegacy(modId);
        discoveredMetadata.put(modId, metadata);
        LOGGER.debug("Discovered legacy mod: {}", luaFile.getFileName());
    }

    private void validateDependencies() {
        for (LuaModMetadata metadata : discoveredMetadata.values()) {
            for (LuaModMetadata.Dependency dep : metadata.dependencies) {
                LuaModMetadata depMetadata = discoveredMetadata.get(dep.id);

                if (depMetadata == null) {
                    if (dep.required) {
                        recordError(metadata.id, "dependencies",
                                "Missing required dependency: " + dep.id, null);
                    } else {
                        LOGGER.debug("Mod {} has optional dependency {} that is not present",
                                metadata.id, dep.id);
                    }
                    continue;
                }

                if (!dep.matches(depMetadata.version)) {
                    String msg = String.format(
                            "Dependency %s has incompatible version. Required: %s, Found: %s",
                            dep.id, dep.version, depMetadata.version);

                    if (dep.required) {
                        recordError(metadata.id, "dependencies", msg, null);
                    } else {
                        LOGGER.warn("[{}] {}", metadata.id, msg);
                    }
                }
            }
        }
    }

    private List<String> calculateLoadOrder() {
        List<String> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String modId : discoveredMetadata.keySet()) {
            if (!visited.contains(modId)) {
                try {
                    visitMod(modId, visited, visiting, order);
                } catch (CyclicDependencyException e) {
                    recordError(modId, "dependencies", e.getMessage(), e);
                    if (!order.contains(modId)) {
                        order.add(modId);
                    }
                }
            }
        }

        LOGGER.debug("Load order: {}", order);
        return order;
    }

    private void visitMod(String modId, Set<String> visited, Set<String> visiting, List<String> order)
            throws CyclicDependencyException {

        if (visiting.contains(modId)) {
            throw new CyclicDependencyException("Cyclic dependency detected involving " + modId);
        }

        if (visited.contains(modId)) {
            return;
        }

        visiting.add(modId);

        LuaModMetadata metadata = discoveredMetadata.get(modId);
        if (metadata != null) {
            for (LuaModMetadata.Dependency dep : metadata.dependencies) {
                if (dep.ordering == LuaModMetadata.Dependency.Ordering.AFTER) {
                    if (discoveredMetadata.containsKey(dep.id)) {
                        visitMod(dep.id, visited, visiting, order);
                    }
                }
            }
        }

        visiting.remove(modId);
        visited.add(modId);
        order.add(modId);
    }

    private static final Path REPO_MODS_DIR = Paths.get("mods", "downloaded");

    public void loadMod(String modId) {
        LuaModMetadata metadata = discoveredMetadata.get(modId);
        if (metadata == null) {
            return;
        }

        for (LuaModMetadata.Dependency dep : metadata.dependencies) {
            if (dep.required && !loadedMods.containsKey(dep.id)) {
                recordError(modId, "loading",
                        "cannot load: required dependency " + dep.id + " failed to load", null);
                return;
            }
        }

        try {
            Path modPath = REPO_MODS_DIR.resolve(modId);
            boolean isRepoMod = Files.isDirectory(modPath);

            if (!isRepoMod) {
                modPath = Paths.get("mods").resolve(modId);
                if (!Files.isDirectory(modPath)) {
                    modPath = Paths.get("mods");
                }
            }

            LuaModContainer container = new LuaModContainer(metadata, modPath, isRepoMod);

            boolean success = true;
            if (metadata.mainScript != null && !metadata.mainScript.isEmpty()) {
                if (metadata.mainScript.contains("/") || modPath.equals(Paths.get("mods"))) {
                    Path luaFile = Paths.get("mods").resolve(metadata.mainScript);
                    success = container.loadLegacyScript(luaFile);
                } else {
                    success = container.loadMainScript();
                }
            }

            if (success && metadata.clientScript != null) {
                success = container.loadClientScript();
            }

            if (success && metadata.serverScript != null) {
                success = container.loadServerScript();
            }

            if (success) {
                loadedMods.put(modId, container);
                LOGGER.info("Loaded {} mod: {} v{}", (isRepoMod ? "sandboxed repository" : "local"), metadata.name,
                        metadata.version);
            } else {
                recordError(modId, "loading", "Failed to load scripts", null);
            }

        } catch (Exception e) {
            recordError(modId, "loading", "Failed to load mod", e);
        }
    }

    /**
     * Initialize all loaded mods
     */
    public void initializeAllMods() {
        int successCount = 0;

        for (LuaModContainer mod : loadedMods.values()) {
            if (mod.callInitialize()) {
                successCount++;
            }
        }

        LOGGER.info("initialized {}/{} Lua mod(s)", successCount, loadedMods.size());
    }

    public void runDatagen() {
        int datagenCount = 0;

        for (LuaModContainer mod : loadedMods.values()) {
            if (mod.hasDatagen()) {
                if (mod.loadDatagenScript()) {
                    if (mod.generateData()) {
                        datagenCount++;
                    }
                }
            }
        }

        LOGGER.info("generated data for {} Lua mod(s)", datagenCount);
    }

    public static void preGenerateAssets(Path modsPath) {
        if (!Files.exists(modsPath)) {
            LOGGER.info("no mods directory for some reason??");
            return;
        }

        try (Stream<Path> paths = Files.list(modsPath)) {
            paths.filter(Files::isDirectory).forEach(path -> {
                LOGGER.info("checking directory: {}", path);

                Path metadataPath = path.resolve("lua_mod.json");
                if (!Files.exists(metadataPath)) {
                    LOGGER.info("no metadata!");
                    return;
                }

                try {
                    String metadataJson = Files.readString(metadataPath);
                    LuaModMetadata metadata = LuaModMetadata.fromJson(metadataJson);

                    if (metadata.hasDatagen()) {
                        LOGGER.info("Pre-generating assets for: {}", metadata.id);

                        boolean isRepoMod = path.startsWith(REPO_MODS_DIR);

                        Path tmpPath = path.resolveSibling(path.getFileName().toString() + "_tmp");

                        deleteDirectorySilently(tmpPath);
                        Files.createDirectories(tmpPath);

                        LuaModContainer container = new LuaModContainer(metadata, path, isRepoMod, true);
                        container.generateAssetsOnly(tmpPath);

                        Path realAssetsPath = path.resolve("assets");
                        Path tmpAssetsPath = tmpPath.resolve("assets");

                        if (Files.exists(tmpAssetsPath)) {
                            deleteDirectorySilently(realAssetsPath);
                            Files.move(tmpAssetsPath, realAssetsPath);
                        }

                        deleteDirectorySilently(tmpPath);
                    }

                } catch (Exception e) {
                    LOGGER.error("Failed to pre-generate assets for {}", path, e);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to pre-generate assets", e);
        }
    }

    public boolean reloadMod(String modId) {
        LuaModContainer existing = loadedMods.get(modId);
        if (existing == null) {
            LOGGER.warn("Cannot reload mod {} - not loaded", modId);
            return false;
        }

        try {
            LuaModMetadata metadata = existing.getMetadata();

            EventAPI.clearHandlersForMod(modId);
            Path modPath = existing.getModPath();

            boolean wasSandboxed = modPath.startsWith(REPO_MODS_DIR);

            LuaModContainer newContainer = new LuaModContainer(metadata, modPath, wasSandboxed);

            if (newContainer.loadMainScript() && newContainer.callInitialize()) {
                loadedMods.put(modId, newContainer);
                LOGGER.info("Reloaded mod: {}", modId);
                return true;
            }

            return false;

        } catch (Exception e) {
            LOGGER.error("Failed to reload mod {}", modId, e);
            return false;
        }
    }

    public void reloadAllMods() {
        LOGGER.info("Reloading all Lua mods...");

        EventAPI.clearAllHandlers();
        loadedMods.clear();
        discoveredMetadata.clear();
        loadErrors.clear();

        discoverAndLoadMods();
        initializeAllMods();

        LOGGER.info("Reload complete");
    }

    public void refresh() {
        LOGGER.info("refreshing...");

        discoverAndLoadMods();
        initializeAllMods();

        LOGGER.info("refresh complete");
    }

    public String getDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Lua Mod Diagnostics ===\n");
        sb.append("Discovered mods: ").append(discoveredMetadata.size()).append("\n");
        sb.append("Loaded mods: ").append(loadedMods.size()).append("\n");
        sb.append("Load errors: ").append(loadErrors.size()).append("\n\n");

        if (!loadErrors.isEmpty()) {
            sb.append("Errors:\n");
            for (LoadError error : loadErrors) {
                sb.append("  - ").append(error).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Loaded mods:\n");
        for (LuaModContainer mod : loadedMods.values()) {
            LuaModMetadata meta = mod.getMetadata();
            sb.append("  - ").append(meta.name).append(" v").append(meta.version);
            sb.append(" (").append(meta.id).append(") - ");
            sb.append(mod.getState()).append("\n");

            if (mod.hasCrashed()) {
                sb.append("    [CRASHED] Errors: ").append(mod.getErrors().size()).append("\n");
            }
        }

        return sb.toString();
    }

    private void recordError(String modId, String state, String message, Throwable cause) {
        LoadError error = new LoadError(modId, state, message, cause);
        loadErrors.add(error);

        if (cause != null) {
            LOGGER.error("[{}] {}: {}", modId, state, message, cause);
        } else {
            LOGGER.error("[{}] {}: {}", modId, state, message);
        }
    }


    public Map<String, LuaModContainer> getLoadedMods() {
        return Collections.unmodifiableMap(loadedMods);
    }

    public LuaModContainer getMod(String modId) {
        return loadedMods.get(modId);
    }

    public List<LoadError> getLoadErrors() {
        return new ArrayList<>(loadErrors);
    }

    public Map<String, LuaModMetadata> getDiscoveredMetadata() {
        return Collections.unmodifiableMap(discoveredMetadata);
    }

    private static class CyclicDependencyException extends Exception {
        public CyclicDependencyException(String message) {
            super(message);
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
            LOGGER.warn("Failed to silently clean up directory profile: {}", path, e);
        }
    }
}