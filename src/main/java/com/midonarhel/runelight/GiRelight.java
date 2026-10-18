package com.midonarhel.runelight;

/**
 * Lights the probes from their hit cache (GiProbes): rays that reached the sky bring the sky's radiance where they
 * point, rays that hit a surface bring its albedo times the light on it, which is the sun or moon where the voxels
 * see it, plus the probes' previous light there for further bounces. Runs on the global illumination's worker
 * whenever the sky or the light changes enough (GlobalIllumination), tens of milliseconds for a whole scene.
 *
 * The sky part is scaled so that a probe under the open sky lights a surface facing up as much as the composite's
 * plain ambient and sky light do: the probes move that light around (less of it under roofs and overhangs, the
 * sky's colors on the walls facing them, light bounced off sunlit ground) rather than changing the exposure.
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
	 * How much the table's sky radiance is scaled: an open probe's irradiance facing up matches the old ambient
	 * plus sky light facing up.
	 */
	static float skyScale(Light light, float[][] directions)
	{
		GiSh.Accumulator acc = new GiSh.Accumulator();
		float[] rgb = new float[3];
		float[] d = directions[0];
		for (int i = 0; i < GiSh.RAYS; ++i)
		{
			sky(light.sky, d[i * 3], d[i * 3 + 1], d[i * 3 + 2], rgb);
			acc.add(d[i * 3], d[i * 3 + 1], d[i * 3 + 2], rgb[0], rgb[1], rgb[2]);
		}
		float[] sh = new float[12];
		acc.finish(sh, 0);
		float open = luminance(GiSh.eval(sh, 0, 0, 0, -1, 0), GiSh.eval(sh, 0, 1, 0, -1, 0), GiSh.eval(sh, 0, 2, 0, -1, 0));
		float target = luminance(light.oldUp[0], light.oldUp[1], light.oldUp[2]);
		if (open < 1e-6f || target <= 0)
		{
			return 1;
		}
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, target / open));
	}

	/**
	 * Light every valid probe. prev: the probes' last light for the bounces (null for none yet); each further
	 * iteration bounces once more from the one before.
	 *
	 * @return 12 floats per probe (GiSh)
	 */
	static float[] relight(GiProbes p, Light light, float[] prev, int iterations, GiProbes.ParallelFor parallel)
	{
		float k = skyScale(light, p.directions);
		float[][] skyRays = new float[GiSh.ROTATIONS][GiSh.RAYS * 3];
		float[] rgb = new float[3];
		for (int r = 0; r < GiSh.ROTATIONS; ++r)
		{
			float[] d = p.directions[r];
			for (int i = 0; i < GiSh.RAYS; ++i)
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
		for (int it = 0; it < Math.max(1, iterations); ++it)
		{
			float[] last = out;
			float[] cur = new float[GiProbes.PROBES * 12];
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
					float e = light.caster[c] * sun + (last != null && b >= 0 ? GiSh.eval(last, b * 12, c, nx, ny, nz) : 0);
					radiance[h * 3 + c] = p.hitAlbedo[h * 3 + c] * e;
				}
			});
			parallel.run(GiProbes.PROBES, i ->
			{
				if (!p.valid[i])
				{
					return;
				}
				GiSh.Accumulator acc = new GiSh.Accumulator();
				int rot = GiSh.rotation(i);
				float[] d = p.directions[rot];
				float[] sr = skyRays[rot];
				int base = i * GiSh.RAYS;
				for (int r = 0; r < GiSh.RAYS; ++r)
				{
					int code = p.rays[base + r];
					float dx = d[r * 3], dy = d[r * 3 + 1], dz = d[r * 3 + 2];
					if (code == GiProbes.MISS)
					{
						acc.add(dx, dy, dz, sr[r * 3], sr[r * 3 + 1], sr[r * 3 + 2]);
					}
					else if (code == GiProbes.BACK)
					{
						acc.addDark();
					}
					else if (!p.hitTwoSided[code])
					{
						acc.add(dx, dy, dz, radiance[code * 3], radiance[code * 3 + 1], radiance[code * 3 + 2]);
					}
					else
					{
						// a thin surface shows the probe the side facing it
						float sun = direct ? Math.max(0, -(dx * lx + dy * ly + dz * lz)) * lit[code] : 0;
						int b = p.hitBounce[code];
						float er = light.caster[0] * sun, eg = light.caster[1] * sun, eb = light.caster[2] * sun;
						if (last != null && b >= 0)
						{
							er += GiSh.eval(last, b * 12, 0, -dx, -dy, -dz);
							eg += GiSh.eval(last, b * 12, 1, -dx, -dy, -dz);
							eb += GiSh.eval(last, b * 12, 2, -dx, -dy, -dz);
						}
						acc.add(dx, dy, dz, p.hitAlbedo[code * 3] * er, p.hitAlbedo[code * 3 + 1] * eg, p.hitAlbedo[code * 3 + 2] * eb);
					}
				}
				acc.finish(cur, i * 12);
			});
			out = cur;
		}
		return out;
	}
}
