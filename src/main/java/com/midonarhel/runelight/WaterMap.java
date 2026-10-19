package com.midonarhel.runelight;

import net.runelite.api.Constants;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;

/**
 * The water of one top-level scene, worked out on the CPU when the scene loads.
 *
 * Water tiles are the ones with the game's water textures (117 HD's list). Their terrain is uploaded lowered into
 * a bed (SceneUploader), deeper further from shore as 117 HD does, so there is something to see through the
 * water; the surface is not geometry at all but drawn by the composite pass (water.glsl) over the bed, from the
 * surface heights and the per-tile data in {@link #texture}: flow, water type, distance to shore, wave energy.
 *
 * Bed depth and bed color only depend on a tile's own surroundings (at most {@link #MAX_LEVEL} tiles away), so
 * zones reused from the previous scene line up with newly uploaded ones.
 */
final class WaterMap
{
	static final int SIZE = Constants.EXTENDED_SCENE_SIZE;
	static final int CORNERS = SIZE + 1;

	// water types, indices into the palette in water.glsl
	static final int NONE = 0;
	static final int FRESH = 1;
	static final int OCEAN = 2;
	static final int OCEAN_DARK = 3;
	static final int OCEAN_PALE = 4;
	static final int SWAMP = 5;
	static final int CAVE = 6;

	// what an area does to the water in it (Areas)
	static final int CLEAR = 0;
	static final int MURKY_SWAMP = 1;
	static final int MURKY_CAVE = 2;

	// vertex flags in the texture id short of terrain (above SurfaceMaterials' bits): the frag shader writes them
	// to the material target so the composite knows bed (always under water) from land (never)
	static final int BED_FLAG = 1 << 13;
	static final int LAND_FLAG = 1 << 14;

	// 117 HD's bed depth per tile from shore (ProceduralGenerator.DEPTH_LEVEL_SLOPE * 0.55), cut off early so the
	// depth never depends on tiles more than a zone away
	static final int MAX_LEVEL = 8;
	private static final int[] DEPTH = {82, 165, 258, 335, 385, 412, 451, 506};
	// at least this deep in the middle of a water tile, so a stream between two banks still holds water
	static final int MIN_DEPTH = 56;

	interface Areas
	{
		/**
		 * @return {@link #CLEAR}, {@link #MURKY_SWAMP} or {@link #MURKY_CAVE} for a scene tile
		 */
		int murk(int sceneX, int sceneY, int plane);
	}

	// per tile: final water type, render level of the water tile (-1 none), the game's water texture, the bed color
	final byte[] type = new byte[SIZE * SIZE];
	final short[] waterTexture = new short[SIZE * SIZE];
	final byte[] level = new byte[SIZE * SIZE];
	private final short[] bedHsl = new short[SIZE * SIZE];
	// per corner: depth level (0 at the shore)
	final byte[] depthLevel = new byte[CORNERS * CORNERS];
	// per corner: the world flow map's flow right at the corner (finer than its tiles' average), NaN where unknown
	private final float[] cornerFlowX = new float[CORNERS * CORNERS];
	private final float[] cornerFlowZ = new float[CORNERS * CORNERS];
	// per level, the terrain heights with the bed lowered, for bed normals
	private final int[][][] bedHeights = new int[Constants.MAX_Z][][];
	final WaterBodies bodies = new WaterBodies(SIZE);
	private int baseX, baseY;

	// RGBA float texture, two pages of CORNERS x CORNERS side by side, row by row:
	// page 0 (surface height, on land the nearest water's; flow x, flow z in tiles/s; type of the tile whose sw corner
	// this is),
	// page 1 (distance to shore in tiles, wave energy 0-1, share of the corner's tiles that are water, turbulence 0-1)
	float[] texture;
	boolean hasWater;

	/**
	 * Water type for a vanilla texture id, before the area and body decide: FRESH, SWAMP or a sea type, or NONE.
	 */
	static int textureKind(int texture)
	{
		switch (texture)
		{
			case 1:
			case 24:
				return FRESH;
			case 25:
				return SWAMP;
			case 208:
				return OCEAN_DARK;
		}
		if (texture < 130 || texture > 189)
		{
			return NONE;
		}
		// sailing seas, five depths each: plain, stormy, sunbaked, profane, cursed, scalding, icy, endless,
		// crystal, fetid, tangled, jagged
		switch ((texture - 130) / 5)
		{
			case 0:
			case 2:
				return OCEAN;
			case 6:
			case 7:
			case 8:
				return OCEAN_PALE;
			case 9:
				return SWAMP;
			default:
				return OCEAN_DARK;
		}
	}

