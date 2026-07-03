package net.peasoup.luacubed;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

public class LuaBridge {
    private static final Logger LOGGER = LogManager.getLogger("net.peasoup.luacubed.LuaBridge");

    public static LuaValue toLua(Object javaObject) {
        if (javaObject == null) {
            return LuaValue.NIL;
        }
        return CoerceJavaToLua.coerce(javaObject);
    }
    
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

    public static LuaValue safeCall(LuaFunction function, Object... args) {
        try {
            return function.invoke(toLuaVarargs(args)).arg1();
        } catch (Exception e) {
            LOGGER.error("Error calling Lua function: {}", e.getMessage(), e);
            return LuaValue.NIL;
        }
    }
}