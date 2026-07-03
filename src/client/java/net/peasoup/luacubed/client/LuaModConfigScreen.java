package net.peasoup.luacubed.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.peasoup.luacubed.LuaModContainer;
import net.peasoup.luacubed.NotificationManager;

/**
 * Config editor screen for Lua mods
 */
public class LuaModConfigScreen extends Screen {
    private static final int ENTRY_HEIGHT = 30;
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
            if ((key = next.arg1()).isnil())
                break;

            LuaValue value = next.arg(2);
            String fullKey = prefix.isEmpty() ? key.tojstring() : prefix + "." + key.tojstring();

            if (value.istable()) {
                LuaTable t = value.checktable();
                // detect your custom ui tables
                if (!t.get("choices").isnil() || !t.get("min").isnil()) {
                    entries.add(new ConfigEntry(fullKey, value)); // treat as a leaf value
                } else {
                    parseConfigTable(fullKey, t); // standard nested table
                }
            } else {
                entries.add(new ConfigEntry(fullKey, value));
            }
        }
    }

    @Override
    protected void init() {
        int y = this.height - 30;

        // Save button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("💾 Save"), button -> save())
                .dimensions(this.width / 2 - 155, y, 100, 20).build());

        // Cancel button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> this.close())
                .dimensions(this.width / 2 - 50, y, 100, 20).build());

        // Reset button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("🔄 Reset"), button -> reset())
                .dimensions(this.width / 2 + 55, y, 100, 20).build());

        // Create text fields for each entry
        int entryY = 50;
        for (ConfigEntry entry : entries) {
            if (entryY > this.height - 70)
                break;
            int widgetX = this.width / 2 + 10;

            if (entry.originalValue.isboolean()) {
                // toggle button for booleans
                boolean current = Boolean.parseBoolean(entry.getCurrentValue());
                ButtonWidget btn = ButtonWidget.builder(Text.literal(current ? "true" : "false"), button -> {
                    boolean next = !button.getMessage().getString().equals("true");
                    button.setMessage(Text.literal(String.valueOf(next)));
                    entry.setModifiedValue(String.valueOf(next));
                    modified = true;
                }).dimensions(widgetX, entryY, 200, 20).build();
                entry.widget = btn; // you'll need to change entry.widget type to ClickableWidget
                this.addDrawableChild(btn);

            } else if (entry.originalValue.istable() && !entry.originalValue.get("choices").isnil()) {
                // cycling choice button (implement using minecraft's
                // CyclingButtonWidget.builder)
                // extract the array from entry.originalValue.get("choices") and set up the
                // builder!

            } else if (entry.originalValue.istable() && !entry.originalValue.get("min").isnil()) {
                // slider widget
                // extract min/max/def, and extend vanilla SliderWidget here to update
                // entry.setModifiedValue() on drag

            } else {
                // fallback to standard text field for raw strings/ints/floats
                TextFieldWidget field = new TextFieldWidget(this.textRenderer, widgetX, entryY, 200, 20,
                        Text.literal(entry.key));
                field.setMaxLength(100);
                field.setText(entry.getCurrentValue());
                field.setChangedListener(text -> {
                    entry.setModifiedValue(text);
                    modified = true;
                });
                entry.widget = field; // change ConfigEntry.widget to ClickableWidget to allow polymorphism
                this.addSelectableChild(field);
            }
            entryY += ENTRY_HEIGHT;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta); // Call super first

        // Title and modified indicator
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFFFF);
        if (modified) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("● Modified").formatted(Formatting.YELLOW),
                    this.width / 2 - 150, 25, 0xFFFFAA00);
        }

        // Config entries
        int entryY = 50;
        int index = 0;

        for (ConfigEntry entry : entries) {
            if (index < scrollOffset) {
                index++;
                continue;
            }

            if (entryY > this.height - 70)
                break;

            // Background
            context.fill(this.width / 2 - 210, entryY - 2, this.width / 2 + 215, entryY + 23, 0x80202020);

            // Key name
            context.drawTextWithShadow(this.textRenderer, entry.key, this.width / 2 - 200, entryY + 5, 0xFFFFFFFF);

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
            context.drawTextWithShadow(this.textRenderer, "↕ Scroll", this.width / 2 + 160, this.height - 50,
                    0xFF888888);
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
                NotificationManager.show(Text.literal("✓ Config saved"), NotificationManager.Type.INFO, 3000);
            }

            this.close();

        } catch (Exception e) {
            if (client != null && client.player != null) {
                NotificationManager.show(Text.literal("✗ Failed to save config: " + e.getMessage()),
                        NotificationManager.Type.ERROR, 3000);
            }
        }
    }

    private void reset() {
        if (mod.getMetadata().config != null && mod.getMetadata().config.defaultConfig != null) {
            Map<String, String> flatDefaults = new HashMap<>();
            flattenMap("", mod.getMetadata().config.defaultConfig, flatDefaults);

            for (ConfigEntry entry : entries) {
                if (flatDefaults.containsKey(entry.key)) {
                    entry.setModifiedValue(flatDefaults.get(entry.key));
                }
            }
            modified = true;
            this.clearAndInit(); // rebuilds the ui with new values
        }
    }

    // add this helper method right below reset()
    private void flattenMap(String prefix, Map<String, Object> map, Map<String, String> flat) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nestedMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> castedMap = (Map<String, Object>) nestedMap;
                flattenMap(key, castedMap, flat);
            } else {
                flat.put(key, String.valueOf(entry.getValue()));
            }
        }
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
        ClickableWidget widget;

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