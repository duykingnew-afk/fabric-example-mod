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

package com.duykingnew.pocketfsr.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The mod's settings, stored as JSON in {@code config/pocketfsr.json}.
 */
public final class PocketFsrConfig {
    /** How the low resolution picture is brought back to the screen resolution. */
    public enum Mode {
        /** FSR 1.0: EASU upscale, then optional RCAS sharpen. */
        FSR1,
        /** Plain bilinear stretch: the cheapest option, and the fallback if the shaders fail to load. */
        BILINEAR
    }

    public static final float MIN_SCALE = 0.25f;
    public static final float MAX_SCALE = 1.0f;

    private static final Logger LOGGER = LoggerFactory.getLogger("PocketFSR");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PocketFsrConfig instance = new PocketFsrConfig();

    /** Master switch. */
    public boolean enabled = true;
    /** Fraction of the window resolution that the 3D world is rendered at (0.25 - 1.0). */
    public float scale = 0.67f;
    /** Upscaling method. */
    public Mode mode = Mode.FSR1;
    /** RCAS sharpening, 0 = off, 1 = strongest. Only used by {@link Mode#FSR1}. */
    public float sharpness = 0.8f;
    /** Keep upscaling when the Iris shader mod is installed. Set to false if a shader pack looks wrong. */
    public boolean allowWithIris = true;

    public static PocketFsrConfig get() {
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("pocketfsr.json");
    }

    public static void load() {
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                PocketFsrConfig loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PocketFsrConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            } catch (Exception e) {
                LOGGER.warn("Could not read {}, using defaults", file, e);
            }
        }
        instance.sanitize();
        save();
    }

    public static void save() {
        instance.sanitize();
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not write the config file", e);
        }
    }

    /** Keeps every value inside its valid range, whatever was typed into the file. */
    public void sanitize() {
        if (Float.isNaN(scale)) {
            scale = 0.67f;
        }
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        if (Float.isNaN(sharpness)) {
            sharpness = 0.8f;
        }
        sharpness = Math.max(0.0f, Math.min(1.0f, sharpness));
        if (mode == null) {
            mode = Mode.FSR1;
        }
    }
}
