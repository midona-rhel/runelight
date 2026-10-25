package com.midonarhel.runelight;

/**
 * Works out the colour the surfaces give the air around them, not the light: the global illumination here is a colour
 * transfer, the grass' green on the wall beside it, however dim the grass is. A face emits its surface's chroma, the
 * albedo scaled to a luminance of 1, times how lit it is (0 to 1: the sun or moon where a ray through the voxels reaches
 * it, softened by the overcast, plus a share of the sky it sees, so shaded surfaces bleed a little too). A dark green
 * grass and a bright white wall then give their colour equally strongly, and a gray one gives plain white, which tints
 * nothing. The air is given that colour, thinning out with the distance (GiEmitters), and what a voxel holds is the sum of
 * what it is given: the colour of what is near it, as a tint whose brightness is how lit that is and how near. The
 * composite tints its light with it (see light_occlusion.glsl giTint). Runs on the global illumination's worker whenever
 * the sky or the light changes enough (GlobalIllumination), tens of milliseconds for a whole scene.
 *
 * The result is the texels of the volume the shader reads, see texels.
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
	// how lit a face is by the open sky it faces, against by the sun facing it squarely
	static final float SKY_SHARE = 0.25f;
	// an albedo with less luminance than this has no chroma worth the name and emits white
	static final float MIN_LUMA = 0.01f;
	// no channel of the chroma is more than this, so a pure green does not give light that is all green
	static final float MAX_CHROMA = 2.5f;
	// A volume texel's red, green and blue are the square root of the sum of colour a voxel is given, over this, so the
	// small sums at the edge of the reach have a byte to themselves; 1 over this is the greatest they reach.
	static final float TEXEL_RANGE = 4;

	/** What lights the faces this time. */
	static final class Light
	{
		final float[] caster = new float[3];
		float shadow0 = 0, shadow1 = 1;
		final float[] dir = new float[3];
		boolean bounce = true;

		/**
		 * @param table gi_sky_frag.glsl's output, rgba floats row by row
		 * @param casterDir toward the light that casts shadows
		 * @param bounce whether the sun or moon lights the faces; if not, only the sky does
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
	 * The volume's texels, RGBA bytes. The grid's voxels x fastest, then z, then y, as the grid holds them, twice: first
	 * the field of the colour flat surfaces give (floors, roofs), then the one of the standing ones'; y is the layer, so
	 * the field of the standing surfaces' starts at layer ny. rgb is the square root of the sum of colour a voxel is
	 * given, over TEXEL_RANGE, times 255, and a is 255 where a voxel is given any of the field's and 0 where not: the
	 * lookup reads between texels and takes the average of the ones that are (gi.glsl).
	 */
	static byte[] relight(GiEmitters e, Light light, GiEmitters.ParallelFor parallel)
	{
		float lx = light.dir[0], ly = light.dir[1], lz = light.dir[2];
		boolean direct = light.bounce && luminance(light.caster[0], light.caster[1], light.caster[2]) > 1e-5f;
		GiGrid grid = e.grid;

		// what each face emits: its surface's chroma times how lit it is
		float[] emitted = new float[e.faceCount * 3];
		parallel.run(e.faceCount, f ->
		{
			float nx = e.faceNormal[f * 3], ny = e.faceNormal[f * 3 + 1], nz = e.faceNormal[f * 3 + 2];
			float lit = 0;
			float ndl = nx * lx + ny * ly + nz * lz;
			if (direct && ndl > 0)
			{
				// sun or moon, with the overcast's softening of shadows, from the middle of the voxel the face looks into
				int c = e.faceCell[f];
				float ox = grid.cellX(c) + 0.5f + lx * SUN_LIFT, oy = grid.cellY(c) + 0.5f + ly * SUN_LIFT;
				float oz = grid.cellZ(c) + 0.5f + lz * SUN_LIFT;
				boolean seen = grid.trace(ox, oy, oz, lx, ly, lz, SUN_DISTANCE, true, f) < 0;
				lit = ndl * (seen ? light.shadow1 : light.shadow0);
			}
			lit = Math.min(1, lit + SKY_SHARE * e.faceSky[f]);
			for (int c = 0; c < 3; ++c)
			{
				emitted[f * 3 + c] = e.faceChroma[f * 3 + c] * lit;
			}
		});

		// what each voxel is given: the sum over the faces that reach it
		int cells = grid.cells.length;
		byte[] texels = new byte[cells * 2 * 4];
		parallel.run(e.cellCount, i ->
		{
			int at = e.cellIndex[i] * 4;
			float[] flat = new float[3], standing = new float[3];
			boolean hasFlat = false, hasStanding = false;
			for (int k = e.inStart[i]; k < e.inStart[i + 1]; ++k)
			{
				int f = e.inFace[k];
				float wf = e.inFlat[k], ws = e.inStanding[k];
				hasFlat |= wf > 0;
				hasStanding |= ws > 0;
				for (int c = 0; c < 3; ++c)
				{
					flat[c] += wf * emitted[f * 3 + c];
					standing[c] += ws * emitted[f * 3 + c];
				}
			}
			encode(texels, at, flat, hasFlat);
			encode(texels, at + cells * 4, standing, hasStanding);
		});
		return texels;
	}

	private static void encode(byte[] texels, int at, float[] sum, boolean given)
	{
		if (!given)
		{
			return;
		}
		for (int c = 0; c < 3; ++c)
		{
			texels[at + c] = (byte) Math.round(Math.sqrt(Math.max(0, Math.min(1, sum[c] / TEXEL_RANGE))) * 255);
		}
		texels[at + 3] = (byte) 255;
	}

	/** What a texel's rgb holds: the sum of colour, out of the square root over TEXEL_RANGE. */
	static float decode(byte texel)
	{
		float u = (texel & 0xff) / 255f;
		return u * u * TEXEL_RANGE;
	}

	/**
	 * How many of the volume's voxels are tinted by more than 0.05 of luminance, in either field, and the greatest luminance
	 * of any, for the log.
	 */
	static float[] stats(GiEmitters e, byte[] texels)
	{
		int cells = e.grid.cells.length;
		int tinted = 0;
		float most = 0;
		for (int i = 0; i < e.cellCount; ++i)
		{
			for (int field = 0; field < 2; ++field)
			{
				int at = (e.cellIndex[i] + field * cells) * 4;
				float y = luminance(decode(texels[at]), decode(texels[at + 1]), decode(texels[at + 2]));
				tinted += y > 0.05f ? 1 : 0;
				most = Math.max(most, y);
			}
		}
		return new float[]{tinted, most};
	}
}
