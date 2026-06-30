package net.peasoup.language.lua.api;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.attribute.ClampedEntityAttribute;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.*;
import net.minecraft.item.equipment.ArmorMaterial;
import net.minecraft.item.equipment.ArmorMaterials;
import net.minecraft.item.equipment.EquipmentType;
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
import net.minecraft.util.Rarity;
import net.peasoup.language.lua.LuaBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Enhanced Lua API for registering game content
 * Supports blocks, items, entities, sounds, particles, and more
 */
public class RegistryAPI {
    private static final Logger LOGGER = LogManager.getLogger("RegistryAPI");
    private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Map<String, BlockSoundGroup> SOUND_GROUPS = new HashMap<>();

    static {
        // Initialize sound groups
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
    // Central storage for everything we've registered
    private final Map<String, Block> blockCache = new HashMap<>();
    private final Map<String, Item> itemCache = new HashMap<>();
    private final Map<String, SoundEvent> soundCache = new HashMap<>();
    private final Map<String, StatusEffect> effectCache = new HashMap<>();
    private final Map<String, ParticleType<?>> particleCache = new HashMap<>();
    private final Map<String, Enchantment> enchantmentCache = new HashMap<>();
    private final Map<String, Potion> potionCache = new HashMap<>();
    private final Map<String, RegistryKey<DamageType>> damageTypeCache = new HashMap<>();
    private final Map<String, EntityAttribute> attributeCache = new HashMap<>();

    // ==========================================
    // VALIDATION
    // ==========================================

    public RegistryAPI(String modId) {
        this.modId = modId;
    }

    private static @NotNull Item getItem(String type, ArmorMaterial material, Item.Settings settings) {
        EquipmentType equipmentType = switch (type) {
            case "helmet" -> EquipmentType.HELMET;
            case "chestplate" -> EquipmentType.CHESTPLATE;
            case "leggings" -> EquipmentType.LEGGINGS;
            case "boots" -> EquipmentType.BOOTS;
            case "body" -> EquipmentType.BODY;
            default ->
                    throw new LuaError("Unknown armor type: " + type + ". Valid types: helmet, chestplate, leggings, boots");
        };

        return new ArmorItem(material, equipmentType, settings);
    }

    // Tool materials are accessed directly when needed
    private ToolMaterial getToolMaterial(String name) {
        return switch (name.toLowerCase()) {
            case "wood", "wooden" -> ToolMaterial.WOOD;
            case "stone" -> ToolMaterial.STONE;
            case "iron" -> ToolMaterial.IRON;
            case "gold", "golden" -> ToolMaterial.GOLD;
            case "diamond" -> ToolMaterial.DIAMOND;
            case "netherite" -> ToolMaterial.NETHERITE;
            default -> null;
        };
    }

    // Armor materials are accessed directly when needed
    private ArmorMaterial getArmorMaterial(String name) {
        return switch (name.toLowerCase()) {
            case "leather" -> ArmorMaterials.LEATHER;
            case "chain", "chainmail" -> ArmorMaterials.CHAIN;
            case "iron" -> ArmorMaterials.IRON;
            case "gold", "golden" -> ArmorMaterials.GOLD;
            case "diamond" -> ArmorMaterials.DIAMOND;
            case "turtle_scute" -> ArmorMaterials.TURTLE_SCUTE;
            case "armadillo_scute" -> ArmorMaterials.ARMADILLO_SCUTE;
            case "netherite" -> ArmorMaterials.NETHERITE;
            default -> null;
        };
    }

    public void install(Globals globals) {
        LuaTable registry = new LuaTable();

        // ===== BLOCKS =====
        registry.set("register_block", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaValue.valueOf(registerBlock(name.tojstring(), settings).toString());
            }
        });

        // ===== ITEMS =====
        registry.set("register_item", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerItem(name.tojstring(), settings));
            }
        });

        registry.set("register_tool", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerTool(name.tojstring(), settings));
            }
        });

        registry.set("register_armor", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerArmor(name.tojstring(), settings));
            }
        });

        // ===== SOUNDS =====
        registry.set("register_sound", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                validateName(name.tojstring());
                return LuaValue.valueOf(registerSound(name.tojstring()).toString());
            }
        });

        // ===== STATUS EFFECTS =====
        registry.set("register_effect", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerStatusEffect(name.tojstring(), settings));
            }
        });

        // ===== PARTICLES =====
        registry.set("register_particle", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                validateName(name.tojstring());
                return LuaValue.valueOf(registerParticle(name.tojstring()).toString());
            }
        });

        // ===== ITEM GROUPS =====
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

        // ===== UTILITY =====
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

        // ===== ENCHANTMENTS =====
        registry.set("register_enchantment", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerEnchantment(name.tojstring(), settings));
            }
        });

