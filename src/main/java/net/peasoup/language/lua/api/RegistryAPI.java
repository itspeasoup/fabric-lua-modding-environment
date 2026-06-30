package net.peasoup.language.lua.api;

import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.peasoup.language.lua.LuaBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;

import java.util.HashMap;
import java.util.Map;

/**
 * Lua API for registering items, blocks, and other game content
 */
public class RegistryAPI {
    private static final Logger LOGGER = LogManager.getLogger("RegistryAPI");
    private final String modId;
    private final Map<String, Item> registeredItems = new HashMap<>();

    public RegistryAPI(String modId) {
        this.modId = modId;
    }

    /**
     * Install this API into a Lua globals table
     */
    public void install(Globals globals) {
        LuaTable registry = new LuaTable();

        // registry.register_item(name, settings_table)
        registry.set("register_item", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settingsTable) {
                if (!name.isstring()) {
                    throw new LuaError("Item name must be a string");
                }
                return LuaBridge.toLua(registerItem(name.tojstring(), settingsTable));
            }
        });

        // registry.add_to_item_group(item_name, group_name)
        registry.set("add_to_item_group", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue itemName, LuaValue groupName) {
                if (!itemName.isstring() || !groupName.isstring()) {
                    throw new LuaError("Both arguments must be strings");
                }
                addToItemGroup(itemName.tojstring(), groupName.tojstring());
                return LuaValue.NIL;
            }
        });

        globals.set("registry", registry);
    }

    private Item registerItem(String name, LuaValue settingsTable) {
        // Create registry key
        RegistryKey<Item> itemKey = RegistryKey.of(
                RegistryKeys.ITEM,
                Identifier.of(modId, name)
        );

        // Create item settings
        Item.Settings settings = new Item.Settings().registryKey(itemKey);

        // Apply settings from Lua table if provided
        if (settingsTable.istable()) {
            LuaTable table = settingsTable.checktable();

            // Example: settings.max_stack_size
            LuaValue maxStack = table.get("max_stack_size");
            if (maxStack.isint()) {
                settings.maxCount(maxStack.toint());
            }

            // Example: settings.fireproof
            LuaValue fireproof = table.get("fireproof");
            if (fireproof.toboolean()) {
                settings.fireproof();
            }

            // Add more setting mappings as needed
        }

        // Create and register the item
        Item item = new Item(settings);
        Registry.register(Registries.ITEM, itemKey, item);

        String fullId = modId + ":" + name;
        registeredItems.put(fullId, item);

        LOGGER.info("Registered item: {}", fullId);
        return item;
    }

    private void addToItemGroup(String itemName, String groupName) {
        String fullId = itemName.contains(":") ? itemName : modId + ":" + itemName;
        Item item = registeredItems.get(fullId);

        if (item == null) {
            LOGGER.error("Cannot add item {} to group - item not registered", fullId);
            return;
        }

        // Map group names to ItemGroups
        switch (groupName.toLowerCase()) {
            case "building_blocks" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS)
                    .register(entries -> entries.add(item));
            case "colored_blocks" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.COLORED_BLOCKS)
                    .register(entries -> entries.add(item));
            case "natural" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL)
                    .register(entries -> entries.add(item));
            case "functional" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL)
                    .register(entries -> entries.add(item));
            case "redstone" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE)
                    .register(entries -> entries.add(item));
            case "tools" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS)
                    .register(entries -> entries.add(item));
            case "combat" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT)
                    .register(entries -> entries.add(item));
            case "food_and_drink" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.FOOD_AND_DRINK)
                    .register(entries -> entries.add(item));
            case "ingredients" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS)
                    .register(entries -> entries.add(item));
            case "spawn_eggs" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS)
                    .register(entries -> entries.add(item));
            default -> {
                ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS)
                        .register(entries -> entries.add(item));
                LOGGER.warn("Unknown item group '{}', defaulting to ingredients", groupName);
            }
        }

        LOGGER.info("Added item {} to group {}", fullId, groupName);
    }

    public Map<String, Item> getRegisteredItems() {
        return new HashMap<>(registeredItems);
    }
}