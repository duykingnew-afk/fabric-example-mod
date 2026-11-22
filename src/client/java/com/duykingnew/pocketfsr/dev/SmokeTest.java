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
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CI-only driver, active only when the environment variable {@code POCKETFSR_SMOKETEST} is set.
 * It creates a world, renders it natively and through each upscaling mode, saves screenshots to
 * {@code smoketest/} in the game directory, then quits. Never active for normal players.
 */
public final class SmokeTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("PocketFSR-SMOKE");

    private enum Stage { WAIT_TITLE, WAIT_WORLD, SETTLE, NATIVE, FSR67, FSR, BILINEAR, DONE }

    private static Stage stage = Stage.WAIT_TITLE;
    private static int stageTicks;
    private static long frameMark;
    private static boolean shotPending;
    private static long startNanos = System.nanoTime();

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
        if ((System.nanoTime() - startNanos) / 1_000_000_000L > 14 * 60) {
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
                    // let the first chunks load and mesh
                    if (stageTicks == 1) {
                        mc.player.setXRot(22.0F); // looks at the ground within reach, so the block outline is drawn
                    }
                    if (stageTicks > 20 * 25) {
                        PocketFsrConfig.get().enabled = false;
                        begin(mc, Stage.NATIVE);
                    }
                }
                case NATIVE -> shoot(mc, "native", () -> {
                    PocketFsrConfig cfg = PocketFsrConfig.get();
                    cfg.enabled = true;
                    cfg.scale = 0.67F;
                    cfg.mode = PocketFsrConfig.Mode.FSR1;
                    cfg.sharpness = 0.8F;
                    begin(mc, Stage.FSR67);
                });
                case FSR67 -> shoot(mc, "fsr67", () -> {
                    PocketFsrConfig.get().scale = 0.5F;
                    begin(mc, Stage.FSR);
                });
                case FSR -> shoot(mc, "fsr50", () -> {
                    PocketFsrConfig.get().mode = PocketFsrConfig.Mode.BILINEAR;
                    begin(mc, Stage.BILINEAR);
                });
                case BILINEAR -> shoot(mc, "bilinear50", () -> {
                    next(Stage.DONE);
                    LOGGER.info("SMOKETEST DONE");
                    mc.stop();
                });
                default -> {
                }
            }
        } catch (Throwable t) {
            LOGGER.error("smoke test failed in stage {}", stage, t);
            System.exit(4);
        }
    }

    private static void begin(Minecraft mc, Stage to) {
        frameMark = UpscaleManager.worldFrames();
        shotPending = true;
        next(to);
    }

    /** Waits for a few fresh world frames with the current settings, saves a screenshot, then runs {@code then}. */
    private static void shoot(Minecraft mc, String name, Runnable then) throws Exception {
        if (!shotPending || UpscaleManager.worldFrames() < frameMark + 8) {
            return;
        }
        shotPending = false;
        int glError = GL11.glGetError();
        LOGGER.info("{}: frames={}, GL error=0x{}, blocked={}", name, UpscaleManager.worldFrames(),
                Integer.toHexString(glError), UpscaleManager.blockedKey());
        Path dir = mc.gameDirectory.toPath().resolve("smoketest");
        Files.createDirectories(dir);
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(dir.resolve(name + ".png"));
            LOGGER.info("saved {}.png ({}x{})", name, image.getWidth(), image.getHeight());
        }
        then.run();
    }

    private static void createWorld(Minecraft mc) {
        LevelSettings settings = new LevelSettings("pocketfsr-smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                true, new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel("pocketfsr-smoke-" + System.currentTimeMillis(), settings,
                new WorldOptions(20260101L, false, false), WorldPresets::createNormalWorldDimensions, mc.screen);
    }
}
