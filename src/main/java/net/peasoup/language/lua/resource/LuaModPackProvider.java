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
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Resource pack provider that loads generated assets from Lua mods
 */
public class LuaModPackProvider implements ResourcePackProvider {
    private static final Logger LOGGER = LogManager.getLogger("LuaModPackProvider");
    private final ResourceType type;

    public LuaModPackProvider(ResourceType type) {
        this.type = type;
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

                        ResourcePackInfo resourcePackInfo = new ResourcePackInfo("lua_mod_" + modId, Text.literal("lua mod: " + modId), ResourcePackSource.BUILTIN, Optional.empty());

                        ResourcePackProfile profile = ResourcePackProfile.create(resourcePackInfo, new ResourcePackProfile.PackFactory() {
                            @Override
                            public ResourcePack open(ResourcePackInfo info) {
                                return new LuaModResourcePack(finalModPath, finalType, info);
                            }

                            @Override
                            public ResourcePack openWithOverlays(ResourcePackInfo info, ResourcePackProfile.Metadata metadata) {
                                // We don't use overlays for Lua mods, so just return the normal pack!
                                return open(info);
                            }
                        }, type, new ResourcePackPosition(true, ResourcePackProfile.InsertionPosition.TOP, false));

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
    private static class LuaModResourcePack extends AbstractFileResourcePack {
        private final Path modPath;
        private final ResourceType type;
        private final String modId;
        private final Set<String> namespaces;

        public LuaModResourcePack(Path modPath, ResourceType type, ResourcePackInfo info) {
            super(info);
            this.modPath = modPath;
            this.type = type;
            this.modId = modPath.getFileName().toString();
            this.namespaces = discoverNamespaces();
        }

        /**
         * Discover all namespaces this pack contains
         */
        private Set<String> discoverNamespaces() {
            Set<String> result = new HashSet<>();
            Path basePath = type == ResourceType.CLIENT_RESOURCES ? modPath.resolve("assets") : modPath.resolve("data");

            if (!Files.exists(basePath)) {
                return result;
            }

            try (Stream<Path> paths = Files.list(basePath)) {
                paths.filter(Files::isDirectory).forEach(p -> result.add(p.getFileName().toString()));
            } catch (Exception e) {
                LOGGER.error("Failed to discover namespaces in {}", modPath, e);
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

            Path basePath = type == ResourceType.CLIENT_RESOURCES ? modPath.resolve("assets") : modPath.resolve("data");
            Path searchPath = basePath.resolve(namespace).resolve(prefix);

            if (!Files.exists(searchPath)) {
                return;
            }

            try (Stream<Path> paths = Files.walk(searchPath)) {
                paths.filter(Files::isRegularFile).forEach(path -> {
                    Path relativePath = basePath.resolve(namespace).relativize(path);
                    String idPath = relativePath.toString().replace('\\', '/');

                    Identifier id = Identifier.of(namespace, idPath);
                    InputSupplier<InputStream> supplier = () -> Files.newInputStream(path);

                    consumer.accept(id, supplier);
                });
            } catch (Exception e) {
                LOGGER.error("Failed to find resources in {}/{}", namespace, prefix, e);
            }
        }

        @Override
        public Set<String> getNamespaces(ResourceType type) {
            if (type == this.type) {
                return namespaces;
            }
            return Set.of();
        }

        public String getName() {
            return "lua_mod_" + modId;
        }

        @Override
        public void close() {
            // Nothing to close for file-based pack
        }
    }
}