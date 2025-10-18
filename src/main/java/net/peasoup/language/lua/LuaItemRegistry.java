package net.peasoup.language.lua;

import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class LuaItemRegistry {
    private static final Logger LOGGER = LogManager.getLogger("lua-item-registry");
    private static final Map<String, Item> REGISTERED_ITEMS = new HashMap<>();

    public static Item register(String namespace, String name, Function<Item.Settings, Item> itemFactory, Item.Settings settings) {
        // Create the item key
        RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(namespace, name));

        // Create the item instance
        Item item = itemFactory.apply(settings.registryKey(itemKey));

        // Register the item
        Registry.register(Registries.ITEM, itemKey, item);

        // Store for later reference
        String fullId = namespace + ":" + name;
        REGISTERED_ITEMS.put(fullId, item);

        LOGGER.info("Registered Lua item: {}", fullId);
        return item;
    }

    public static Item register(String namespace, String name, Item.Settings settings) {
        return register(namespace, name, Item::new, settings);
    }

    public static void addToItemGroup(String itemId, String groupId) {
        Item item = REGISTERED_ITEMS.get(itemId);
        if (item == null) {
            LOGGER.error("Cannot add item {} to group {} - item not found", itemId, groupId);
            return;
        }

        // Add to vanilla item groups
        switch (groupId.toLowerCase()) {
            case "ingredients" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS)
                .register(entries -> entries.add(item));
            case "building_blocks" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS)
                .register(entries -> entries.add(item));
            case "tools" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS)
                .register(entries -> entries.add(item));
            case "combat" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT)
                .register(entries -> entries.add(item));
            case "food_and_drink" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.FOOD_AND_DRINK)
                .register(entries -> entries.add(item));
            case "redstone" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE)
                .register(entries -> entries.add(item));
            case "misc" -> ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS)
                .register(entries -> entries.add(item)); // Use INGREDIENTS as fallback for misc
            default -> {
                // Default to ingredients group for unknown groups
                ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS)
                    .register(entries -> entries.add(item));
                LOGGER.warn("Unknown item group: {}, added to ingredients instead", groupId);
            }
        }

        LOGGER.info("Added item {} to group {}", itemId, groupId);
    }
}
