package com.midonarhel.runelight;

import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;

/**
 * Ground color blending, the color half of 117 HD's ground blending (ProceduralGenerator.TerrainDataGenerator).
 *
 * Vanilla gives every tile its own corner colors, so where a path (overlay) meets grass (underlay) the two tiles
 * have different colors at the same vertex: a hard edge that zigzags along the tile shapes. Here every untextured
 * ground vertex gets one color, shared by all tiles and tile-model faces that touch it, so colors interpolate
 * across tile borders. Overlay colors win over underlay colors at a shared vertex, and near-black colors (used
 * under walls) only win where nothing else touches the vertex. Textured tiles (water, lava, ...) keep their own
 * colors and don't contribute.
 *
 * Built per zone at upload time from the zone's tiles plus a one-tile border, which covers every tile touching
 * the zone's vertices, so the result doesn't depend on which zone is uploaded first or on the scene being whole.
 */
final class GroundBlender
{
	static boolean enabled = true;

	private static final int HIDDEN_HSL = 12345678;

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

	private static final int HIGH_PRIORITY = 1 << 16;
	private static final int TOUCHES_OVERLAY = 1 << 17;
	private static final int TOUCHES_UNDERLAY = 1 << 18;
	private static final int HAS_COLOR = 1 << 19;

	// lookup result flags
	static final int BORDER = 1 << 16;
	static final int BLENDED = 1 << 17;

	// Overlays 117 HD never blends anywhere: carpets, bricks, cobblestone and tiled floors, lava, black tiles.
	// Blending would smear their crisp patterns. (117 HD has ~400 more area-specific exceptions; not imported.)
	private static final int[] UNBLENDED_OVERLAYS = {7, 13, 19, 27, 28, 37, 46, 117, 128, 137, 156, 163, 171, 179, 199};
	private static final boolean[] unblendedOverlay = new boolean[1 << 16];

	static
	{
		for (int id : UNBLENDED_OVERLAYS)
		{
			unblendedOverlay[id] = true;
		}
	}

	private static final int CAPACITY = 1 << 14; // a zone plus border has a few thousand ground vertices at most
	private static final int MASK = CAPACITY - 1;
	private static final int MAX_COUNT = CAPACITY * 3 / 4;

	private final int[] keys = new int[CAPACITY];
	private final int[] values = new int[CAPACITY]; // flags | hsl
	private final int[] stamps = new int[CAPACITY];
	private int stamp;
	private int count;

	private int[][][] heights;
	private short[][][] overlayIds;
	private int offset; // scene -> extended tile coordinates
	private final float[] slope = new float[2];
	private final float[] corners = new float[8];

	/**
	 * Collect the blended vertex colors around zone (mzx, mzz).
	 */
	void build(Scene scene, int mzx, int mzz)
	{
		if (++stamp == 0)
		{
			java.util.Arrays.fill(stamps, 0);
			stamp = 1;
		}
		count = 0;

		heights = scene.getTileHeights();
		overlayIds = scene.getOverlayIds();
		offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? RuneLightPlugin.SCENE_OFFSET : 0;

		Tile[][][] tiles = scene.getExtendedTiles();
		int x0 = Math.max((mzx << 3) - 1, 0), x1 = Math.min((mzx << 3) + 8, tiles[0].length - 1);
		int y0 = Math.max((mzz << 3) - 1, 0), y1 = Math.min((mzz << 3) + 8, tiles[0][0].length - 1);
		// same order as 117 HD, so equal-rank ties resolve the same way
		for (int z = 0; z < tiles.length; ++z)
		{
			for (int x = x0; x <= x1; ++x)
			{
				for (int y = y0; y <= y1; ++y)
				{
					Tile tile = tiles[z][x][y];
					if (tile == null)
					{
						continue;
					}
					addTile(tile, x, y);
					Tile bridge = tile.getBridge();
					if (bridge != null)
					{
						addTile(bridge, x, y);
					}
				}
			}
		}
	}

	/**
	 * The ground vertex at extended local position (x, height, z), or -1 if no ground tile touches it: its blended
	 * color in the low 16 bits when {@link #BLENDED} is set, and {@link #BORDER} where overlay and underlay meet.
	 */
	int lookup(int x, int height, int z)
	{
		int key = key(x, height, z);
		for (int i = hash(key); ; i = i + 1 & MASK)
		{
			if (stamps[i] != stamp)
			{
				return -1;
			}
			if (keys[i] == key)
			{
				int v = values[i];
				int border = (v & (TOUCHES_OVERLAY | TOUCHES_UNDERLAY)) == (TOUCHES_OVERLAY | TOUCHES_UNDERLAY) ? BORDER : 0;
				return border | ((v & HAS_COLOR) != 0 ? BLENDED | v & 0xffff : 0);
			}
		}
	}

