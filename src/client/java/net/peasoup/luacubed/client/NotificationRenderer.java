package net.peasoup.luacubed.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.peasoup.luacubed.NotificationManager;
import net.peasoup.luacubed.NotificationManager.Notification;

public class NotificationRenderer {
    private static final int PADDING = 6;
    private static final int LINE_HEIGHT = 9;
    private static final int VERTICAL_TEXT_PADDING = 8;
    private static final int MAX_WIDTH = 250; // Maximum allowed width for a notification card

    public enum Corner {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    public static void render(DrawContext context, Corner corner) {
        if (NotificationManager.activeNotifications.isEmpty())
            return;

        MinecraftClient client = MinecraftClient.getInstance();

        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        boolean calledFromHudCallback = false;
        for (StackTraceElement element : stackTrace) {
            if (element.getClassName().contains("HudRenderCallback")) {
                calledFromHudCallback = true;
                break;
            }
        }

        if (client.currentScreen != null && calledFromHudCallback) {
            return;
        }
        // -------------------------

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        NotificationManager.activeNotifications.removeIf(Notification::isExpired);

        // Track running Y-coordinate dynamically since box heights change now
        int currentY = (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) ? PADDING : screenHeight - PADDING;

        for (Notification notification : NotificationManager.activeNotifications) {
            long age = System.currentTimeMillis() - notification.startTime;
            long timeLeft = notification.displayDurationMs - age;

            // --- 1. SMOOTH FADE OUT ALPHA CALCULATION ---
            // Fade out completely over the final 500 milliseconds
            float alphaFactor = 1.0f;
            if (timeLeft < 500) {
                alphaFactor = Math.max(0.0f, timeLeft / 500f);
            }

            int alphaHex = ((int) (alphaFactor * 255)) & 0xFF;
            int bgAlphaHex = ((int) (alphaFactor * 0xAA)) & 0xFF; // Match old 0xAA baseline transparency

            if (alphaHex == 0)
                continue;

            List<? extends net.minecraft.text.StringVisitable> lines = client.textRenderer.getTextHandler()
                    .wrapLines(notification.message, MAX_WIDTH - 20, net.minecraft.text.Style.EMPTY);

            List<Text> wrappedLines = new ArrayList<>();
            for (net.minecraft.text.StringVisitable line : lines) {
                wrappedLines.add(Text.literal(line.getString()));
            }

            // Find the widest individual line to size the background box properly
            int cardContentWidth = 0;
            for (Text line : wrappedLines) {
                cardContentWidth = Math.max(cardContentWidth, client.textRenderer.getWidth(line));
            }
            int boxWidth = cardContentWidth + 20;
            int boxHeight = (wrappedLines.size() * LINE_HEIGHT) + VERTICAL_TEXT_PADDING;

            // Slide-in math logic remains identical
            notification.currentXOffset = Math.max(0,
                    notification.currentXOffset - (notification.currentXOffset * 0.15f));

            int x = switch (corner) {
                case TOP_LEFT, BOTTOM_LEFT -> PADDING + (int) notification.currentXOffset;
                case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - boxWidth - PADDING + (int) notification.currentXOffset;
            };

            // Recalculate working layout height coordinate for bottom stacks
            if (corner == Corner.BOTTOM_LEFT || corner == Corner.BOTTOM_RIGHT) {
                currentY -= boxHeight;
            }

            // --- 3. DYNAMIC COLOR RENDERING WITH ALPHA ---
            int boxColor = (bgAlphaHex << 24) | 0x000000;
            int accentColor = (alphaHex << 24) | notification.type.color;
            int textColor = (alphaHex << 24) | 0xFFFFFF;

            // Draw Background Box & Accent Bar
            context.fill(x, currentY, x + boxWidth, currentY + boxHeight, boxColor);
            context.fill(x, currentY, x + 3, currentY + boxHeight, accentColor);

            // Draw Wrapped Text Lines
            int textY = currentY + (VERTICAL_TEXT_PADDING / 2);
            for (Text line : wrappedLines) {
                context.drawTextWithShadow(client.textRenderer, line, x + 8, textY, textColor);
                textY += LINE_HEIGHT;
            }

            // Set up placement positions for subsequent notifications in the stack
            if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) {
                currentY += boxHeight + 4;
            } else {
                currentY -= 4;
            }
        }
    }
}
