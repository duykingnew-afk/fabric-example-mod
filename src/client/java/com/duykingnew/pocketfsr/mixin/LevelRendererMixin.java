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
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    /**
     * Glowing-entity outlines go through a second, window-sized render target that the game clears, fills and
     * blits itself. It cannot line up with the smaller world target, so no outlines are drawn while the world
     * is upscaled (the entities themselves are still drawn normally).
     */
    @Inject(method = "shouldShowEntityOutlines", at = @At("HEAD"), cancellable = true)
    private void pocketfsr$noOutlinesWhileUpscaling(CallbackInfoReturnable<Boolean> cir) {
        if (UpscaleManager.outlinesSuppressed()) {
            cir.setReturnValue(false);
        }
    }
}