	static boolean isSeaTexture(int texture)
	{
		return texture >= 130 && texture <= 189 || texture == 208;
	}

	static boolean isWaterTexture(int texture)
	{
		return textureKind(texture) != NONE;
	}

	static WaterMap build(Scene scene, Areas areas)
	{
		return build(scene, areas, WaterFlow.world());
	}

	static WaterMap build(Scene scene, Areas areas, WaterFlow flowMap)
	{
		WaterMap m = new WaterMap();
		m.load(scene, areas, flowMap);
		return m;
	}

	private void load(Scene scene, Areas areas, WaterFlow flowMap)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		int[][][] heights = scene.getTileHeights();
		baseX = scene.getBaseX() - RuneLightPlugin.SCENE_OFFSET;
		baseY = scene.getBaseY() - RuneLightPlugin.SCENE_OFFSET;

		java.util.Arrays.fill(level, (byte) -1);
		int[] kind = new int[SIZE * SIZE];
		short[] underlay = new short[SIZE * SIZE];
		UnderlayColors ground = new UnderlayColors(scene);
		int[] murk = new int[SIZE * SIZE];
		boolean[] landCorner = new boolean[CORNERS * CORNERS];
		WaterBodies b = bodies;
		float[] flow = new float[2];
		int[] world = new int[3];
		b.mapShaped = flowMap != null && flowMap.shaped();
		java.util.Arrays.fill(cornerFlowX, Float.NaN);
		java.util.Arrays.fill(cornerFlowZ, Float.NaN);
		// instances are built from rotated chunks: their corners keep the tiles' average
		if (flowMap != null && !scene.isInstance())
		{
			for (int cy = 0; cy < CORNERS; ++cy)
			{
				for (int cx = 0; cx < CORNERS; ++cx)
				{
					if (flowMap.corner(baseX + cx, baseY + cy, flow))
					{
						cornerFlowX[cy * CORNERS + cx] = flow[0];
						cornerFlowZ[cy * CORNERS + cx] = flow[1];
					}
				}
			}
		}

		for (int x = 0; x < SIZE; ++x)
		{
			for (int y = 0; y < SIZE; ++y)
			{
				int i = y * SIZE + x;
				Tile water = null;
				int k = NONE, plane = 0;
				boolean loaded = false;
				// the lowest water at this spot; water stacked above it on other planes stays vanilla
				search:
				for (int z = 0; z < tiles.length; ++z)
				{
					Tile t = tiles[z][x][y];
					loaded |= t != null;
					for (int c = 0; c < 2 && t != null; ++c, t = t.getBridge())
					{
						k = tileKind(t);
						if (k != NONE)
						{
							water = t;
							plane = z;
							break search;
						}
					}
				}
				if (water == null)
				{
					// past the loaded map the water may go on: neither shore nor land
					if (loaded)
					{
						landCorners(landCorner, x, y);
						ground.add(tiles[0][x][y], 0, x, y, heights[0]);
					}
					else
					{
						b.unknown[i] = true;
					}
					continue;
				}

				int lvl = water.getRenderLevel();
				level[i] = (byte) lvl;
				waterTexture[i] = (short) tileTexture(water);
				kind[i] = k;
				underlay[i] = ground.id(plane, x, y);
				b.water[i] = true;
				b.sea[i] = isSea(water);
				int[][] h = heights[lvl];
				b.surface[i] = (h[x][y] + h[x + 1][y] + h[x][y + 1] + h[x + 1][y + 1]) * 0.25f;
				if (flowMap != null && mapFlow(scene, x, y, plane, flowMap, world, flow))
				{
					b.mapFlowX[i] = flow[0];
					b.mapFlowZ[i] = flow[1];
				}
				murk[i] = areas == null ? CLEAR : areas.murk(x - RuneLightPlugin.SCENE_OFFSET, y - RuneLightPlugin.SCENE_OFFSET, lvl);
				hasWater = true;

				SceneTileModel model = water.getSceneTileModel();
				if (model != null)
				{
					// shore tiles: corners of their land faces stay at the shore
					landModelCorners(model, landCorner, x, y, water.getSceneLocation().getX() << 7, water.getSceneLocation().getY() << 7);
				}
			}
		}
		if (!hasWater)
		{
			return;
		}

