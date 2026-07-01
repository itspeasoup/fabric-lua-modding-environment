package net.peasoup.language.lua.resource;

import net.minecraft.resource.*;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Resource pack provider that loads generated assets from Lua mods
 */
public class LuaModPackProvider implements ResourcePackProvider {
    private static final Logger LOGGER = LogManager.getLogger("LuaModPackProvider");
    private final ResourceType type;

    public LuaModPackProvider(ResourceType type) {
        this.type = type;
    }

    // Put this right inside your LuaModPackProvider class scope!
    public static final Map<String, String> VIRTUAL_CACHE = new ConcurrentHashMap<>();

    /**
     * Global utility to store dynamic JSON maps across threads.
     * Example key structure: "fuck/data/recipe/my_recipe.json"
     */
    public static void addVirtualFile(String modId, ResourceType type, String subPath, String jsonString) {
        String dirKey = (type == ResourceType.CLIENT_RESOURCES) ? "assets" : "data";
        String internalKey = modId + "/" + dirKey + "/" + subPath;
        VIRTUAL_CACHE.put(internalKey, jsonString);
    }

    @Override
    public void register(Consumer<ResourcePackProfile> profileAdder) {
        // Get all loaded mods (or discover them if loader doesn't exist yet)
        Path modsPath = Paths.get("mods");
        if (!Files.exists(modsPath)) {
            return;
        }

        try (Stream<Path> paths = Files.list(modsPath)) {
            paths.filter(Files::isDirectory).forEach(modPath -> {
                Path metadataPath = modPath.resolve("lua_mod.json");
                if (!Files.exists(metadataPath)) {
                    return;
                }

                try {
                    // Check if this mod has assets for this resource type
                    if (hasResources(modPath)) {
                        String modId = modPath.getFileName().toString();

                        // Store modPath and type in effectively final variables for lambda
                        final Path finalModPath = modPath;
                        final ResourceType finalType = type;

                        ResourcePackInfo resourcePackInfo = new ResourcePackInfo("lua_mod_" + modId,
                                Text.literal("lua mod: " + modId), ResourcePackSource.BUILTIN, Optional.empty());

                        ResourcePackProfile profile = ResourcePackProfile.create(resourcePackInfo,
                                new ResourcePackProfile.PackFactory() {
                                    @Override
                                    public ResourcePack open(ResourcePackInfo info) {
                                        return new LuaModResourcePack(finalModPath, finalType, info);
                                    }

                                    @Override
                                    public ResourcePack openWithOverlays(ResourcePackInfo info,
                                            ResourcePackProfile.Metadata metadata) {
                                        // We don't use overlays for Lua mods, so just return the normal pack!
                                        return open(info);
                                    }
                                }, type,
                                new ResourcePackPosition(true, ResourcePackProfile.InsertionPosition.TOP, false));

                        if (profile != null) {
                            profileAdder.accept(profile);
                            LOGGER.debug("Registered resource pack for mod: {} (type: {})", modId, type);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to register resource pack for {}", modPath, e);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to scan mods directory for resources", e);
        }
    }

    /**
     * Check if a mod has resources for the current resource type
     */
    private boolean hasResources(Path modPath) {
        if (type == ResourceType.CLIENT_RESOURCES) {
            return Files.exists(modPath.resolve("assets"));
        } else {
            return Files.exists(modPath.resolve("data"));
        }
    }

    /**
     * Actual resource pack implementation
     */
    /**
     * Actual resource pack implementation that blends physical files with Lua
     * virtual files.
     */
    private static class LuaModResourcePack extends AbstractFileResourcePack {
        private final Path modPath;
        private final ResourceType type;
        private final Set<String> namespaces;

        public LuaModResourcePack(Path modPath, ResourceType type, ResourcePackInfo info) {
            super(info);
            this.modPath = modPath;
            this.type = type;
            this.namespaces = discoverNamespaces();
        }

        /**
         * Discover all namespaces this pack contains (both on disk and virtual from
         * Lua).
         */
        /**
         * Dynamically return namespaces so that files added via Lua *after* boot-up
         * are recognized immediately without requiring a full pack reconstruction.
         */
        @Override
        public Set<String> getNamespaces(ResourceType type) {
            if (type != this.type) {
                return Set.of();
            }
            // Re-run discovery dynamically to catch newly generated Lua namespaces
            return discoverNamespaces();
        }

        private Set<String> discoverNamespaces() {
            Set<String> result = new HashSet<>();

            // 1. Read namespaces from physical disk paths
            Path basePath = type == ResourceType.CLIENT_RESOURCES ? modPath.resolve("assets") : modPath.resolve("data");
            if (Files.exists(basePath)) {
                try (Stream<Path> paths = Files.list(basePath)) {
                    paths.filter(Files::isDirectory).forEach(p -> result.add(p.getFileName().toString()));
                } catch (Exception e) {
                    LOGGER.error("Failed to discover disk namespaces in {}", modPath, e);
                }
            }

            // 2. FIXED: Read virtual namespaces safely by matching the layout pattern
            // Instead of guessing if the modId matches the folder name, parse out the
            // namespace directly.
            String dirKey = (type == ResourceType.CLIENT_RESOURCES) ? "/assets/" : "/data/";

            for (String key : VIRTUAL_CACHE.keySet()) {
                if (key.contains(dirKey)) {
                    // Example key: "fuck/assets/fuck/lang/en_us.json"
                    int dirIndex = key.indexOf(dirKey);
                    String subPath = key.substring(dirIndex + dirKey.length()); // "fuck/lang/en_us.json"

                    int slashIndex = subPath.indexOf('/');
                    if (slashIndex > 0) {
                        String dynamicNamespace = subPath.substring(0, slashIndex); // "fuck"
                        result.add(dynamicNamespace);
                    }
                }
            }

            return result;
        }

        @Override
        public InputSupplier<InputStream> openRoot(String... segments) {
            Path path = modPath;
            for (String segment : segments) {
                path = path.resolve(segment);
            }

            final Path finalPath = path;
            if (!Files.exists(finalPath) || !Files.isRegularFile(finalPath)) {
                return null;
            }

            return () -> Files.newInputStream(finalPath);
        }

        @Override
        public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
            if (type != this.type) {
                return null;
            }

            // 1. Look inside the virtual memory cache first
            String modId = modPath.getFileName().toString();
            String dirKey = (type == ResourceType.CLIENT_RESOURCES) ? "assets" : "data";
            String expectedCacheKey = modId + "/" + dirKey + "/" + id.getNamespace() + "/" + id.getPath();

            if (VIRTUAL_CACHE.containsKey(expectedCacheKey)) {
                String jsonContent = VIRTUAL_CACHE.get(expectedCacheKey);
                return () -> new java.io.ByteArrayInputStream(
                        jsonContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            // 2. Fallback to physical disk lookup
            Path basePath = type == ResourceType.CLIENT_RESOURCES ? modPath.resolve("assets") : modPath.resolve("data");
            Path resourcePath = basePath.resolve(id.getNamespace()).resolve(id.getPath());

            if (!Files.exists(resourcePath) || !Files.isRegularFile(resourcePath)) {
                return null;
            }

            return () -> Files.newInputStream(resourcePath);
        }

        @Override
        public void findResources(ResourceType type, String namespace, String prefix, ResultConsumer consumer) {
            if (type != this.type || !namespaces.contains(namespace)) {
                return;
            }

            // 1. Scan and consumer normal disk files
            Path basePath = type == ResourceType.CLIENT_RESOURCES ? modPath.resolve("assets") : modPath.resolve("data");
            Path searchPath = basePath.resolve(namespace).resolve(prefix);

            if (Files.exists(searchPath)) {
                try (Stream<Path> paths = Files.walk(searchPath)) {
                    paths.filter(Files::isRegularFile).forEach(path -> {
                        Path relativePath = searchPath.relativize(path);
                        String idPath = relativePath.toString().replace('\\', '/');
                        String cleanPath = prefix + "/" + idPath;

                        Identifier id = Identifier.of(namespace, cleanPath);
                        consumer.accept(id, () -> Files.newInputStream(path));
                    });
                } catch (Exception e) {
                    LOGGER.error("Failed to map physical file resources in {}/{}", namespace, prefix, e);
                }
            }

            // 2. Scan and consume active virtual assets inside VIRTUAL_CACHE
            String modId = modPath.getFileName().toString();
            String dirKey = (type == ResourceType.CLIENT_RESOURCES) ? "assets" : "data";
            String cachePrefixFilter = modId + "/" + dirKey + "/" + namespace + "/" + prefix + "/";

            for (Map.Entry<String, String> entry : VIRTUAL_CACHE.entrySet()) {
                String cacheKey = entry.getKey();
                if (cacheKey.startsWith(cachePrefixFilter)) {
                    // Strips down key string to the expected internal ID format
                    String internalPath = cacheKey.substring((modId + "/" + dirKey + "/" + namespace + "/").length());
                    Identifier virtualId = Identifier.of(namespace, internalPath);

                    String jsonContent = entry.getValue();
                    consumer.accept(virtualId, () -> new java.io.ByteArrayInputStream(
                            jsonContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
            }
        }

        @Override
        public void close() {
            // Memory layout handles cleanup automatically
        }
    }
}