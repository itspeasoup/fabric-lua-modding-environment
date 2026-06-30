package net.peasoup.language.lua.mixin;

import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ResourceType;
import net.peasoup.language.lua.resource.LuaModPackProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

@Mixin(ResourcePackManager.class)
public abstract class ResourcePackManagerMixin {

    @Mutable
    @Final
    @Shadow
    private Set<ResourcePackProvider> providers;

    @Inject(method = "<init>([Lnet/minecraft/resource/ResourcePackProvider;)V", at = @At("RETURN"))
    private void injectLuaProviders(ResourcePackProvider[] providers, CallbackInfo ci) {
        // Create a NEW mutable HashSet (don't copy the existing one which might be immutable)

        // Add all existing providers
        Set<ResourcePackProvider> mutableProviders = new HashSet<>(this.providers);

        // Add our Lua providers
        // Since we don't have 'type' passed in anymore, we register for both.
        // Minecraft will only call the relevant one for the manager instance.
        mutableProviders.add(new LuaModPackProvider(ResourceType.CLIENT_RESOURCES));
        mutableProviders.add(new LuaModPackProvider(ResourceType.SERVER_DATA));

        // Set it as a NEW HashSet (not immutable copy!)
        this.providers = mutableProviders;
    }
}