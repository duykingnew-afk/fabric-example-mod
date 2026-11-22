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
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {
    /**
     * The game binds its main target with {@code bindWrite(false)} (keep the current viewport) after it has
     * cleared some other, window-sized target; that clear leaves the viewport at the window size. With the
     * smaller world target standing in as the main target the viewport must be set again, or everything drawn
     * afterwards (water, clouds, hand, block outline) is scaled up and cut off.
     */
    @ModifyVariable(method = "bindWrite(Z)V", at = @At("HEAD"), argsOnly = true)
    private boolean pocketfsr$setViewportOnWorldTarget(boolean setViewport) {
        return setViewport || UpscaleManager.isLowTarget((RenderTarget) (Object) this);
    }
}
