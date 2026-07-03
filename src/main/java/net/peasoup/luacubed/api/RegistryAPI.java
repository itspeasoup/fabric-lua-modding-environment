package net.peasoup.luacubed.api;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;

import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.component.ComponentType;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.attribute.ClampedEntityAttribute;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.potion.Potion;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.peasoup.luacubed.LuaBridge;

public class RegistryAPI {
    private static final Logger LOGGER = LogManager.getLogger("RegistryAPI");
    private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Map<String, BlockSoundGroup> SOUND_GROUPS = new HashMap<>();

    static {
        for (Field field : BlockSoundGroup.class.getFields()) {
            if (field.getType() == BlockSoundGroup.class && Modifier.isStatic(field.getModifiers())) {
                try {
                    SOUND_GROUPS.put(field.getName().toLowerCase(), (BlockSoundGroup) field.get(null));
                } catch (Exception ignored) {
                }
            }
        }
    }

    private final String modId;
    private final Map<String, Block> blockCache = new HashMap<>();
    private final Map<String, Item> itemCache = new HashMap<>();
    private final Map<String, SoundEvent> soundCache = new HashMap<>();
    private final Map<String, StatusEffect> effectCache = new HashMap<>();
    private final Map<String, ParticleType<?>> particleCache = new HashMap<>();
    private final Map<String, Potion> potionCache = new HashMap<>();
    private final Map<String, RegistryKey<DamageType>> damageTypeCache = new HashMap<>();
    private final Map<String, EntityAttribute> attributeCache = new HashMap<>();


    public RegistryAPI(String modId) {
        this.modId = modId;
    }

