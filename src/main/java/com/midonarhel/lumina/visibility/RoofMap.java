package com.midonarhel.lumina.visibility;

import com.midonarhel.lumina.*;

import java.util.Set;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;

/**
 * The roofs of a scene: per level and tile, the lowest point of the roof objects on it (the game's object types 12 to
 * 21: the roof pieces and their edges, which overhang the walls) and the roof id the client hides it by. A lamp's line
 * that passes through that height on a roofed tile goes through the roof (light/lamp.glsl), so the roof over a lamp
 * keeps its light from the roof's top, and the roof over a room keeps the room's lamps from the roof's outside. The
 * client hides the roofs over the player: those are not drawn and stop nothing ({@link #bottoms}).
 *
 * GL-free and immutable once built. Arrays are [level][x][y] in extended scene tiles, like WallMap.
 */
public final class RoofMap
{
	static final int TYPE_ROOF_FIRST = 12, TYPE_ROOF_LAST = 21;

	public final int size;
	// the lowest point of the tile's roof objects (world y, down), NaN where there are none
	private final float[] bottom;
	private final int[] roofId;

	private RoofMap(int size)
	{
		this.size = size;
		bottom = new float[WallMap.PLANES * size * size];
		roofId = new int[WallMap.PLANES * size * size];
		java.util.Arrays.fill(bottom, Float.NaN);
	}

	private int index(int level, int x, int y)
	{
		return (level * size + x) * size + y;
	}

	/** A map of the given size with one roof (tests): its lowest point and roof id on a level's tile. */
	static RoofMap of(int size, int level, int x, int y, float bottom, int roofId)
	{
		RoofMap m = new RoofMap(size);
		m.bottom[m.index(level, x, y)] = bottom;
		m.roofId[m.index(level, x, y)] = roofId;
		return m;
	}

	/** From the scene's tiles: off the client thread is fine, as for the diagonal walls (BuildingLights). */
	public static RoofMap build(Scene scene)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		int[][][] roofs = scene.getRoofs();
		int size = tiles != null && tiles.length > 0 ? tiles[0].length : 0;
		RoofMap m = new RoofMap(size);
		for (int level = 0; level < WallMap.PLANES && tiles != null && level < tiles.length; ++level)
		{
			for (int x = 0; x < size && x < tiles[level].length; ++x)
			{
				for (int y = 0; y < size && y < tiles[level][x].length; ++y)
				{
					Tile tile = tiles[level][x][y];
					GameObject[] objects = tile != null ? tile.getGameObjects() : null;
					if (objects == null)
					{
						continue;
					}
					float low = Float.NaN;
					for (GameObject go : objects)
					{
						int type = go != null ? go.getConfig() & 0x1F : -1;
						if (type < TYPE_ROOF_FIRST || type > TYPE_ROOF_LAST)
						{
							continue;
						}
						float b = lowest(go);
						if (!Float.isNaN(b) && (Float.isNaN(low) || b > low))
						{
							low = b;
						}
					}
					if (!Float.isNaN(low))
					{
						int i = m.index(level, x, y);
						m.bottom[i] = low;
						m.roofId[i] = roofs != null && level < roofs.length && x < roofs[level].length && y < roofs[level][x].length
							? roofs[level][x][y] : 0;
					}
				}
			}
		}
		return m;
	}

	/** The object's lowest point, world y (down); NaN with no model. */
	private static float lowest(GameObject go)
	{
		// off the client thread (BuildingLights' worker): only a plain model; an animated one's is made on the client's
		Renderable r = go.getRenderable();
		Model model = r instanceof Model ? (Model) r : null;
		if (model == null || model.getVerticesCount() == 0)
		{
			return Float.NaN;
		}
		float[] vy = model.getVerticesY();
		float max = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < model.getVerticesCount() && i < vy.length; ++i)
		{
			max = Math.max(max, vy[i]);
		}
		return go.getZ() + max;
	}

	/** Whether any level has a roof on the tile, drawn or not. */
	public boolean any(int x, int y)
	{
		for (int level = 0; level < WallMap.PLANES; ++level)
		{
			if (!Float.isNaN(bottom[index(level, x, y)]))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The drawn roofs' lowest points per tile into out (rows from the south, east along each, size * size), as
	 * lamp.glsl reads them: world y plus BIAS, 0 where no drawn roof is. A tile's lowest drawn roof counts. The client
	 * hides a roof whose id it lists while the player is on a level below it (Zone.renderOpaque).
	 */
	public void bottoms(Set<Integer> hidden, int playerLevel, float[] out)
	{
		java.util.Arrays.fill(out, 0, size * size, 0);
		for (int level = 0; level < WallMap.PLANES; ++level)
		{
			boolean hiding = hidden != null && !hidden.isEmpty() && level > playerLevel;
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					int i = index(level, x, y);
					float b = bottom[i];
					if (Float.isNaN(b) || hiding && roofId[i] > 0 && hidden.contains(roofId[i]))
					{
						continue;
					}
					int o = y * size + x;
					float v = b + BIAS;
					if (v > 0 && (out[o] == 0 || v > out[o]))
					{
						out[o] = v;
					}
				}
			}
		}
	}

	// lamp.glsl's LAMP_ROOF_BIAS: the stored heights are above 0, and 0 is no roof
	public static final float BIAS = 65536;
}
