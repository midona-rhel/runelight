package com.midonarhel.runelight;

import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;

/**
 * The height of the ground over the scene, for the volumetric fog to measure its height from (fog_frag.glsl): fog
 * lies on the terrain instead of on one flat plane through the player, so climbing a hill neither thickens the fog
 * below nor thins it above.
 *
 * One value per tile corner (so the texture's bilinear filter gives the terrain's own slopes) for each plane the
 * player can stand on: the floor that plane sees at each spot, which is the highest of the planes up to it with a
 * tile there, a bridge deck over what is under it. Water tiles hold the surface height, so fog lies on lakes.
 *
 * The building rooms come with it ({@link #texels}), so the fog's march has them in the same fetch as the ground.
 */
final class FogGround
{
	static final int PLANES = Constants.MAX_Z;
	static final int CORNERS = WaterMap.CORNERS;
	/** Texture width: the planes' pages side by side. */
	static final int WIDTH = PLANES * CORNERS;
	/** Floats per corner in {@link #texels}. */
	static final int CHANNELS = 4;
	// world y the rooms reach up to where nothing stops them (y points down)
	private static final float NO_TOP = -1e6f;
	// corners per side of the blocks that hold the highest ground, for the fog's per-frame bound
	private static final int BLOCK = 8;
	private static final int BLOCKS = (CORNERS + BLOCK - 1) / BLOCK;
	// an overlay's hidden tile (SceneTilePaint colors)
	private static final int HIDDEN_HSL = 12345678;

	// world y of the ground, row by row, the planes side by side
	final float[] texture = new float[WIDTH * CORNERS];
	// per plane and block, the smallest y (the highest ground) in it
	private final float[][] blockTop = new float[PLANES][BLOCKS * BLOCKS];

	static FogGround build(Scene scene)
	{
		FogGround g = new FogGround();
		g.load(scene.getExtendedTiles(), scene.getTileHeights());
		return g;
	}

	private void load(Tile[][][] tiles, int[][][] heights)
	{
		final int size = WaterMap.SIZE;
		// per tile, the render level of its floor, -1 for none
		byte[] floor = new byte[size * size];
		for (int plane = 0; plane < PLANES; ++plane)
		{
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					floor[y * size + x] = (byte) floorLevel(tiles, plane, x, y);
				}
			}

