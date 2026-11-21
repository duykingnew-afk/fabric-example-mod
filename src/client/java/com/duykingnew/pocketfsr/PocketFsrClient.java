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

package com.duykingnew.pocketfsr;

import com.duykingnew.pocketfsr.config.PocketFsrConfig;
import com.duykingnew.pocketfsr.gui.PocketFsrScreen;
import com.duykingnew.pocketfsr.render.FsrShaders;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class PocketFsrClient implements ClientModInitializer {
    public static final String MOD_ID = "pocketfsr";

    private static KeyMapping toggleKey;
    private static KeyMapping settingsKey;

    @Override
    public void onInitializeClient() {
        PocketFsrConfig.load();
        FsrShaders.register(MOD_ID);

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.pocketfsr.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "key.categories.pocketfsr"));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.pocketfsr.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.pocketfsr"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                PocketFsrConfig cfg = PocketFsrConfig.get();
                cfg.enabled = !cfg.enabled;
                PocketFsrConfig.save();
                if (client.player != null) {
                    client.player.displayClientMessage(
                            Component.translatable(cfg.enabled ? "pocketfsr.message.on" : "pocketfsr.message.off"), true);
                }
            }
            while (settingsKey.consumeClick()) {
                if (!(client.screen instanceof PocketFsrScreen)) {
                    client.setScreen(new PocketFsrScreen(client.screen));
                }
            }
        });

        // A small button in the corner of the video settings and the pause menu, so touch devices
        // (which usually have no F7/F8 keys) can reach the settings too.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof VideoSettingsScreen || screen instanceof PauseScreen) {
                Screens.getButtons(screen).add(Button.builder(
                                Component.translatable("pocketfsr.button"),
                                button -> Minecraft.getInstance().setScreen(new PocketFsrScreen(screen)))
                        .bounds(4, 4, 90, 20)
                        .build());
            }
        });
    }
}
