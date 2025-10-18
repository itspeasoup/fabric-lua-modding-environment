package net.peasoup.language.lua;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

public class LuaModMetadata {
    @SerializedName("schema_version")
    public String id;
    public String version;
    public String name;
    public String description;

    @SerializedName("main_script")
    public String mainScript = "main.lua";

    @SerializedName("client_script")

    // Datagen support
    public DatagenConfig datagen;

    public static class DatagenConfig {
        public boolean enabled = false;

        @SerializedName("datagen_script")
        public String datagenScript = "datagen.lua";
    }

    public static LuaModMetadata fromJson(String json) {
        return new Gson().fromJson(json, LuaModMetadata.class);
    }
}
