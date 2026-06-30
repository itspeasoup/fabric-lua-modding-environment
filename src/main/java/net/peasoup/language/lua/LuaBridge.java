package net.peasoup.language.lua;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

/**
 * Utility class for bridging between Java and Lua types
 */
public class LuaBridge {
    private static final Logger LOGGER = LogManager.getLogger("net.peasoup.language.lua.LuaBridge");

    /**
     * Convert a Java object to a Lua value
     */
    public static LuaValue toLua(Object javaObject) {
        if (javaObject == null) {
            return LuaValue.NIL;
        }
        return CoerceJavaToLua.coerce(javaObject);
    }

    /**
     * Convert a Java array to Lua varargs
     */
    public static Varargs toLuaVarargs(Object[] javaArray) {
        if (javaArray == null || javaArray.length == 0) {
            return LuaValue.NONE;
        }

        LuaValue[] luaValues = new LuaValue[javaArray.length];
        for (int i = 0; i < javaArray.length; i++) {
            luaValues[i] = toLua(javaArray[i]);
        }
        return LuaValue.varargsOf(luaValues);
    }

    /**
     * Safely call a Lua function with Java arguments and return the result
     */
    public static LuaValue safeCall(LuaFunction function, Object... args) {
        try {
            // Change: Added 'return' and return the first argument of the result
            return function.invoke(toLuaVarargs(args)).arg1();
        } catch (Exception e) {
            LOGGER.error("Error calling Lua function: {}", e.getMessage(), e);
            return LuaValue.NIL; // Return NIL if it fails so we don't crash
        }
    }
}