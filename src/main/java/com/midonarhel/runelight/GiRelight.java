package com.midonarhel.runelight;

/**
 * Lights the probes from their hit cache (GiProbes): rays that reached the sky bring the sky's radiance where they
 * point, rays that hit a surface bring its albedo times the light on it, which is the sun or moon where the voxels
 * see it, plus the light the probe in front of the voxel had last time, for further bounces. A probe's color is the
 * weighted average of its rays, whichever way they point: surfaces are treated as rough, scattering light everywhere,
 * so no surface gets more from one that faces it than from any other. Runs on the global illumination's worker
 * whenever the sky or the light changes enough (GlobalIllumination), tens of milliseconds for a whole scene.
 *
 * The sky part is scaled so that a probe under the open sky lights a surface facing up as much as the composite's
 * plain ambient and sky light do: the probes move that light around (less of it under roofs and overhangs, the
 * colors of the ground and walls around) rather than changing the exposure.
 */
final class GiRelight
{
	// the sky table drawn by gi_sky_frag.glsl: SKY_W x SKY_H radiance over the sphere, then one row of light
	static final int SKY_W = 32, SKY_H = 16;
	static final int TABLE_FLOATS = SKY_W * (SKY_H + 1) * 4;
	// sun visibility rays reach this many voxels
	static final float SUN_DISTANCE = 128;
	// the open-sky normalization stays within this range, so a broken table can't blow the light up
	static final float MIN_SCALE = 0.02f, MAX_SCALE = 64;
	// sun and moon light bounced off what they fall on, relative to the light itself: above 1 because the voxels'
	// albedos are the dim vertex and texture colors, and the bounce is what makes sunlit ground warm the shade
	static final float SUN_BOUNCE = 1.4f;
	// A probe's color is an average over the sphere, which is about half what a surface facing the open sky receives
	// from it; a surface hit by a ray reflects this much of the average of the probe in front of it, between that
	// average and the most a facing surface would get. Kept low enough that light bouncing in a closed room dies out.
	static final float INCIDENT_GAIN = 1.5f;
	// the light shown with the sky (relative to the open sky's) stays within this, the range the composite can carry
	static final float MAX_RELATIVE = 4;

	/** What lights the probes this time. */
	static final class Light
	{
		final float[] sky = new float[SKY_W * SKY_H * 3];
		final float[] caster = new float[3];
		final float[] oldUp = new float[3];
		float shadow0 = 0, shadow1 = 1;
		final float[] dir = new float[3];
		boolean bounce = true;

		/**
		 * @param table gi_sky_frag.glsl's output, rgba floats row by row
		 * @param casterDir toward the light that casts shadows
		 */
		static Light fromTable(float[] table, float[] casterDir, boolean bounce)
		{
			Light l = new Light();
			for (int i = 0; i < SKY_W * SKY_H; ++i)
			{
				for (int c = 0; c < 3; ++c)
				{
					float v = table[i * 4 + c];
					l.sky[i * 3 + c] = Float.isFinite(v) ? Math.max(0, v) : 0;
				}
			}
			int row = SKY_W * SKY_H * 4;
			for (int c = 0; c < 3; ++c)
			{
				l.caster[c] = finite(table[row + c]);
				l.oldUp[c] = finite(table[row + 4 + c]);
			}
			l.shadow0 = Math.max(0, Math.min(1, finite(table[row + 8])));
			l.shadow1 = Math.max(0, Math.min(1, Float.isFinite(table[row + 12]) ? table[row + 12] : 1));
			for (int c = 0; c < 3; ++c)
			{
				l.caster[c] *= SUN_BOUNCE;
			}
			System.arraycopy(casterDir, 0, l.dir, 0, 3);
			l.bounce = bounce;
			return l;
		}

		private static float finite(float v)
		{
			return Float.isFinite(v) ? Math.max(0, v) : 0;
		}
	}

	/** The probes' light (RGB each), whole and as shown. */
	static final class Result
	{
		/** Sky and surfaces together: what the next bounces build on. */
		final float[] light;
		/**
		 * What goes to the GPU: with bounceOnly the surfaces' light alone, in the composite's units; else the whole
		 * light relative to what a probe under the open sky would have (1 where all of the sky is seen, nothing bounces).
		 */
		final float[] shown;

		Result(float[] light, float[] shown)
		{
			this.light = light;
			this.shown = shown;
		}
	}

	private GiRelight()
	{
	}

