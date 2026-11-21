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

package com.duykingnew.pocketfsr.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/**
 * Holds the two FSR shader programs. They are loaded (and reloaded after F3+T / resource pack changes)
 * through Fabric's core shader registration, from {@code assets/pocketfsr/shaders/core}.
 */
public final class FsrShaders {
    private static volatile ShaderInstance easu;
    private static volatile ShaderInstance rcas;
    private static volatile ShaderInstance blit;

    private FsrShaders() {
    }

    public static void register(String modId) {
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            context.register(ResourceLocation.fromNamespaceAndPath(modId, "fsr_easu"), DefaultVertexFormat.POSITION, shader -> easu = shader);
            context.register(ResourceLocation.fromNamespaceAndPath(modId, "fsr_rcas"), DefaultVertexFormat.POSITION, shader -> rcas = shader);
            context.register(ResourceLocation.fromNamespaceAndPath(modId, "fsr_blit"), DefaultVertexFormat.POSITION, shader -> blit = shader);
        });
    }

    public static ShaderInstance easu() {
        return easu;
    }

    public static ShaderInstance rcas() {
        return rcas;
    }

    public static ShaderInstance blit() {
        return blit;
    }

    public static boolean ready() {
        return easu != null && rcas != null && blit != null;
    }
}
