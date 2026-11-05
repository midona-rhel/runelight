package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.LuminaPlugin;

/**
 * For the scorecard's door: a zone uploaded again without what stands in a box, as the client uploads a zone again when
 * a door opens: a new Zone with buffers of its own takes the old one's place, whatever a method keeps per zone sees it.
 */
public final class CardZones
{
	private static final int VERT_INTS = 6, TRI_INTS = VERT_INTS * 3;

	private CardZones()
	{
	}

	/** The upright triangles of the zone wholly inside the box (world units local to the scene): how many. */
	public static int inBox(ZoneSnapshot s, int baseX, int baseZ, float[] box)
	{
		return strip(s, baseX, baseZ, box, null);
	}

	/**
	 * Puts a zone with those triangles gone in the scene's place of s: a new Zone from a copy of s with them degenerate.
	 * The plugin's context is its top level's.
	 */
	public static int replace(LuminaPlugin plugin, ZoneSnapshot s, int baseX, int baseZ, float[] box)
	{
		ZoneSnapshot c = new ZoneSnapshot();
		c.x = s.x;
		c.z = s.z;
		c.sizeO = s.sizeO;
		c.sizeA = s.sizeA;
		c.bufLen = s.bufLen;
		c.bufLenA = s.bufLenA;
		c.vertsO = s.vertsO.clone();
		c.vertsA = s.vertsA;
		c.levelOffsets = s.levelOffsets;
		c.rids = s.rids;
		c.roofStart = s.roofStart;
		c.roofEnd = s.roofEnd;
		c.swayRanges = s.swayRanges;
		c.swayRangeCount = s.swayRangeCount;
		c.alphaModels.addAll(s.alphaModels);
		c.alphaFaces.addAll(s.alphaFaces);
		int removed = strip(s, baseX, baseZ, box, c.vertsO);
		Zone z = new Zone();
		c.restore(z, plugin.sceneLoader);
		z.initialized = true;
		plugin.root.zones[s.x][s.z] = z;
		return removed;
	}

	private static int strip(ZoneSnapshot s, int baseX, int baseZ, float[] box, int[] into)
	{
		int[] v = s.vertsO;
		if (v == null)
		{
			return 0;
		}
		int n = 0;
		float[] p = new float[9];
		for (int i = 0; i + TRI_INTS <= v.length; i += TRI_INTS)
		{
			boolean in = true;
			for (int k = 0; k < 3 && in; ++k)
			{
				int a = v[i + k * VERT_INTS], b = v[i + k * VERT_INTS + 1];
				float x = (short) a + baseX, y = (short) (a >> 16), z = (short) b + baseZ;
				p[k * 3] = x;
				p[k * 3 + 1] = y;
				p[k * 3 + 2] = z;
				in = x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5];
			}
			if (!in)
			{
				continue;
			}
			float e1x = p[3] - p[0], e1y = p[4] - p[1], e1z = p[5] - p[2], e2x = p[6] - p[0], e2y = p[7] - p[1], e2z = p[8] - p[2];
			float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
			float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (l < 1e-3f || Math.abs(ny) / l > 0.7f)
			{
				continue;
			}
			++n;
			if (into != null)
			{
				// all three corners on the first: nothing drawn
				for (int k = 1; k < 3; ++k)
				{
					System.arraycopy(v, i, into, i + k * VERT_INTS, 3);
				}
			}
		}
		return n;
	}

	static
	{
		// the format strip reads
		assert Zone.VERT_SIZE == VERT_INTS * 4;
	}
}
