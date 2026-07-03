package net.peasoup.luacubed.mixin;

import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ResourceType;
import net.peasoup.luacubed.resource.LuaModPackProvider;
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
        Set<ResourcePackProvider> customProviders = new HashSet<>(this.providers);
        customProviders.add(new LuaModPackProvider(ResourceType.CLIENT_RESOURCES));
        customProviders.add(new LuaModPackProvider(ResourceType.SERVER_DATA));

        // locks the set so it behaves exactly like vanilla expects!
        this.providers = java.util.Collections.unmodifiableSet(customProviders);
    }
}