	/** Sky radiance toward unit direction d, bilinear from the table (gi_sky_frag.glsl's layout). */
	static void sky(float[] sky, float dx, float dy, float dz, float[] out)
	{
		float s = Math.max(-1, Math.min(1, -dy));
		float u = (float) (Math.atan2(dz, dx) / (2 * Math.PI)) + 0.5f;
		float v = (s + 1) * 0.5f;
		float fx = u * SKY_W - 0.5f, fy = v * SKY_H - 0.5f;
		int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
		float tx = fx - x0, ty = fy - y0;
		int x1 = Math.floorMod(x0 + 1, SKY_W);
		x0 = Math.floorMod(x0, SKY_W);
		int y1 = Math.min(SKY_H - 1, Math.max(0, y0 + 1));
		y0 = Math.min(SKY_H - 1, Math.max(0, y0));
		for (int c = 0; c < 3; ++c)
		{
			float a = sky[(y0 * SKY_W + x0) * 3 + c], b = sky[(y0 * SKY_W + x1) * 3 + c];
			float e = sky[(y1 * SKY_W + x0) * 3 + c], f = sky[(y1 * SKY_W + x1) * 3 + c];
			out[c] = (a + (b - a) * tx) * (1 - ty) + (e + (f - e) * tx) * ty;
		}
	}

	static float luminance(float r, float g, float b)
	{
		return 0.2126f * r + 0.7152f * g + 0.0722f * b;
	}

