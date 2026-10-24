package com.midonarhel.runelight;

import static org.junit.Assert.assertTrue;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * gi.glsl's lookup in Java, for the tests: the same list of probes, the same weights, with the constants read from
 * the shader. Positions are exact here, where the shader reads them back from half floats.
 */
final class GiLookup
{
	private static String glsl;

	private GiLookup()
	{
	}

	/** The value of a #define of gi.glsl. */
	static synchronized float define(String name)
	{
		try
		{
			if (glsl == null)
			{
				glsl = new String(Files.readAllBytes(
					Paths.get("src/main/resources/com/midonarhel/runelight/gi.glsl")));
			}
		}
		catch (java.io.IOException ex)
		{
			throw new RuntimeException(ex);
		}
		Matcher m = Pattern.compile("#define " + name + " ([0-9.]+)u?").matcher(glsl);
		assertTrue(name, m.find());
		return Float.parseFloat(m.group(1));
	}

	/** The probe standing over the tile's center on its own (no wall probe), -1 for none. */
	static int ground(GiProbes p, int tx, int tz)
	{
		int t = tz * GiProbes.SIZE + tx;
		for (int k = p.tileStart[t]; k < p.tileStart[t + 1]; ++k)
		{
			int i = p.tileProbes[k];
			if (p.outward[i * 2] == 0 && p.outward[i * 2 + 1] == 0)
			{
				return i;
			}
		}
		return -1;
	}

	/** The probes of a tile that stand in front of a wall, by the unit direction (outX, outZ) from the wall. */
	static java.util.List<Integer> wall(GiProbes p, int tx, int tz, float outX, float outZ)
	{
		java.util.List<Integer> out = new java.util.ArrayList<>();
		int t = tz * GiProbes.SIZE + tx;
		for (int k = p.tileStart[t]; k < p.tileStart[t + 1]; ++k)
		{
			int i = p.tileProbes[k];
			if (Math.abs(p.outward[i * 2] - outX) < 1e-3 && Math.abs(p.outward[i * 2 + 1] - outZ) < 1e-3)
			{
				out.add(i);
			}
		}
		return out;
	}

	/**
	 * giVisibility: the share of the probe's rays that Chebyshev's bound lets reach a point dist voxels away along
	 * unit (dx, dy, dz).
	 */
	static float visibility(float[] v, int probe, float dx, float dy, float dz, float dist)
	{
		int o = probe * GiProbes.VISIBILITY;
		float[] d = {dx, dy, dz};
		float mean = 0, variance = 0;
		for (int axis = 0; axis < 3; ++axis)
		{
			int bin = axis * 2 + (d[axis] < 0 ? 1 : 0);
			mean += d[axis] * d[axis] * v[o + bin];
			variance += d[axis] * d[axis] * v[o + 6 + bin];
		}
		float excess = dist - mean;
		if (excess <= 0)
		{
			return 1;
		}
		variance = Math.max(variance, define("GI_MIN_VARIANCE"));
		float c = variance / (variance + excess * excess);
		return c * c * c;
	}

	private static float smoothstep(float edge0, float edge1, float x)
	{
		float t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
		return t * t * (3 - 2 * t);
	}

	/**
	 * giIrradiance for the surface at world position (px, py, pz) facing unit n, far from the scene's edges: rgb and
	 * how much of it counts, from the probes' light (GiRelight's layout).
	 */
	static float[] irradiance(GiProbes p, float[] light, float[] pos, float[] n)
	{
		float bias = define("GI_NORMAL_BIAS"), radius = define("GI_RADIUS"), near = define("GI_NEAR");
		float facingSoft = define("GI_FACING_SOFT");
		int maxProbes = (int) define("GI_MAX_PROBES");
		float[] q = {pos[0] + n[0] * bias, pos[1] + n[1] * bias, pos[2] + n[2] * bias};
		int tile = GiProbes.tileOf(q[0], q[2]);
		float[] sum = new float[3];
		float wsum = 0;
		int read = 0;
		for (int e = 0; e < GiProbes.LIST && read < maxProbes; ++e)
		{
			int id = p.lists[tile * GiProbes.LIST + e];
			if (id == GiProbes.NONE)
			{
				break;
			}
			float fx = q[0] - p.position[id * 3], fy = q[1] - p.position[id * 3 + 1], fz = q[2] - p.position[id * 3 + 2];
			float dist = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
			if (dist >= radius)
			{
				continue;
			}
			float tx = p.position[id * 3] - pos[0], ty = p.position[id * 3 + 1] - pos[1], tz = p.position[id * 3 + 2] - pos[2];
			float facing = smoothstep(-facingSoft, facingSoft, (tx * n[0] + ty * n[1] + tz * n[2])
				/ (float) Math.sqrt(Math.max(tx * tx + ty * ty + tz * tz, 1)));
			if (facing <= 0)
			{
				continue;
			}
			read++;
			float reach = 1 - dist / radius;
			float d = Math.max(dist, 1e-3f);
			float w = reach * reach / (1 + dist / near) * facing
				* visibility(p.visibility, id, fx / d, fy / d, fz / d, dist / GiGrid.VOXEL);
			if (w > 1e-5f)
			{
				for (int c = 0; c < 3; ++c)
				{
					sum[c] += GiRelight.irradiance(light, id, n[0], n[1], n[2], c) * w;
				}
				wsum += w;
			}
		}
		if (wsum <= 1e-5f)
		{
			return new float[4];
		}
		return new float[]{sum[0] / wsum, sum[1] / wsum, sum[2] / wsum, Math.min(1, wsum / define("GI_FULL_WEIGHT"))};
	}
}
