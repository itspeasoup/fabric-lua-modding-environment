package net.peasoup.luacubed.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.peasoup.luacubed.LuaCubed;
import net.peasoup.luacubed.LuaModContainer;
import net.peasoup.luacubed.LuaModLoader;
import net.peasoup.luacubed.LuaModMetadata;
import net.peasoup.luacubed.NotificationManager;

public class LuaModsScreen extends Screen {
    private static final int MOD_ENTRY_HEIGHT = 36;
    private static final int SIDEBAR_WIDTH = 200;
    private static final int PADDING = 20;
    private final Screen parent;
    private final LuaModLoader modLoader;
    private List<LuaModContainer> modList;
    private int selectedIndex = -1;
    private int scrollOffset = 0;
    private ButtonWidget reloadButton;
    private ButtonWidget disableButton;
    private ButtonWidget configButton;
    private TextFieldWidget searchField;

    private String searchQuery = "";

    public LuaModsScreen(Screen parent) {
        super(Text.literal("lua mod list"));
        this.parent = parent;
        this.modLoader = LuaCubed.getModLoader();
        this.modList = new ArrayList<>(modLoader.getLoadedMods().values());
    }

    @Override
    protected void init() {
        this.searchField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 20, 200, 20,
                Text.literal("search mods..."));
        this.searchField.setMaxLength(50);
        this.searchField.setChangedListener(this::onSearchChanged);
        this.addSelectableChild(this.searchField);

        int buttonX = this.width - SIDEBAR_WIDTH;
        int buttonY = 50 + PADDING;
        int buttonWidth = SIDEBAR_WIDTH - PADDING * 2;

        this.reloadButton = ButtonWidget.builder(Text.literal("reload mod"), button -> reloadSelectedMod())
                .dimensions(buttonX, buttonY, buttonWidth, 20).build();
        this.addDrawableChild(this.reloadButton);

        buttonY += 25;
        this.disableButton = ButtonWidget.builder(Text.literal("disable mod"), button -> toggleSelectedMod())
                .dimensions(buttonX, buttonY, buttonWidth, 20).build();
        this.addDrawableChild(this.disableButton);

        buttonY += 25;
        this.configButton = ButtonWidget.builder(Text.literal("config"), button -> openConfig())
                .dimensions(buttonX, buttonY, buttonWidth, 20).build();
        this.addDrawableChild(this.configButton);

