package net.peasoup.luacubed;

import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

public class NotificationManager {
    
    public enum Type {
        INFO(0x55FF55),
        WARN(0xFFAA00),
        ERROR(0xFF5555);

        public final int color;
        Type(int color) { this.color = color; }
    }

    public static class Notification {
        public final Text message;
        public final Type type;
        public final long displayDurationMs;
        public final long startTime;
        public float currentXOffset = 100f;

        public Notification(Text message, Type type, long durationMs) {
            this.message = message;
            this.type = type;
            this.displayDurationMs = durationMs;
            this.startTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - startTime > displayDurationMs;
        }
    }

    // Keep this public so the client renderer can read it
    public static final List<Notification> activeNotifications = new ArrayList<>();

    public static void show(Text message, Type type, long durationMs) {
        activeNotifications.add(new Notification(message, type, durationMs));
    }
}
