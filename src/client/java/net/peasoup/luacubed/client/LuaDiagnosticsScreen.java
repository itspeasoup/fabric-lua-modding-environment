package net.peasoup.luacubed.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.peasoup.luacubed.LuaCubed;
import net.peasoup.luacubed.LuaModContainer;
import net.peasoup.luacubed.LuaModLoader;
import net.peasoup.luacubed.NotificationManager;

// diagnostics to help with debugging!
public class LuaDiagnosticsScreen extends Screen {
    private static final int LINE_HEIGHT = 12;
    private final Screen parent;
    private final LuaModLoader modLoader;
    private int scrollOffset = 0;
    private List<String> diagnosticLines;

    public LuaDiagnosticsScreen(Screen parent) {
        super(Text.literal("Lua Mods Diagnostics"));
        this.parent = parent;
        this.modLoader = LuaCubed.getModLoader();

        generateDiagnostics();
    }

    private void generateDiagnostics() {
        diagnosticLines = new ArrayList<>();

        diagnosticLines.add("§l§6=== lua modding environment diagnostics ===");
        diagnosticLines.add("");

        diagnosticLines.add("§e§loverall statistics:");
        diagnosticLines.add("  discovered mods: §f" + modLoader.getDiscoveredMetadata().size());
        diagnosticLines.add("  successfully loaded: §a" + modLoader.getLoadedMods().size());
        diagnosticLines.add("  load errors: §c" + modLoader.getLoadErrors().size());

        long crashedMods = modLoader.getLoadedMods().values().stream().filter(LuaModContainer::hasCrashed).count();
        diagnosticLines.add("  crashed mods: §c" + crashedMods);

        long disabledMods = modLoader.getLoadedMods().values().stream().filter(m -> m.getState() == LuaModContainer.ModState.disabled).count();
        diagnosticLines.add("  disabled mods: §7" + disabledMods);

        diagnosticLines.add("");

        if (!modLoader.getLoadErrors().isEmpty()) {
            diagnosticLines.add("§c§lload errors:");
            for (LuaModLoader.LoadError error : modLoader.getLoadErrors()) {
                diagnosticLines.add("  §c✗ §7[" + error.modId + "/" + error.state + "]");
                diagnosticLines.add("    §f" + error.message);
                if (error.cause != null) {
                    diagnosticLines.add("    §8" + error.cause.getClass().getSimpleName() + ": " + error.cause.getMessage());
                }
                diagnosticLines.add("");
            }
        }

        if (crashedMods > 0) {
            diagnosticLines.add("§c§lcrashed mod details:");
            for (LuaModContainer mod : modLoader.getLoadedMods().values()) {
                if (mod.hasCrashed()) {
                    diagnosticLines.add("  §c✗ §f" + mod.getMetadata().name + " §7(" + mod.getMetadata().id + ")");
                    diagnosticLines.add("    §7state: §f" + mod.getState());
                    diagnosticLines.add("    §7errors: §c" + mod.getErrors().size());

                    for (LuaModContainer.ModError error : mod.getErrors()) {
                        diagnosticLines.add("      §8- [" + error.state + "] " + error.message);
                    }
                    diagnosticLines.add("");
                }
            }
        }

        diagnosticLines.add("§a§lsuccessfully running mods:");
        for (LuaModContainer mod : modLoader.getLoadedMods().values()) {
            if (!mod.hasCrashed() && mod.getState() == LuaModContainer.ModState.running) {
                diagnosticLines.add("  §a✓ §f" + mod.getMetadata().name + " §7v" + mod.getMetadata().version);

                // Show config/datagen info
                List<String> features = new ArrayList<>();
                if (mod.getMetadata().hasConfig()) features.add("config");
                if (mod.getMetadata().hasDatagen()) features.add("datagen");
                if (!mod.getMetadata().dependencies.isEmpty())
                    features.add(mod.getMetadata().dependencies.size() + " deps");

                if (!features.isEmpty()) {
                    diagnosticLines.add("    §8[" + String.join(", ", features) + "]");
                }
            }
        }

        diagnosticLines.add("");
        diagnosticLines.add("§7end of diagnostics report");
    }

    @Override
    protected void init() {
        this.addDrawableChild(ButtonWidget.builder(Text.literal("refresh"), button -> {
            generateDiagnostics();
            scrollOffset = 0;
        }).dimensions(this.width / 2 - 155, this.height - 30, 100, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("copy to clipboard"), button -> copyToClipboard()).dimensions(this.width / 2 - 50, this.height - 30, 100, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("done"), button -> this.close()).dimensions(this.width / 2 + 55, this.height - 30, 100, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFFFF);

        int contentX = 20;
        int contentY = 30;
        int contentWidth = this.width - 40;
        int contentHeight = this.height - 70;

        context.fill(contentX, contentY, contentX + contentWidth, contentY + contentHeight, 0x80000000);

        int y = contentY + 5;
        int index = 0;

        for (String line : diagnosticLines) {
            if (index < scrollOffset) {
                index++;
                continue;
            }

            if (y + LINE_HEIGHT > contentY + contentHeight) break;

            context.drawTextWithShadow(this.textRenderer, Text.literal(line), contentX + 5, y, 0xFFFFFFFF);

            y += LINE_HEIGHT;
            index++;
        }

        if (diagnosticLines.size() * LINE_HEIGHT > contentHeight) {
            int scrollBarHeight = Math.max(20, (contentHeight * contentHeight) / (diagnosticLines.size() * LINE_HEIGHT));
            int scrollBarY = contentY + (int) ((contentHeight - scrollBarHeight) * ((float) scrollOffset / Math.max(1, diagnosticLines.size() - contentHeight / LINE_HEIGHT)));

            context.fill(contentX + contentWidth - 5, scrollBarY, contentX + contentWidth - 2, scrollBarY + scrollBarHeight, 0xFFAAAAAA);

            context.drawTextWithShadow(this.textRenderer, "↕", contentX + contentWidth - 15, contentY + 5, 0xFF888888);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int contentHeight = this.height - 70;
        int maxScroll = Math.max(0, diagnosticLines.size() - contentHeight / LINE_HEIGHT);
        scrollOffset = Math.max(0, Math.min(maxScroll, (int) (scrollOffset - verticalAmount)));
        return true;
    }

    private void copyToClipboard() {
        StringBuilder sb = new StringBuilder();
        for (String line : diagnosticLines) {
            sb.append(line.replaceAll("§.", "")).append("\n");
        }

        if (client != null) {
            client.keyboard.setClipboard(sb.toString());

            if (client.player != null) {
                NotificationManager.show(Text.literal("✓ diagnostics copied to clipboard"), NotificationManager.Type.INFO, 3000);
            }
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }
}