// ===== POTIONS =====
        registry.set("register_potion", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerPotion(name.tojstring(), settings));
            }
        });

// ===== DAMAGE TYPES =====
        registry.set("register_damage_type", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaValue.valueOf(registerDamageType(name.tojstring(), settings).toString());
            }
        });

// ===== GAME RULES =====
        registry.set("register_game_rule", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaValue.valueOf(registerGameRule(name.tojstring(), settings));
            }
        });

// ===== ATTRIBUTES =====
        registry.set("register_attribute", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerAttribute(name.tojstring(), settings));
            }
        });

// ===== RECIPE TYPES =====
        registry.set("register_recipe_type", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue settings) {
                validateName(name.tojstring());
                return LuaBridge.toLua(registerRecipeType(name.tojstring(), settings));
            }
        });

// ===== BLOCK ENTITIES =====
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
        // Note: Enchantments in 1.21.4+ use data-driven system
        // This requires datapack files, not registry
        // We'll create the datapack JSON instead

        Identifier id = Identifier.of(modId, name);

        if (!settingsTable.istable()) {
            throw new LuaError("Enchantment requires settings table");
        }

        LuaTable table = settingsTable.checktable();

        // Extract settings
        int maxLevel = table.get("max_level").optint(1);
        String weight = table.get("weight").optjstring("common"); // common, uncommon, rare, very_rare
        int minCost = table.get("min_cost").optint(1);
        int maxCost = table.get("max_cost").optint(30);

        LOGGER.info("Registered enchantment: {} (requires datapack JSON)", id);

        // You'd need to generate the datapack JSON here
        // For now, just log it
        LOGGER.warn("Enchantments require datapack JSON files - use datagen API");

        return null; // Return a placeholder
    }

    // ==========================================
// POTION REGISTRATION
// ==========================================
    private Potion registerPotion(String name, LuaValue settingsTable) {
        Identifier id = Identifier.of(modId, name);

        if (!settingsTable.istable()) {
            throw new LuaError("Potion requires settings table");
        } 

        LuaTable table = settingsTable.checktable();

        // Get base effect
        String effectId = table.get("effect").optjstring("");
        if (effectId.isEmpty()) {
            throw new LuaError("Potion requires 'effect' parameter");
        }

        // Look up the status effect
        Identifier effectIdentifier = Identifier.tryParse(effectId.contains(":") ? effectId : modId + ":" + effectId);
        RegistryEntry<StatusEffect> effect = Registries.STATUS_EFFECT.getEntry(effectIdentifier)
                .orElseThrow(() -> new LuaError("Unknown status effect: " + effectId));

        int duration = table.get("duration").optint(3600); // 3 minutes default (20 ticks/sec * 60 * 3)
        int amplifier = table.get("amplifier").optint(0);

        // Create status effect instance
        StatusEffectInstance effectInstance = new StatusEffectInstance(effect, duration, amplifier);

        // Create potion with varargs (can have multiple effects!)
        Potion potion = new Potion(name, effectInstance);

        // Register
        Registry.register(Registries.POTION, id, potion);
        potionCache.put(id.toString(), potion);

        LOGGER.info("Registered potion: {}", id);
        return potion;
    }

    // ==========================================
// DAMAGE TYPE REGISTRATION
// ==========================================
    private Identifier registerDamageType(String name, LuaValue settingsTable) {
        Identifier id = Identifier.of(modId, name);
        RegistryKey<DamageType> key = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, id);

        if (!settingsTable.istable()) {
            throw new LuaError("Damage type requires settings table");
        }

        LuaTable table = settingsTable.checktable();

        // Extract settings
        String msgId = table.get("message_id").optjstring(name);
        String scaling = table.get("scaling").optjstring("when_caused_by_living_non_player");
        float exhaustion = (float) table.get("exhaustion").optdouble(0.1);

        damageTypeCache.put(id.toString(), key);

        LOGGER.info("Registered damage type: {} (requires datapack JSON)", id);
        LOGGER.warn("Damage types require datapack JSON files - use datagen API");

        return id;
    }

    // ==========================================