			float[] top = blockTop[plane];
			java.util.Arrays.fill(top, Float.MAX_VALUE);
			for (int cy = 0; cy < CORNERS; ++cy)
			{
				for (int cx = 0; cx < CORNERS; ++cx)
				{
					// the highest of the floors meeting at the corner
					float h = Float.MAX_VALUE;
					for (int ty = Math.max(cy - 1, 0); ty <= Math.min(cy, size - 1); ++ty)
					{
						for (int tx = Math.max(cx - 1, 0); tx <= Math.min(cx, size - 1); ++tx)
						{
							int level = floor[ty * size + tx];
							if (level >= 0)
							{
								h = Math.min(h, heights[level][cx][cy]);
							}
						}
					}
					if (h == Float.MAX_VALUE)
					{
						// nothing loaded here
						h = heights[0][cx][cy];
					}
					texture[cy * WIDTH + plane * CORNERS + cx] = h;
					int b = cy / BLOCK * BLOCKS + cx / BLOCK;
					top[b] = Math.min(top[b], h);
				}
			}
		}
	}

	/**
	 * Render level of the floor a player on {@code plane} has at tile (x, y): the highest plane up to theirs with a
	 * visible floor tile there, or -1.
	 */
	private static int floorLevel(Tile[][][] tiles, int plane, int x, int y)
	{
		for (int z = Math.min(plane, tiles.length - 1); z >= 0; --z)
		{
			Tile t = tiles[z][x][y];
			if (t == null)
			{
				continue;
			}
			Tile bridge = t.getBridge();
			if (bridge != null)
			{
				t = bridge;
			}
			SceneTilePaint paint = t.getSceneTilePaint();
			if (paint != null && paint.getNeColor() != HIDDEN_HSL || t.getSceneTileModel() != null)
			{
				return t.getRenderLevel();
			}
		}
		return -1;
	}

	/**
	 * The texture as the fog samples it (fog_frag.glsl), {@link #CHANNELS} floats per corner: the ground y; whether a
	 * tile at the corner is in a building room on the storey standing on that ground (RoomMap), and in one open to the
	 * sky (OpenRooms), 1 or 0; and the world y of the top of the room's storeys, above which it is outdoors (rooms.glsl's
	 * ROOM_STOREY and the storeys over it; Float.MAX_VALUE away from rooms). A room's whole outline counts as in it,
	 * so the shader keeps rooms whole and fades them out within half a tile outside their walls. Diagonal walls are
	 * left to that fade, and rooms on an upper storey over open ground are outdoors: the fog in them shows only with
	 * their roof hidden, and then the player is up there, on the ground of that page.
	 *
	 * @param rooms null: no rooms
	 * @param open null: none open
	 */
	void texels(RoomMap rooms, OpenRooms open, float[] out)
	{
		final int size = WaterMap.SIZE;
		final int n = rooms != null ? Math.min(rooms.size, size) : 0;
		// per tile (x * size + y) of the plane's page: 0 outdoors, 1 in a room, 2 in an open one; and its top
		byte[] in = new byte[size * size];
		float[] top = new float[size * size];
		for (int plane = 0; plane < PLANES; ++plane)
		{
			int page = plane * CORNERS;
			java.util.Arrays.fill(in, (byte) 0);
			for (int x = 0; x < n; ++x)
			{
				for (int y = 0; y < n; ++y)
				{
					float ground = (texture[y * WIDTH + page + x] + texture[y * WIDTH + page + x + 1]
						+ texture[(y + 1) * WIDTH + page + x] + texture[(y + 1) * WIDTH + page + x + 1]) * 0.25f;
					// just above the storey's margin, so it is the storey this ground is the floor of
					float from = ground - RoomMap.PLANE_MARGIN - 1;
					int room = roomAt(rooms, x, y, from);
					if (room == 0)
					{
						continue;
					}
					in[x * size + y] = (byte) (open != null && open.isOpen(room) ? 2 : 1);
					top[x * size + y] = roomTop(rooms, x, y, from);
				}
			}

			for (int cy = 0; cy < CORNERS; ++cy)
			{
				for (int cx = 0; cx < CORNERS; ++cx)
				{
					int o = (cy * WIDTH + page + cx) * CHANNELS;
					float room = 0, opened = 0, t = Float.MAX_VALUE;
					for (int x = Math.max(cx - 1, 0); x <= Math.min(cx, n - 1); ++x)
					{
						for (int y = Math.max(cy - 1, 0); y <= Math.min(cy, n - 1); ++y)
						{
							int c = in[x * size + y];
							if (c != 0)
							{
								room = 1;
								opened = Math.max(opened, c - 1);
								t = Math.min(t, top[x * size + y]);
							}
						}
					}
					out[o] = texture[cy * WIDTH + page + cx];
					out[o + 1] = room;
					out[o + 2] = opened;
					out[o + 3] = t;
				}
			}
			// the corners just outside a room take its top too, for the fade there
			for (int cy = 0; cy < CORNERS; ++cy)
			{
				for (int cx = 0; cx < CORNERS; ++cx)
				{
					int o = (cy * WIDTH + page + cx) * CHANNELS;
					if (out[o + 1] == 0)
					{
						continue;
					}
					for (int y = Math.max(cy - 1, 0); y <= Math.min(cy + 1, CORNERS - 1); ++y)
					{
						for (int x = Math.max(cx - 1, 0); x <= Math.min(cx + 1, CORNERS - 1); ++x)
						{
							int k = (y * WIDTH + page + x) * CHANNELS;
							if (out[k + 1] == 0)
							{
								out[k + 3] = Math.min(out[k + 3], out[o + 3]);
							}
						}
					}
				}
			}
		}
	}

	/** RoomMap.roomAt at the center of extended tile (x, y), at world y wy. */
	private static int roomAt(RoomMap m, int x, int y, float wy)
	{
		int plane = 0;
		for (int k = 1; k < RoomMap.PLANES; ++k)
		{
			if (wy <= m.floors[m.index(k, x, y)] - RoomMap.PLANE_MARGIN)
			{
				plane = k;
			}
		}
		return wy < m.floors[m.index(plane, x, y)] - RoomMap.STOREY ? 0 : m.room(plane, x, y);
	}

	/**
	 * The world y up to which the tile stays in a room from {@code from} up (y points down): the storey boundaries
	 * above, the floors and the rooms' tops, as long as the next one up is in a room too.
	 */
	private static float roomTop(RoomMap m, int x, int y, float from)
	{
		float t = from;
		while (true)
		{
			float next = NO_TOP;
			for (int k = 0; k < RoomMap.PLANES; ++k)
			{
				float floor = m.floors[m.index(k, x, y)];
				float cap = floor - RoomMap.STOREY;
				if (cap < t && cap > next)
				{
					next = cap;
				}
				float storey = floor - RoomMap.PLANE_MARGIN;
				if (k > 0 && storey < t && storey > next)
				{
					next = storey;
				}
			}
			if (next == NO_TOP || roomAt(m, x, y, next - 1) == 0)
			{
				return next;
			}
			t = next;
		}
	}

	/**
	 * World y of the highest ground on {@code plane} within the square [minX, maxX] x [minZ, maxZ] (scene-local world
	 * units), to the precision of {@link #BLOCK}-tile blocks: the fog's layer has no use above it.
	 */
	float highest(int plane, float minX, float minZ, float maxX, float maxZ)
	{
		final float tile = Perspective.LOCAL_TILE_SIZE;
		int off = RuneLightPlugin.SCENE_OFFSET;
		int bx0 = block(minX / tile + off), bx1 = block(maxX / tile + off);
		int bz0 = block(minZ / tile + off), bz1 = block(maxZ / tile + off);
		float[] top = blockTop[Math.min(Math.max(plane, 0), PLANES - 1)];
		float best = Float.MAX_VALUE;
		for (int bz = bz0; bz <= bz1; ++bz)
		{
			for (int bx = bx0; bx <= bx1; ++bx)
			{
				best = Math.min(best, top[bz * BLOCKS + bx]);
			}
		}
		return best;
	}

	private static int block(float corner)
	{
		return Math.min(Math.max((int) Math.floor(corner), 0), CORNERS - 1) / BLOCK;
	}
}
