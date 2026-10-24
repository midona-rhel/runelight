package com.midonarhel.runelight;

import java.util.Arrays;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;

/** Immutable ground triangles for placing outdoor skylight probes; never inferred from solid voxels. */
final class SkyTerrain
{
	final int[][] triangles = new int[SkyGrid.SIZE * SkyGrid.SIZE][];

	static SkyTerrain capture(Scene scene, int offset)
	{
		SkyTerrain terrain = new SkyTerrain();
		Tile[][] tiles = scene.getExtendedTiles()[0];
		int[][] heights = scene.getTileHeights()[0];
		for (int z = 0; z < SkyGrid.SIZE; ++z)
			for (int x = 0; x < SkyGrid.SIZE; ++x)
			{
				int tx = x + offset, tz = z + offset;
				if (tx >= tiles.length || tz >= tiles[tx].length || tiles[tx][tz] == null) continue;
				Tile tile = tiles[tx][tz];
				SceneTilePaint paint = tile.getSceneTilePaint();
				SceneTileModel model = tile.getSceneTileModel();
				int[] data = null;
				if (paint != null && paint.getNeColor() != 12345678)
				{
					int x0 = x * 128, x1 = x0 + 128, z0 = z * 128, z1 = z0 + 128;
					int sw = heights[tx][tz], se = heights[tx + 1][tz];
					int nw = heights[tx][tz + 1], ne = heights[tx + 1][tz + 1];
					// The renderer's two triangles, split from SE to NW (not a bilinear height patch).
					data = new int[]{x1, ne, z1, x0, nw, z1, x1, se, z0,
						x0, sw, z0, x1, se, z0, x0, nw, z1};
				}
				else if (model != null)
				{
					int[] a = model.getFaceX(), b = model.getFaceY(), c = model.getFaceZ();
					int[] vx = model.getVertexX(), vy = model.getVertexY(), vz = model.getVertexZ();
					int[] colors = model.getTriangleColorA();
					data = new int[a.length * 9];
					int n = 0;
					for (int f = 0; f < a.length; ++f)
					{
						if (colors[f] == 12345678) continue;
						for (int v : new int[]{a[f], b[f], c[f]})
						{
							data[n++] = vx[v]; data[n++] = vy[v]; data[n++] = vz[v];
						}
					}
					if (n != data.length) data = Arrays.copyOf(data, n);
				}
				terrain.triangles[z * SkyGrid.SIZE + x] = data;
			}
		return terrain;
	}

	float height(float x, float z, float fallback)
	{
		int tx = (int) Math.floor(x / 128), tz = (int) Math.floor(z / 128);
		if (tx < 0 || tz < 0 || tx >= SkyGrid.SIZE || tz >= SkyGrid.SIZE) return fallback;
		int[] t = triangles[tz * SkyGrid.SIZE + tx];
		if (t == null) return fallback;
		for (int i = 0; i < t.length; i += 9)
		{
			float ax = t[i], az = t[i + 2], bx = t[i + 3], bz = t[i + 5], cx = t[i + 6], cz = t[i + 8];
			float det = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz);
			if (Math.abs(det) < 0.001f) continue;
			float a = ((bz - cz) * (x - cx) + (cx - bx) * (z - cz)) / det;
			float b = ((cz - az) * (x - cx) + (ax - cx) * (z - cz)) / det;
			float c = 1 - a - b;
			if (a >= -0.00001f && b >= -0.00001f && c >= -0.00001f)
				return a * t[i + 1] + b * t[i + 4] + c * t[i + 7];
		}
		return fallback;
	}
}
