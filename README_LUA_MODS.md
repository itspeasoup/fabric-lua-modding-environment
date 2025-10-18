# Lua Modding Environment - Structured Mod System

This system allows you to create Lua mods with a structure similar to Fabric mods, complete with datagen support!

## Mod Structure

### Structured Mods (Recommended)
```
mods/
├── your-mod-name/
│   ├── lua_mod.json          # Mod metadata (like fabric.mod.json)
│   ├── main.lua              # Main mod script (server-side)
│   ├── client.lua            # Client-side script (optional)
│   ├── datagen.lua           # Data generation script (optional)
│   ├── assets/               # Assets folder (optional)
│   └── data/                 # Generated data output (auto-created)
```

### Legacy Mods (Still Supported)
```
mods/
├── old-mod.lua               # Single file mods still work
└── another-mod.lua
```

## lua_mod.json Format

```json
{
  "schema_version": 1,
  "id": "your_mod_id",
  "version": "1.0.0",
  "name": "Your Mod Name",
  "description": "A description of your mod",
  "authors": ["Your Name"],
  "license": "MIT",
  "environment": "*",
  "main_script": "main.lua",
  "client_script": "client.lua",
  "contact": {
    "homepage": "https://your-website.com",
    "sources": "https://github.com/you/your-mod"
  },
  "depends": {
    "minecraft": ">=1.21.0"
  },
  "datagen": {
    "enabled": true,
    "output_path": "data",
    "datagen_script": "datagen.lua"
  }
}
```

## Lua Mod Structure

### Basic Mod Template
```lua
-- main.lua
mod = {
    info = MOD_INFO,  -- Contains id, name, version, etc.
    
    onInitialize = function()
        print("My mod initialized!")
        
        -- Register event handlers
        register_event("ServerLifecycleEvents", "SERVER_STARTED", function()
            print("Server started!")
        end)
    end
}

-- Auto-initialize
if mod.onInitialize then
    mod.onInitialize()
end
```

### Available Globals in Lua Mods

- `MOD_INFO` - Table containing mod metadata (id, name, version, description)
- `mod_resource(path)` - Returns "mod_id:path" for resource identification
- `mod_path(filename)` - Returns absolute path to file in mod directory
- `print(...)` - Logs to server console with [Lua print] prefix
- `register_event(className, eventName, handler)` - Register Fabric event handlers
- `describe_class(object)` - Get class information about Java objects

### Datagen Support

In your `datagen.lua` file:

```lua
mod = {
    info = MOD_INFO,
    
    onDatagen = function()
        print("Generating data for " .. MOD_INFO.name)
        
        -- Generate recipes
        add_recipe({
            type = "minecraft:crafting_shapeless",
            ingredients = {
                { item = "minecraft:stick" },
                { item = "minecraft:coal" }
            },
            result = {
                item = "minecraft:torch",
                count = 4
            }
        })
        
        -- Generate loot tables
        add_loot_table("mymod:entities/custom_mob", {
            type = "minecraft:entity",
            pools = { ... }
        })
        
        -- Generate tags
        add_tag("items", "mymod:custom_items", {
            "minecraft:stick",
            "minecraft:stone"
        })
    end
}
```

## Features

### Hot Reloading
- Automatically reloads when .lua or lua_mod.json files change
- Clears old event handlers to prevent duplicates

### Event System
- Full access to Fabric API events
- Same `register_event` system as before
- Events are automatically cleaned up on reload

### Datagen Integration
- Run `./gradlew runDatagen` to generate data files
- Lua mods with datagen enabled will be called during generation
- Generated data is saved to `src/main/generated`

### Mod Discovery
- Automatically discovers both structured and legacy mods
- Structured mods take precedence if both formats exist
- Clear logging of what mods are loaded

## Migration from Legacy Mods

Your old `.lua` files still work! But to use the new features:

1. Create a folder in `mods/` with your mod name
2. Create `lua_mod.json` with your mod metadata
3. Move your mod code to `main.lua` and wrap it in the `mod` table structure
4. Optionally add `datagen.lua` for data generation

## Examples

Check out the example mods in your `mods/` folder:
- `purr-mod/` - Converted from your original purr.lua
- `enhanced-example/` - Shows advanced features and datagen

## Running Datagen

```bash
./gradlew runDatagen
```

This will:
1. Load all Lua mods with datagen enabled
2. Call their `onDatagen` functions
3. Generate data files to `src/main/generated`
4. Include generated data in your mod build
