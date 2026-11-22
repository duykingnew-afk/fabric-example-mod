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
import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostics only: reports viewport and framebuffer changes during an upscaled world frame. */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerDebugMixin {
    @Inject(method = "_viewport", at = @At("HEAD"))
    private static void pocketfsr$viewport(int x, int y, int width, int height, CallbackInfo ci) {
        UpscaleManager.debugGl("viewport", x + "," + y + " " + width + "x" + height);
    }

    @Inject(method = "_glBindFramebuffer", at = @At("HEAD"))
    private static void pocketfsr$bindFramebuffer(int target, int framebuffer, CallbackInfo ci) {
        UpscaleManager.debugGl("bindFramebuffer", "target=" + target + " fb=" + framebuffer);
    }
}
