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

import com.mojang.blaze3d.pipeline.TextureTarget;

/**
 * The reduced resolution target that the 3D world is drawn into.
 *
 * <p>It is a plain {@link TextureTarget}, plus two methods that only matter when the Iris shader mod is
 * installed. Iris adds {@code iris$getDepthBufferVersion()} and {@code iris$getColorBufferVersion()} to every
 * render target: a counter of how many times its buffers were destroyed. Iris decides whether it has to attach a
 * different depth texture (and re-create its copies of it) by comparing that counter with the last one it saw.
 * Pocket FSR makes Iris see two different targets in turn, the game's main target and this one, and both start
 * counting at 0, so after a few resizes the numbers can be equal. Iris then keeps using the other target's depth
 * texture, which has another size: the frame is garbled and the GL log fills with
 * {@code GL_INVALID_VALUE in glCopyImageSubData}.
 *
 * <p>The two methods below answer with numbers that are far above anything the main target will reach, and that
 * grow every time this target is resized, so Iris always sees a change when the target it renders to changes.
 * Without Iris nothing calls them. They are written without {@code @Override} on purpose: they only exist at
 * runtime, added to {@code RenderTarget} by Iris, and an instance method with the same name and descriptor in
 * this subclass takes precedence over that one.
 */
final class WorldTarget extends TextureTarget {
    /** The main target counts one per resize of the window; this is never reached by that. */
    private static final int VERSION_BASE = 1_000_000_000;

    private int generation;

    WorldTarget(int width, int height, boolean useDepth, boolean clearError) {
        super(width, height, useDepth, clearError);
    }

    @Override
    public void resize(int width, int height, boolean clearError) {
        this.generation++;
        super.resize(width, height, clearError);
    }

    /** Called by Iris (see the class comment). */
    public int iris$getDepthBufferVersion() {
        return VERSION_BASE + this.generation;
    }

    /** Called by Iris (see the class comment). */
    public int iris$getColorBufferVersion() {
        return VERSION_BASE + this.generation;
    }
}
