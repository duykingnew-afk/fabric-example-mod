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
import net.minecraft.core.registries.Registries;
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
 * It creates a flat world with a small test scene (a block to point at, water, glass, a chest and a few
 * mobs), renders it natively and through each upscaling mode, saves screenshots to {@code smoketest/} in the
 * game directory, then quits. Never active for normal players.
 */
public final class SmokeTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("PocketFSR-SMOKE");

    private record Shot(String name, boolean enabled, float scale, PocketFsrConfig.Mode mode, float pitch) {
    }

    private static final float AHEAD = 0.0F;
    private static final float SKY = -55.0F;
    private static final Shot[] SHOTS = {
            new Shot("native", false, 1.0F, PocketFsrConfig.Mode.FSR1, AHEAD),
            new Shot("fsr67", true, 0.67F, PocketFsrConfig.Mode.FSR1, AHEAD),
            new Shot("fsr50", true, 0.5F, PocketFsrConfig.Mode.FSR1, AHEAD),
            new Shot("bilinear50", true, 0.5F, PocketFsrConfig.Mode.BILINEAR, AHEAD),
            new Shot("native-sky", false, 1.0F, PocketFsrConfig.Mode.FSR1, SKY),
            new Shot("fsr67-sky", true, 0.67F, PocketFsrConfig.Mode.FSR1, SKY),
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
                    if (UpscaleManager.worldFrames() < frameMark + 8) {
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
        cfg.sharpness = 0.8F;
        mc.player.setXRot(shot.pitch());
        mc.player.xRotO = shot.pitch();
        frameMark = UpscaleManager.worldFrames();
    }

    private static void saveShot(Minecraft mc, Shot shot) throws Exception {
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