	/**
	 * How much the table's sky radiance is scaled: the irradiance on a surface facing up, under the open sky, matches
	 * the old ambient plus sky light facing up (the mean radiance plus twice the mean radiance along the normal).
	 */
	static float skyScale(Light light)
	{
		float[] d = GiProbes.SKY_RAYS;
		int n = d.length / 3;
		float[] rgb = new float[3];
		float mean = 0, along = 0;
		for (int i = 0; i < n; ++i)
		{
			sky(light.sky, d[i * 3], d[i * 3 + 1], d[i * 3 + 2], rgb);
			float l = luminance(rgb[0], rgb[1], rgb[2]);
			mean += l;
			along -= l * d[i * 3 + 1];
		}
		float open = (mean + 2 * along) / n;
		float target = luminance(light.oldUp[0], light.oldUp[1], light.oldUp[2]);
		if (open < 1e-6f || target <= 0)
		{
			return 1;
		}
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, target / open));
	}

	/**
	 * Light every valid probe. prev: the probes' last whole light for the bounces (null for none yet); each further
	 * iteration bounces once more from the one before.
	 *
	 * @param bounceOnly show the light of the rays that hit surfaces alone, with none of the sky's own: what the
	 *                   skylight mode adds to its plain ambient and sky light
	 */
	static Result relight(GiProbes p, Light light, float[] prev, int iterations, GiProbes.ParallelFor parallel,
		boolean bounceOnly)
	{
		float k = skyScale(light);
		int rays = GiProbes.RAYS;
		float[][] skyRays = new float[GiProbes.ROTATIONS][rays * 3];
		float[] rgb = new float[3];
		float[] open = new float[3];
		for (int r = 0; r < GiProbes.ROTATIONS; ++r)
		{
			float[] d = GiProbes.DIRECTIONS[r];
			for (int i = 0; i < rays; ++i)
			{
				sky(light.sky, d[i * 3], d[i * 3 + 1], d[i * 3 + 2], rgb);
				for (int c = 0; c < 3; ++c)
				{
					skyRays[r][i * 3 + c] = rgb[c] * k;
					// what a probe sees of the sky where nothing is in the way, the rays that point up
					if (r == 0 && d[i * 3 + 1] < 0)
					{
						open[c] += rgb[c] * k * GiProbes.WEIGHT[i] / GiProbes.WEIGHT_SUM;
					}
				}
			}
		}

		// sun or moon on each hit voxel, with the overcast's softening of shadows
		float[] lit = new float[p.hits];
		float lx = light.dir[0], ly = light.dir[1], lz = light.dir[2];
		boolean direct = light.bounce && luminance(light.caster[0], light.caster[1], light.caster[2]) > 1e-5f;
		if (direct)
		{
			parallel.run(p.hits, h ->
			{
				float nx = p.hitNormal[h * 3], ny = p.hitNormal[h * 3 + 1], nz = p.hitNormal[h * 3 + 2];
				boolean two = p.hitTwoSided[h];
				if (!two && nx * lx + ny * ly + nz * lz <= 0)
				{
					return;
				}
				float off = two ? 0.75f : 0.5f;
				float ox = p.hitPos[h * 3] + (nx + lx) * off;
				float oy = p.hitPos[h * 3 + 1] + (ny + ly) * off;
				float oz = p.hitPos[h * 3 + 2] + (nz + lz) * off;
				boolean seen = p.grid.trace(ox, oy, oz, lx, ly, lz, SUN_DISTANCE, true, h) < 0;
				lit[h] = seen ? light.shadow1 : light.shadow0;
			});
		}

		float[] out = prev;
		float[] radiance = new float[p.hits * 3];
		float[] surfaces = null;
		float inv = 1f / GiProbes.WEIGHT_SUM;
		for (int it = 0; it < Math.max(1, iterations); ++it)
		{
			float[] last = out;
			float[] cur = new float[GiProbes.PROBES * 3];
			float[] curSurfaces = bounceOnly ? new float[GiProbes.PROBES * 3] : null;
			// light leaving each one-sided hit voxel
			parallel.run(p.hits, h ->
			{
				if (p.hitTwoSided[h])
				{
					return;
				}
				float nx = p.hitNormal[h * 3], ny = p.hitNormal[h * 3 + 1], nz = p.hitNormal[h * 3 + 2];
				float sun = direct ? Math.max(0, nx * lx + ny * ly + nz * lz) * lit[h] : 0;
				int b = p.hitBounce[h];
				for (int c = 0; c < 3; ++c)
				{
					float e = light.caster[c] * sun + (last != null && b >= 0 ? last[b * 3 + c] * INCIDENT_GAIN : 0);
					radiance[h * 3 + c] = p.hitAlbedo[h * 3 + c] * e;
				}
			});
			parallel.run(GiProbes.PROBES, i ->
			{
				if (!p.valid[i])
				{
					return;
				}
				float[] d = GiProbes.DIRECTIONS[GiProbes.rotation(i)];
				float[] sr = skyRays[GiProbes.rotation(i)];
				int base = i * rays;
				float fr = 0, fg = 0, fb = 0, ur = 0, ug = 0, ub = 0;
				for (int r = 0; r < rays; ++r)
				{
					int code = p.rays[base + r];
					float w = GiProbes.WEIGHT[r];
					if (code == GiProbes.MISS)
					{
						// the sky is no surface: it counts toward the whole light but not the surfaces'
						fr += sr[r * 3] * w;
						fg += sr[r * 3 + 1] * w;
						fb += sr[r * 3 + 2] * w;
						continue;
					}
					if (code == GiProbes.BACK)
					{
						continue;
					}
					float tr, tg, tb;
					if (!p.hitTwoSided[code])
					{
						tr = radiance[code * 3];
						tg = radiance[code * 3 + 1];
						tb = radiance[code * 3 + 2];
					}
					else
					{
						// a thin surface shows the probe the side facing it
						float dx = d[r * 3], dy = d[r * 3 + 1], dz = d[r * 3 + 2];
						float sun = direct ? Math.max(0, -(dx * lx + dy * ly + dz * lz)) * lit[code] : 0;
						int b = p.hitBounce[code];
						float er = light.caster[0] * sun, eg = light.caster[1] * sun, eb = light.caster[2] * sun;
						if (last != null && b >= 0)
						{
							er += last[b * 3] * INCIDENT_GAIN;
							eg += last[b * 3 + 1] * INCIDENT_GAIN;
							eb += last[b * 3 + 2] * INCIDENT_GAIN;
						}
						tr = p.hitAlbedo[code * 3] * er;
						tg = p.hitAlbedo[code * 3 + 1] * eg;
						tb = p.hitAlbedo[code * 3 + 2] * eb;
					}
					fr += tr * w;
					fg += tg * w;
					fb += tb * w;
					ur += tr * w;
					ug += tg * w;
					ub += tb * w;
				}
				cur[i * 3] = fr * inv;
				cur[i * 3 + 1] = fg * inv;
				cur[i * 3 + 2] = fb * inv;
				if (curSurfaces != null)
				{
					curSurfaces[i * 3] = ur * inv;
					curSurfaces[i * 3 + 1] = ug * inv;
					curSurfaces[i * 3 + 2] = ub * inv;
				}
			});
			out = cur;
			surfaces = curSurfaces;
		}
		return new Result(out, bounceOnly ? surfaces : relative(out, open));
	}

	/** The whole light over what the open sky gives a probe, per channel; 1 everywhere when there is no sky light. */
	private static float[] relative(float[] light, float[] open)
	{
		float[] out = new float[light.length];
		float l = luminance(open[0], open[1], open[2]);
		if (l < 1e-5f)
		{
			java.util.Arrays.fill(out, 1);
			return out;
		}
		// a channel the sky has next to none of would otherwise blow up
		float[] den = {Math.max(open[0], l * 0.1f), Math.max(open[1], l * 0.1f), Math.max(open[2], l * 0.1f)};
		for (int i = 0; i < light.length; ++i)
		{
			out[i] = Math.min(MAX_RELATIVE, light[i] / den[i % 3]);
		}
		return out;
	}
}
