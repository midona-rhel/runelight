package com.midonarhel.runelight;

/**
 * Lights the patches (GiPatches): radiosity, one bounce. Every patch is lit directly, by the sun or moon where a ray
 * through the voxels reaches it (softened by the overcast, as the composite softens shadows) and by the sky it sees,
 * and sends on albedo times that light, the light it leaves with. What a patch gathers is the sum of what the patches
 * linked to it leave with, each weighed by its form factor: the light that reached it by bouncing, which is all that is
 * sent to the GPU, since the composite lights the surface with the sun and sky itself. Surfaces are treated as rough,
 * scattering light everywhere. Runs on the global illumination's worker whenever the sky or the light changes enough
 * (GlobalIllumination), a few tens of milliseconds for a whole scene.
 *
 * The sky is scaled so that a patch under the open sky, facing up, gets what the composite's plain ambient and sky light
 * give a surface facing up.
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
	// the sun's rays start this far (voxels) toward the light, as well as off the surface, so they clear what it is on
	static final float SUN_LIFT = 0.5f;

	/** What lights the patches this time. */
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
		 * @param bounce whether the sun or moon lights the patches; if not, only the sky does
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
	 * How much the table's sky radiance is scaled: the irradiance on a patch facing up, under the open sky, matches the
	 * old ambient plus sky light facing up. The irradiance on a patch, in the units of the radiance a uniform sky would
	 * have, is four times the mean over the sphere's directions of radiance times the cosine with its normal, for the
	 * ones above its horizon.
	 */
	static float skyScale(Light light)
	{
		float[] d = GiPatches.SKY_RAYS;
		float[] rgb = new float[3];
		float open = 0;
		for (int i = 0; i < GiPatches.SKY_SAMPLES; ++i)
		{
			float up = -d[i * 3 + 1];
			if (up >= GiPatches.SKY_MIN_COS)
			{
				sky(light.sky, d[i * 3], d[i * 3 + 1], d[i * 3 + 2], rgb);
				open += luminance(rgb[0], rgb[1], rgb[2]) * up;
			}
		}
		open *= 4f / GiPatches.SKY_SAMPLES;
		float target = luminance(light.oldUp[0], light.oldUp[1], light.oldUp[2]);
		if (open < 1e-6f || target <= 0)
		{
			return 1;
		}
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, target / open));
	}

	/**
	 * The light every patch gathers, rgb per patch: zero for the floors' empty slots, and for patches no other sees.
	 */
	static float[] relight(GiPatches p, Light light, GiPatches.ParallelFor parallel)
	{
		float k = skyScale(light) * 4f / GiPatches.SKY_SAMPLES;
		// the sky's radiance toward each of the directions patches look for it in, scaled by the factor irradiance has
		float[] skyRays = new float[GiPatches.SKY_SAMPLES * 3];
		float[] rgb = new float[3];
		for (int r = 0; r < GiPatches.SKY_SAMPLES; ++r)
		{
			float[] d = GiPatches.SKY_RAYS;
			sky(light.sky, d[r * 3], d[r * 3 + 1], d[r * 3 + 2], rgb);
			for (int c = 0; c < 3; ++c)
			{
				skyRays[r * 3 + c] = rgb[c] * k;
			}
		}
		float lx = light.dir[0], ly = light.dir[1], lz = light.dir[2];
		boolean direct = light.bounce && luminance(light.caster[0], light.caster[1], light.caster[2]) > 1e-5f;

		// the light leaving each patch: its albedo times the light on it
		float[] leaving = new float[p.count * 3];
		parallel.run(p.count, i ->
		{
			if (!p.exists(i))
			{
				return;
			}
			float nx = p.normal[i * 3], ny = p.normal[i * 3 + 1], nz = p.normal[i * 3 + 2];
			float on = 0;
			float ndl = nx * lx + ny * ly + nz * lz;
			if (direct && ndl > 0)
			{
				// sun or moon, with the overcast's softening of shadows
				float[] o = new float[3];
				p.origin(i, o);
				boolean seen = p.grid.trace(o[0] + lx * SUN_LIFT, o[1] + ly * SUN_LIFT, o[2] + lz * SUN_LIFT, lx, ly, lz,
					SUN_DISTANCE, true, i) < 0;
				on = ndl * (seen ? light.shadow1 : light.shadow0);
			}
			float[] e = {light.caster[0] * on, light.caster[1] * on, light.caster[2] * on};
			// the sky in the directions the patch sees it in
			long open = p.sky[i];
			for (int r = 0; open != 0; ++r, open >>>= 1)
			{
				if ((open & 1) != 0)
				{
					float cos = GiPatches.SKY_RAYS[r * 3] * nx + GiPatches.SKY_RAYS[r * 3 + 1] * ny
						+ GiPatches.SKY_RAYS[r * 3 + 2] * nz;
					for (int c = 0; c < 3; ++c)
					{
						e[c] += skyRays[r * 3 + c] * cos;
					}
				}
			}
			for (int c = 0; c < 3; ++c)
			{
				leaving[i * 3 + c] = p.albedo[i * 3 + c] * e[c];
			}
		});

		// the light each patch gathers
		float[] gathered = new float[p.count * 3];
		parallel.run(p.count, i ->
		{
			for (int l = p.linkStart[i]; l < p.linkStart[i + 1]; ++l)
			{
				int j = p.linkTarget[l];
				float w = p.linkWeight[l];
				for (int c = 0; c < 3; ++c)
				{
					gathered[i * 3 + c] += w * leaving[j * 3 + c];
				}
			}
		});
		return gathered;
	}
}