	/**
	 * Whether tile (ex, ey) on {@code level} keeps its own colors because its overlay is never blended.
	 */
	boolean unblended(int level, int ex, int ey)
	{
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

	private void addTile(Tile tile, int ex, int ey)
	{
		int level = tile.getRenderLevel();
		int[][] h = heights[level];

		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint != null)
		{
			if (paint.getTexture() != -1 || paint.getNeColor() == HIDDEN_HSL)
			{
				return;
			}
			boolean overlay = overlayIds != null && overlayIds[level][ex][ey] != 0;
			boolean blend = !unblended(level, ex, ey);
			addCorner(h, ex, ey, paint.getSwColor(), overlay, blend);
			addCorner(h, ex + 1, ey, paint.getSeColor(), overlay, blend);
			addCorner(h, ex + 1, ey + 1, paint.getNeColor(), overlay, blend);
			addCorner(h, ex, ey + 1, paint.getNwColor(), overlay, blend);
			return;
		}

		SceneTileModel model = tile.getSceneTileModel();
		if (model == null)
		{
			return;
		}

		final int[] faceX = model.getFaceX();
		final int[] faceY = model.getFaceY();
		final int[] faceZ = model.getFaceZ();
		final int[] vertexX = model.getVertexX();
		final int[] vertexY = model.getVertexY();
		final int[] vertexZ = model.getVertexZ();
		final int[] colorA = model.getTriangleColorA();
		final int[] colorB = model.getTriangleColorB();
		final int[] colorC = model.getTriangleColorC();
		final int[] textures = model.getTriangleTextureId();
		// a tile model with any texture (shores) keeps its overlay faces' own colors, as in 117 HD
		final boolean blendOverlay = textures == null && !unblended(level, ex, ey);
		final int shape = model.getShape();
		final int baseX = (ex - offset) << 7, baseZ = (ey - offset) << 7;
		tileSlopes(h, ex, ey, slope, corners);

		for (int face = 0; face < faceX.length; ++face)
		{
			if (colorA[face] == HIDDEN_HSL || textures != null && textures[face] != -1)
			{
				continue;
			}
			boolean overlay = isOverlayFace(shape, face);
			boolean blend = !overlay || blendOverlay;
			addModelVertex(vertexX, vertexY, vertexZ, faceX[face], colorA[face], overlay, blend, baseX, baseZ);
			addModelVertex(vertexX, vertexY, vertexZ, faceY[face], colorB[face], overlay, blend, baseX, baseZ);
			addModelVertex(vertexX, vertexY, vertexZ, faceZ[face], colorC[face], overlay, blend, baseX, baseZ);
		}
	}

	private void addCorner(int[][] h, int x, int y, int color, boolean overlay, boolean blend)
	{
		cornerSlope(h, x, y, slope);
		add(x << 7, h[x][y], y << 7, color, overlay, blend, slope[0], slope[1]);
	}

	private void addModelVertex(int[] vx, int[] vy, int[] vz, int v, int color, boolean overlay, boolean blend, int baseX, int baseZ)
	{
		interpolateSlope(corners, (vx[v] - baseX) / 128f, (vz[v] - baseZ) / 128f, slope);
		add(vx[v] + (offset << 7), vy[v], vz[v] + (offset << 7), color, overlay, blend, slope[0], slope[1]);
	}

	/**
	 * @param blend whether the color takes part in blending; unblended tiles still mark overlay/underlay borders
	 */
	private void add(int x, int height, int z, int color, boolean overlay, boolean blend, float dhdx, float dhdz)
	{
		int touch = overlay ? TOUCHES_OVERLAY : TOUCHES_UNDERLAY;
		int value = 0;
		boolean lowPriority = false;
		if (color < 0 || color == HIDDEN_HSL)
		{
			blend = false;
		}
		if (blend)
		{
			// near-black, used under walls and such; only where no proper color touches the vertex
			lowPriority = color <= 2;
			if (VertexShading.undoVanillaShading)
			{
				color = undoShading(color, dhdx, dhdz);
			}
			value = HAS_COLOR | (lowPriority ? 0 : HIGH_PRIORITY) | color & 0xffff;
		}

		int key = key(x, height, z);
		for (int i = hash(key); ; i = i + 1 & MASK)
		{
			if (stamps[i] != stamp)
			{
				if (count >= MAX_COUNT)
				{
					return; // can't happen with sane tile data; the vertex keeps its own color
				}
				stamps[i] = stamp;
				keys[i] = key;
				values[i] = touch | value;
				++count;
				return;
			}
			if (keys[i] == key)
			{
				int old = values[i] | touch;
				// 117 HD's rules: overlays replace what's there, underlays only fill empty vertices, and a low
				// priority color never replaces a high priority one, so paths keep their color up to their edge.
				// Unlike 117 HD a proper underlay color also replaces a near-black one, so wall bases don't leave
				// dark smudges on the grass beside them.
				if (blend && !(lowPriority && (old & HIGH_PRIORITY) != 0)
					&& (overlay || (old & HAS_COLOR) == 0 || !lowPriority && (old & HIGH_PRIORITY) == 0))
				{
					old = old & ~(HAS_COLOR | HIGH_PRIORITY | 0xffff) | value;
				}
				values[i] = old;
				return;
			}
		}
	}

	static int undoShading(int color, float dhdx, float dhdz)
	{
		// how much the normal (dhdx, -1, dhdz) faces +x +z, which vanilla's light leaves dark
		float dot = (dhdx + dhdz) / (1.4142135f * (float) Math.sqrt(dhdx * dhdx + 1 + dhdz * dhdz));
		int l = color & 0x7f;

		float lighten = Math.max(dot, 0);
		int adjusted = (int) (Math.max(l - LIGHTEN_SUBTRACT, 0) * LIGHTEN_MULTIPLIER + LIGHTEN_BASE);
		l = (int) (l + (adjusted - l) * lighten);

		float darken = -Math.min(dot, 0);
		int darkened = (int) (l * DARKEN_MULTIPLIER);
		l = (int) (GAIN * (l + (darkened - l) * darken));

		l = Math.min(l, MAX_LIGHTNESS);
		return color & ~0x7f | l;
	}

	// 117 HD's vertex key: x and z in quarter tiles (10 bits each, enough for the extended scene), height in the
	// top 12 bits so bridges and the ground below them stay apart
	private static int key(int x, int height, int z)
	{
		return (height & 0xfff) << 20 | (z >> 5 & 0x3ff) << 10 | x >> 5 & 0x3ff;
	}

	private static int hash(int key)
	{
		return (key * 0x9e3779b1) >>> (32 - 14);
	}
}
