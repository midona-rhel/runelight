package com.midonarhel.runelight;

/**
 * Lights the probes from their hit cache (GiProbes): rays that reached the sky bring the sky's radiance where they
 * point, rays that hit a surface bring its albedo times the RGB light on it, which is the sun or moon where the voxels
 * see it, plus the light the probe in front of the voxel had last time, for further bounces. A probe's color is the
 * weighted average of its rays, whichever way they point: surfaces are treated as rough, scattering light everywhere,
 * so no surface gets more from one that faces it than from any other. Runs on the global illumination's worker
 * whenever the sky or the light changes enough (GlobalIllumination), tens of milliseconds for a whole scene.
 *
 * The sky is part of the light the further bounces build on (scaled so that a probe under the open sky gets what the
 * composite's plain ambient and sky light give a surface facing up), but what is shown is the light of the rays that
 * hit surfaces alone: the composite adds that to its skylight, which already has the sky's own.
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
	// Do not amplify energy on successive bounces.
	static final float INCIDENT_GAIN = 1.0f;

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
		/** What goes to the GPU: the light the rays that hit surfaces bring alone, in the composite's units. */
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

	/** Preserve the incident light's color through each diffuse bounce. */
	static float incident(float[] caster, float sun, float[] last, int b, int channel)
	{
		return caster[channel] * sun + (last != null && b >= 0 ? last[b * 3 + channel] * INCIDENT_GAIN : 0);
	}

	/**
	 * Light every valid probe. prev: the probes' last whole light for the bounces (null for none yet); each further
	 * iteration bounces once more from the one before.
	 *
	 */
	static Result relight(GiProbes p, Light light, float[] prev, int iterations, GiProbes.ParallelFor parallel)
	{
		float k = skyScale(light);
		int rays = GiProbes.RAYS;
		float[][] skyRays = new float[GiProbes.ROTATIONS][rays * 3];
		float[] rgb = new float[3];
		for (int r = 0; r < GiProbes.ROTATIONS; ++r)
		{
			float[] d = GiProbes.DIRECTIONS[r];
			for (int i = 0; i < rays; ++i)
			{
				sky(light.sky, d[i * 3], d[i * 3 + 1], d[i * 3 + 2], rgb);
				for (int c = 0; c < 3; ++c)
				{
					skyRays[r][i * 3 + c] = rgb[c] * k;
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
			float[] curSurfaces = new float[GiProbes.PROBES * 3];
			// light leaving each one-sided hit voxel
			parallel.run(p.hits, h ->
			{
				if (p.hitTwoSided[h])
				{
					return;
				}
				float nx = p.hitNormal[h * 3], ny = p.hitNormal[h * 3 + 1], nz = p.hitNormal[h * 3 + 2];
				// lit or not, whatever angle the light comes in at: the bounce is the color of what is lit around
				float sun = direct && nx * lx + ny * ly + nz * lz > 0 ? lit[h] : 0;
				int b = p.hitBounce[h];

				for (int c = 0; c < 3; ++c)
				{
					radiance[h * 3 + c] = p.hitAlbedo[h * 3 + c] * incident(light.caster, sun, last, b, c);
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
						float sun = direct && dx * lx + dy * ly + dz * lz < 0 ? lit[code] : 0;
						int b = p.hitBounce[code];
						tr = p.hitAlbedo[code * 3] * incident(light.caster, sun, last, b, 0);
						tg = p.hitAlbedo[code * 3 + 1] * incident(light.caster, sun, last, b, 1);
						tb = p.hitAlbedo[code * 3 + 2] * incident(light.caster, sun, last, b, 2);
					}
					// A smooth local-bounce window, reaching zero at the ray limit (voxel units).
					float dx = p.hitPos[code * 3] - p.position[i * 3] / GiGrid.VOXEL;
					float dy = p.hitPos[code * 3 + 1] + p.grid.layer0 - p.position[i * 3 + 1] / GiGrid.VOXEL;
					float dz = p.hitPos[code * 3 + 2] - p.position[i * 3 + 2] / GiGrid.VOXEL;
					float t = Math.min(1, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / GiProbes.MAX_DISTANCE);
					w *= 1 - t * t * (3 - 2 * t);
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
				curSurfaces[i * 3] = ur * inv;
				curSurfaces[i * 3 + 1] = ug * inv;
				curSurfaces[i * 3 + 2] = ub * inv;
			});
			out = cur;
			surfaces = curSurfaces;
		}
		return new Result(out, surfaces);
	}

}
