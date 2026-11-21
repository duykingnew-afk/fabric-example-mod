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

package com.duykingnew.pocketfsr.gui;

import com.duykingnew.pocketfsr.config.PocketFsrConfig;
import com.duykingnew.pocketfsr.render.UpscaleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** The settings screen: on/off, render scale, method and sharpening. */
public class PocketFsrScreen extends Screen {
    private final Screen parent;
    private SharpnessSlider sharpnessSlider;

    public PocketFsrScreen(Screen parent) {
        super(Component.translatable("pocketfsr.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        PocketFsrConfig cfg = PocketFsrConfig.get();
        int w = Math.min(220, this.width - 20);
        int x = this.width / 2 - w / 2;
        int y = 34;

        this.addRenderableWidget(Button.builder(enabledLabel(cfg), button -> {
            cfg.enabled = !cfg.enabled;
            button.setMessage(enabledLabel(cfg));
        }).bounds(x, y, w, 20).build());

        this.addRenderableWidget(new ScaleSlider(x, y + 24, w, 20, cfg));

        this.sharpnessSlider = new SharpnessSlider(x, y + 72, w, 20, cfg);
        this.sharpnessSlider.active = cfg.mode == PocketFsrConfig.Mode.FSR1;

        this.addRenderableWidget(Button.builder(modeLabel(cfg), button -> {
            cfg.mode = cfg.mode == PocketFsrConfig.Mode.FSR1 ? PocketFsrConfig.Mode.BILINEAR : PocketFsrConfig.Mode.FSR1;
            button.setMessage(modeLabel(cfg));
            this.sharpnessSlider.active = cfg.mode == PocketFsrConfig.Mode.FSR1;
        }).bounds(x, y + 48, w, 20).build());

        this.addRenderableWidget(this.sharpnessSlider);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(x, this.height - 28, w, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

        PocketFsrConfig cfg = PocketFsrConfig.get();
        int infoY = 34 + 72 + 28;
        String blocked = UpscaleManager.blockedKey();
        if (blocked != null) {
            graphics.drawCenteredString(this.font, Component.translatable(blocked), this.width / 2, infoY, 0xFF5555);
        } else {
            Minecraft mc = Minecraft.getInstance();
            int sw = mc.getWindow().getWidth();
            int sh = mc.getWindow().getHeight();
            int rw = Math.max(32, Math.round(sw * cfg.scale));
            int rh = Math.max(32, Math.round(sh * cfg.scale));
            graphics.drawCenteredString(this.font,
                    Component.translatable("pocketfsr.option.resolution", rw, rh, sw, sh),
                    this.width / 2, infoY, 0xA0A0A0);
        }
    }

    @Override
    public void onClose() {
        PocketFsrConfig.save();
        this.minecraft.setScreen(this.parent);
    }

    private static Component enabledLabel(PocketFsrConfig cfg) {
        return Component.translatable("pocketfsr.option.enabled",
                Component.translatable(cfg.enabled ? "pocketfsr.option.on" : "pocketfsr.option.off"));
    }

    private static Component modeLabel(PocketFsrConfig cfg) {
        return Component.translatable("pocketfsr.option.mode",
                Component.translatable(cfg.mode == PocketFsrConfig.Mode.FSR1 ? "pocketfsr.mode.fsr1" : "pocketfsr.mode.bilinear"));
    }

    /** Render scale, 25% - 100% in steps of 5%. */
    private static final class ScaleSlider extends AbstractSliderButton {
        private final PocketFsrConfig cfg;

        ScaleSlider(int x, int y, int width, int height, PocketFsrConfig cfg) {
            super(x, y, width, height, Component.empty(), toSlider(cfg.scale));
            this.cfg = cfg;
            this.updateMessage();
        }

        private static double toSlider(float scale) {
            return (scale - PocketFsrConfig.MIN_SCALE) / (PocketFsrConfig.MAX_SCALE - PocketFsrConfig.MIN_SCALE);
        }

        private static float fromSlider(double value) {
            float raw = (float) (PocketFsrConfig.MIN_SCALE + value * (PocketFsrConfig.MAX_SCALE - PocketFsrConfig.MIN_SCALE));
            return Math.round(raw * 20.0F) / 20.0F;
        }

        @Override
        protected void updateMessage() {
            int percent = Math.round(fromSlider(this.value) * 100.0F);
            this.setMessage(Component.translatable("pocketfsr.option.scale", percent));
        }

        @Override
        protected void applyValue() {
            this.cfg.scale = fromSlider(this.value);
        }
    }

    /** RCAS sharpening, 0% (off) - 100%. */
    private static final class SharpnessSlider extends AbstractSliderButton {
        private final PocketFsrConfig cfg;

        SharpnessSlider(int x, int y, int width, int height, PocketFsrConfig cfg) {
            super(x, y, width, height, Component.empty(), cfg.sharpness);
            this.cfg = cfg;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            int percent = Math.round((float) this.value * 100.0F);
            if (percent <= 0) {
                this.setMessage(Component.translatable("pocketfsr.option.sharpness.off"));
            } else {
                this.setMessage(Component.translatable("pocketfsr.option.sharpness", percent));
            }
        }

        @Override
        protected void applyValue() {
            this.cfg.sharpness = (float) this.value;
        }
    }
}
