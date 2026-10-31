package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.*;

import net.runelite.api.Scene;

/**
 * The ground's colour rules: 117 HD's undo of the light vanilla bakes into terrain colours
 * (ProceduralGenerator.TerrainDataGenerator), which tiles keep the game's colours, and the corner slopes the undo
 * and the terrain normals go by. Every tile is drawn in its own corner colours: colours are never blended between
 * tiles (the user's rule), on land or under the water.
 */
public final class GroundColors
{
	// 117 HD's table of which faces of a tile model are overlay faces, indexed by shape - 1
	private static final boolean[][] OVERLAY_FACES = {
		{true, true, true, true},
		{false, true},
		{false, false, true},
		{false, false, true},
		{false, true, true},
		{false, true, true},
		{false, false, true, true},
		{false, false, false, true},
		{false, true, true, true},
		{false, false, false, true, true, true},
		{true, true, true, false, false, false},
		{true, true, false, false, false, false},
	};

	// 117 HD's approximate reversal of the lighting vanilla bakes into terrain colors: brighten slopes facing
	// away from vanilla's light, darken those facing it, and undo vanilla's general darkening of the ground
	private static final float LIGHTEN_MULTIPLIER = 1.5f;
	private static final int LIGHTEN_BASE = 15;
	private static final int LIGHTEN_SUBTRACT = 3;
	private static final float DARKEN_MULTIPLIER = 0.5f;
	private static final float GAIN = 1.25f;
	private static final int MAX_LIGHTNESS = 55; // keeps bright ground from washing out

	// Overlays whose tiles 117 HD leaves in the game's colours, not undone: its tile overrides with "blended": false
	// at its defaults (tile_overrides.json, area ALL: OVERLAY_SWAMP_WATER 7 128 156, OVERLAY_CARPET 13 163, LAVA 19,
	// OVERLAY_BRICK_BROWN 27 46, OVERLAY_28 28, VANILLA_BLACK_TILE 37; OVERWORLD: the Savannah church tiles 199). In
	// 117 HD the undo happens only to colours that go into its blending, so these, like textured tiles, stay raw.
	// (Its ~400 area-limited ones are not imported.)
	private static final int[] UNBLENDED_OVERLAYS = {7, 13, 19, 27, 28, 37, 46, 128, 156, 163, 199};
	private static final boolean[] unblendedOverlay = new boolean[1 << 16];

	static
	{
		for (int id : UNBLENDED_OVERLAYS)
		{
			unblendedOverlay[id] = true;
		}
	}

	/**
	 * Whether tile (ex, ey) on {@code level} keeps the game's colours, not undone: an overlay 117 HD never blends.
	 */
	static boolean unblended(Scene scene, int level, int ex, int ey)
	{
		short[][][] overlayIds = scene.getOverlayIds();
		return overlayIds != null && unblendedOverlay[overlayIds[level][ex][ey] & 0xffff];
	}

	static boolean isOverlayFace(int shape, int face)
	{
		int s = shape - 1;
		return s >= 0 && s < OVERLAY_FACES.length && face < OVERLAY_FACES[s].length && OVERLAY_FACES[s][face];
	}

	/**
	 * Height slope (dh/dx, dh/dz) at tile corner (x, y), in extended tile coordinates. The surface normal is
	 * (dh/dx, -1, dh/dz), y down.
	 */
	static void cornerSlope(int[][] h, int x, int y, float[] out)
	{
		int maxX = h.length - 1, maxY = h[0].length - 1;
		int xa = Math.max(x - 1, 0), xb = Math.min(x + 1, maxX);
		int ya = Math.max(y - 1, 0), yb = Math.min(y + 1, maxY);
		x = Math.min(Math.max(x, 0), maxX);
		y = Math.min(Math.max(y, 0), maxY);
		out[0] = (h[xb][y] - h[xa][y]) / (float) ((xb - xa) * 128);
		out[1] = (h[x][yb] - h[x][ya]) / (float) ((yb - ya) * 128);
	}

	/**
	 * Slopes at the four corners of tile (x, y): sw, se, nw, ne as (dx, dz) pairs.
	 */
	static void tileSlopes(int[][] h, int x, int y, float[] slope, float[] out)
	{
		cornerSlope(h, x, y, slope);
		out[0] = slope[0];
		out[1] = slope[1];
		cornerSlope(h, x + 1, y, slope);
		out[2] = slope[0];
		out[3] = slope[1];
		cornerSlope(h, x, y + 1, slope);
		out[4] = slope[0];
		out[5] = slope[1];
		cornerSlope(h, x + 1, y + 1, slope);
		out[6] = slope[0];
		out[7] = slope[1];
	}

	/**
	 * Bilinear slope inside a tile, from {@link #tileSlopes}; fx, fz in 0-1 across the tile. Tile model vertex
	 * heights are bilinear in the corner heights, so this matches the smooth corner normals of plain tiles.
	 */
	static void interpolateSlope(float[] c, float fx, float fz, float[] out)
	{
		fx = Math.min(Math.max(fx, 0), 1);
		fz = Math.min(Math.max(fz, 0), 1);
		float sx = c[0] + (c[2] - c[0]) * fx, nx = c[4] + (c[6] - c[4]) * fx;
		float sz = c[1] + (c[3] - c[1]) * fx, nz = c[5] + (c[7] - c[5]) * fx;
		out[0] = sx + (nx - sx) * fz;
		out[1] = sz + (nz - sz) * fz;
	}

	static int undoShading(int color, float dhdx, float dhdz)
	{
		// how much the normal (dhdx, -1, dhdz) faces +x +z, which vanilla's light leaves dark; the arithmetic as
		// 117 HD's (ProceduralGenerator 733-755, its mix(a, b, t) = a (1 - t) + b t), so the truncations land alike
		float dot = (dhdx + dhdz) / (float) Math.sqrt(2 * (dhdx * dhdx + 1 + dhdz * dhdz));
		int l = color & 0x7f;

		float lighten = Math.max(dot, 0);
		int adjusted = (int) (Math.max(l - LIGHTEN_SUBTRACT, 0) * LIGHTEN_MULTIPLIER + LIGHTEN_BASE);
		l = (int) (l * (1 - lighten) + adjusted * lighten);

		float darken = -Math.min(dot, 0);
		int darkened = (int) (l * DARKEN_MULTIPLIER);
		l = (int) (GAIN * (l * (1 - darken) + darkened * darken));

		l = Math.min(l, MAX_LIGHTNESS);
		return color & ~0x7f | l;
	}
}
