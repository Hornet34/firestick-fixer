// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.hatim.firestickfixer;

/**
 * Color constants for the programmatically-built UI. Mirrors the
 * named entries in {@code res/values/colors.xml} so both Java and
 * XML drawables share a single canonical palette. Keep both in sync.
 */
public final class Colors {

    private Colors() {}

    /** Mirrors {@code @color/brand}. FireStick Fixer Fire orange. */
    public static final int BRAND = 0xFFFF8A00;

    /** Mirrors {@code @color/text_white}. */
    public static final int WHITE = 0xFFFFFFFF;

    /** Mirrors {@code @color/text_neutral}. Light gray for labels. */
    public static final int NEUTRAL = 0xFFE5E7EB;

    /** Mirrors {@code @color/surface_tip_box}. The pinned tip strip. */
    public static final int TIP_BG = 0xFF17191E;

    /** Mirrors {@code @color/bg_bar}. Title bar. */
    public static final int BAR = 0xFF17191E;

    /** Mirrors {@code @color/text_dim}. Section headings, secondary text. */
    public static final int DIM = 0xFF9CA3AF;

    /** Mirrors {@code @color/ok_green}. "All good" states. */
    public static final int OK = 0xFF4ADE80;

    /** Mirrors {@code @color/amber}. Attention states. */
    public static final int AMBER = 0xFFFBBF24;
}
