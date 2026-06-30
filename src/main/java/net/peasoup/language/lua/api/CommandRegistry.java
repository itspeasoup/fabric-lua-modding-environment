package net.peasoup.language.lua.api;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

public class CommandRegistry {
    public static void register(Globals globals) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(CommandManager.literal("lua").requires(source -> source.hasPermissionLevel(2)) // OP only
                .then(CommandManager.argument("code", StringArgumentType.greedyString()).executes(context -> {
                    String code = StringArgumentType.getString(context, "code");

                    try {
                        // Load and execute the string
                        LuaValue chunk = globals.load(code);
                        Varargs results = chunk.invoke();

                        // Format the return value (if any)
                        String output = results.tojstring();
                        if (output.equals("nil")) output = "Executed successfully (no return)";

                        String finalOutput = output;
                        context.getSource().sendFeedback(() -> Text.literal("§a[Lua Result]: §f" + finalOutput), false);
                        return 1;
                    } catch (Exception e) {
                        context.getSource().sendError(Text.literal("§c[Lua Error]: §f" + e.getMessage()));
                        return 0;
                    }
                }))));
    }
}