        buttonY += 30;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("reload all mods"), button -> reloadAllMods())
                .dimensions(buttonX, buttonY, buttonWidth, 20).build());

        buttonY += 25;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("diagnostics"), button -> openDiagnostics())
                .dimensions(buttonX, buttonY, buttonWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("done"), button -> this.close())
                .dimensions(this.width / 2 - 100, this.height - 30, 200, 20).build());

        updateButtonStates();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFFFF);
        renderModList(context, mouseX, mouseY);
        renderSidebar(context);

        this.searchField.render(context, mouseX, mouseY, delta);

        renderTooltips(context, mouseX, mouseY);
    }

    private void renderModList(DrawContext context, int mouseX, int mouseY) {
        int listWidth = this.width - SIDEBAR_WIDTH - PADDING * 3;
        int listX = PADDING;
        int listY = 50;
        int listHeight = this.height - 90;

        context.fill(listX, listY, listX + listWidth, listY + listHeight, 0x80000000);

        int y = listY + 5;
        int index = 0;

        for (LuaModContainer mod : modList) {
            if (index < scrollOffset) {
                index++;
                continue;
            }

            if (y + MOD_ENTRY_HEIGHT > listY + listHeight)
                break;

            boolean hovered = mouseX >= listX && mouseX <= listX + listWidth && mouseY >= y
                    && mouseY <= y + MOD_ENTRY_HEIGHT;
            boolean selected = index == selectedIndex;

            renderModEntry(context, mod, listX, y, listWidth, hovered, selected);

            y += MOD_ENTRY_HEIGHT;
            index++;
        }

        if (modList.size() * MOD_ENTRY_HEIGHT > listHeight) {
            context.drawTextWithShadow(this.textRenderer, "scroll", listX + listWidth - 40, listY + listHeight - 15,
                    0xFF888888);
        }
    }

    private void renderModEntry(DrawContext context, LuaModContainer mod, int x, int y, int width, boolean hovered,
            boolean selected) {
        LuaModMetadata meta = mod.getMetadata();

        int bgColor = selected ? 0x80505050 : (hovered ? 0x80303030 : 0x80202020);
        context.fill(x + 5, y, x + width - 5, y + MOD_ENTRY_HEIGHT - 2, bgColor);

        int stateColor = mod.hasCrashed() ? 0xFFFF0000
                : (mod.getState() == LuaModContainer.ModState.disabled ? 0xFF888888 : 0xFF00FF00);
        context.fill(x + 5, y + 5, x + 10, y + MOD_ENTRY_HEIGHT - 7, stateColor);

        String name = meta.name;
        if (name.length() > 25) {
            name = name.substring(0, 22) + "...";
        }

        String state = mod.hasCrashed() ? "crashed"
                : mod.getState() == LuaModContainer.ModState.disabled ? "disabled" : "running";
        Formatting stateFormat = mod.hasCrashed() ? Formatting.RED
                : mod.getState() == LuaModContainer.ModState.disabled ? Formatting.GRAY : Formatting.GREEN;
        int resolvedStateColor = stateFormat.getColorValue() != null ? stateFormat.getColorValue() : 0xFFFFFFFF;

        context.drawTextWithShadow(this.textRenderer, Text.literal(name).formatted(Formatting.BOLD), x + 15, y + 5,
                0xFFFFFFFF);

        context.drawTextWithShadow(this.textRenderer,
                Text.literal("v" + meta.version + " • " + meta.id).formatted(Formatting.GRAY), x + 15, y + 16,
                0xFF888888);

        context.drawTextWithShadow(this.textRenderer, Text.literal(state).formatted(stateFormat), x + width - 70,
                y + 10, resolvedStateColor);
    }

    private void renderSidebar(DrawContext context) {
        int sidebarX = this.width - SIDEBAR_WIDTH - PADDING;
        int sidebarY = 50;
        int sidebarHeight = this.height - 90;

        context.fill(sidebarX, sidebarY, this.width - PADDING, sidebarY + sidebarHeight, 0x80000000);

        if (selectedIndex >= 0 && selectedIndex < modList.size()) {
            LuaModContainer mod = modList.get(selectedIndex);
            LuaModMetadata meta = mod.getMetadata();

            int textX = sidebarX + PADDING;
            int textY = sidebarY + 185;
            int textWidth = SIDEBAR_WIDTH - PADDING * 2;

            context.drawTextWithShadow(this.textRenderer, Text.literal("selected mod").formatted(Formatting.GOLD),
                    textX, textY, 0xFFFFAA00);
            textY += 15;

            if (!meta.description.isEmpty()) {
                List<String> wrapped = wrapText(meta.description, textWidth);
                for (String line : wrapped) {
                    context.drawTextWithShadow(this.textRenderer, line, textX, textY, 0xFFAAAAAA);
                    textY += 10;
                }
                textY += 5;
            }

            if (meta.authors.length > 0) {
                context.drawTextWithShadow(this.textRenderer, Text.literal("authors: ").formatted(Formatting.GRAY),
                        textX, textY, 0xFF888888);
                textY += 10;
                context.drawTextWithShadow(this.textRenderer, String.join(", ", meta.authors), textX, textY,
                        0xFFAAAAAA);
                textY += 15;
            }

            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("state: ").formatted(Formatting.GRAY).append(mod.getState().toString()), textX, textY,
                    0xFFAAAAAA);
            textY += 10;

            if (!meta.dependencies.isEmpty()) {
                textY += 5;
                context.drawTextWithShadow(this.textRenderer, Text.literal("dependencies:").formatted(Formatting.GRAY),
                        textX, textY, 0xFF888888);
                textY += 10;

                for (LuaModMetadata.Dependency dep : meta.dependencies) {
                    String depText = "• " + dep.id + (dep.required ? "" : " (opt)");
                    context.drawTextWithShadow(this.textRenderer, depText, textX, textY, 0xFFAAAAAA);
                    textY += 10;
                }
            }

            if (mod.hasCrashed()) {
                textY += 5;
                context.drawTextWithShadow(this.textRenderer,
                        Text.literal("errors (" + mod.getErrors().size() + ")").formatted(Formatting.RED), textX,
                        textY, 0xFFFF5555);
            }
        } else {
            int textY = sidebarY + 165;

            context.drawCenteredTextWithShadow(this.textRenderer, "select a mod", sidebarX + SIDEBAR_WIDTH / 2, textY,
                    0xFF888888);

            textY += 15;

            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("total mods: " + modList.size()).formatted(Formatting.GRAY),
                    sidebarX + SIDEBAR_WIDTH / 2, textY, 0xFFAAAAAA);
            textY += 15;

            long crashed = modList.stream().filter(LuaModContainer::hasCrashed).count();
            if (crashed > 0) {
                context.drawCenteredTextWithShadow(this.textRenderer,
                        Text.literal("crashed: " + crashed).formatted(Formatting.RED), sidebarX + SIDEBAR_WIDTH / 2,
                        textY, 0xFFFF5555);
            }
        }
    }

    private void renderTooltips(DrawContext context, int mouseX, int mouseY) {
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int listWidth = this.width - SIDEBAR_WIDTH - PADDING * 3;
        int listX = PADDING;
        int listY = 50;
        int listHeight = this.height - 90;

        if (mouseX >= listX && mouseX <= listX + listWidth && mouseY >= listY && mouseY <= listY + listHeight) {

            int relativeY = (int) mouseY - listY - 5;
            int clickedIndex = scrollOffset + (relativeY / MOD_ENTRY_HEIGHT);

            if (clickedIndex >= 0 && clickedIndex < modList.size()) {
                selectedIndex = clickedIndex;
                updateButtonStates();
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, modList.size() - (this.height - 140) / MOD_ENTRY_HEIGHT);
        scrollOffset = Math.max(0, Math.min(maxScroll, (int) (scrollOffset - verticalAmount)));
        return true;
    }

    private void onSearchChanged(String query) {
        this.searchQuery = query.toLowerCase();
        filterMods();
        selectedIndex = -1;
        scrollOffset = 0;
        updateButtonStates();
    }

    private void filterMods() {
        if (searchQuery.isEmpty()) {
            modList = new ArrayList<>(modLoader.getLoadedMods().values());
        } else {
            modList = modLoader.getLoadedMods().values().stream().filter(mod -> {
                LuaModMetadata meta = mod.getMetadata();
                return meta.id.toLowerCase().contains(searchQuery) || meta.name.toLowerCase().contains(searchQuery)
                        || meta.description.toLowerCase().contains(searchQuery);
            }).toList();
        }
    }

    private void updateButtonStates() {
        boolean hasSelection = selectedIndex >= 0 && selectedIndex < modList.size();
        reloadButton.active = hasSelection;
        disableButton.active = hasSelection;
        if (hasSelection) {
            LuaModContainer mod = modList.get(selectedIndex);
            if (mod.getState() == LuaModContainer.ModState.disabled) {
                disableButton.setMessage(Text.literal("enable mod"));
            } else {
                disableButton.setMessage(Text.literal("disable mod"));
            }
        }
        configButton.active = hasSelection && modList.get(selectedIndex).getMetadata().hasConfig();
    }

    private void reloadSelectedMod() {
        if (selectedIndex < 0 || selectedIndex >= modList.size())
            return;

        LuaModContainer mod = modList.get(selectedIndex);
        boolean success = modLoader.reloadMod(mod.getMetadata().id);

        filterMods();

        if (client != null && client.player != null) {
            NotificationManager.show(Text.literal(success ? "reloaded " + mod.getMetadata().name : "failed to reload " + mod.getMetadata().name), success ? NotificationManager.Type.INFO : NotificationManager.Type.ERROR, 3000);
        }
    }

    private void toggleSelectedMod() {
        if (selectedIndex < 0 || selectedIndex >= modList.size())
            return;

        LuaModContainer mod = modList.get(selectedIndex);
        if (mod.getState() == LuaModContainer.ModState.disabled) {
            reloadSelectedMod();

            disableButton.setMessage(Text.literal("disable mod"));
        } else {
            mod.disable();
            filterMods();

            if (client != null && client.player != null) {
                NotificationManager.show(Text.literal("disabled " + mod.getMetadata().name),
                        NotificationManager.Type.INFO, 3000);
            }
            disableButton.setMessage(Text.literal("enable mod"));
        }
    }

    private void openConfig() {
        if (selectedIndex < 0 || selectedIndex >= modList.size())
            return;

        LuaModContainer mod = modList.get(selectedIndex);
        if (mod.getMetadata().hasConfig()) {
            if (client != null) {
                client.setScreen(new LuaModConfigScreen(this, mod));
            }
        }
    }

    private void reloadAllMods() {
        modLoader.reloadAllMods();
        filterMods();
        selectedIndex = -1;

        if (client != null && client.player != null) {
            NotificationManager.show(Text.literal("Reloaded all mods"), NotificationManager.Type.INFO, 3000);
        }
    }

    private void openDiagnostics() {
        if (client != null) {
            client.setScreen(new LuaDiagnosticsScreen(this));
        }
    }

    private List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            String test = current.isEmpty() ? word : current + " " + word;
            if (textRenderer.getWidth(test) > maxWidth) {
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    lines.add(word);
                }
            } else {
                if (!current.isEmpty())
                    current.append(" ");
                current.append(word);
            }
        }

        if (!current.isEmpty()) {
            lines.add(current.toString());
        }

        return lines;
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }
}