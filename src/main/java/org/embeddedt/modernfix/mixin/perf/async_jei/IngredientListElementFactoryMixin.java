package org.embeddedt.modernfix.mixin.perf.async_jei;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.gui.ingredients.IIngredientListElement;
import mezz.jei.ingredients.IngredientListElementFactory;
import net.minecraft.util.NonNullList;
import org.embeddedt.modernfix.jei.async.IAsyncJeiStarter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IngredientListElementFactory.class)
public class IngredientListElementFactoryMixin {
    @Inject(method = "addToBaseList", at = @At(value = "INVOKE", target = "Lmezz/jei/ingredients/IngredientOrderTracker;getOrderIndex(Ljava/lang/Object;Lmezz/jei/api/ingredients/IIngredientHelper;)I"), remap = false)
    private static void checkForInterrupt(NonNullList<IIngredientListElement<?>> baseList, IIngredientManager ingredientManager, IIngredientType ingredientType, CallbackInfo ci) {
        IAsyncJeiStarter.checkForLoadInterruption();
    }
}