		depthLevels(landCorner, CORNERS, MAX_LEVEL, depthLevel);
		b.solve();

		// a body takes the murk most of its tiles have, so it doesn't change color halfway across
		int[][] votes = new int[b.bodies][3];
		// and fresh water or swamp water by what most of its tiles are drawn with: a river with a few tiles of the
		// swamp texture would show them as dark squares
		int[][] kinds = new int[b.bodies][2];
		for (int i = 0; i < kind.length; ++i)
		{
			if (b.body[i] >= 0)
			{
				votes[b.body[i]][murk[i]]++;
				if (kind[i] == FRESH || kind[i] == SWAMP)
				{
					kinds[b.body[i]][kind[i] == SWAMP ? 1 : 0]++;
				}
			}
		}
		for (int i = 0; i < kind.length; ++i)
		{
			if (b.body[i] >= 0 && (kind[i] == FRESH || kind[i] == SWAMP))
			{
				int[] k = kinds[b.body[i]];
				kind[i] = k[1] > k[0] ? SWAMP : FRESH;
			}
		}
		for (int i = 0; i < kind.length; ++i)
		{
			int body = b.body[i];
			if (body < 0)
			{
				continue;
			}
			int[] v = votes[body];
			int bodyMurk = v[MURKY_CAVE] > v[CLEAR] && v[MURKY_CAVE] >= v[MURKY_SWAMP] ? MURKY_CAVE
				: v[MURKY_SWAMP] > v[CLEAR] ? MURKY_SWAMP : CLEAR;
			type[i] = (byte) finalType(kind[i], bodyMurk, b.bodyClass[body]);
			int own = ground.color(underlay[i]);
			bedHsl[i] = (short) (own >= 0 && murk[i] == CLEAR && kind[i] != SWAMP ? own : bedColor(kind[i], murk[i]));
		}

		for (int z = 0; z < bedHeights.length; ++z)
		{
			int[][] h = heights[z];
			int[][] bed = new int[CORNERS][CORNERS];
			for (int x = 0; x < CORNERS; ++x)
			{
				for (int y = 0; y < CORNERS; ++y)
				{
					bed[x][y] = h[x][y] + (cornerLevel(x, y) == z ? bedOffset(x, y) : 0);
				}
			}
			bedHeights[z] = bed;
		}