// GAME RULE REGISTRATION
// ==========================================
    private String registerGameRule(String name, LuaValue settingsTable) {
        if (!settingsTable.istable()) {
            throw new LuaError("Game rule requires settings table");
        }

        LuaTable table = settingsTable.checktable();

        String type = table.get("type").optjstring("boolean");

        // Game rules are registered statically, so we can't truly register them dynamically
        // But we can create a helper for creating game rule keys

        LOGGER.warn("Game rules must be registered in Java code statically");
        LOGGER.info("Game rule '{}' noted for manual registration", name);

        return modId + ":" + name;
    }

    // ==========================================
// ATTRIBUTE REGISTRATION
// ==========================================
    private EntityAttribute registerAttribute(String name, LuaValue settingsTable) {
        Identifier id = Identifier.of(modId, name);

        if (!settingsTable.istable()) {
            throw new LuaError("Attribute requires settings table");
        }

        LuaTable table = settingsTable.checktable();

        double defaultValue = table.get("default").optdouble(0.0);
        double min = table.get("min").optdouble(0.0);
        double max = table.get("max").optdouble(1024.0);

        // Create attribute
        EntityAttribute attribute = new ClampedEntityAttribute("attribute.name." + modId + "." + name, defaultValue, min, max).setTracked(true);

        // Register
        Registry.register(Registries.ATTRIBUTE, id, attribute);
        attributeCache.put(id.toString(), attribute);

        LOGGER.info("Registered attribute: {}", id);
        return attribute;
    }

    // ==========================================
// RECIPE TYPE REGISTRATION
// ==========================================
    private RecipeType<?> registerRecipeType(String name, LuaValue settingsTable) {
        Identifier id = Identifier.of(modId, name);

        // Create simple recipe type
        RecipeType<?> recipeType = new RecipeType<Recipe<?>>() {
            @Override
            public String toString() {
                return id.toString();
            }
        };

        // Register
        Registry.register(Registries.RECIPE_TYPE, id, recipeType);

        LOGGER.info("Registered recipe type: {}", id);
        return recipeType;
    }

    // ==========================================
