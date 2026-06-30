# lua modding environment - a guide for cool people

hey!! welcome to the lua modding environment!! this is like fabric, but for people who prefer lua over java (valid opinion tbh)

## okay so what do i do first

you're gonna wanna create a folder in the `mods/` directory. let's say you wanna make a cool mod called `super-duper-mod`. you'd make:

```
mods/
└── super-duper-mod/
    ├── lua_mod.json        # mod metadata (like fabric.mod.json but simpler)
    ├── main.lua            # your mod code goes here
    ├── client.lua          # client-side stuff (optional)
    └── datagen.lua         # data generation (optional)
```

## the lua_mod.json file - what you gotta fill out

this is where you tell the loader what your mod is

```json
{
  "schema_version": 1,
  "id": "super_duper_mod",
  "version": "1.0.0",
  "name": "Super Duper Mod",
  "description": "a mod that does super duper things",
  "authors": ["your-name"],
  "license": "MIT",
  "environment": "*",
  "main_script": "main.lua",
  "client_script": "client.lua",
  "contact": {
    "homepage": "https://your-website.com",
    "sources": "https://github.com/you/super-duper-mod"
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

### the fields explained real quick

- `id`: your mod's unique identifier (no spaces, use underscores)
- `version`: semantic versioning (major.minor.patch)
- `environment`: `"*"` for both client and server, `"server"` for server only, `"client"` for client only
- `depends`: what your mod needs (minecraft version, fabric-api, other mods)
- `datagen`: if you wanna generate recipes, tags, loot tables, etc, enable this

## writing main.lua - your mod's entry point

```lua
-- this is your mod!!
mod = {
    info = MOD_INFO,  -- Contains id, name, version from lua_mod.json
    
    onInitialize = function()
        print("my mod is starting up!")
        
        -- Register event handlers
        register_event("ServerLifecycleEvents", "SERVER_STARTED", function()
            print("server started!! let's goooo")
        end)
    end
}

-- the loader will automatically call this
if mod.onInitialize then
    mod.onInitialize()
end
```

### what globals you get for free

- `MOD_INFO` - a table with your mod's metadata (id, name, version, description)
- `mod_resource(path)` - returns `"mod_id:path"` for resource identification
- `mod_path(filename)` - returns the absolute path to a file in your mod directory
- `print(...)` - logs to the console with your mod id prefixed
- `register_event(className, eventName, handler)` - register fabric event handlers
- `describe_class(object)` - prints class info about java objects (useful for debugging)

## hooking into fabric events - event system

this is how you respond to things happening in the game

```lua
mod = {
    info = MOD_INFO,
    
    onInitialize = function()
        -- Server lifecycle events
        register_event("ServerLifecycleEvents", "SERVER_STARTED", function()
            print("server's up!")
        end)
        
        register_event("ServerLifecycleEvents", "SERVER_STOPPING", function()
            print("server's going down :'(")
        end)
        
        -- Tick events (happens every game tick)
        register_event("ServerTickEvents", "START", function()
            -- do something every tick
        end)
        
        -- Player events
        register_event("ServerPlayerEvents", "AFTER_RESPAWN", function(player, isAlive)
            print("player respawned: " .. tostring(player))
        end)
        
        -- Block break events
        register_event("PlayerBlockBreakEvents", "BEFORE", function(world, player, pos, state, blockEntity)
            print("player trying to break block at " .. tostring(pos))
        end)
    end
}
```

### available fabric events

here's what's available out of the box:

**lifecycle events:**
- `CommonLifecycleEvents` - both sides
- `ServerLifecycleEvents` - server only (SERVER_STARTED, SERVER_STOPPING, etc)
- `ServerTickEvents` - every game tick

**networking & players:**
- `ServerPlayConnectionEvents` - when players join/leave
- `ServerPlayNetworking` - custom networking stuff
- `ServerLoginConnectionEvents` - login related
- `ServerPlayerEvents` - player specific events

**interactions:**
- `PlayerBlockBreakEvents` - breaking blocks
- `UseBlockCallback` - right-clicking blocks
- `UseEntityCallback` - right-clicking entities
- `AttackBlockCallback` - left-clicking blocks
- `AttackEntityCallback` - attacking entities

if you want more events, you can extend the hardcoded list in `LuaEventBridge.java`, or let me know and i'll add em

## data generation - making recipes, tags, loot tables

if you enable datagen in your `lua_mod.json`, you can generate minecraft data files automatically

```lua
-- datagen.lua
mod = {
    info = MOD_INFO,
    
    onDatagen = function()
        print("generating data for " .. MOD_INFO.name)
        
        -- Register items
        register_item("cool_item", {
            max_count = 64,
            fireproof = false
        })
        
        -- Add item to creative tab
        add_item_to_group("super_duper_mod:cool_item", "ingredients")
        
        -- Create a recipe
        add_recipe({
            id = "super_duper_mod:cool_item",
            type = "minecraft:crafting_shapeless",
            ingredients = {
                { item = "minecraft:stick" },
                { item = "minecraft:coal" }
            },
            result = {
                item = "super_duper_mod:cool_item",
                count = 4
            }
        })
        
        -- Create a loot table
        add_loot_table("super_duper_mod:blocks/cool_block", {
            type = "minecraft:block",
            pools = { }
        })
        
        -- Create tags
        add_tag("items", "super_duper_mod:cool_items", {
            "super_duper_mod:cool_item",
            "minecraft:stick"
        })
    end
}
```

### generating data

to actually generate the files:

```bash
./gradlew runDatagen
```

this will call all your `onDatagen` functions and generate files to `src/main/generated/data`

## setting up assets - textures and models

put your textures and models in:

```
mods/super-duper-mod/
└── assets/
    └── super_duper_mod/
        ├── textures/
        │   └── item/
        │       └── cool_item.png
        └── models/
            └── item/
                └── cool_item.json
```

the datagen system can auto-generate item models for you if you register items, but you'll need to provide the textures

## common stuff you might wanna do

### printing debug info

```lua
print("debug info: " .. tostring(value))
```

### checking object types

```lua
local info = describe_class(some_java_object)
print(info)  -- prints class name, fields, methods
```

### getting mod resources

```lua
local texture_path = mod_resource("textures/item/cool_item.png")
-- returns: "super_duper_mod:textures/item/cool_item.png"
```

### getting file paths

```lua
local file_path = mod_path("assets/super_duper_mod/textures/item/cool_item.png")
print(file_path)  -- absolute path to your mod directory
```

## error handling - when things go wrong

if your lua code crashes, check the logs. they'll have your mod id prefixed so you can find em:

```
[00:00:00] [Render thread/INFO] [lua-mod-container] [super_duper_mod] Error in add_recipe: Invalid recipe ID format
```

common errors:
- missing required arguments (the logger will tell you how many you need)
- wrong types (like passing a number where a string is expected)
- invalid JSON format (bad field names or structure)

## what's coming soon™

- NeoForge support (maybe)
- Mixin support (for bytecode manipulation)
- More event classes
- Client-side rendering hooks
- Custom networking
- Command registration

## how do i ask for help

if something breaks or you need a feature, just open an issue on the github!! or if you find a cool way to use this, let me know i'd love to see it

## fin

that's basically it!! you know how to make a lua mod now. go make cool stuff and have fun!! remember to backup your work and test often :>
