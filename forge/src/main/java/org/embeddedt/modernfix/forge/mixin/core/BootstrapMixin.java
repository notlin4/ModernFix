package org.embeddedt.modernfix.forge.mixin.core;

import net.minecraft.server.Bootstrap;
import org.apache.logging.log4j.Logger;
import org.embeddedt.modernfix.forge.load.ModWorkManagerQueue;
import org.embeddedt.modernfix.util.TimeFormatter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.management.ManagementFactory;

@Mixin(Bootstrap.class)
public class BootstrapMixin {
    @Shadow private static boolean isBootstrapped;

    @Shadow @Final private static Logger LOGGER;

    @Inject(method = "bootStrap", at = @At("HEAD"))
    private static void doModernFixBootstrap(CallbackInfo ci) {
        if(!isBootstrapped) {
            LOGGER.info("ModernFix reached bootstrap stage ({} after launch)", TimeFormatter.formatNanos(ManagementFactory.getRuntimeMXBean().getUptime() * 1000L));
            ModWorkManagerQueue.replace();
        }
    }
}
