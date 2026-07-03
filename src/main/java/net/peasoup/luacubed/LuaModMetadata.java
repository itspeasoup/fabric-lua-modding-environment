package net.peasoup.luacubed;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Metadata for structured Lua mods (loaded from lua_mod.json)
 * Enhanced with dependencies, config, and better validation
 */
public class LuaModMetadata {
    private static final Pattern MOD_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9]+)?$");

    // Required fields
    public String id;
    public String version;
    public String name;

    // Optional fields
    public String description = "";
    public String[] authors = new String[0];
    public String license = "All Rights Reserved";
    public String homepage = null;
    public String icon = null; // Path to icon file (e.g., "icon.png")

    // Version requirements
    @SerializedName("minecraft_version")
    public String minecraftVersion = null;

    @SerializedName("lua_modding_version")
    public String luaModdingVersion = null;

    // Dependencies
    public List<Dependency> dependencies = new ArrayList<>();

    // Script configuration
    @SerializedName("main_script")
    public String mainScript = "main.lua";

    @SerializedName("client_script")
    public String clientScript = null; // Optional client-side script

    @SerializedName("server_script")
    public String serverScript = null; // Optional dedicated server script

    // Environment
    public Environment environment = Environment.BOTH;

    // Datagen configuration
    public DatagenConfig datagen = null;

    // Config system
    public ConfigSpec config = null;

    // Mixins (for advanced users)
    public String mixins = null; // Path to mixin config JSON

    /**
     * Parse metadata from JSON string with validation
     */
    public static LuaModMetadata fromJson(String json) throws ValidationException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        LuaModMetadata metadata = gson.fromJson(json, LuaModMetadata.class);
        metadata.validate();
        return metadata;
    }

    /**
     * Create metadata for legacy standalone .lua files
     */
    public static LuaModMetadata createLegacy(String modId) {
        LuaModMetadata metadata = new LuaModMetadata();
        metadata.id = modId.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        metadata.name = modId;
        metadata.version = "0.0.0";
        metadata.description = "legacy lua mod";
        metadata.mainScript = modId + ".lua";
        metadata.environment = Environment.BOTH;
        return metadata;
    }

    /**
     * Validate the metadata
     */
    public void validate() throws ValidationException {
        // Validate required fields
        if (id == null || id.isEmpty()) {
            throw new ValidationException("mod ID is required");
        }
        if (!MOD_ID_PATTERN.matcher(id).matches()) {
            throw new ValidationException("invalid mod ID: '" + id + "'. must be lowercase, start with a letter, " + "and contain only letters, numbers, and underscores");
        }
        if (version == null || version.isEmpty()) {
            throw new ValidationException("mod version is required");
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new ValidationException("invalid version format: '" + version + "'. must follow semver (e.g., 1.0.0)");
        }
        if (name == null || name.isEmpty()) {
            throw new ValidationException("mod name is required");
        }

        // Validate scripts exist (relative paths)
        if (mainScript == null || mainScript.isEmpty()) {
            throw new ValidationException("main script path is required");
        }
        if (!mainScript.endsWith(".lua")) {
            throw new ValidationException("main script must be a .lua file");
        }

        // Validate dependencies
        for (Dependency dep : dependencies) {
            if (dep.id == null || dep.id.isEmpty()) {
                throw new ValidationException("dependency must have an ID");
            }
        }
    }

    public boolean hasDatagen() {
        return datagen != null && datagen.enabled;
    }

    public boolean hasConfig() {
        return config != null && config.enabled;
    }

    public boolean isClientSide() {
        return environment == Environment.CLIENT || environment == Environment.BOTH;
    }

    public boolean isServerSide() {
        return environment == Environment.SERVER || environment == Environment.BOTH;
    }

    @Override
    public String toString() {
        return name + " v" + version + " (" + id + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LuaModMetadata that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    public enum Environment {
        @SerializedName("client") CLIENT, @SerializedName("server") SERVER, @SerializedName("both") BOTH
    }

    public static class Dependency {
        public String id;
        public String version = "*"; // Supports semver ranges like ">=1.0.0", "^1.2.0", etc.
        public boolean required = true;

        @SerializedName("ordering")
        public Ordering ordering = Ordering.NONE;

        public boolean matches(String actualVersion) {
            if (version.equals("*")) return true;

            // Simple version matching - you can expand this for full semver support
            if (version.startsWith(">=")) {
                return compareVersions(actualVersion, version.substring(2)) >= 0;
            } else if (version.startsWith(">")) {
                return compareVersions(actualVersion, version.substring(1)) > 0;
            } else if (version.startsWith("<=")) {
                return compareVersions(actualVersion, version.substring(2)) <= 0;
            } else if (version.startsWith("<")) {
                return compareVersions(actualVersion, version.substring(1)) < 0;
            } else if (version.startsWith("^")) {
                // Caret: compatible with version (same major version)
                String target = version.substring(1);
                String[] targetParts = target.split("\\.");
                String[] actualParts = actualVersion.split("\\.");
                return targetParts[0].equals(actualParts[0]) && compareVersions(actualVersion, target) >= 0;
            } else if (version.startsWith("~")) {
                // Tilde: compatible with minor version
                String target = version.substring(1);
                String[] targetParts = target.split("\\.");
                String[] actualParts = actualVersion.split("\\.");
                return targetParts[0].equals(actualParts[0]) && targetParts[1].equals(actualParts[1]) && compareVersions(actualVersion, target) >= 0;
            }

            // Exact match
            return actualVersion.equals(version);
        }

        private int compareVersions(String v1, String v2) {
            String[] parts1 = v1.split("[-.]");
            String[] parts2 = v2.split("[-.]");

            for (int i = 0; i < Math.min(parts1.length, parts2.length); i++) {
                try {
                    int n1 = Integer.parseInt(parts1[i]);
                    int n2 = Integer.parseInt(parts2[i]);
                    if (n1 != n2) return Integer.compare(n1, n2);
                } catch (NumberFormatException e) {
                    int cmp = parts1[i].compareTo(parts2[i]);
                    if (cmp != 0) return cmp;
                }
            }
            return Integer.compare(parts1.length, parts2.length);
        }

        public enum Ordering {
            @SerializedName("before") BEFORE, @SerializedName("after") AFTER, @SerializedName("none") NONE
        }
    }

    public static class DatagenConfig {
        public boolean enabled = false;

        @SerializedName("datagen_script")
        public String datagenScript = "datagen.lua";

        @SerializedName("generate_assets")
        public boolean generateAssets = true;

        @SerializedName("generate_data")
        public boolean generateData = true;
    }

    public static class ConfigSpec {
        public boolean enabled = true;
        public String path = "config"; // Directory to store config files

        @SerializedName("default_config")
        public Map<String, Object> defaultConfig = new HashMap<>();
    }

    public static class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }
    }
}