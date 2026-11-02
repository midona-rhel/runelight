package com.midonarhel.lumina.light;

/**
 * A light that does not cast shadows. Position in world space (game units, y down), color in 0-1,
 * intensity scales the color, radius is where the light reaches zero.
 */
public class PointLight
{
	float x, y, z;
	float radius;
	float r, g, b;
	float intensity;
	// logical plane, -1 unknown: the storey whose walls and floors stop it
	int plane = -1;
	// where its lines to what it lights start (x, z: in its own tile, off the wall it hangs on), and its storey's floor
	// and ceiling (y, down); none known: its position, no floor or ceiling
	float ox, oz, floorY = Float.POSITIVE_INFINITY, ceilY = Float.NEGATIVE_INFINITY;
	// what decides which lights a full cluster keeps (LightClusters): steady, its definition's strength times reach
	float importance;
	// The light map (LightMap): the lamp's id in it once it carries the lamp (0: it doesn't, the walls alone stop it),
	// and its share as it eases in (LayerEase). Set after the frame's lamps are gathered (SceneLights.mapLamps).
	int mapId;
	float mapEase;
	// how much of it shows while the map takes it on: 0 while it waits, dark, for its map, then up to 1 as it fades in
	// with it (LightMap.waitsDark); its intensity times this is what it gives
	float mapFade = 1;
	// the frame's bake of the lamp (SceneLights' list of them), -1 for a lamp the map doesn't bake: one that moves, comes
	// and goes, or fades; and the scene object it is on (VoxelCapture.ownerOf, 0: none), whose geometry never shades it
	int bakeSlot = -1;
	int owner;

	public PointLight set(float x, float y, float z, float radius, float r, float g, float b, float intensity)
	{
		this.x = x;
		this.y = y;
		this.z = z;
		this.radius = radius;
		this.r = r;
		this.g = g;
		this.b = b;
		this.intensity = intensity;
		this.plane = -1;
		ox = x;
		oz = z;
		floorY = Float.POSITIVE_INFINITY;
		ceilY = Float.NEGATIVE_INFINITY;
		importance = intensity * radius;
		mapId = 0;
		mapEase = 0;
		mapFade = 1;
		bakeSlot = -1;
		owner = 0;
		return this;
	}

	public PointLight onPlane(int plane)
	{
		this.plane = plane;
		return this;
	}

	/** Where its lines start, and its storey's floor and ceiling (lamp.glsl). */
	PointLight from(float ox, float oz, float floorY, float ceilY)
	{
		this.ox = ox;
		this.oz = oz;
		this.floorY = floorY;
		this.ceilY = ceilY;
		return this;
	}

	/**
	 * Drop the lamps that stand twice: the same light at the same place, from two objects on two storeys (the game keeps
	 * some wall decorations on both planes; stall5 had 5 of 106 lamps so, each lighting what it reaches twice). The one on
	 * the lower storey stays; the order of the rest is kept.
	 */
	static void dropDoubles(java.util.List<PointLight> lights)
	{
		if (lights.size() < 2)
		{
			return;
		}
		java.util.Map<Long, Integer> at = new java.util.HashMap<>(lights.size() * 2);
		boolean[] drop = null;
		for (int i = 0; i < lights.size(); ++i)
		{
			PointLight l = lights.get(i);
			long key = (long) Math.round(l.x) * 73856093L ^ (long) Math.round(l.y) * 19349663L ^ (long) Math.round(l.z) * 83492791L
				^ (long) Float.floatToIntBits(l.radius) * 2654435761L ^ (long) Float.floatToIntBits(l.r + 2 * l.g + 4 * l.b) * 40503L;
			Integer j = at.get(key);
			if (j == null)
			{
				at.put(key, i);
				continue;
			}
			PointLight k = lights.get(j);
			if (!k.same(l))
			{
				continue;
			}
			if (drop == null)
			{
				drop = new boolean[lights.size()];
			}
			// the lower storey's stays
			if (l.plane >= 0 && (k.plane < 0 || l.plane < k.plane))
			{
				drop[j] = true;
				at.put(key, i);
			}
			else
			{
				drop[i] = true;
			}
		}
		if (drop == null)
		{
			return;
		}
		int w = 0;
		for (int i = 0; i < lights.size(); ++i)
		{
			if (!drop[i])
			{
				lights.set(w++, lights.get(i));
			}
		}
		lights.subList(w, lights.size()).clear();
	}

	private boolean same(PointLight o)
	{
		return Math.abs(x - o.x) < 1 && Math.abs(y - o.y) < 1 && Math.abs(z - o.z) < 1 && radius == o.radius && r == o.r && g == o.g
			&& b == o.b;
	}

	/** What a full cluster keeps it by: more is kept first. */
	PointLight importance(float importance)
	{
		this.importance = importance;
		return this;
	}
}
