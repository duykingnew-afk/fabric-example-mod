/*
 * Pocket FSR
 * Copyright (c) 2026 duykingnew-afk
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package com.duykingnew.pocketfsr.mixin;

import com.duykingnew.pocketfsr.render.UpscaleManager;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostics only: reports the real GL viewport when the hand is drawn. */
@Mixin(GameRenderer.class)
public abstract class GameRendererDebugMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), require = 0)
    private void pocketfsr$hand(CallbackInfo ci) {
        UpscaleManager.debugStage("renderItemInHand");
    }
}