    public void install(Globals globals) {
        LuaTable registry = new LuaTable();

        registry.set("register_block", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaValue.valueOf(registerBlock(name.tojstring(), settings).toString());
            }
        });

        registry.set("register_item", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerItem(name.tojstring(), settings));
            }
        });

        registry.set("register_sound", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                validateName(name.tojstring());
                return LuaValue.valueOf(registerSound(name.tojstring()).toString());
            }
        });

        registry.set("register_effect", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerStatusEffect(name.tojstring(), settings));
            }
        });

        registry.set("register_particle", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                validateName(name.tojstring());
                return LuaValue.valueOf(registerParticle(name.tojstring()).toString());
            }
        });

        registry.set("add_to_group", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue group) {
                addToItemGroup(name.tojstring(), group.tojstring());
                return LuaValue.NIL;
            }
        });

        registry.set("create_group", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue id, LuaValue settings) {
                validateName(id.tojstring());
                return LuaValue.valueOf(createCustomGroup(id.tojstring(), settings).toString());
            }
        });

        registry.set("is_registered", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                String fullId = name.tojstring().contains(":") ? name.tojstring() : modId + ":" + name.tojstring();
                return LuaValue.valueOf(itemCache.containsKey(fullId) || blockCache.containsKey(fullId));
            }
        });

        registry.set("get_item", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                String fullId = name.tojstring().contains(":") ? name.tojstring() : modId + ":" + name.tojstring();
                Item item = itemCache.get(fullId);
                return item != null ? LuaBridge.toLua(item) : LuaValue.NIL;
            }
        });

        registry.set("register_enchantment", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerEnchantment(name.tojstring(), settings));
            }
        });

        registry.set("register_potion", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerPotion(name.tojstring(), settings));
            }
        });

        registry.set("register_damage_type", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaValue.valueOf(registerDamageType(name.tojstring(), settings).toString());
            }
        });

        registry.set("register_game_rule", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaValue.valueOf(registerGameRule(name.tojstring(), settings));
            }
        });

        registry.set("register_attribute", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerAttribute(name.tojstring(), settings));
            }
        });

        registry.set("register_recipe_type", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerRecipeType(name.tojstring(), settings));
            }
        });

        registry.set("register_block_entity_type", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerBlockEntityType(name.tojstring(), settings));
            }
        });

        globals.set("registry", registry);
    }

    private Enchantment registerEnchantment(String name, LuaValue settingsTable) {
        LOGGER.warn("Enchantments require datapack JSON files - use datagen API");
        return null;
    }

    private Potion registerPotion(String name, LuaValue settingsTable) {
        Identifier id = Identifier.of(modId, name);

        if (!settingsTable.istable()) {
            throw new LuaError("Potion requires settings table");
        }

        LuaTable table = settingsTable.checktable();

        String effectId = table.get("effect").optjstring("");
        if (effectId.isEmpty()) {
            throw new LuaError("Potion requires 'effect' parameter");
        }

        Identifier effectIdentifier = Identifier.tryParse(effectId.contains(":") ? effectId : modId + ":" + effectId);
        RegistryEntry<StatusEffect> effect = Registries.STATUS_EFFECT.getEntry(effectIdentifier)
                .orElseThrow(() -> new LuaError("Unknown status effect: " + effectId));

        int duration = table.get("duration").optint(3600);
        int amplifier = table.get("amplifier").optint(0);

        StatusEffectInstance effectInstance = new StatusEffectInstance(effect, duration, amplifier);
        Potion potion = new Potion(name, effectInstance);

        Registry.register(Registries.POTION, id, potion);
        potionCache.put(id.toString(), potion);

        LOGGER.info("Registered potion: {}", id);
        return potion;
    }

    private Identifier registerDamageType(String name, LuaValue settingsTable) {
        Identifier id = Identifier.of(modId, name);
        RegistryKey<DamageType> key = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, id);

        if (!settingsTable.istable()) {
            throw new LuaError("Damage type requires settings table");
        }

        damageTypeCache.put(id.toString(), key);
        LOGGER.warn("Damage types require datapack JSON files - use datagen API");

        return id;
    }

    private String registerGameRule(String name, LuaValue settingsTable) {
        if (!settingsTable.istable()) {
            throw new LuaError("Game rule requires settings table");
        }

        LOGGER.warn("Game rules must be registered in Java code statically");
        return modId + ":" + name;
    }

    private EntityAttribute registerAttribute(String name, LuaValue settingsTable) {
        Identifier id = Identifier.of(modId, name);

        if (!settingsTable.istable()) {
            throw new LuaError("Attribute requires settings table");
        }

        LuaTable table = settingsTable.checktable();

        double defaultValue = table.get("default").optdouble(0.0);
        double min = table.get("min").optdouble(0.0);
        double max = table.get("max").optdouble(1024.0);

        EntityAttribute attribute = new ClampedEntityAttribute("attribute.name." + modId + "." + name, defaultValue,
                min, max).setTracked(true);

        Registry.register(Registries.ATTRIBUTE, id, attribute);
        attributeCache.put(id.toString(), attribute);

        LOGGER.info("Registered attribute: {}", id);
        return attribute;
    }

    private RecipeType<?> registerRecipeType(String name, LuaValue settingsTable) {
        Identifier id = Identifier.of(modId, name);

        RecipeType<?> recipeType = new RecipeType<Recipe<?>>() {
            @Override
            public String toString() {
                return id.toString();
            }
        };

        Registry.register(Registries.RECIPE_TYPE, id, recipeType);

        LOGGER.info("Registered recipe type: {}", id);
        return recipeType;
    }

    private BlockEntityType<?> registerBlockEntityType(String name, LuaValue settingsTable) {
        if (!settingsTable.istable()) {
            throw new LuaError("Block entity type requires settings table");
        }

        LOGGER.warn("Block entity types require Java classes - cannot create from Lua");
        return null;
    }


    private void validateName(String name) {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new LuaError("Invalid registry name: '" + name
                    + "'. Must be lowercase, start with a letter, and contain only letters, numbers, and underscores.");
        }
    }

    private com.google.gson.JsonElement convertLuaToJson(LuaValue value) {
        if (value.isboolean())
            return new com.google.gson.JsonPrimitive(value.toboolean());
        if (value.isint())
            return new com.google.gson.JsonPrimitive(value.toint());
        if (value.isnumber())
            return new com.google.gson.JsonPrimitive(value.todouble());
        if (value.isstring())
            return new com.google.gson.JsonPrimitive(value.tojstring());

        if (value.istable()) {
            LuaTable table = value.checktable();
            if (table.length() > 0) {
                com.google.gson.JsonArray array = new com.google.gson.JsonArray();
                for (int i = 1; i <= table.length(); i++) {
                    array.add(convertLuaToJson(table.get(i)));
                }
                return array;
            } else { // It's a standard dictionary map
                com.google.gson.JsonObject object = new com.google.gson.JsonObject();
                for (LuaValue key : table.keys()) {
                    object.add(key.tojstring(), convertLuaToJson(table.get(key)));
                }
                return object;
            }
        }
        return com.google.gson.JsonNull.INSTANCE;
    }

    @SuppressWarnings("unchecked")
    private <T> void injectComponent(Item.Settings settings, ComponentType<T> type, Object value) {
        settings.component(type, (T) value);
    }

    private Identifier registerBlock(String name, LuaValue settingsTable) {
        Identifier id = Identifier.of(modId, name);
        RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, id);
        RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, id);

        AbstractBlock.Settings blockSettings = AbstractBlock.Settings.create().registryKey(blockKey);

        if (settingsTable.istable()) {
            LuaTable table = settingsTable.checktable();

            float hardness = (float) table.get("hardness").optdouble(1.0);
            float resistance = (float) table.get("resistance").optdouble(hardness);
            blockSettings.strength(hardness, resistance);

            String soundGroup = table.get("sound").optjstring("stone").toLowerCase();
            blockSettings.sounds(SOUND_GROUPS.getOrDefault(soundGroup, BlockSoundGroup.STONE));

            if (table.get("no_collision").optboolean(false)) {
                blockSettings.noCollision();
            }

            if (table.get("luminance").isint()) {
                int light = table.get("luminance").toint();
                if (light < 0 || light > 15) {
                    throw new LuaError("Luminance must be between 0 and 15");
                }
                blockSettings.luminance(state -> light);
            }

            if (table.get("transparent").optboolean(false)) {
                blockSettings.nonOpaque();
            }

            if (table.get("slippery").isboolean()) {
                float slipperiness = table.get("slippery").toboolean() ? 0.98f : 0.6f;
                blockSettings.slipperiness(slipperiness);
            } else if (table.get("slipperiness").isnumber()) {
                blockSettings.slipperiness((float) table.get("slipperiness").todouble());
            }

            if (table.get("requires_tool").optboolean(false)) {
                blockSettings.requiresTool();
            }
        }

        Block block = new Block(blockSettings);
        Registry.register(Registries.BLOCK, blockKey, block);

        Item.Settings itemSettings = new Item.Settings().registryKey(itemKey);
        BlockItem item = new BlockItem(block, itemSettings);
        Registry.register(Registries.ITEM, itemKey, item);

        blockCache.put(id.toString(), block);
        itemCache.put(id.toString(), item);

        LOGGER.info("Registered block & item: {}", id);
        return id;
    }

    private Item registerItem(String name, LuaValue settingsTable) {
        Identifier id = Identifier.of(modId, name);
        RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, id);

        Item.Settings settings = new Item.Settings().registryKey(itemKey);

        if (settingsTable.istable()) {
            LuaTable table = settingsTable.checktable();

            settings.maxCount(table.get("max_stack").optint(64));

            LuaValue componentsValue = table.get("components");
            if (componentsValue.istable()) {
                LuaTable componentsTable = componentsValue.checktable();
                LuaValue[] keys = componentsTable.keys();

                for (LuaValue luaKey : keys) {
                    String componentKey = luaKey.tojstring();
                    LuaValue luaData = componentsTable.get(luaKey);

                    Identifier componentId = Identifier.of(componentKey);
                    ComponentType<?> componentType = Registries.DATA_COMPONENT_TYPE.get(componentId);

                    if (componentType != null && componentType.getCodec() != null) {
                        try {
                            com.google.gson.JsonElement jsonElement = convertLuaToJson(luaData);

                            Object componentObject = componentType.getCodec()
                                    .parse(JsonOps.INSTANCE, jsonElement)
                                    .getOrThrow(msg -> new RuntimeException("Failed to parse component: " + msg));

                            injectComponent(settings, componentType, componentObject);
                            LOGGER.info("Successfully bound component data dynamically: {}", componentId);

                        } catch (Exception e) {
                            LOGGER.error("Failed to dynamically bind component '{}': {}", componentKey, e.getMessage());
                        }
                    } else {
                        LOGGER.warn("Skipping component '{}': Not found or lacks a registered codec.", componentKey);
                    }
                }
            }
        }

        Item item = new Item(settings);
        Registry.register(Registries.ITEM, itemKey, item);

        itemCache.put(id.toString(), item);
        LOGGER.info("Successfully registered item to game registries: {}", id);

        return item;
    }


    private Identifier registerSound(String name) {
        Identifier id = Identifier.of(modId, name);
        SoundEvent sound = SoundEvent.of(id);

        Registry.register(Registries.SOUND_EVENT, id, sound);
        soundCache.put(id.toString(), sound);

        LOGGER.info("Registered sound: {}", id);
        return id;
    }


    private StatusEffect registerStatusEffect(String name, LuaValue settingsTable) {
        Identifier id = Identifier.of(modId, name);

        StatusEffectCategory category = StatusEffectCategory.BENEFICIAL;
        int color = 0x00FF00;

        if (settingsTable.istable()) {
            LuaTable table = settingsTable.checktable();

            String categoryStr = table.get("category").optjstring("beneficial").toLowerCase();
            category = switch (categoryStr) {
                case "harmful" -> StatusEffectCategory.HARMFUL;
                case "neutral" -> StatusEffectCategory.NEUTRAL;
                default -> StatusEffectCategory.BENEFICIAL;
            };

            if (table.get("color").isint()) {
                color = table.get("color").toint();
            } else if (table.get("color").isstring()) {
                String colorStr = table.get("color").tojstring();
                try {
                    color = Integer.parseInt(colorStr.replace("#", ""), 16);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid color format: {}, using default", colorStr);
                }
            }
        }

        StatusEffect effect = new StatusEffect(category, color) {
        };
        Registry.register(Registries.STATUS_EFFECT, id, effect);

        effectCache.put(id.toString(), effect);
        LOGGER.info("Registered status effect: {}", id);
        return effect;
    }


    private Identifier registerParticle(String name) {
        Identifier id = Identifier.of(modId, name);

        SimpleParticleType particle = net.fabricmc.fabric.api.particle.v1.FabricParticleTypes.simple();
        Registry.register(Registries.PARTICLE_TYPE, id, particle);

        particleCache.put(id.toString(), particle);
        LOGGER.info("Registered particle: {} - Note: Requires client-side renderer registration", id);
        return id;
    }

    private final Map<RegistryKey<ItemGroup>, List<Item>> pendingGroupEntries = new HashMap<>();

    public void registerGroupEvents() {
        pendingGroupEntries.forEach((groupKey, itemList) -> {
            ItemGroupEvents.modifyEntriesEvent(groupKey).register(entries -> {
                itemList.forEach(entries::add);
            });
        });
    }

    private void addToItemGroup(String itemName, String groupName) {
        String fullId = itemName.contains(":") ? itemName : modId + ":" + itemName;
        Item item = itemCache.get(fullId);

        if (item == null) {
            throw new LuaError("Cannot add '" + fullId + "' to group - not registered yet.");
        }

        RegistryKey<ItemGroup> groupKey;

        groupKey = switch (groupName.toLowerCase()) {
            case "building" -> ItemGroups.BUILDING_BLOCKS;
            case "natural" -> ItemGroups.NATURAL;
            case "functional" -> ItemGroups.FUNCTIONAL;
            case "redstone" -> ItemGroups.REDSTONE;
            case "tools" -> ItemGroups.TOOLS;
            case "combat" -> ItemGroups.COMBAT;
            case "food" -> ItemGroups.FOOD_AND_DRINK;
            case "ingredients" -> ItemGroups.INGREDIENTS;
            case "spawn_eggs" -> ItemGroups.SPAWN_EGGS;
            case "operator" -> ItemGroups.OPERATOR;
            default -> null; // Not vanilla, check custom registry next
        };

        if (groupKey == null) {
            Identifier customGroupIdent = Identifier.of(modId, groupName.toLowerCase());
            groupKey = RegistryKey.of(Registries.ITEM_GROUP.getKey(), customGroupIdent);
        }

        pendingGroupEntries.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(item);
        LOGGER.info("Queued {} for group {}", fullId, groupName);
    }

    private Identifier createCustomGroup(String name, LuaValue settings) {
        Identifier id = Identifier.of(modId, name);
        LuaTable table = settings.checktable();

        String iconId = table.get("icon").optjstring("minecraft:apple");
        String title = table.get("title").optjstring(name);

        final Identifier finalIconIdentifier = Identifier.of(iconId);

        RegistryKey<ItemGroup> groupKey = RegistryKey.of(Registries.ITEM_GROUP.getKey(), id);

        ItemGroup group = FabricItemGroup.builder()
                .icon(() -> new ItemStack(Registries.ITEM.get(finalIconIdentifier)))
                .displayName(Text.literal(title))
                .entries((context, entries) -> {
                    List<Item> items = pendingGroupEntries.getOrDefault(groupKey, List.of());
                    items.forEach(entries::add);
                })
                .build();

        Registry.register(Registries.ITEM_GROUP, id, group);
        LOGGER.info("Created custom item group: {}", id);
        return id;
    }

}