		buildTexture(heights);
	}

	/**
	 * The world flow map's flow at extended scene tile (ex, ey), turned into scene directions (instances are built
	 * from rotated chunks of the real map), tiles per second x and z into flow.
	 */
	static boolean mapFlow(Scene scene, int ex, int ey, int plane, WaterFlow flowMap, int[] world, float[] flow)
	{
		int sx = ex - RuneLightPlugin.SCENE_OFFSET, sy = ey - RuneLightPlugin.SCENE_OFFSET;
		if (!scene.isInstance())
		{
			return flowMap.flow(scene.getBaseX() + sx, scene.getBaseY() + sy, flow);
		}
		if (!EnvironmentManager.toWorld(scene, sx, sy, plane, world) || !flowMap.flow(world[0], world[1], flow))
		{
			return false;
		}
		int chunk = scene.getInstanceTemplateChunks()[plane][sx / 8][sy / 8];
		rotateToScene(4 - (chunk >> 1 & 3) & 3, flow);
		return true;
	}

	/**
	 * Turn a world direction into the scene's for a template chunk placed with {@code rotation} as
	 * {@link EnvironmentManager#toWorld} maps it.
	 */
	static void rotateToScene(int rotation, float[] v)
	{
		float wx = v[0], wy = v[1];
		switch (rotation)
		{
			case 1:
				v[0] = -wy;
				v[1] = wx;
				break;
			case 2:
				v[0] = -wx;
				v[1] = -wy;
				break;
			case 3:
				v[0] = wy;
				v[1] = -wx;
				break;
			default:
				break;
		}
	}

	private static int tileKind(Tile t)
	{
		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null)
		{
			return paint.getNeColor() != 12345678 ? textureKind(paint.getTexture()) : NONE;
		}
		SceneTileModel model = t.getSceneTileModel();
		if (model != null && model.getTriangleTextureId() != null)
		{
			for (int tex : model.getTriangleTextureId())
			{
				int k = textureKind(tex);
				if (k != NONE)
				{
					return k;
				}
			}
		}
		return NONE;
	}

	/** The tile's water texture (its first, on a shore tile), or -1. */
	private static int tileTexture(Tile t)
	{
		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null)
		{
			return isWaterTexture(paint.getTexture()) ? paint.getTexture() : -1;
		}
		SceneTileModel model = t.getSceneTileModel();
		if (model != null && model.getTriangleTextureId() != null)
		{
			for (int tex : model.getTriangleTextureId())
			{
				if (isWaterTexture(tex))
				{
					return tex;
				}
			}
		}
		return -1;
	}

	private static boolean isSea(Tile t)
	{
		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null)
		{
			return isSeaTexture(paint.getTexture());
		}
		int[] tex = t.getSceneTileModel().getTriangleTextureId();
		for (int id : tex)
		{
			if (isSeaTexture(id))
			{
				return true;
			}
		}
		return false;
	}

	private static void landCorners(boolean[] land, int x, int y)
	{
		land[y * CORNERS + x] = true;
		land[y * CORNERS + x + 1] = true;
		land[(y + 1) * CORNERS + x] = true;
		land[(y + 1) * CORNERS + x + 1] = true;
	}

	private static void landModelCorners(SceneTileModel model, boolean[] land, int x, int y, int lx, int lz)
	{
		int[] tex = model.getTriangleTextureId();
		int[] fa = model.getFaceX(), fb = model.getFaceY(), fc = model.getFaceZ();
		int[] vx = model.getVertexX(), vz = model.getVertexZ();
		for (int f = 0; f < fa.length; ++f)
		{
			if (isWaterTexture(tex[f]))
			{
				continue;
			}
			for (int v : new int[]{fa[f], fb[f], fc[f]})
			{
				int ox = vx[v] - lx, oz = vz[v] - lz;
				if ((ox == 0 || ox == 128) && (oz == 0 || oz == 128))
				{
					land[(y + (oz >> 7)) * CORNERS + x + (ox >> 7)] = true;
				}
			}
		}
	}

	/**
	 * Depth level of every corner: 0 on land and at the shore, then one more per tile (4-connected) away from the
	 * nearest land corner, up to {@code max}. Off the grid is not land, so the sea deepens toward the scene edge.
	 */
	static void depthLevels(boolean[] land, int n, int max, byte[] out)
	{
		int[] queue = new int[n * n];
		int tail = 0;
		java.util.Arrays.fill(out, (byte) -1);
		for (int i = 0; i < land.length; ++i)
		{
			if (land[i])
			{
				out[i] = 0;
				queue[tail++] = i;
			}
		}
		int head = 0;
		while (head < tail)
		{
			int i = queue[head++];
			int d = out[i] + 1;
			if (d > max)
			{
				continue;
			}
			int x = i % n, y = i / n;
			if (x > 0 && out[i - 1] < 0)
			{
				out[i - 1] = (byte) d;
				queue[tail++] = i - 1;
			}
			if (x < n - 1 && out[i + 1] < 0)
			{
				out[i + 1] = (byte) d;
				queue[tail++] = i + 1;
			}
			if (y > 0 && out[i - n] < 0)
			{
				out[i - n] = (byte) d;
				queue[tail++] = i - n;
			}
			if (y < n - 1 && out[i + n] < 0)
			{
				out[i + n] = (byte) d;
				queue[tail++] = i + n;
			}
		}
		for (int i = 0; i < out.length; ++i)
		{
			if (out[i] < 0)
			{
				out[i] = (byte) max;
			}
		}
	}

	static int depthForLevel(int level)
	{
		return level <= 0 ? 0 : DEPTH[Math.min(level, DEPTH.length) - 1];
	}

	private static int finalType(int kind, int murk, int bodyClass)
	{
		if (kind != FRESH)
		{
			return kind;
		}
		if (murk == MURKY_CAVE)
		{
			return CAVE;
		}
		if (murk == MURKY_SWAMP)
		{
			return SWAMP;
		}
		return bodyClass == WaterBodies.OCEAN ? OCEAN : FRESH;
	}

	/**
	 * The color each underlay is drawn with on the land around, learned from the plain ground tiles there (vanilla's
	 * baked lighting undone where the land's is): a water tile is an overlay on an underlay, which is the ground under the water.
	 */
	private static final class UnderlayColors
	{
		private final short[][][] ids;
		// underlay ids are a few hundred at most
		private static final int IDS = 1 << 12;
		private final int[] h = new int[IDS], s = new int[IDS], l = new int[IDS], n = new int[IDS];

		UnderlayColors(Scene scene)
		{
			ids = scene.getUnderlayIds();
		}

		short id(int plane, int x, int y)
		{
			return ids == null ? 0 : ids[plane][x][y];
		}

		void add(Tile t, int plane, int x, int y, int[][] heights)
		{
			int id = id(plane, x, y) & 0xffff;
			SceneTilePaint paint = t == null ? null : t.getSceneTilePaint();
			if (id == 0 || id >= IDS || paint == null || paint.getTexture() != -1 || paint.getNeColor() == 12345678)
			{
				return;
			}
			float dhdx = (heights[x + 1][y] + heights[x + 1][y + 1] - heights[x][y] - heights[x][y + 1]) / 256f;
			float dhdz = (heights[x][y + 1] + heights[x + 1][y + 1] - heights[x][y] - heights[x + 1][y]) / 256f;
			for (int c : new int[]{paint.getSwColor(), paint.getSeColor(), paint.getNeColor(), paint.getNwColor()})
			{
				// as the land around is drawn: vanilla's lighting undone only with ground blending (SceneUploader)
				if (VertexShading.undoVanillaShading && GroundBlender.enabled)
				{
					c = GroundBlender.undoShading(c, dhdx, dhdz);
				}
				h[id] += c >> 10 & 63;
				s[id] += c >> 7 & 7;
				l[id] += c & 127;
				n[id]++;
			}
		}

		/** The underlay's mean color, packed HSL, or -1 where none of its ground was seen. */
		int color(short id)
		{
			int i = id & 0xffff, c;
			if (i == 0 || i >= IDS || (c = n[i]) == 0)
			{
				return -1;
			}
			return (h[i] + c / 2) / c << 10 | (s[i] + c / 2) / c << 7 | (l[i] + c / 2) / c;
		}
	}

	// bed colors, packed HSL: sand under clear water, mud in swamps, dark stone in caves
	private static final int SAND = 8 << 10 | 2 << 7 | 40;
	private static final int PALE_SAND = 8 << 10 | 1 << 7 | 46;
	private static final int MUD = 9 << 10 | 3 << 7 | 16;
	private static final int STONE = 6 << 10 | 1 << 7 | 22;

	private static int bedColor(int kind, int murk)
	{
		if (murk == MURKY_CAVE)
		{
			return STONE;
		}
		if (murk == MURKY_SWAMP || kind == SWAMP)
		{
			return MUD;
		}
		return kind == OCEAN_PALE ? PALE_SAND : SAND;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Upload queries, extended tile/corner coordinates

	/**
	 * Whether a terrain tile at (ex, ey) on render level {@code lvl} with texture {@code texture} is uploaded as
	 * bed. Water elsewhere (stacked on another plane) keeps its vanilla look.
	 */
	boolean isBed(int ex, int ey, int lvl, int texture)
	{
		if (ex < 0 || ey < 0 || ex >= SIZE || ey >= SIZE || !isWaterTexture(texture))
		{
			return false;
		}
		return level[ey * SIZE + ex] == lvl;
	}

	boolean hasBed(int ex, int ey, int lvl)
	{
		return ex >= 0 && ey >= 0 && ex < SIZE && ey < SIZE && level[ey * SIZE + ex] == lvl;
	}

	/**
	 * How far the bed sinks at corner (cx, cy), world units (y down, so added to the height).
	 */
	int bedOffset(int cx, int cy)
	{
		if (cx < 0 || cy < 0 || cx >= CORNERS || cy >= CORNERS)
		{
			return 0;
		}
		return depthForLevel(depthLevel[cy * CORNERS + cx]);
	}

	/**
	 * Bed offset inside tile (ex, ey) at tile-local position (lx, lz) in 0-128, bilinear between its corners.
	 */
	int bedOffset(int ex, int ey, int lx, int lz)
	{
		float fx = lx / 128f, fz = lz / 128f;
		float s = bedOffset(ex, ey) + (bedOffset(ex + 1, ey) - bedOffset(ex, ey)) * fx;
		float n = bedOffset(ex, ey + 1) + (bedOffset(ex + 1, ey + 1) - bedOffset(ex, ey + 1)) * fx;
		return Math.round(s + (n - s) * fz);
	}

	/**
	 * Whether bed tile (ex, ey) touches the shore at a corner, so its corners can't hold it deep enough and it is
	 * uploaded with a sunken middle.
	 */
	boolean isShoreTile(int ex, int ey)
	{
		return depthLevel(ex, ey) == 0 || depthLevel(ex + 1, ey) == 0 || depthLevel(ex, ey + 1) == 0
			|| depthLevel(ex + 1, ey + 1) == 0;
	}

	/**
	 * How far the middle of bed tile (ex, ey) sinks: as deep as its corners on average, and at least
	 * {@link #MIN_DEPTH}.
	 */
	int centerOffset(int ex, int ey)
	{
		int avg = bedOffset(ex, ey) + bedOffset(ex + 1, ey) + bedOffset(ex, ey + 1) + bedOffset(ex + 1, ey + 1) + 2 >> 2;
		return Math.max(avg, MIN_DEPTH);
	}

	/**
	 * The heights of render level {@code lvl} with the bed lowered.
	 */
	int[][] bedHeights(int lvl)
	{
		return bedHeights[lvl];
	}

	// the bed's lightness lost per depth level
	static final int DEPTH_DARKEN = 2;

	/**
	 * The bed's own color at corner (cx, cy), extended coordinates, with depth level {@code depth}: the mean of the
	 * water tiles' around it, so neighboring tiles of different ground agree on it; a little darker deeper down and a
	 * little uneven.
	 */
	int bedColor(int cx, int cy, int depth)
	{
		int hue = 0, sat = 0, light = 0, n = 0, first = -1;
		for (int y = cy - 1; y <= cy; ++y)
		{
			for (int x = cx - 1; x <= cx; ++x)
			{
				int i = clampTile(y) * SIZE + clampTile(x);
				if (level[i] < 0)
				{
					continue;
				}
				int c = bedHsl[i];
				// hues averaged around the first one, so they don't wrap
				int hc = c >> 10 & 63;
				if (first < 0)
				{
					first = hc;
				}
				hue += first + ((hc - first + 32 & 63) - 32);
				sat += c >> 7 & 7;
				light += c & 127;
				++n;
			}
		}
		if (n == 0)
		{
			int c = bedHsl[clampTile(cy) * SIZE + clampTile(cx)];
			hue = c >> 10 & 63;
			sat = c >> 7 & 7;
			light = c & 127;
			n = 1;
		}
		// stable across scene loads: hash the position on the real map
		long gx = (long) (baseX + cx) * 128, gz = (long) (baseY + cy) * 128;
		long h = gx * 0x9E3779B97F4A7C15L ^ gz * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		int jitter = (int) (h >>> 60) - 8; // -8..7
		int l = Math.round((float) light / n) + jitter / 2 - depth * DEPTH_DARKEN;
		return (Math.round((float) hue / n) & 63) << 10 | Math.round((float) sat / n) << 7 | Math.max(4, Math.min(100, l));
	}

	private int cornerLevel(int cx, int cy)
	{
		// the bed sinks only where every tile around the corner is water, on that water's level
		int l = -1;
		for (int dx = -1; dx <= 0; ++dx)
		{
			for (int dy = -1; dy <= 0; ++dy)
			{
				int x = cx + dx, y = cy + dy;
				if (x >= 0 && y >= 0 && x < SIZE && y < SIZE && level[y * SIZE + x] >= 0)
				{
					l = level[y * SIZE + x];
				}
			}
		}
		return l;
	}

	int depthLevel(int cx, int cy)
	{
		if (cx < 0 || cy < 0 || cx >= CORNERS || cy >= CORNERS)
		{
			return 0;
		}
		return depthLevel[cy * CORNERS + cx];
	}

	private static int clampTile(int v)
	{
		return Math.max(0, Math.min(SIZE - 1, v));
	}

	// ---------------------------------------------------------------------------------------------------------

	private void buildTexture(int[][][] heights)
	{
		WaterBodies b = bodies;
		int w = CORNERS * 2;
		float[] t = new float[w * CORNERS * 4];
		for (int cy = 0; cy < CORNERS; ++cy)
		{
			for (int cx = 0; cx < CORNERS; ++cx)
			{
				// over the water tiles touching this corner
				float height = 0, fx = 0, fz = 0, shore = 0, energy = 0, turbulence = 0;
				// the map's flow of the tiles here and how much the scene sped it up, for the corner's own map flow
				float mx = 0, mz = 0, gain = 0;
				int mapped = 0;
				int lowest = 0;
				int count = 0;
				for (int dx = -1; dx <= 0; ++dx)
				{
					for (int dy = -1; dy <= 0; ++dy)
					{
						int x = cx + dx, y = cy + dy;
						if (x < 0 || y < 0 || x >= SIZE || y >= SIZE)
						{
							continue;
						}
						int i = y * SIZE + x;
						if (!b.water[i])
						{
							continue;
						}
						// the tile's lowest corner (y down: the largest): vanilla water tiles at a bank climb it with
						// their outer corners, so the corner heights there are the bank, not the water level, and objects
						// near the shore (piers, posts) would count as under water
						int[][] h = heights[level[i]];
						int low = Math.max(Math.max(h[x][y], h[x + 1][y]), Math.max(h[x][y + 1], h[x + 1][y + 1]));
						// the lowest of the touching tiles' levels, not their average: where water steps down (falls,
						// rapids) the surface must not ramp up over the rocks between the two levels
						lowest = count == 0 ? low : Math.max(lowest, low);
						fx += b.flowX[i];
						fz += b.flowZ[i];
						if (b.hasMapFlow(i))
						{
							mx += b.mapFlowX[i];
							mz += b.mapFlowZ[i];
							gain += b.gain[i];
							++mapped;
						}
						shore += b.shore[i];
						energy += b.energy[i];
						turbulence += b.turbulence[i];
						++count;
					}
				}
				if (count > 0)
				{
					height = lowest;
					fx /= count;
					fz /= count;
					int c = cy * CORNERS + cx;
					if (mapped == count && !Float.isNaN(cornerFlowX[c]))
					{
						// the map's detail between its tiles: what the corner's own samples say beyond their tiles' mean
						fx += (cornerFlowX[c] - mx / count) * gain / count;
						fz += (cornerFlowZ[c] - mz / count) * gain / count;
					}
					shore /= count;
					energy /= count;
					turbulence /= count;
				}
				else
				{
					// filled in below
					height = Float.NaN;
				}
				int tile = cx < SIZE && cy < SIZE ? type[cy * SIZE + cx] : 0;

				int p0 = (cy * w + cx) * 4;
				t[p0] = height;
				t[p0 + 1] = fx;
				t[p0 + 2] = fz;
				t[p0 + 3] = tile;
				int p1 = (cy * w + CORNERS + cx) * 4;
				t[p1] = Math.min(shore, 32);
				t[p1 + 1] = energy;
				t[p1 + 2] = count / 4f;
				t[p1 + 3] = turbulence;
			}
		}
		spreadLevel(t, w);
		texture = t;
	}

	/**
	 * Gives every land corner the surface height of the nearest water corner (NaN marks land in page 0's height).
	 * The shader filters the heights between corners and follows the view ray across cells to find the surface: a
	 * land corner carrying the land's height would ramp the surface up the bank near every shore, and the crossing
	 * would jump between the ramp and the water as the view moves.
	 */
	static void spreadLevel(float[] t, int w)
	{
		int[] queue = new int[CORNERS * CORNERS];
		int head = 0, tail = 0;
		for (int i = 0; i < CORNERS * CORNERS; ++i)
		{
			int cx = i % CORNERS, cy = i / CORNERS;
			if (!Float.isNaN(t[(cy * w + cx) * 4]))
			{
				queue[tail++] = i;
			}
		}
		if (tail == 0)
		{
			for (int cy = 0; cy < CORNERS; ++cy)
			{
				for (int cx = 0; cx < CORNERS; ++cx)
				{
					t[(cy * w + cx) * 4] = 0;
				}
			}
			return;
		}
		while (head < tail)
		{
			int i = queue[head++];
			int cx = i % CORNERS, cy = i / CORNERS;
			float h = t[(cy * w + cx) * 4];
			for (int d = 0; d < 4; ++d)
			{
				int nx = cx + (d == 0 ? 1 : d == 1 ? -1 : 0), ny = cy + (d == 2 ? 1 : d == 3 ? -1 : 0);
				if (nx < 0 || ny < 0 || nx >= CORNERS || ny >= CORNERS)
				{
					continue;
				}
				int p = (ny * w + nx) * 4;
				if (Float.isNaN(t[p]))
				{
					t[p] = h;
					queue[tail++] = ny * CORNERS + nx;
				}
			}
		}
	}
}
