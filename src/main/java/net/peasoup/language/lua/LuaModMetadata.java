package net.peasoup.language.lua;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

public class LuaModMetadata {
    @SerializedName("schema_version")
    public int schemaVersion = 1;

    public String id;
    public String version;
    public String name;
    public String description;
    public List<String> authors;

    @SerializedName("main_script")
    public String mainScript = "main.lua";

    @SerializedName("client_script")
    public String clientScript;

    public String license;
    public String icon;
    public String environment = "*"; // "*", "client", "server"

    public Map<String, String> contact;
    public Map<String, String> depends;
    public Map<String, String> suggests;

    // Datagen support
    public DatagenConfig datagen;

    public static class DatagenConfig {
        public boolean enabled = false;

        @SerializedName("output_path")
        public String outputPath = "data";

        @SerializedName("datagen_script")
        public String datagenScript = "datagen.lua";
    }

    public static LuaModMetadata fromJson(String json) {
        return new Gson().fromJson(json, LuaModMetadata.class);
    }

    public String toJson() {
        return new Gson().toJson(this);
    }
}
