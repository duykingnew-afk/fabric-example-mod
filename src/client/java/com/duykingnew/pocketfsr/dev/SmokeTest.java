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

package com.duykingnew.pocketfsr.dev;

import com.duykingnew.pocketfsr.config.PocketFsrConfig;
import com.duykingnew.pocketfsr.render.FsrShaders;
import com.duykingnew.pocketfsr.render.UpscaleManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CI-only driver, active only when the environment variable {@code POCKETFSR_SMOKETEST} is set.
 * It creates a flat world with a small test scene (a block to point at, water, glass, a chest and a few
 * mobs), renders it natively and through each upscaling mode, saves screenshots to {@code smoketest/} in the
 * game directory, then quits. Never active for normal players.
 */
public final class SmokeTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("PocketFSR-SMOKE");

    /**
     * One screenshot. The last four values only matter for the diagnostic shots: {@code freshState} sets
     * {@link UpscaleManager#debugFreshState}, {@code lowFilter} {@link UpscaleManager#debugLowFilter}, and
     * {@code dump} also saves the low resolution picture (and the EASU result) of the frame and logs the GL state,
     * to see where a difference between two modes comes from.
     */
    private record Shot(String name, boolean enabled, float scale, PocketFsrConfig.Mode mode, float pitch,
                        float sharpness, boolean freshState, boolean dump, int lowFilter) {
        Shot(String name, boolean enabled, float scale, PocketFsrConfig.Mode mode, float pitch) {
            this(name, enabled, scale, mode, pitch, 0.8F, false, false, -1);
        }
    }

    /** A diagnostic shot at 50%, looking ahead. */
    private static Shot diag(String name, PocketFsrConfig.Mode mode, float sharpness, boolean freshState, int lowFilter) {
        return new Shot(name, true, 0.5F, mode, 0.0F, sharpness, freshState, true, lowFilter);
    }

    private static final float AHEAD = 0.0F;
    private static final float SKY = -55.0F;
    /**
     * Which shots to take ({@code POCKETFSR_SMOKETEST_SET}): {@code default}; {@code diag}, where a difference between
     * two modes comes from; {@code compare}, how the picture falls with the render scale.
     */
    private static final String SET = System.getenv().getOrDefault("POCKETFSR_SMOKETEST_SET",
            System.getenv("POCKETFSR_SMOKETEST_DIAG") != null ? "diag" : "default");
    /** World frames to wait after each change before the picture is taken (a shader pack needs a while to settle). */
    private static final int FRAMES = Integer.parseInt(System.getenv().getOrDefault("POCKETFSR_SMOKETEST_FRAMES", "8"));
    /** {@code POCKETFSR_SMOKETEST_CURVE}: log the brightness and the time of every world frame. */
    private static final boolean CURVE = System.getenv("POCKETFSR_SMOKETEST_CURVE") != null;

    private static final Shot[] DIAG_SHOTS = {
            new Shot("d-native", false, 1.0F, PocketFsrConfig.Mode.FSR1, AHEAD),
            // the plain stretch (the one that looks right with every pack), with its normal and with the other filter
            diag("d-bilinear", PocketFsrConfig.Mode.BILINEAR, 0.8F, false, -1),
            diag("d-bilinear-nearest", PocketFsrConfig.Mode.BILINEAR, 0.8F, false, GL11.GL_NEAREST),
            // EASU alone onto the screen; EASU into the scratch target and RCAS onto the screen; with the other filter
            diag("d-easu", PocketFsrConfig.Mode.FSR1, 0.0F, false, -1),
            diag("d-fsr", PocketFsrConfig.Mode.FSR1, 0.8F, false, -1),
            diag("d-fsr-linear", PocketFsrConfig.Mode.FSR1, 0.8F, false, GL11.GL_LINEAR),
            // the same with the GL state set again before every pass
            diag("d-easu-fresh", PocketFsrConfig.Mode.FSR1, 0.0F, true, -1),
            diag("d-fsr-fresh", PocketFsrConfig.Mode.FSR1, 0.8F, true, -1),
            new Shot("d-native-2", false, 1.0F, PocketFsrConfig.Mode.FSR1, AHEAD),
    };
    /** The same view at different scales and sharpness: how far from the native picture does each one stay? */
    private static Shot compare(String name, float scale, PocketFsrConfig.Mode mode, float sharpness) {
        return new Shot(name, true, scale, mode, AHEAD, sharpness, false, false, -1);
    }

    private static final Shot[] COMPARE_SHOTS = {
            new Shot("c-native", false, 1.0F, PocketFsrConfig.Mode.FSR1, AHEAD),
            compare("c-fsr70", 0.70F, PocketFsrConfig.Mode.FSR1, 0.8F),
            compare("c-fsr50", 0.50F, PocketFsrConfig.Mode.FSR1, 0.8F),
            compare("c-fsr25", 0.25F, PocketFsrConfig.Mode.FSR1, 0.8F),
            compare("c-fsr25-sharp", 0.25F, PocketFsrConfig.Mode.FSR1, 1.0F),
            compare("c-bilinear25", 0.25F, PocketFsrConfig.Mode.BILINEAR, 0.8F),
            compare("c-bilinear70", 0.70F, PocketFsrConfig.Mode.BILINEAR, 0.8F),
            new Shot("c-native-2", false, 1.0F, PocketFsrConfig.Mode.FSR1, AHEAD),
    };

    private static final Shot[] DEFAULT_SHOTS = {
            new Shot("native", false, 1.0F, PocketFsrConfig.Mode.FSR1, AHEAD),
            new Shot("fsr67", true, 0.67F, PocketFsrConfig.Mode.FSR1, AHEAD),
            new Shot("fsr50", true, 0.5F, PocketFsrConfig.Mode.FSR1, AHEAD),
            new Shot("bilinear50", true, 0.5F, PocketFsrConfig.Mode.BILINEAR, AHEAD),
            new Shot("native-sky", false, 1.0F, PocketFsrConfig.Mode.FSR1, SKY),
            new Shot("fsr67-sky", true, 0.67F, PocketFsrConfig.Mode.FSR1, SKY),
            // switch on and off and between sizes a few more times: the pictures must match the first ones
            new Shot("native-again", false, 1.0F, PocketFsrConfig.Mode.FSR1, AHEAD),
            new Shot("fsr67-again", true, 0.67F, PocketFsrConfig.Mode.FSR1, AHEAD),
            new Shot("native-last", false, 1.0F, PocketFsrConfig.Mode.FSR1, AHEAD),
            new Shot("fsr50-last", true, 0.5F, PocketFsrConfig.Mode.FSR1, AHEAD),
            new Shot("fsr67-last", true, 0.67F, PocketFsrConfig.Mode.FSR1, AHEAD),
    };

    private static final Shot[] SHOTS = switch (SET) {
        case "diag" -> DIAG_SHOTS;
        case "compare" -> COMPARE_SHOTS;
        default -> DEFAULT_SHOTS;
    };

    /** Commands run as the player; every position is relative to where the player stands, facing +Z. */
    private static final String[] SCENE = {
            "gamerule doDaylightCycle false",
            "gamerule doWeatherCycle false",
            "gamerule doMobSpawning false",
            "time set noon",
            "weather clear",
            "fill ~-3 ~-1 ~6 ~3 ~-1 ~10 water",
            "fill ~-5 ~ ~5 ~-3 ~2 ~5 light_blue_stained_glass",
            "setblock ~ ~1 ~3 stone",
            "setblock ~-1 ~ ~3 chest",
            "summon pig ~-4 ~ ~7 {NoAI:1b}",
            "summon pig ~2 ~ ~4 {NoAI:1b,Glowing:1b}",
            "summon armor_stand ~4 ~ ~5",
    };

    private enum Stage { WAIT_TITLE, WAIT_WORLD, SETTLE, SHOOT, DONE }

    private static Stage stage = Stage.WAIT_TITLE;
    private static int stageTicks;
    private static int shotIndex;
    private static long frameMark;
    /** Upscaled frames seen in the current diagnostic shot (the GL state is logged once, in the middle). */
    private static int diagFrames;
    private static long lastCurveFrame = -1;
    private static long shotStartNanos;
    private static final long START_NANOS = System.nanoTime();
    /** Lets a second run (for example with Iris) keep its screenshots apart: {@code POCKETFSR_SMOKETEST_PREFIX=iris-}. */
    private static final String PREFIX = System.getenv().getOrDefault("POCKETFSR_SMOKETEST_PREFIX", "");

    private SmokeTest() {
    }

    public static void install() {
        LOGGER.info("smoke test armed");
        ClientTickEvents.END_CLIENT_TICK.register(SmokeTest::onTick);
    }

    private static void next(Stage to) {
        LOGGER.info("stage {} -> {} (shaders ready: {}, blocked: {})", stage, to, FsrShaders.ready(), UpscaleManager.blockedKey());
        stage = to;
        stageTicks = 0;
    }

    private static void onTick(Minecraft mc) {
        stageTicks++;
        if ((System.nanoTime() - START_NANOS) / 1_000_000_000L > 20 * 60) {
            LOGGER.error("smoke test timed out in stage {}", stage);
            System.exit(3);
        }
        try {
            switch (stage) {
                case WAIT_TITLE -> {
                    if (mc.getOverlay() == null && mc.screen instanceof TitleScreen && stageTicks > 40) {
                        createWorld(mc);
                        next(Stage.WAIT_WORLD);
                    }
                }
                case WAIT_WORLD -> {
                    if (mc.level != null && mc.player != null && mc.screen == null) {
                        next(Stage.SETTLE);
                    }
                }
                case SETTLE -> {
                    if (stageTicks == 1) {
                        mc.player.setYRot(0.0F);
                        mc.player.yRotO = 0.0F;
                        for (String command : SCENE) {
                            mc.player.connection.sendCommand(command);
                        }
                    }
                    // let the chunks load and mesh, the commands run and the chat messages fade
                    if (stageTicks > 20 * 25) {
                        startShot(mc);
                        next(Stage.SHOOT);
                    }
                }
                case SHOOT -> {
                    if (CURVE && UpscaleManager.worldFrames() != lastCurveFrame) {
                        lastCurveFrame = UpscaleManager.worldFrames();
                        logCurve(mc, SHOTS[shotIndex]);
                    }
                    if (UpscaleManager.worldFrames() < frameMark + FRAMES) {
                        return;
                    }
                    saveShot(mc, SHOTS[shotIndex]);
                    shotIndex++;
                    if (shotIndex < SHOTS.length) {
                        startShot(mc);
                    } else {
                        next(Stage.DONE);
                        LOGGER.info("SMOKETEST DONE");
                        mc.stop();
                    }
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            LOGGER.error("smoke test failed in stage {}", stage, t);
            System.exit(4);
        }
    }

    /** Applies the settings of the next shot; the screenshot is taken after a few fresh world frames. */
    private static void startShot(Minecraft mc) {
        Shot shot = SHOTS[shotIndex];
        PocketFsrConfig cfg = PocketFsrConfig.get();
        cfg.enabled = shot.enabled();
        cfg.scale = shot.scale();
        cfg.mode = shot.mode();
        cfg.sharpness = shot.sharpness();
        UpscaleManager.debugFreshState = shot.freshState();
        UpscaleManager.debugLowFilter = shot.lowFilter();
        diagFrames = 0;
        if (shot.dump()) {
            UpscaleManager.debugCapture(
                    target -> {
                        dump(mc, target, "low-" + shot.name());
                        if (++diagFrames == 5) {
                            logGlState(shot, target);
                        }
                    },
                    target -> {
                        dump(mc, target, "mid-" + shot.name());
                        if (diagFrames == 5) {
                            LOGGER.info("{}{} the scratch target: {}", PREFIX, shot.name(), textureInfo(target.getColorTextureId()));
                        }
                    });
        }
        mc.player.setXRot(shot.pitch());
        mc.player.xRotO = shot.pitch();
        frameMark = UpscaleManager.worldFrames();
        shotStartNanos = System.nanoTime();
    }

    /** One log line per world frame: the brightness of the whole picture (HUD included) and the time since the shot began. */
    private static void logCurve(Minecraft mc, Shot shot) {
        RenderTarget main = mc.getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
        try {
            RenderSystem.bindTexture(main.getColorTextureId());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            long sum = 0;
            long count = 0;
            for (int y = 0; y < h; y += 4) {
                for (int x = 0; x < w; x += 4) {
                    int at = (y * w + x) * 4;
                    sum += (pixels.get(at) & 255) + (pixels.get(at + 1) & 255) + (pixels.get(at + 2) & 255);
                    count += 3;
                }
            }
            LOGGER.info("{}curve {} frame {} ms {} luma {}", PREFIX, shot.name(), UpscaleManager.worldFrames() - frameMark,
                    (System.nanoTime() - shotStartNanos) / 1_000_000L,
                    String.format(java.util.Locale.ROOT, "%.2f", sum / (double) count));
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /** Saves one render target as a PNG (used by the diagnostic shots). */
    private static void dump(Minecraft mc, RenderTarget target, String name) {
        try {
            Path dir = mc.gameDirectory.toPath().resolve("smoketest");
            Files.createDirectories(dir);
            try (NativeImage image = Screenshot.takeScreenshot(target)) {
                image.writeToFile(dir.resolve(PREFIX + name + ".png"));
            }
        } catch (Exception e) {
            LOGGER.warn("could not save {}", name, e);
        }
    }

    /**
     * Logs the real GL state (not what Minecraft's cache believes) just before the upscaling passes draw, and what
     * the low resolution target really holds: a leftover from a shader pack shows up here.
     */
    private static void logGlState(Shot shot, RenderTarget low) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            int[] viewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            StringBuilder out = new StringBuilder();
            out.append("blend=").append(GL11.glIsEnabled(GL11.GL_BLEND));
            out.append(" blendFunc=").append(GL11.glGetInteger(0x80C9)).append('/').append(GL11.glGetInteger(0x80C8))
                    .append('/').append(GL11.glGetInteger(0x80CB)).append('/').append(GL11.glGetInteger(0x80CA));
            out.append(" depthTest=").append(GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
            out.append(" cull=").append(GL11.glIsEnabled(GL11.GL_CULL_FACE));
            out.append(" scissor=").append(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
            out.append(" stencil=").append(GL11.glIsEnabled(GL11.GL_STENCIL_TEST));
            out.append(" framebufferSrgb=").append(GL11.glIsEnabled(0x8DB9));
            out.append(" dither=").append(GL11.glIsEnabled(GL11.GL_DITHER));
            out.append(" multisample=").append(GL11.glIsEnabled(0x809D));
            out.append(" colorMask=").append(mask.get(0)).append(mask.get(1)).append(mask.get(2)).append(mask.get(3));
            out.append(" viewport=").append(viewport[0]).append(',').append(viewport[1]).append(',')
                    .append(viewport[2]).append(',').append(viewport[3]);
            out.append(" activeTexture=").append(GL11.glGetInteger(0x84E0) - 0x84C0);
            out.append(" boundTexture0=").append(GL11.glGetInteger(0x8069));
            out.append(" boundSampler0=").append(GL11.glGetInteger(0x8919));
            out.append(" program=").append(GL11.glGetInteger(0x8B8D));
            int drawFbo = GL11.glGetInteger(0x8CA6);
            out.append(" drawFramebuffer=").append(drawFbo);
            out.append(" drawColorEncoding=0x").append(Integer.toHexString(
                    GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, 0x8210)));
            LOGGER.info("{}{} GL state before the passes: {}", PREFIX, shot.name(), out);

            // what is really attached to the low resolution target, against the texture the passes read from
            int readFbo = GL11.glGetInteger(0x8CAA);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, low.frameBufferId);
            int attachedColor = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, 0x8CD1);
            int attachedDepth = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, 0x8CD1);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            LOGGER.info("{}{} the low target: size {}x{}, framebuffer {}, color attachment {} (the passes read texture {}), "
                            + "depth attachment {} (its depth texture {}); texture: {}", PREFIX, shot.name(),
                    low.width, low.height, low.frameBufferId, attachedColor, low.getColorTextureId(),
                    attachedDepth, low.getDepthTextureId(), textureInfo(low.getColorTextureId()));
        } catch (Throwable t) {
            LOGGER.warn("could not read the GL state", t);
        }
    }

    /** Filter, wrap, mip range, swizzle and format of a 2D texture, read without disturbing what is bound. */
    private static String textureInfo(int texture) {
        int previous = GL11.glGetInteger(0x8069);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        try {
            return "id " + texture + " min/mag filter " + Integer.toHexString(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, 0x2801))
                    + "/" + Integer.toHexString(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, 0x2800))
                    + " wrap " + Integer.toHexString(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, 0x2802))
                    + "/" + Integer.toHexString(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, 0x2803))
                    + " levels " + GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, 0x813C)
                    + ".." + GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, 0x813D)
                    + " swizzle " + Integer.toHexString(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, 0x8E42))
                    + "/" + Integer.toHexString(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, 0x8E43))
                    + "/" + Integer.toHexString(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, 0x8E44))
                    + "/" + Integer.toHexString(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, 0x8E45))
                    + " format 0x" + Integer.toHexString(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, 0x1003))
                    + " " + GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, 0x1000)
                    + "x" + GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, 0x1001);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    private static void saveShot(Minecraft mc, Shot shot) throws Exception {
        UpscaleManager.debugCapture(null, null);
        int glError = GL11.glGetError();
        LOGGER.info("{}{}: frames={}, GL error=0x{}, blocked={}, active={}, shader pack in use: {}", PREFIX, shot.name(),
                UpscaleManager.worldFrames(), Integer.toHexString(glError), UpscaleManager.blockedKey(),
                shot.enabled(), shaderPackState());
        Path dir = mc.gameDirectory.toPath().resolve("smoketest");
        Files.createDirectories(dir);
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(dir.resolve(PREFIX + shot.name() + ".png"));
            LOGGER.info("saved {}{}.png ({}x{})", PREFIX, shot.name(), image.getWidth(), image.getHeight());
        }
    }

    /** Asks Iris (if it is installed) whether a shader pack is being used, so the log shows what was really tested. */
    private static String shaderPackState() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return String.valueOf(api.getMethod("isShaderPackInUse").invoke(instance));
        } catch (ReflectiveOperationException | LinkageError e) {
            return "no Iris";
        }
    }

    private static void createWorld(Minecraft mc) {
        LevelSettings settings = new LevelSettings("pocketfsr-smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                true, new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel("pocketfsr-smoke-" + System.currentTimeMillis(), settings,
                new WorldOptions(20260101L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                mc.screen);
    }
}
