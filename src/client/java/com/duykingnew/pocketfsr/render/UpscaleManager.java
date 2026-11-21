/*
 * Pocket FSR
 * Copyright (c) 2026 duykingnew-afk
 *
 * The idea of drawing the world into a smaller render target and upscaling it before the GUI is
 * drawn follows the approach of the "Super Resolution" mod (GPL-3.0, by 187J3X1-114514).
 * The upscaling shaders are ports of AMD FidelityFX Super Resolution 1.0 (MIT, see LICENSE-FSR.txt).
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

import com.duykingnew.pocketfsr.config.PocketFsrConfig;
import com.duykingnew.pocketfsr.mixin.MinecraftAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the 3D world at a reduced resolution and upscales it to the window.
 *
 * <p>Frame flow (all on the render thread):
 * <ol>
 *   <li>{@link #beginWorld()} runs at the start of {@code GameRenderer.renderLevel}. The game's "main render
 *   target" is swapped for a smaller one, so the sky, terrain, entities, particles and the hand are all drawn
 *   at the reduced resolution.</li>
 *   <li>{@link #endWorld()} runs when {@code renderLevel} returns. The real target is put back, and the small
 *   picture is upscaled onto it (FSR 1.0 EASU, optionally followed by RCAS, or a plain bilinear stretch).</li>
 *   <li>The game then draws the GUI (hotbar, chat, menus...) on the real target at full resolution, so the
 *   interface stays sharp.</li>
 * </ol>
 */
