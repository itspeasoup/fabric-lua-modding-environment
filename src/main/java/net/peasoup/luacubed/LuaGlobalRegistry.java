package net.peasoup.luacubed;

import java.util.HashMap;
import java.util.Map;

public class LuaGlobalRegistry {
    private static final Map<String, String> SHORTCUT_CLASSES = new HashMap<>();
    private static boolean initialized = false;

    public static Map<String, String> getShortcuts() {
        if (!initialized) {
            SHORTCUT_CLASSES.put("Text", "net.minecraft.text.Text");
            SHORTCUT_CLASSES.put("MinecraftClient", "net.minecraft.client.MinecraftClient");
            SHORTCUT_CLASSES.put("Identifier", "net.minecraft.util.Identifier");
            SHORTCUT_CLASSES.put("BlockPos", "net.minecraft.util.math.BlockPos");
            SHORTCUT_CLASSES.put("Vec3d", "net.minecraft.util.math.Vec3d");
            SHORTCUT_CLASSES.put("Formatting", "net.minecraft.util.Formatting");
            SHORTCUT_CLASSES.put("Hand", "net.minecraft.util.Hand");
            
            SHORTCUT_CLASSES.put("NotificationManager", "net.peasoup.luacubed.NotificationManager");
            SHORTCUT_CLASSES.put("NotificationRenderer", "net.peasoup.luacubed.client.NotificationRenderer");

            SHORTCUT_CLASSES.put("Math", "java.lang.Math");
            SHORTCUT_CLASSES.put("Float", "java.lang.Float");
            SHORTCUT_CLASSES.put("Double", "java.lang.Double");
            SHORTCUT_CLASSES.put("Integer", "java.lang.Integer");
            SHORTCUT_CLASSES.put("System", "java.lang.System");
            
            initialized = true;
        }
        return SHORTCUT_CLASSES;
    }
}
