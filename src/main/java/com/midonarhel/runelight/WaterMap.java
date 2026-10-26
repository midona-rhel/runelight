package com.midonarhel.runelight;

import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;

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
	// heightRange's bound when the heights aren't known, far past any the game has
	static final int HEIGHT_UNKNOWN = 1 << 20;
	static final int CORNERS = SIZE + 1;
	// pages of the data texture, side by side
	static final int PAGES = 3;
	// farthest the shore distance field is kept (tiles); the waves are all within it
	static final float SHORE_FIELD_REACH = 32f;

	// water types, indices into the palette in water.glsl
	static final int NONE = 0;
	static final int FRESH = 1;
	static final int OCEAN = 2;
	static final int OCEAN_DARK = 3;
	static final int OCEAN_PALE = 4;
	static final int SWAMP = 5;
	static final int CAVE = 6;

	// vertex flags in the texture id short of terrain (above SurfaceMaterials' bits): the frag shader writes them
	// to the material target so the composite knows bed (always under water) from land (never)
	static final int BED_FLAG = 1 << SurfaceMaterials.SHIFT + 3;
	static final int LAND_FLAG = 1 << SurfaceMaterials.SHIFT + 4;

	// 117 HD's bed depth per tile from shore (ProceduralGenerator.DEPTH_LEVEL_SLOPE * 0.55), cut off early so the
	// depth never depends on tiles more than a zone away
	static final int MAX_LEVEL = 8;
	private static final int[] DEPTH = {82, 165, 258, 335, 385, 412, 451, 506};
	// at least this deep in the middle of a water tile, so a stream between two banks still holds water
	static final int MIN_DEPTH = 56;

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
	// the scene's own terrain heights
	private int[][][] tileHeights;
	final WaterBodies bodies = new WaterBodies(SIZE);
	private int baseX, baseY;

	// RGBA float texture, three pages of CORNERS x CORNERS side by side, row by row:
	// page 0 (surface height, on land the nearest water's; flow x, flow z in tiles/s; type of the tile whose sw corner
	// this is),
	// page 1 (distance to shore in tiles, wave energy 0-1, share of the corner's tiles that are water, turbulence 0-1),
	// page 2 (straight line distance to the nearest land corner in tiles, share of the corner's water tiles that are
	// open sea 0-1: the shore waves' field, see WaterBodies.ocean; the fall frame x, z)
	float[] texture;
	boolean hasWater;

	// Things standing in the water (posts, rocks, hulls): where they cross the surface, on a grid of CONTACT_RES cells
	// a tile, spread out over CONTACT_REACH cells as a closeness 0-255 (255 where one crosses); null with none. The
	// water foams and roughens around them (water_frag.glsl), and the flow slows and churns at corners near them.
	static final int CONTACT_RES = 4;
	static final int CONTACT_SIZE = SIZE * CONTACT_RES;
	static final int CONTACT_REACH = 3;
	// world units (one a few millimetres) above the surface that a model may end and still count as in the water
	private static final float CONTACT_TOLERANCE = 2f;
	// how far above the surface (world units) the foot of a model may hover and still stand in it: on a bank tile the
	// corners climb, and the model sits at their mean
	private static final float CONTACT_HOVER = 48f;
	// at a corner right by one the flow keeps 1 - CONTACT_SLOW of itself and churns at least CONTACT_TURB (rougher
	// waves, short of the rapids' foam)
	private static final float CONTACT_SLOW = 0.6f;
	private static final float CONTACT_TURB = 0.25f;
	byte[] contact;

	// Waterfalls: the water above a fall flows toward its lip and churns there, so the rapids' foam drifts to the edge
	// and gathers along it, to pour over. Per corner, within LIP_REACH tiles of a lip: the unit direction to it times
	// 1 at the lip and 0 at the reach (x, z), and the lip's height (y down). A corner's water counts only at the lip's
	// own level (LIP_LEVEL world units), not the pool the fall lands in.
	private static final float LIP_REACH = 2.5f;
	private static final float LIP_LEVEL = 48f;
	// tiles a second of flow toward the lip at the lip, and the turbulence there
	private static final float LIP_FLOW = 1.2f;
	private static final float LIP_CHURN = 2f;
	// a steep face of fake water is a fall's: its normal's vertical part below this
	private static final float LIP_STEEP = 0.5f;
	private final float[] lipPullX = new float[CORNERS * CORNERS];
	private final float[] lipPullZ = new float[CORNERS * CORNERS];
	private final float[] lipLevel = new float[CORNERS * CORNERS];
	private final float[] lipStrength = new float[CORNERS * CORNERS];
	// where it lands: the foot of a fall is a contact source reaching FOOT_REACH contact cells, and the pool's water is
	// pushed out from it, FOOT_FLOW tiles a second at the foot, as far as FOOT_PUSH tiles; the same fields as the lip's
	private static final int FOOT_REACH = 10;
	private static final float FOOT_PUSH = 3f;
	private static final float FOOT_FLOW = 0.8f;
	private final float[] footPullX = new float[CORNERS * CORNERS];
	private final float[] footPullZ = new float[CORNERS * CORNERS];
	private final float[] footLevel = new float[CORNERS * CORNERS];
	private final float[] footStrength = new float[CORNERS * CORNERS];

	// Falls of the terrain itself: a water tile whose corners drop FALL_DROP world units or more across its run, with
	// water above and below it, is a fall's (a bank's tile climbs too, but has land on one side). Its surface is the slope,
	// not the level of the pool below; the water on it runs downhill FALL_FLOW tiles a second; its lip and foot are a
	// fall's (lips, feet); and the foam is laid out along the slope (fallFrame).
	private static final float FALL_DROP = 128f;
	private static final float FALL_FLOW = 2.5f;
	// extended tile steps of a fall's four directions
	private static final int[] FALL_DX = {1, -1, 0, 0}, FALL_DZ = {0, 0, 1, -1};
	// the frame reaches this many tiles above a fall's lip, below its foot and out to its sides: as far as the lip's and
	// the foot's contact (CONTACT_REACH, FOOT_REACH) spreads
	private static final int FRAME_UP = 3, FRAME_DOWN = 4, FRAME_SIDE = 4;
	// the contact on a fall's face, per cell: all of it at the lip and the foot, FALL_THIN less in the middle, so the foam is
	// dense where the water goes over and where it lands
	private static final float FALL_THIN = 0.4f;
	private byte[] fallContact;
	// per tile: a fall's
	final boolean[] fall = new boolean[SIZE * SIZE];
	// per corner, in the fall's frame: the fall's direction (a unit vector in the scene's x, z) times 1 plus the length the
	// slope has added to the run by here, in tiles; so the distance along the surface from the lip is the distance along the
	// direction plus 128 times the vector's length less 1. Zero outside, where the foam is laid out flat.
	final float[] frameX = new float[CORNERS * CORNERS];
	final float[] frameZ = new float[CORNERS * CORNERS];

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

	static WaterMap build(Scene scene, WaterOverrides overrides)
	{
		return build(scene, overrides, WaterFlow.world());
	}

	static WaterMap build(Scene scene, WaterOverrides overrides, WaterFlow flowMap)
	{
		WaterMap m = new WaterMap();
		m.load(scene, overrides, flowMap);
		return m;
	}

	private void load(Scene scene, WaterOverrides overrides, WaterFlow flowMap)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		int[][][] heights = scene.getTileHeights();
		tileHeights = heights;
		baseX = scene.getBaseX() - RuneLightPlugin.SCENE_OFFSET;
		baseY = scene.getBaseY() - RuneLightPlugin.SCENE_OFFSET;

		java.util.Arrays.fill(level, (byte) -1);
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
				// its type as 117 HD gives it, tile by tile: by its tile override where one gives water, else by its
				// texture
				int over = overrides == null || !EnvironmentManager.toWorld(scene, x - RuneLightPlugin.SCENE_OFFSET,
					y - RuneLightPlugin.SCENE_OFFSET, lvl, world) ? NONE
					: overrides.tileType(world[0], world[1], world[2], scene.getOverlayIds()[lvl][x][y],
					scene.getUnderlayIds()[lvl][x][y]);
				type[i] = (byte) (over != NONE ? over : k);
				bedHsl[i] = (short) bedColor(type[i]);
				b.water[i] = true;
				b.sea[i] = isSea(water);
				int[][] h = heights[lvl];
				b.surface[i] = (h[x][y] + h[x + 1][y] + h[x][y + 1] + h[x + 1][y + 1]) * 0.25f;
				if (flowMap != null && mapFlow(scene, x, y, plane, flowMap, world, flow))
				{
					b.mapFlowX[i] = flow[0];
					b.mapFlowZ[i] = flow[1];
				}
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

		// the lips and feet of the waterfalls, segments {x0, z0, x1, z1, y}
		java.util.List<float[]> lips = new java.util.ArrayList<>(), feet = new java.util.ArrayList<>();
		eachModel(tiles, (id, r, orient, x, height, z) -> fallEdgesOf(id, r, orient, x, height, z, lips, feet));
		terrainFalls(heights, lips, feet);
		pullToLines(lips, LIP_REACH, lipPullX, lipPullZ, lipLevel, lipStrength);
		pullToLines(feet, FOOT_PUSH, footPullX, footPullZ, footLevel, footStrength);
		findContacts(tiles, heights, lips, feet);
		buildTexture(heights, landCorner);
	}

	/**
	 * Write what the water is drawn from, for the scene dump: the tiles' type, game texture and level, then the data
	 * texture and the contact map (none, as a zero length, without).
	 */
	void write(java.io.DataOutputStream out) throws java.io.IOException
	{
		out.write(type);
		for (short t : waterTexture)
		{
			out.writeShort(t);
		}
		out.write(level);
		out.writeInt(texture.length);
		for (float f : texture)
		{
			out.writeFloat(f);
		}
		out.writeInt(contact != null ? contact.length : 0);
		if (contact != null)
		{
			out.write(contact);
		}
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

	// bed colors, packed HSL: sand under clear water, mud in swamps, dark stone in caves
	private static final int SAND = 8 << 10 | 2 << 7 | 40;
	private static final int PALE_SAND = 8 << 10 | 1 << 7 | 46;
	private static final int MUD = 9 << 10 | 3 << 7 | 16;
	private static final int STONE = 6 << 10 | 1 << 7 | 22;

	private static int bedColor(int type)
	{
		if (type == CAVE)
		{
			return STONE;
		}
		if (type == SWAMP)
		{
			return MUD;
		}
		return type == OCEAN_PALE ? PALE_SAND : SAND;
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
	private static final int DEPTH_DARKEN = 2;

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

	/**
	 * Find where the scene's objects cross the water's surface into {@link #contact}.
	 */
	private void findContacts(Tile[][][] tiles, int[][][] heights, java.util.List<float[]> lips, java.util.List<float[]> feet)
	{
		WaterBodies b = bodies;
		// each water tile's surface: its lowest corner (y down), as buildTexture takes it
		float[] surfaceY = new float[SIZE * SIZE];
		java.util.Arrays.fill(surfaceY, Float.NaN);
		for (int i = 0; i < surfaceY.length; ++i)
		{
			if (b.water[i])
			{
				int x = i % SIZE, y = i / SIZE;
				int[][] h = heights[level[i]];
				surfaceY[i] = Math.max(Math.max(h[x][y], h[x + 1][y]), Math.max(h[x][y + 1], h[x + 1][y + 1]));
			}
		}
		boolean[] marked = new boolean[CONTACT_SIZE * CONTACT_SIZE];
		boolean any = eachModel(tiles, (id, r, orient, x, height, z) -> crossings(id, r, orient, x, height, z, surfaceY, marked));
		if (!any && lips.isEmpty() && feet.isEmpty())
		{
			return;
		}
		// each crossing raises the closeness of the cells within CONTACT_REACH of it
		byte[] c = new byte[marked.length];
		spread(marked, CONTACT_REACH, c);
		// the lips and feet of the waterfalls count only on the water at their own level: the upper pool by the lip, the
		// lower one by the foot
		for (float[] l : lips)
		{
			spreadLine(l, CONTACT_REACH, surfaceY, c);
		}
		for (float[] f : feet)
		{
			spreadLine(f, FOOT_REACH, surfaceY, c);
		}
		// the foam on a fall's face
		for (int i = 0; fallContact != null && i < fallContact.length; ++i)
		{
			c[i] = (byte) Math.max(c[i] & 255, fallContact[i] & 255);
		}
		contact = c;
	}

	/**
	 * Raise the closeness in c of the cells within reach cells of every marked one.
	 */
	private static void spread(boolean[] marked, int reach, byte[] c)
	{
		for (int cy = 0; cy < CONTACT_SIZE; ++cy)
		{
			for (int cx = 0; cx < CONTACT_SIZE; ++cx)
			{
				if (marked[cy * CONTACT_SIZE + cx])
				{
					spreadCell(cx, cy, reach, c, null, 0);
				}
			}
		}
	}

	/**
	 * Raise the closeness in c around cell (cx, cy), within reach cells; with a surfaceY (per tile, NaN where none) only
	 * the cells over water within LIP_LEVEL of {@code level} of it.
	 */
	private static void spreadCell(int cx, int cy, int reach, byte[] c, float[] surfaceY, float level)
	{
		for (int dy = -reach; dy <= reach; ++dy)
		{
			for (int dx = -reach; dx <= reach; ++dx)
			{
				int x = cx + dx, y = cy + dy;
				float near = 1 - (float) Math.sqrt(dx * dx + dy * dy) / reach;
				if (near <= 0 || x < 0 || y < 0 || x >= CONTACT_SIZE || y >= CONTACT_SIZE)
				{
					continue;
				}
				if (surfaceY != null)
				{
					float s = surfaceY[y / CONTACT_RES * SIZE + x / CONTACT_RES];
					if (!(Math.abs(s - level) <= LIP_LEVEL))
					{
						continue;
					}
				}
				int j = y * CONTACT_SIZE + x;
				c[j] = (byte) Math.max(c[j] & 255, Math.round(near * 255));
			}
		}
	}

	/**
	 * Raise the closeness in c around a segment {x0, z0, x1, z1, y} (world units) of a waterfall's lip or foot, on the
	 * water at its level (surfaceY, per tile) only.
	 */
	static void spreadLine(float[] l, int reach, float[] surfaceY, byte[] c)
	{
		float cell = 128f / CONTACT_RES;
		int steps = 1 + (int) (Math.hypot(l[2] - l[0], l[3] - l[1]) / (cell * 0.5f));
		for (int i = 0; i <= steps; ++i)
		{
			float t = (float) i / steps;
			int gx = (int) ((l[0] + (l[2] - l[0]) * t) / cell);
			int gz = (int) ((l[1] + (l[3] - l[1]) * t) / cell);
			if (gx >= 0 && gz >= 0 && gx < CONTACT_SIZE && gz < CONTACT_SIZE)
			{
				spreadCell(gx, gz, reach, c, surfaceY, l[4]);
			}
		}
	}

	/**
	 * The lips and feet of one model's fall faces: segments {x0, z0, x1, z1, y} of the edges of its steep fake water faces
	 * that lie at the top of its water, and at the bottom of it (scene extended world units, y down).
	 */
	private static boolean fallEdgesOf(int id, Renderable r, int orient, int x, int height, int z, java.util.List<float[]> lips,
		java.util.List<float[]> feet)
	{
		Model m = r instanceof Model ? (Model) r
			: r instanceof DynamicObject ? ((DynamicObject) r).getModelZbuf() : null;
		if (m == null)
		{
			return false;
		}
		int material = SurfaceMaterials.forObject(id, r);
		float[] vx = m.getVerticesX(), vy = m.getVerticesY(), vz = m.getVerticesZ();
		int[] f1 = m.getFaceIndices1(), f2 = m.getFaceIndices2(), f3 = m.getFaceIndices3();
		int[] colors1 = m.getFaceColors1(), colors3 = m.getFaceColors3();
		short[] textures = m.getFaceTextures();
		byte[] transparencies = m.getFaceTransparencies();
		float sin = orient != 0 ? Perspective.SINE[orient] / 65536f : 0;
		float cos = orient != 0 ? Perspective.COSINE[orient] / 65536f : 1;
		float ox = x + (RuneLightPlugin.SCENE_OFFSET << 7), oz = z + (RuneLightPlugin.SCENE_OFFSET << 7);
		boolean[] fake = new boolean[m.getFaceCount()];
		float top = Float.POSITIVE_INFINITY, foot = Float.NEGATIVE_INFINITY;
		for (int f = 0; f < fake.length; ++f)
		{
			if (colors3[f] == -2)
			{
				continue;
			}
			int texture = textures != null ? textures[f] : -1;
			if (SurfaceMaterials.isFake(SurfaceMaterials.face(material, texture, colors1[f] & 0xffff,
				SurfaceMaterials.opacity(transparencies, f))))
			{
				fake[f] = true;
				top = Math.min(top, Math.min(vy[f1[f]], Math.min(vy[f2[f]], vy[f3[f]])) + height);
				foot = Math.max(foot, Math.max(vy[f1[f]], Math.max(vy[f2[f]], vy[f3[f]])) + height);
			}
		}
		boolean any = false;
		float[] px = new float[3], py = new float[3], pz = new float[3];
		for (int f = 0; f < fake.length; ++f)
		{
			if (!fake[f])
			{
				continue;
			}
			int[] v = {f1[f], f2[f], f3[f]};
			for (int k = 0; k < 3; ++k)
			{
				px[k] = vz[v[k]] * sin + vx[v[k]] * cos + ox;
				pz[k] = vz[v[k]] * cos - vx[v[k]] * sin + oz;
				py[k] = vy[v[k]] + height;
			}
			float ax = px[1] - px[0], ay = py[1] - py[0], az = pz[1] - pz[0];
			float bx = px[2] - px[0], by = py[2] - py[0], bz = pz[2] - pz[0];
			float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1e-3f || Math.abs(ny) / len >= LIP_STEEP)
			{
				continue;
			}
			for (int k = 0; k < 3; ++k)
			{
				int k2 = (k + 1) % 3;
				if (py[k] <= top + 4 && py[k2] <= top + 4)
				{
					lips.add(new float[]{px[k], pz[k], px[k2], pz[k2], top});
					any = true;
				}
				else if (py[k] >= foot - 4 && py[k2] >= foot - 4)
				{
					feet.add(new float[]{px[k], pz[k], px[k2], pz[k2], foot});
					any = true;
				}
			}
		}
		return any;
	}

	/**
	 * Find the falls of the terrain (fall, the frame) and add their lips and feet. Fall tiles one after another count as one
	 * fall, lipped at the top of the first and footed at the bottom of the last.
	 */
	private void terrainFalls(int[][][] heights, java.util.List<float[]> lips, java.util.List<float[]> feet)
	{
		byte[] dir = new byte[SIZE * SIZE];
		java.util.Arrays.fill(dir, (byte) -1);
		// the slope runs along one axis and is level across it
		float across = FALL_DROP / 4;
		for (int y = 0; y < SIZE; ++y)
		{
			for (int x = 0; x < SIZE; ++x)
			{
				if (!bodies.water[y * SIZE + x])
				{
					continue;
				}
				int[][] h = heights[level[y * SIZE + x]];
				float a = h[x][y], b = h[x + 1][y], c = h[x][y + 1], d = h[x + 1][y + 1];
				float dropX = (b + d - a - c) / 2, dropZ = (c + d - a - b) / 2;
				if (Math.abs(dropX) >= FALL_DROP && Math.abs(a - c) <= across && Math.abs(b - d) <= across)
				{
					dir[y * SIZE + x] = (byte) (dropX > 0 ? 0 : 1);
				}
				else if (Math.abs(dropZ) >= FALL_DROP && Math.abs(a - b) <= across && Math.abs(c - d) <= across)
				{
					dir[y * SIZE + x] = (byte) (dropZ > 0 ? 2 : 3);
				}
			}
		}
		for (int y = 0; y < SIZE; ++y)
		{
			for (int x = 0; x < SIZE; ++x)
			{
				int k = dir[y * SIZE + x];
				if (k < 0 || dirAt(dir, x - FALL_DX[k], y - FALL_DZ[k]) == k)
				{
					continue;
				}
				// the first tile of a fall
				int n = 1;
				while (dirAt(dir, x + n * FALL_DX[k], y + n * FALL_DZ[k]) == k)
				{
					++n;
				}
				int lastX = x + (n - 1) * FALL_DX[k], lastY = y + (n - 1) * FALL_DZ[k];
				if (waterAt(heights, x - FALL_DX[k], y - FALL_DZ[k], edgeHeight(heights, x, y, k))
					&& waterAt(heights, lastX + FALL_DX[k], lastY + FALL_DZ[k], edgeHeight(heights, lastX, lastY, k ^ 1)))
				{
					markFall(heights, x, y, k, n, lips, feet);
				}
			}
		}
	}

	private static int dirAt(byte[] dir, int x, int y)
	{
		return x >= 0 && y >= 0 && x < SIZE && y < SIZE ? dir[y * SIZE + x] : -1;
	}

	/**
	 * Whether extended tile (x, y) is water whose level (its lowest corner, y down) is within LIP_LEVEL of `level`.
	 */
	private boolean waterAt(int[][][] heights, int x, int y, float level)
	{
		if (x < 0 || y < 0 || x >= SIZE || y >= SIZE || !bodies.water[y * SIZE + x])
		{
			return false;
		}
		int[][] h = heights[this.level[y * SIZE + x]];
		return Math.abs(Math.max(Math.max(h[x][y], h[x + 1][y]), Math.max(h[x][y + 1], h[x + 1][y + 1])) - level) <= LIP_LEVEL;
	}

	/**
	 * The corners {x0, y0, x1, y1} of the edge of tile (x, y) that a fall running in direction k comes in over; its edge
	 * going out is the one coming in for the opposite direction (k ^ 1).
	 */
	private static int[] edge(int x, int y, int k)
	{
		switch (k)
		{
			case 0:
				return new int[]{x, y, x, y + 1};
			case 1:
				return new int[]{x + 1, y, x + 1, y + 1};
			case 2:
				return new int[]{x, y, x + 1, y};
			default:
				return new int[]{x, y + 1, x + 1, y + 1};
		}
	}

	private float edgeHeight(int[][][] heights, int x, int y, int k)
	{
		int[][] h = heights[level[y * SIZE + x]];
		int[] e = edge(x, y, k);
		return (h[e[0]][e[1]] + h[e[2]][e[3]]) / 2f;
	}

	/**
	 * Make the n tiles from (x, y) in direction k a fall: its tiles, lip and foot, and the frame of the foam laid out along
	 * its surface.
	 */
	private void markFall(int[][][] heights, int x, int y, int k, int n, java.util.List<float[]> lips, java.util.List<float[]> feet)
	{
		if (fallContact == null)
		{
			fallContact = new byte[CONTACT_SIZE * CONTACT_SIZE];
		}
		// how much longer than its run each tile's slope is, tiles: the foam is laid out along the slope
		float[] longer = new float[n + 1];
		for (int j = 0; j < n; ++j)
		{
			int tx = x + j * FALL_DX[k], ty = y + j * FALL_DZ[k];
			fall[ty * SIZE + tx] = true;
			for (int cy = 0; cy < CONTACT_RES; ++cy)
			{
				for (int cx = 0; cx < CONTACT_RES; ++cx)
				{
					int a = FALL_DX[k] != 0 ? (FALL_DX[k] > 0 ? cx : CONTACT_RES - 1 - cx) : (FALL_DZ[k] > 0 ? cy : CONTACT_RES - 1 - cy);
					float along = (j * CONTACT_RES + a + 0.5f) / (n * CONTACT_RES);
					fallContact[(ty * CONTACT_RES + cy) * CONTACT_SIZE + tx * CONTACT_RES + cx] =
						(byte) Math.round(255 * (1 - FALL_THIN * 4 * along * (1 - along)));
				}
			}
			float drop = edgeHeight(heights, tx, ty, k ^ 1) - edgeHeight(heights, tx, ty, k);
			longer[j + 1] = longer[j] + (float) Math.sqrt(1 + drop * drop / (128f * 128f)) - 1;
		}
		int lastX = x + (n - 1) * FALL_DX[k], lastY = y + (n - 1) * FALL_DZ[k];
		int[] up = edge(x, y, k), down = edge(lastX, lastY, k ^ 1);
		lips.add(new float[]{up[0] * 128f, up[1] * 128f, up[2] * 128f, up[3] * 128f, edgeHeight(heights, x, y, k)});
		feet.add(new float[]{down[0] * 128f, down[1] * 128f, down[2] * 128f, down[3] * 128f, edgeHeight(heights, lastX, lastY, k ^ 1)});
		// the frame: along the fall from its lip, and across it from the tile's sides
		boolean alongX = FALL_DX[k] != 0;
		int across = alongX ? y : x;
		for (int u = -FRAME_UP; u <= n + FRAME_DOWN; ++u)
		{
			float length = 1 + longer[Math.max(0, Math.min(n, u))];
			for (int v = across - FRAME_SIDE; v <= across + 1 + FRAME_SIDE; ++v)
			{
				int cx = alongX ? (FALL_DX[k] > 0 ? x + u : x + 1 - u) : v;
				int cy = alongX ? v : (FALL_DZ[k] > 0 ? y + u : y + 1 - u);
				if (cx < 0 || cy < 0 || cx >= CORNERS || cy >= CORNERS || frameX[cy * CORNERS + cx] != 0
					|| frameZ[cy * CORNERS + cx] != 0)
				{
					continue;
				}
				frameX[cy * CORNERS + cx] = FALL_DX[k] * length;
				frameZ[cy * CORNERS + cx] = FALL_DZ[k] * length;
			}
		}
	}

	/**
	 * Per corner of the CORNERS grid, the pull toward the nearest of the lines (segments as fallEdgesOf gives them): the
	 * unit direction there, scaled down to 0 at reach tiles, the lip's height and the strength itself (1 on the lip, 0 at
	 * the reach; a corner right on the lip has no direction). Corners with no lip in reach keep zeros.
	 */
	static void pullToLines(java.util.List<float[]> lips, float reach, float[] pullX, float[] pullZ, float[] level, float[] best)
	{
		java.util.Arrays.fill(pullX, 0);
		java.util.Arrays.fill(pullZ, 0);
		java.util.Arrays.fill(best, 0);
		for (float[] l : lips)
		{
			int x0 = Math.max(0, (int) Math.floor(Math.min(l[0], l[2]) / 128f - reach));
			int x1 = Math.min(CORNERS - 1, (int) Math.ceil(Math.max(l[0], l[2]) / 128f + reach));
			int z0 = Math.max(0, (int) Math.floor(Math.min(l[1], l[3]) / 128f - reach));
			int z1 = Math.min(CORNERS - 1, (int) Math.ceil(Math.max(l[1], l[3]) / 128f + reach));
			float dx = l[2] - l[0], dz = l[3] - l[1];
			float len2 = dx * dx + dz * dz;
			for (int cz = z0; cz <= z1; ++cz)
			{
				for (int cx = x0; cx <= x1; ++cx)
				{
					float qx = cx * 128f - l[0], qz = cz * 128f - l[1];
					float t = len2 > 0 ? Math.max(0, Math.min(1, (qx * dx + qz * dz) / len2)) : 0;
					float ex = dx * t - qx, ez = dz * t - qz;
					float d = (float) Math.sqrt(ex * ex + ez * ez) / 128f;
					float strength = 1 - d / reach;
					int c = cz * CORNERS + cx;
					if (strength <= best[c])
					{
						continue;
					}
					best[c] = strength;
					// right on the lip there is no direction to it, only the churn
					pullX[c] = d < 1e-3f ? 0 : ex / (d * 128f) * strength;
					pullZ[c] = d < 1e-3f ? 0 : ez / (d * 128f) * strength;
					level[c] = l[4];
				}
			}
		}
	}

	/**
	 * What to do with one model of the scene: its object id, renderable, orientation and scene local position; whether it
	 * found anything.
	 */
	private interface ModelVisit
	{
		boolean visit(int id, Renderable r, int orient, int x, int height, int z);
	}

	/**
	 * Visit the models of the objects on the tiles at or by water; whether any visit found something.
	 */
	private boolean eachModel(Tile[][][] tiles, ModelVisit visit)
	{
		boolean any = false;
		for (Tile[][] plane : tiles)
		{
			for (int x = 0; x < SIZE; ++x)
			{
				for (int y = 0; y < SIZE; ++y)
				{
					Tile t = plane[x][y];
					if (t == null || !nearWater(x, y))
					{
						continue;
					}
					// the bridge tile under a deck holds what stands in the water below it (piers)
					for (Tile u = t; u != null; u = u.getBridge())
					{
						any |= visitTile(u, visit);
					}
				}
			}
		}
		return any;
	}

	/**
	 * Visit the objects on one tile.
	 */
	private static boolean visitTile(Tile t, ModelVisit visit)
	{
		boolean any = false;
		GameObject[] objects = t.getGameObjects();
		for (int k = 0; objects != null && k < objects.length; ++k)
		{
			GameObject o = objects[k];
			if (o != null && o.getSceneMinLocation().equals(t.getSceneLocation()))
			{
				any |= visit.visit(o.getId(), o.getRenderable(), o.getModelOrientation(), o.getX(), o.getZ(), o.getY());
			}
		}
		GroundObject g = t.getGroundObject();
		if (g != null)
		{
			any |= visit.visit(g.getId(), g.getRenderable(), 0, g.getX(), g.getZ(), g.getY());
		}
		WallObject w = t.getWallObject();
		if (w != null)
		{
			any |= visit.visit(w.getId(), w.getRenderable1(), 0, w.getX(), w.getZ(), w.getY());
			any |= visit.visit(w.getId(), w.getRenderable2(), 0, w.getX(), w.getZ(), w.getY());
		}
		DecorativeObject d = t.getDecorativeObject();
		if (d != null)
		{
			any |= visit.visit(d.getId(), d.getRenderable(), 0, d.getX() + d.getXOffset(), d.getZ(), d.getY() + d.getYOffset());
			any |= visit.visit(d.getId(), d.getRenderable2(), 0, d.getX() + d.getXOffset2(), d.getZ(),
				d.getY() + d.getYOffset2());
		}
		return any;
	}

	/**
	 * Whether scene local (x, height, z) is over a water tile, within `tolerance` of the game's water level there (not,
	 * say, on a bridge over it).
	 */
	boolean atWaterLevel(int x, int height, int z, int tolerance)
	{
		int off = RuneLightPlugin.SCENE_OFFSET << 7;
		int ex = x + off, ez = z + off, tx = ex >> 7, tz = ez >> 7;
		if (tx < 0 || tz < 0 || tx >= SIZE || tz >= SIZE || level[tz * SIZE + tx] < 0)
		{
			return false;
		}
		int[][] h = tileHeights[level[tz * SIZE + tx]];
		float fx = (ex & 127) / 128f, fz = (ez & 127) / 128f;
		float s = (h[tx][tz] * (1 - fx) + h[tx + 1][tz] * fx) * (1 - fz) + (h[tx][tz + 1] * (1 - fx) + h[tx + 1][tz + 1] * fx) * fz;
		return Math.abs(height - s) <= tolerance;
	}

	/**
	 * The lowest and highest height (y, down) of the extended tiles [x0, x1) x [y0, y1): the water level's corners
	 * where a tile has water, the ground's on plane 0 elsewhere. {min, max}.
	 */
	int[] heightRange(int x0, int y0, int x1, int y1)
	{
		if (tileHeights == null)
		{
			// a map built without the scene's heights: any height
			return new int[]{-HEIGHT_UNKNOWN, HEIGHT_UNKNOWN};
		}
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
		for (int y = y0; y < y1; ++y)
		{
			for (int x = x0; x < x1; ++x)
			{
				int[][] h = tileHeights[Math.max(level[y * SIZE + x], 0)];
				for (int c = 0; c < 4; ++c)
				{
					int v = h[x + (c & 1)][y + (c >> 1)];
					min = Math.min(min, v);
					max = Math.max(max, v);
				}
			}
		}
		return new int[]{min, max};
	}

	/**
	 * The sunk bed's height (y down) at scene local (x, z) over a water tile (atWaterLevel).
	 */
	int bedHeight(int x, int z)
	{
		int off = RuneLightPlugin.SCENE_OFFSET << 7;
		int ex = x + off, ez = z + off, tx = ex >> 7, tz = ez >> 7;
		int[][] bed = bedHeights[level[tz * SIZE + tx]];
		float fx = (ex & 127) / 128f, fz = (ez & 127) / 128f;
		return Math.round((bed[tx][tz] * (1 - fx) + bed[tx + 1][tz] * fx) * (1 - fz)
			+ (bed[tx][tz + 1] * (1 - fx) + bed[tx + 1][tz + 1] * fx) * fz);
	}

	/**
	 * Whether extended tile (x, y) or one of its neighbours is water.
	 */
	boolean nearWater(int x, int y)
	{
		for (int dy = -1; dy <= 1; ++dy)
		{
			for (int dx = -1; dx <= 1; ++dx)
			{
				int tx = x + dx, ty = y + dy;
				if (tx >= 0 && ty >= 0 && tx < SIZE && ty < SIZE && bodies.water[ty * SIZE + tx])
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Mark where the faces of an object's model cross the water's surface: the model as the scene uploader places it
	 * (scene local x, height and z, turned by orient), its faces over water tiles cut at their surface level (or just
	 * above its foot where that hovers over it, CONTACT_HOVER at most). Plants swaying in the wind (reeds) are left out.
	 */
	private static boolean crossings(int id, Renderable r, int orient, int x, int height, int z, float[] surfaceY,
		boolean[] marked)
	{
		Model m = r instanceof Model ? (Model) r
			: r instanceof DynamicObject ? ((DynamicObject) r).getModelZbuf() : null;
		if (m == null || WindSway.sways(WindSway.forObject(id, r)))
		{
			return false;
		}
		float[] vx = m.getVerticesX(), vy = m.getVerticesY(), vz = m.getVerticesZ();
		int[] f1 = m.getFaceIndices1(), f2 = m.getFaceIndices2(), f3 = m.getFaceIndices3();
		int[] colors = m.getFaceColors3();
		float sin = orient != 0 ? Perspective.SINE[orient] / 65536f : 0;
		float cos = orient != 0 ? Perspective.COSINE[orient] / 65536f : 1;
		float ox = x + (RuneLightPlugin.SCENE_OFFSET << 7), oz = z + (RuneLightPlugin.SCENE_OFFSET << 7);
		float foot = Float.NEGATIVE_INFINITY;
		for (int v = 0; v < m.getVerticesCount(); ++v)
		{
			foot = Math.max(foot, vy[v] + height);
		}
		float[] px = new float[3], py = new float[3], pz = new float[3];
		float[] cx = new float[2], cz = new float[2];
		boolean any = false;
		for (int f = 0; f < m.getFaceCount(); ++f)
		{
			if (colors[f] == -2)
			{
				continue;
			}
			int[] v = {f1[f], f2[f], f3[f]};
			for (int k = 0; k < 3; ++k)
			{
				px[k] = vz[v[k]] * sin + vx[v[k]] * cos + ox;
				pz[k] = vz[v[k]] * cos - vx[v[k]] * sin + oz;
				py[k] = vy[v[k]] + height;
			}
			int tx = (int) ((px[0] + px[1] + px[2]) / 3) >> 7, ty = (int) ((pz[0] + pz[1] + pz[2]) / 3) >> 7;
			if (tx < 0 || ty < 0 || tx >= SIZE || ty >= SIZE || Float.isNaN(surfaceY[ty * SIZE + tx]))
			{
				continue;
			}
			// cut a hair above the surface (y down), so what ends right at it counts as standing in it
			float s = surfaceY[ty * SIZE + tx] - CONTACT_TOLERANCE;
			if (foot < s)
			{
				// a foot above the surface: standing in it if it hovers only a little, else clear of it
				if (s - foot > CONTACT_HOVER)
				{
					continue;
				}
				s = foot - CONTACT_TOLERANCE;
			}
			int n = 0;
			for (int k = 0; k < 3 && n < 2; ++k)
			{
				int k2 = (k + 1) % 3;
				float da = py[k] - s, db = py[k2] - s;
				if (da == db || da * db > 0)
				{
					continue;
				}
				float t = da / (da - db);
				cx[n] = px[k] + (px[k2] - px[k]) * t;
				cz[n] = pz[k] + (pz[k2] - pz[k]) * t;
				++n;
			}
			if (n == 0)
			{
				continue;
			}
			if (n == 1)
			{
				cx[1] = cx[0];
				cz[1] = cz[0];
			}
			// along the cut, half a cell at a time
			float cell = 128f / CONTACT_RES;
			int steps = 1 + (int) (Math.hypot(cx[1] - cx[0], cz[1] - cz[0]) / (cell * 0.5f));
			for (int i = 0; i <= steps; ++i)
			{
				float t = (float) i / steps;
				int gx = (int) ((cx[0] + (cx[1] - cx[0]) * t) / cell);
				int gz = (int) ((cz[0] + (cz[1] - cz[0]) * t) / cell);
				if (gx >= 0 && gz >= 0 && gx < CONTACT_SIZE && gz < CONTACT_SIZE)
				{
					marked[gz * CONTACT_SIZE + gx] = true;
					any = true;
				}
			}
		}
		return any;
	}

	private static int clampTile(int v)
	{
		return Math.max(0, Math.min(SIZE - 1, v));
	}

	// ---------------------------------------------------------------------------------------------------------

	private void buildTexture(int[][][] heights, boolean[] landCorner)
	{
		WaterBodies b = bodies;
		int w = CORNERS * PAGES;
		float[] t = new float[w * CORNERS * 4];
		float[] toLand = new float[CORNERS * CORNERS];
		distanceToLand(landCorner, CORNERS, SHORE_FIELD_REACH, toLand);
		for (int cy = 0; cy < CORNERS; ++cy)
		{
			for (int cx = 0; cx < CORNERS; ++cx)
			{
				// over the water tiles touching this corner
				float height = 0, fx = 0, fz = 0, shore = 0, energy = 0, turbulence = 0;
				int open = 0;
				// the map's flow of the tiles here and how much the scene sped it up, for the corner's own map flow
				float mx = 0, mz = 0, gain = 0;
				int mapped = 0;
				int lowest = 0;
				int count = 0;
				// the terrain's own height here when a fall's tile has this corner
				boolean onFall = false;
				int fallHeight = 0;
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
						if (fall[i])
						{
							onFall = true;
							fallHeight = h[cx][cy];
						}
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
						if (b.ocean[i])
						{
							++open;
						}
						++count;
					}
				}
				if (count > 0)
				{
					// a fall's surface is its slope: its corners are the terrain's, not the pool's level below
					height = onFall ? fallHeight : lowest;
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
					if (lipStrength[c] > 0 && Math.abs(height - lipLevel[c]) <= LIP_LEVEL)
					{
						// the water above a fall runs to its lip and churns there
						fx += lipPullX[c] * LIP_FLOW;
						fz += lipPullZ[c] * LIP_FLOW;
						turbulence = Math.max(turbulence, Math.min(LIP_CHURN * lipStrength[c], 1f));
					}
					if (footStrength[c] > 0 && Math.abs(height - footLevel[c]) <= LIP_LEVEL)
					{
						// the pool a fall lands in is pushed out from its foot
						fx -= footPullX[c] * FOOT_FLOW;
						fz -= footPullZ[c] * FOOT_FLOW;
						turbulence = Math.max(turbulence, Math.min(LIP_CHURN * footStrength[c], 1f));
					}
					if (contact != null)
					{
						// slower and churning right by things standing in the water
						int ccx = Math.min(cx * CONTACT_RES, CONTACT_SIZE - 1);
						int ccy = Math.min(cy * CONTACT_RES, CONTACT_SIZE - 1);
						float near = (contact[ccy * CONTACT_SIZE + ccx] & 255) / 255f;
						fx *= 1 - CONTACT_SLOW * near;
						fz *= 1 - CONTACT_SLOW * near;
						turbulence = Math.max(turbulence, CONTACT_TURB * near);
					}
					if (onFall)
					{
						// the water runs down the slope
						float length = (float) Math.hypot(frameX[c], frameZ[c]);
						fx = frameX[c] / length * FALL_FLOW;
						fz = frameZ[c] / length * FALL_FLOW;
					}
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
				int p2 = (cy * w + 2 * CORNERS + cx) * 4;
				t[p2] = toLand[cy * CORNERS + cx];
				t[p2 + 1] = count > 0 ? (float) open / count : 0;
				t[p2 + 2] = frameX[cy * CORNERS + cx];
				t[p2 + 3] = frameZ[cy * CORNERS + cx];
			}
		}
		spreadLevel(t, w);
		texture = t;
	}

	/**
	 * Straight line distance in tiles from every cell of the n x n grid to the nearest {@code land} cell (0 on land),
	 * at most {@code max}; {@code max} everywhere when there is no land. Exact (Felzenszwalb and Huttenlocher's
	 * two passes of lower envelopes of parabolas).
	 */
	static void distanceToLand(boolean[] land, int n, float max, float[] out)
	{
		float inf = 1e20f;
		float[] f = new float[n], d = new float[n];
		int[] v = new int[n];
		float[] z = new float[n + 1];
		float[] g = new float[n * n];
		for (int x = 0; x < n; ++x)
		{
			for (int y = 0; y < n; ++y)
			{
				f[y] = land[y * n + x] ? 0 : inf;
			}
			squaredTransform(f, d, v, z, n);
			for (int y = 0; y < n; ++y)
			{
				g[y * n + x] = d[y];
			}
		}
		for (int y = 0; y < n; ++y)
		{
			System.arraycopy(g, y * n, f, 0, n);
			squaredTransform(f, d, v, z, n);
			for (int x = 0; x < n; ++x)
			{
				out[y * n + x] = Math.min((float) Math.sqrt(d[x]), max);
			}
		}
	}

	private static void squaredTransform(float[] f, float[] d, int[] v, float[] z, int n)
	{
		int k = 0;
		v[0] = 0;
		z[0] = -Float.MAX_VALUE;
		z[1] = Float.MAX_VALUE;
		for (int q = 1; q < n; ++q)
		{
			float s;
			while (true)
			{
				int p = v[k];
				s = ((f[q] + (float) q * q) - (f[p] + (float) p * p)) / (2f * q - 2f * p);
				if (s <= z[k] && k > 0)
				{
					--k;
				}
				else
				{
					break;
				}
			}
			++k;
			v[k] = q;
			z[k] = s;
			z[k + 1] = Float.MAX_VALUE;
		}
		k = 0;
		for (int q = 0; q < n; ++q)
		{
			while (z[k + 1] < q)
			{
				++k;
			}
			float dq = q - v[k];
			d[q] = dq * dq + f[v[k]];
		}
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
