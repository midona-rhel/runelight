package com.midonarhel.runelight;

/**
 * Works out the colour the patches give each other, not the light: the global illumination here is a colour transfer,
 * the grass' green on the wall beside it, however dim the grass is. A patch emits its albedo's chroma, the albedo scaled
 * to a luminance of 1, times how lit it is (0 to 1: the sun or moon where a ray through the voxels reaches it, softened
 * by the overcast, plus a share of the sky it sees, so shaded surfaces bleed a little too). A dark green grass and a
 * bright white wall then give their colour equally strongly, and a gray one gives plain white, which tints nothing.
 * What a patch gathers is the average of what the patches linked to it emit, weighed by their form factors, times how
 * much of its view is other surfaces (the sum of the weights, scaled and held to 1): the colour of what it sees, as a
 * tint whose brightness is how lit that is and how much of it there is. The composite tints its light with it (see
 * light_occlusion.glsl giTint). Runs on the global illumination's worker whenever the sky or the light changes enough
 * (GlobalIllumination), a few milliseconds for a whole scene.
 */
final class GiRelight
{
	// the table gi_sky_frag.glsl draws: SKY_W x SKY_H of the sky's radiance, then one row of light, of which the light
	// that casts shadows (its first value) and the overcast's softening of shadows (the third and fourth) are used
	static final int SKY_W = 32, SKY_H = 16;
	static final int TABLE_FLOATS = SKY_W * (SKY_H + 1) * 4;
	// sun visibility rays reach this many voxels
	static final float SUN_DISTANCE = 128;
	// the sun's rays start this far (voxels) toward the light, as well as off the surface, so they clear what it is on
	static final float SUN_LIFT = 0.5f;
	// how lit a patch is by the open sky it faces, against by the sun facing it squarely
	static final float SKY_SHARE = 0.25f;
	// an albedo with less luminance than this has no chroma worth the name and emits white
	static final float MIN_LUMA = 0.01f;
	// no channel of the chroma is more than this, so a pure green does not give light that is all green
	static final float MAX_CHROMA = 2.5f;
	// a patch whose view is this share of the sum of its weights (the weights of a wall's lowest band beside open ground
	// sum to about 0.4) is wholly covered
	static final float COVERAGE_GAIN = 2.5f;

	/** What lights the patches this time. */
	static final class Light
	{
		final float[] caster = new float[3];
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
			int row = SKY_W * SKY_H * 4;
			for (int c = 0; c < 3; ++c)
			{
				l.caster[c] = finite(table[row + c]);
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

	static float luminance(float r, float g, float b)
	{
		return 0.2126f * r + 0.7152f * g + 0.0722f * b;
	}

	/**
	 * The colour (r, g, b) of luminance 1, into out[at..]: a colour with less luminance than MIN_LUMA is white, and one with
	 * a channel above MAX_CHROMA is moved toward white, keeping the luminance, until it is not.
	 */
	static void chroma(float r, float g, float b, float[] out, int at)
	{
		float l = luminance(r, g, b);
		if (l < MIN_LUMA)
		{
			out[at] = out[at + 1] = out[at + 2] = 1;
			return;
		}
		r /= l;
		g /= l;
		b /= l;
		float most = Math.max(r, Math.max(g, b));
		float k = most > MAX_CHROMA ? (MAX_CHROMA - 1) / (most - 1) : 1;
		out[at] = 1 + (r - 1) * k;
		out[at + 1] = 1 + (g - 1) * k;
		out[at + 2] = 1 + (b - 1) * k;
	}

	/**
	 * What every patch gathers, rgb per patch: the average colour of what it sees times how much of its view that is (see
	 * above), zero for the floors' and roofs' empty slots and for patches no other sees.
	 */
	static float[] relight(GiPatches p, Light light, GiPatches.ParallelFor parallel)
	{
		float lx = light.dir[0], ly = light.dir[1], lz = light.dir[2];
		boolean direct = light.bounce && luminance(light.caster[0], light.caster[1], light.caster[2]) > 1e-5f;

		// what each patch emits: its albedo's chroma times how lit it is
		float[] emitted = new float[p.count * 3];
		parallel.run(p.count, i ->
		{
			if (!p.exists(i))
			{
				return;
			}
			float nx = p.normal[i * 3], ny = p.normal[i * 3 + 1], nz = p.normal[i * 3 + 2];
			float lit = 0;
			float ndl = nx * lx + ny * ly + nz * lz;
			if (direct && ndl > 0)
			{
				// sun or moon, with the overcast's softening of shadows
				float[] o = new float[3];
				p.origin(i, o);
				boolean seen = p.grid.trace(o[0] + lx * SUN_LIFT, o[1] + ly * SUN_LIFT, o[2] + lz * SUN_LIFT, lx, ly, lz,
					SUN_DISTANCE, true, i) < 0;
				lit = ndl * (seen ? light.shadow1 : light.shadow0);
			}
			// the share of the directions it faces that see the sky
			int facing = 0, open = 0;
			for (int r = 0; r < GiPatches.SKY_SAMPLES; ++r)
			{
				float[] d = GiPatches.SKY_RAYS;
				if (d[r * 3] * nx + d[r * 3 + 1] * ny + d[r * 3 + 2] * nz >= GiPatches.SKY_MIN_COS)
				{
					facing++;
					open += (int) (p.sky[i] >>> r & 1);
				}
			}
			if (facing > 0)
			{
				lit += SKY_SHARE * open / facing;
			}
			lit = Math.min(1, lit);
			chroma(p.albedo[i * 3], p.albedo[i * 3 + 1], p.albedo[i * 3 + 2], emitted, i * 3);
			for (int c = 0; c < 3; ++c)
			{
				emitted[i * 3 + c] *= lit;
			}
		});

		// what each patch gathers: the average of what the patches it sees emit, as much of it as it sees
		float[] gathered = new float[p.count * 3];
		parallel.run(p.count, i ->
		{
			float sum = 0;
			for (int l = p.linkStart[i]; l < p.linkStart[i + 1]; ++l)
			{
				int j = p.linkTarget[l];
				float w = p.linkWeight[l];
				sum += w;
				for (int c = 0; c < 3; ++c)
				{
					gathered[i * 3 + c] += w * emitted[j * 3 + c];
				}
			}
			if (sum > 0)
			{
				float k = Math.min(1, sum * COVERAGE_GAIN) / sum;
				for (int c = 0; c < 3; ++c)
				{
					gathered[i * 3 + c] *= k;
				}
			}
		});
		return gathered;
	}
}