public final class UpscaleManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("PocketFSR");
    private static final boolean IRIS_PRESENT = FabricLoader.getInstance().isModLoaded("iris");

    /** The reduced resolution target the world is drawn into. */
    private static RenderTarget lowTarget;
    /** Full resolution scratch target between EASU and RCAS. */
    private static RenderTarget midTarget;
    /** The game's real main target, kept here while {@link #lowTarget} stands in for it. */
    private static RenderTarget screenTarget;
    private static boolean active;
    private static boolean failed;
    private static int lowFilter = -1;
    /** Number of 3D world frames started so far (used by the CI smoke test to wait for fresh frames). */
    private static long worldFrames;

    private UpscaleManager() {
    }

    /**
     * Why upscaling cannot run right now, as a translation key, or {@code null} if nothing blocks it.
     * Used by the settings screen to explain itself.
     */
    public static String blockedKey() {
        if (failed) {
            return "pocketfsr.status.failed";
        }
        if (IRIS_PRESENT) {
            return "pocketfsr.status.iris";
        }
        if (!FsrShaders.ready()) {
            return "pocketfsr.status.shaders";
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && mc.options.graphicsMode().get() == GraphicsStatus.FABULOUS) {
            return "pocketfsr.status.fabulous";
        }
        return null;
    }

    public static long worldFrames() {
        return worldFrames;
    }

    /** Called at the start of {@code GameRenderer.renderLevel}. */
    public static void beginWorld() {
        worldFrames++;
        if (active || failed) {
            return;
        }
        try {
            PocketFsrConfig cfg = PocketFsrConfig.get();
            Minecraft mc = Minecraft.getInstance();
            if (!cfg.enabled || mc.level == null || blockedKey() != null) {
                return;
            }
            RenderTarget main = mc.getMainRenderTarget();
            int sw = main.width;
            int sh = main.height;
            if (sw < 64 || sh < 64) {
                return;
            }
            int rw = Math.min(sw, Math.max(32, Math.round(sw * cfg.scale)));
            int rh = Math.min(sh, Math.max(32, Math.round(sh * cfg.scale)));
            if (rw >= sw && rh >= sh) {
                return; // 100%: nothing to upscale
            }
            RenderTarget low = ensureLowTarget(rw, rh);
            screenTarget = main;
            ((MinecraftAccessor) mc).pocketfsr$setMainRenderTarget(low);
            low.bindWrite(true);
            active = true;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Called when {@code GameRenderer.renderLevel} returns. */
    public static void endWorld() {
        if (!active) {
            return;
        }
        active = false;
        RenderTarget screen = screenTarget;
        screenTarget = null;
        if (screen == null) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            ((MinecraftAccessor) mc).pocketfsr$setMainRenderTarget(screen);
            screen.bindWrite(true);
            present(lowTarget, screen);
        } catch (Throwable t) {
            try {
                ((MinecraftAccessor) Minecraft.getInstance()).pocketfsr$setMainRenderTarget(screen);
                screen.bindWrite(true);
            } catch (Throwable ignored) {
                // nothing more we can do
            }
            fail(t);
        } finally {
            restoreDefaultState();
        }
    }

    private static RenderTarget ensureLowTarget(int w, int h) {
        if (lowTarget == null) {
            lowTarget = new TextureTarget(w, h, true, Minecraft.ON_OSX);
            lowTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            lowFilter = -1;
        } else if (lowTarget.width != w || lowTarget.height != h) {
            lowTarget.resize(w, h, Minecraft.ON_OSX);
            lowFilter = -1;
        }
        return lowTarget;
    }

    private static RenderTarget ensureMidTarget(int w, int h) {
        if (midTarget == null) {
            midTarget = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            midTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            midTarget.setFilterMode(GL11.GL_NEAREST);
        } else if (midTarget.width != w || midTarget.height != h) {
            midTarget.resize(w, h, Minecraft.ON_OSX);
            midTarget.setFilterMode(GL11.GL_NEAREST);
        }
        return midTarget;
    }

    private static void setLowFilter(RenderTarget low, int filter) {
        if (lowFilter != filter) {
            low.setFilterMode(filter);
            lowFilter = filter;
        }
    }

    private static void present(RenderTarget low, RenderTarget screen) {
        PocketFsrConfig cfg = PocketFsrConfig.get();
        int sw = screen.width;
        int sh = screen.height;

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.colorMask(true, true, true, false);

        if (cfg.mode == PocketFsrConfig.Mode.FSR1) {
            setLowFilter(low, GL11.GL_NEAREST);
            if (cfg.sharpness > 0.01F) {
                RenderTarget mid = ensureMidTarget(sw, sh);
                mid.bindWrite(true);
                drawEasu(low, sw, sh);
                screen.bindWrite(true);
                drawRcas(mid, cfg.sharpness);
            } else {
                drawEasu(low, sw, sh);
            }
        } else {
            setLowFilter(low, GL11.GL_LINEAR);
            drawBlit(low, sw, sh);
        }
    }

    private static void drawEasu(RenderTarget low, int sw, int sh) {
        ShaderInstance shader = FsrShaders.easu();
        shader.setSampler("InSampler", low.getColorTextureId());
        set2(shader, "InSize", low.width, low.height);
        set2(shader, "OutSize", sw, sh);
        drawFullscreen(shader);
    }

    private static void drawRcas(RenderTarget source, float sharpness) {
        // RCAS works in "stops" of attenuation: 0 = sharpest, 2 = off. Our 0..1 slider maps onto 2..0.
        float stops = (1.0F - sharpness) * 2.0F;
        ShaderInstance shader = FsrShaders.rcas();
        shader.setSampler("InSampler", source.getColorTextureId());
        Uniform sharp = shader.getUniform("Sharpness");
        if (sharp != null) {
            sharp.set((float) Math.pow(2.0, -stops));
        }
        drawFullscreen(shader);
    }

    private static void drawBlit(RenderTarget low, int sw, int sh) {
        ShaderInstance shader = FsrShaders.blit();
        shader.setSampler("InSampler", low.getColorTextureId());
        set2(shader, "OutSize", sw, sh);
        drawFullscreen(shader);
    }

    private static void set2(ShaderInstance shader, String name, float x, float y) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(x, y);
        }
    }

    /** Draws one quad that covers the whole bound framebuffer (the vertices are already in clip space). */
    private static void drawFullscreen(ShaderInstance shader) {
        RenderSystem.setShader(() -> shader);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.addVertex(-1.0F, -1.0F, 0.0F);
        builder.addVertex(1.0F, -1.0F, 0.0F);
        builder.addVertex(1.0F, 1.0F, 0.0F);
        builder.addVertex(-1.0F, 1.0F, 0.0F);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    /** Puts the GL state back to what the game expects while it moves on to draw the GUI. */
    private static void restoreDefaultState() {
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void fail(Throwable t) {
        failed = true;
        active = false;
        LOGGER.error("Pocket FSR hit an error and switched itself off until the game is restarted", t);
        RenderTarget screen = screenTarget;
        screenTarget = null;
        if (screen != null) {
            try {
                ((MinecraftAccessor) Minecraft.getInstance()).pocketfsr$setMainRenderTarget(screen);
                screen.bindWrite(true);
            } catch (Throwable ignored) {
                // nothing more we can do
            }
        }
    }
}
