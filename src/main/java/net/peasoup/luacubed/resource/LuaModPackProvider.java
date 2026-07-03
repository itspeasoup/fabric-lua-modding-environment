package net.peasoup.luacubed.resource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.resource.AbstractFileResourcePack;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackPosition;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class LuaModPackProvider implements ResourcePackProvider {
    private static final Logger LOGGER = LogManager.getLogger("LuaModPackProvider");
    private final ResourceType type;

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof LuaModPackProvider other))
            return false;
        return this.type == other.type;
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    public LuaModPackProvider(ResourceType type) {
        this.type = type;
    }

    public static final Map<ResourceType, Map<String, Map<Identifier, byte[]>>> VIRTUAL_CACHE = new ConcurrentHashMap<>();

    public static void addVirtualFile(ResourceType type, String namespace, String path, String jsonString) {
        VIRTUAL_CACHE
                .computeIfAbsent(type, t -> new ConcurrentHashMap<>())
                .computeIfAbsent(namespace, n -> new ConcurrentHashMap<>())
                .put(Identifier.of(namespace, path), jsonString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public void register(Consumer<ResourcePackProfile> profileAdder) {
        Path modsPath = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mods");
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
                    if (hasResources(modPath)) {
                        String modId = modPath.getFileName().toString();

                        final Path finalModPath = modPath;
                        final ResourceType finalType = type;

                        ResourcePackInfo resourcePackInfo = new ResourcePackInfo(
                                "lua_mod_" + modId + "_" + type.name().toLowerCase(),
                                Text.literal("lua mod: " + modId),
                                ResourcePackSource.BUILTIN,
                                Optional.empty());

                        ResourcePackProfile profile = ResourcePackProfile.create(resourcePackInfo,
                                new ResourcePackProfile.PackFactory() {
                                    @Override
                                    public ResourcePack open(ResourcePackInfo info) {
                                        return new LuaModResourcePack(finalModPath, finalType, info);
                                    }

                                    @Override
                                    public ResourcePack openWithOverlays(ResourcePackInfo info,
                                            ResourcePackProfile.Metadata metadata) {
                                        return open(info);
                                    }
                                }, type,
                                new ResourcePackPosition(true, ResourcePackProfile.InsertionPosition.TOP, false));

                        if (profile != null) {
                            profileAdder.accept(profile);
                            LOGGER.debug("registered resource pack for mod: {} (type: {})", modId, type);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("failed to register resource pack for {}", modPath, e);
                }
            });
        } catch (Exception e) {
            LOGGER.error("failed to scan mods directory for resources", e);
        }
    }

    private boolean hasResources(Path modPath) {
        if (type == ResourceType.CLIENT_RESOURCES) {
            return Files.exists(modPath.resolve("assets"));
        } else {
            return Files.exists(modPath.resolve("data"));
        }
    }

    private static class LuaModResourcePack extends AbstractFileResourcePack {
        private final Path modPath;
        private final ResourceType type;

        public LuaModResourcePack(Path modPath, ResourceType type, ResourcePackInfo info) {
            super(info);
            this.modPath = modPath;
            this.type = type;
        }

        @Override
        public Set<String> getNamespaces(ResourceType type) {
            Set<String> ns = (type == this.type) ? discoverNamespaces() : Set.of();
            LOGGER.debug("called getNamespaces({}) (this.type = {}){}", type, this.type, ns.isEmpty() ? "" : " namespace " + ns);
            if (type != this.type)
                return Set.of();
            return ns;
        }

        private Set<String> discoverNamespaces() {
            Set<String> result = new HashSet<>();

            Path basePath = type == ResourceType.CLIENT_RESOURCES ? modPath.resolve("assets") : modPath.resolve("data");
            if (Files.exists(basePath)) {
                try (Stream<Path> paths = Files.list(basePath)) {
                    paths.filter(Files::isDirectory).forEach(p -> result.add(p.getFileName().toString()));
                } catch (Exception e) {
                    LOGGER.error("Failed to discover disk namespaces in {}", modPath, e);
                }
            }

            Map<String, Map<Identifier, byte[]>> typeCache = VIRTUAL_CACHE.get(this.type);
            if (typeCache != null) {
                result.addAll(typeCache.keySet());
            }

            LOGGER.debug("discoverNamespaces() => {}", result);
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
                LOGGER.debug("type mismatch: {} is inequal to {}.", type, this.type);
                return null;
            } else {
            }

            String modId = modPath.getFileName().toString();
            String dirKey = (type == ResourceType.CLIENT_RESOURCES) ? "assets" : "data";

            Map<String, Map<Identifier, byte[]>> typeCache = VIRTUAL_CACHE.get(type);
            if (typeCache != null && typeCache.containsKey(id.getNamespace())) {
                byte[] content = typeCache.get(id.getNamespace()).get(id);
                if (content != null) {
                    return () -> new java.io.ByteArrayInputStream(content);
                }
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
            LOGGER.debug("findResources entry: type={} namespace={} prefix={} thisType={}", type, namespace, prefix, this.type);
            if (type != this.type || !discoverNamespaces().contains(namespace)) {
                return;
            }

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

            Map<String, Map<Identifier, byte[]>> typeMap = VIRTUAL_CACHE.get(type);
            if (typeMap != null && typeMap.containsKey(namespace)) {
                LOGGER.debug("findResources: namespace {} has {} virtual entries: {}", namespace,
                        typeMap.get(namespace).size(), typeMap.get(namespace).keySet());
                for (Map.Entry<Identifier, byte[]> entry : typeMap.get(namespace).entrySet()) {
                    Identifier id = entry.getKey();
                    if (id.getPath().startsWith(prefix + "/")) {
                        LOGGER.debug("findResources: reporting {} to consumer", id);
                        consumer.accept(id, () -> new java.io.ByteArrayInputStream(entry.getValue()));
                    }
                }
            } else {
                LOGGER.debug("findResources: no virtual entries for namespace {} (typeMap null: {})", namespace,
                        typeMap == null);
            }
        }

        @Override
        public void close() {
        }
    }
}