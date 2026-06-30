package net.peasoup.language.lua.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.peasoup.language.lua.LuaModContainer;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.ArrayList;
import java.util.List;

/**
 * Config editor screen for Lua mods
 */
public class LuaModConfigScreen extends Screen {
    private static final int ENTRY_HEIGHT = 30;
    private static final int PADDING = 10;
    private final Screen parent;
    private final LuaModContainer mod;
    private final List<ConfigEntry> entries = new ArrayList<>();
    private int scrollOffset = 0;
    private boolean modified = false;

    public LuaModConfigScreen(Screen parent, LuaModContainer mod) {
        super(Text.literal("config: " + mod.getMetadata().name));
        this.parent = parent;
        this.mod = mod;

        loadConfig();
    }

    private void loadConfig() {
        entries.clear();

        try {
            // Get all config values from Lua
            Globals globals = mod.getGlobals();
            LuaValue configTable = globals.get("config");

            if (configTable.isnil()) {
                return;
            }

            LuaValue getAllFunc = configTable.get("get_all");
            if (getAllFunc.isfunction()) {
                LuaValue allConfig = getAllFunc.call();

                if (allConfig.istable()) {
                    LuaTable table = allConfig.checktable();
                    parseConfigTable("", table);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void parseConfigTable(String prefix, LuaTable table) {
        LuaValue key = LuaValue.NIL;

        while (true) {
            Varargs next = table.next(key);
            if ((key = next.arg1()).isnil()) break;

            LuaValue value = next.arg(2);
            String fullKey = prefix.isEmpty() ? key.tojstring() : prefix + "." + key.tojstring();

            if (value.istable()) {
                // Nested config
                parseConfigTable(fullKey, value.checktable());
            } else {
                // Leaf value
                entries.add(new ConfigEntry(fullKey, value));
            }
        }
    }

    @Override
    protected void init() {
        int y = this.height - 30;

        // Save button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("💾 Save"), button -> save()).dimensions(this.width / 2 - 155, y, 100, 20).build());

        // Cancel button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> this.close()).dimensions(this.width / 2 - 50, y, 100, 20).build());

        // Reset button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("🔄 Reset"), button -> reset()).dimensions(this.width / 2 + 55, y, 100, 20).build());

        // Create text fields for each entry
        int entryY = 50;
        for (ConfigEntry entry : entries) {
            if (entryY > this.height - 70) break;

            TextFieldWidget field = new TextFieldWidget(this.textRenderer, this.width / 2 + 10, entryY, 200, 20, Text.literal(entry.key));
            field.setMaxLength(100);
            field.setText(entry.getCurrentValue());
            field.setChangedListener(text -> {
                entry.setModifiedValue(text);
                modified = true;
            });

            entry.widget = field;
            this.addSelectableChild(field);

            entryY += ENTRY_HEIGHT;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta); // Call super first

        // Title and modified indicator
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);
        if (modified) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("● Modified").formatted(Formatting.YELLOW), this.width / 2 - 150, 25, 0xFFAA00);
        }

        // Config entries
        int entryY = 50;
        int index = 0;

        for (ConfigEntry entry : entries) {
            if (index < scrollOffset) {
                index++;
                continue;
            }

            if (entryY > this.height - 70) break;

            // Background
            context.fill(this.width / 2 - 210, entryY - 2, this.width / 2 + 215, entryY + 23, 0x80202020);

            // Key name
            context.drawTextWithShadow(this.textRenderer, entry.key, this.width / 2 - 200, entryY + 5, 0xFFFFFF);

            // Value field (already rendered by widget)
            if (entry.widget != null) {
                entry.widget.setY(entryY);
                entry.widget.render(context, mouseX, mouseY, delta);
            }

            entryY += ENTRY_HEIGHT;
            index++;
        }

        // Scroll hint
        if (entries.size() * ENTRY_HEIGHT > this.height - 120) {
            context.drawTextWithShadow(this.textRenderer, "↕ Scroll", this.width / 2 + 160, this.height - 50, 0x888888);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, entries.size() - (this.height - 120) / ENTRY_HEIGHT);
        scrollOffset = Math.max(0, Math.min(maxScroll, (int) (scrollOffset - verticalAmount)));
        return true;
    }

    private void save() {
        try {
            Globals globals = mod.getGlobals();
            LuaValue configTable = globals.get("config");

            if (configTable.isnil()) {
                return;
            }

            // Set each modified value
            LuaValue setFunc = configTable.get("set");
            for (ConfigEntry entry : entries) {
                if (entry.isModified()) {
                    LuaValue luaValue = convertToLua(entry.getCurrentValue(), entry.originalValue);
                    setFunc.call(LuaValue.valueOf(entry.key), luaValue);
                }
            }

            // Save to file
            LuaValue saveFunc = configTable.get("save");
            if (saveFunc.isfunction()) {
                saveFunc.call();
            }

            modified = false;

            if (client != null && client.player != null) {
                client.player.sendMessage(Text.literal("✓ Config saved").formatted(Formatting.GREEN), false);
            }

            this.close();

        } catch (Exception e) {
            if (client != null && client.player != null) {
                client.player.sendMessage(Text.literal("✗ Failed to save config: " + e.getMessage()).formatted(Formatting.RED), false);
            }
        }
    }

    private void reset() {
        loadConfig();
        modified = false;
        this.clearAndInit();
    }

    private LuaValue convertToLua(String text, LuaValue original) {
        // Try to preserve type
        if (original.isboolean()) {
            return LuaValue.valueOf(Boolean.parseBoolean(text));
        } else if (original.isnumber()) {
            try {
                if (text.contains(".")) {
                    return LuaValue.valueOf(Double.parseDouble(text));
                } else {
                    return LuaValue.valueOf(Long.parseLong(text));
                }
            } catch (NumberFormatException e) {
                return LuaValue.valueOf(text);
            }
        }
        return LuaValue.valueOf(text);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private static class ConfigEntry {
        final String key;
        final LuaValue originalValue;
        String modifiedValue;
        TextFieldWidget widget;

        ConfigEntry(String key, LuaValue value) {
            this.key = key;
            this.originalValue = value;
            this.modifiedValue = null;
        }

        String getCurrentValue() {
            return modifiedValue != null ? modifiedValue : originalValue.tojstring();
        }

        void setModifiedValue(String value) {
            this.modifiedValue = value;
        }

        boolean isModified() {
            return modifiedValue != null && !modifiedValue.equals(originalValue.tojstring());
        }
    }
}