// BLOCK ENTITY TYPE REGISTRATION
// ==========================================
    private BlockEntityType<?> registerBlockEntityType(String name, LuaValue settingsTable) {
        if (!settingsTable.istable()) {
            throw new LuaError("Block entity type requires settings table");
        }

        LuaTable table = settingsTable.checktable();

        // Get blocks this entity type applies to
        LuaValue blocksValue = table.get("blocks");
        if (!blocksValue.istable()) {
            throw new LuaError("Block entity type requires 'blocks' table parameter");
        }

        // This is complex - block entities need actual Java classes
        // For Lua, we'd need to generate bytecode or use a generic wrapper

        LOGGER.warn("Block entity types require Java classes - cannot create from Lua");
        LOGGER.info("Block entity type '{}' noted but not registered", name);

        return null;
    }

    // ==========================================
    // BLOCK REGISTRATION
    // ==========================================

    private void validateName(String name) {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new LuaError("Invalid registry name: '" + name + "'. " + "Must be lowercase, start with a letter, and contain only letters, numbers, and underscores.");
        }
    }

    // ==========================================
    // ITEM REGISTRATION
    // ==========================================

    private Identifier registerBlock(String name, LuaValue settingsTable) {
        Identifier id = Identifier.of(modId, name);
        RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, id);
        RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, id);

        AbstractBlock.Settings blockSettings = AbstractBlock.Settings.create().registryKey(blockKey);

        if (settingsTable.istable()) {
            LuaTable table = settingsTable.checktable();

            // Basic properties
            float hardness = (float) table.get("hardness").optdouble(1.0);
            float resistance = (float) table.get("resistance").optdouble(hardness);
            blockSettings.strength(hardness, resistance);

            // Sound
            String soundGroup = table.get("sound").optjstring("stone").toLowerCase();
            blockSettings.sounds(SOUND_GROUPS.getOrDefault(soundGroup, BlockSoundGroup.STONE));

            // Collision
            if (table.get("no_collision").optboolean(false)) {
                blockSettings.noCollision();
            }

            // Light
            if (table.get("luminance").isint()) {
                int light = table.get("luminance").toint();
                if (light < 0 || light > 15) {
                    throw new LuaError("Luminance must be between 0 and 15");
                }
                blockSettings.luminance(state -> light);
            }

            // Transparency
            if (table.get("transparent").optboolean(false)) {
                blockSettings.nonOpaque();
            }

            // Slipperiness
            if (table.get("slippery").isboolean()) {
                float slipperiness = table.get("slippery").toboolean() ? 0.98f : 0.6f;
                blockSettings.slipperiness(slipperiness);
            } else if (table.get("slipperiness").isnumber()) {
                blockSettings.slipperiness((float) table.get("slipperiness").todouble());
            }

            // Requires tool
            if (table.get("requires_tool").optboolean(false)) {
                blockSettings.requiresTool();
            }
        }

        Block block = new Block(blockSettings);
        Registry.register(Registries.BLOCK, blockKey, block);

        // Create block item
        BlockItem item = new BlockItem(block, new Item.Settings().registryKey(itemKey));
        Registry.register(Registries.ITEM, itemKey, item);

        // Cache
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

            // Stack size
            settings.maxCount(table.get("max_stack").optint(64));

            // Durability
            if (table.get("durability").isint()) {
                settings.maxDamage(table.get("durability").toint());
            }

            // Rarity
            String rarityStr = table.get("rarity").optjstring("common").toLowerCase();
            Rarity rarity = switch (rarityStr) {
                case "uncommon" -> Rarity.UNCOMMON;
                case "rare" -> Rarity.RARE;
                case "epic" -> Rarity.EPIC;
                default -> Rarity.COMMON;
            };
            settings.rarity(rarity);

            // Fire resistant
            if (table.get("fire_resistant").optboolean(false)) {
                settings.fireproof();
            }

            // Food
            LuaValue food = table.get("food");
            if (food.istable()) {
                FoodComponent.Builder foodBuilder = new FoodComponent.Builder().nutrition(food.get("nutrition").optint(4)).saturationModifier((float) food.get("saturation").optdouble(0.3));

                if (food.get("always_edible").optboolean(false)) {
                    foodBuilder.alwaysEdible();
                }


                settings.food(foodBuilder.build());
            }
        }

        Item item = new Item(settings);
        Registry.register(Registries.ITEM, itemKey, item);

        itemCache.put(id.toString(), item);
        LOGGER.info("Registered item: {}", id);
        return item;
    }

    /**
     * Register a tool with proper attack values
     */
    private Item registerTool(String name, LuaValue settingsTable) {
        if (!settingsTable.istable()) {
            throw new LuaError("Tool requires settings table");
        }

        LuaTable table = settingsTable.checktable();
        String type = table.get("type").optjstring("sword").toLowerCase();
        String materialName = table.get("material").optjstring("iron").toLowerCase();

        ToolMaterial material = getToolMaterial(materialName);
        if (material == null) {
            throw new LuaError("Unknown tool material: " + materialName + ". Valid materials: wood, stone, iron, gold, diamond, netherite");
        }

        Identifier id = Identifier.of(modId, name);
        RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, id);
        Item.Settings settings = new Item.Settings().registryKey(itemKey);

        // Get custom attack values if provided, otherwise use defaults based on type
        Item item = switch (type) {
            case "sword" -> {
                float attackDamage = (float) table.get("attack_damage").optdouble(3.0);
                float attackSpeed = (float) table.get("attack_speed").optdouble(-2.4);
                yield new SwordItem(material, attackDamage, attackSpeed, settings);
            }
            case "pickaxe" -> {
                float attackDamage = (float) table.get("attack_damage").optdouble(1.0);
                float attackSpeed = (float) table.get("attack_speed").optdouble(-2.8);
                yield new PickaxeItem(material, attackDamage, attackSpeed, settings);
            }
            case "axe" -> {
                float attackDamage = (float) table.get("attack_damage").optdouble(6.0);
                float attackSpeed = (float) table.get("attack_speed").optdouble(-3.0);
                yield new AxeItem(material, attackDamage, attackSpeed, settings);
            }
            case "shovel" -> {
                float attackDamage = (float) table.get("attack_damage").optdouble(1.5);
                float attackSpeed = (float) table.get("attack_speed").optdouble(-3.0);
                yield new ShovelItem(material, attackDamage, attackSpeed, settings);
            }
            case "hoe" -> {
                float attackDamage = (float) table.get("attack_damage").optdouble(0.0);
                float attackSpeed = (float) table.get("attack_speed").optdouble(-3.0);
                yield new HoeItem(material, attackDamage, attackSpeed, settings);
            }
            default ->
                    throw new LuaError("Unknown tool type: " + type + ". Valid types: sword, pickaxe, axe, shovel, hoe");
        };

        Registry.register(Registries.ITEM, itemKey, item);

        itemCache.put(id.toString(), item);
        LOGGER.info("Registered tool: {} ({})", id, type);
        return item;
    }

    /**
     * Register armor pieces
     */
    private Item registerArmor(String name, LuaValue settingsTable) {
        if (!settingsTable.istable()) {
            throw new LuaError("Armor requires settings table");
        }

        LuaTable table = settingsTable.checktable();
        String type = table.get("type").optjstring("helmet").toLowerCase();
        String materialName = table.get("material").optjstring("iron").toLowerCase();

        ArmorMaterial material = getArmorMaterial(materialName);
        if (material == null) {
            throw new LuaError("Unknown armor material: " + materialName + ". Valid materials: leather, chain, iron, gold, diamond, turtle, netherite");
        }

        Identifier id = Identifier.of(modId, name);
        RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, id);
        Item.Settings settings = new Item.Settings().registryKey(itemKey);

        Item item = getItem(type, material, settings);
        Registry.register(Registries.ITEM, itemKey, item);

        itemCache.put(id.toString(), item);
        LOGGER.info("Registered armor: {} ({})", id, type);
        return item;
    }

    // ==========================================
    // SOUND REGISTRATION
    // ==========================================

    private Identifier registerSound(String name) {
        Identifier id = Identifier.of(modId, name);
        SoundEvent sound = SoundEvent.of(id);

        Registry.register(Registries.SOUND_EVENT, id, sound);
        soundCache.put(id.toString(), sound);

        LOGGER.info("Registered sound: {}", id);
        return id;
    }

    // ==========================================
    // STATUS EFFECT REGISTRATION
    // ==========================================

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

    // ==========================================
    // PARTICLE REGISTRATION
    // ==========================================

    private Identifier registerParticle(String name) {
        Identifier id = Identifier.of(modId, name);

        // Create particle type using FabricParticleTypes for proper initialization
        SimpleParticleType particle = net.fabricmc.fabric.api.particle.v1.FabricParticleTypes.simple();
        Registry.register(Registries.PARTICLE_TYPE, id, particle);

        particleCache.put(id.toString(), particle);
        LOGGER.info("Registered particle: {} - Note: Requires client-side renderer registration", id);
        return id;
    }

    // ==========================================
    // ITEM GROUP MANAGEMENT
    // ==========================================

    private void addToItemGroup(String itemName, String groupName) {
        String fullId = itemName.contains(":") ? itemName : modId + ":" + itemName;
        Item item = itemCache.get(fullId);

        if (item == null) {
            throw new LuaError("Cannot add '" + fullId + "' to group - not registered yet. " + "Register items before adding them to groups.");
        }

        RegistryKey<ItemGroup> groupKey = switch (groupName.toLowerCase()) {
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
            default -> throw new LuaError("Unknown item group: " + groupName);
        };

        ItemGroupEvents.modifyEntriesEvent(groupKey).register(entries -> entries.add(item));
        LOGGER.info("Added {} to group {}", fullId, groupName);
    }

    private Identifier createCustomGroup(String name, LuaValue settings) {
        Identifier id = Identifier.of(modId, name);
        LuaTable table = settings.checktable();

        String iconId = table.get("icon").optjstring("minecraft:apple");
        String title = table.get("title").optjstring(name);

        ItemGroup group = FabricItemGroup.builder().icon(() -> new ItemStack(Registries.ITEM.get(Identifier.of(iconId)))).displayName(Text.literal(title)).build();

        Registry.register(Registries.ITEM_GROUP, id, group);
        LOGGER.info("Created custom item group: {}", id);
        return id;
    }
}