package com.midonarhel.runelight;

/**
 * Ray directions and first order spherical harmonics for the irradiance probes (GlobalIllumination).
 *
 * A probe stores, per color channel, the irradiance it sees as e0 + e . n (gi.glsl): the radiance its rays
 * gathered, projected onto the constant and linear harmonics and convolved with the cosine lobe, scaled so that
 * light arriving evenly from everywhere at radiance L gives L whichever way a surface faces. That is the
 * composite's unit for light (1 = the surface's full color). With N equally weighted rays this works out to
 * e0 = mean(L) and e = 2 mean(L d).
 */
final class GiSh
{
	static final int RAYS = 64;
	// probes rotate their rays about the vertical by one of this many angles, so neighbours sample between each
	// other's directions instead of all missing the same thin gap
	static final int ROTATIONS = 16;

	private GiSh()
	{
	}

	/**
	 * Spherical Fibonacci directions: count unit vectors spread evenly over the sphere, xyz interleaved.
	 */
	static float[] fibonacci(int count)
	{
		float[] d = new float[count * 3];
		double golden = Math.PI * (3 - Math.sqrt(5));
		for (int i = 0; i < count; ++i)
		{
			double y = 1 - (i + 0.5) * 2.0 / count;
			double r = Math.sqrt(Math.max(0, 1 - y * y));
			double a = golden * i;
			d[i * 3] = (float) (Math.cos(a) * r);
			d[i * 3 + 1] = (float) y;
			d[i * 3 + 2] = (float) (Math.sin(a) * r);
		}
		return d;
	}

	/**
	 * The ray set of every rotation, [rotation][ray * 3 + axis]: the Fibonacci set turned about y.
	 */
	static float[][] rotatedRays()
	{
		float[] base = fibonacci(RAYS);
		float[][] out = new float[ROTATIONS][RAYS * 3];
		for (int r = 0; r < ROTATIONS; ++r)
		{
			double a = 2 * Math.PI * r / ROTATIONS;
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			for (int i = 0; i < RAYS; ++i)
			{
				float x = base[i * 3], y = base[i * 3 + 1], z = base[i * 3 + 2];
				out[r][i * 3] = c * x - s * z;
				out[r][i * 3 + 1] = y;
				out[r][i * 3 + 2] = s * x + c * z;
			}
		}
		return out;
	}

	/** The rotation a probe uses: a hash, so the pattern doesn't line up across the grid. */
	static int rotation(int probe)
	{
		int h = probe * 0x9E3779B1;
		h ^= h >>> 15;
		h *= 0x85EBCA77;
		h ^= h >>> 13;
		return h & (ROTATIONS - 1);
	}

	/**
	 * Accumulates one probe's rays into irradiance coefficients.
	 */
	static final class Accumulator
	{
		// per channel: sum L, sum L dx, sum L dy, sum L dz
		final float[] sum = new float[12];
		int rays;

		void clear()
		{
			java.util.Arrays.fill(sum, 0);
			rays = 0;
		}

		void add(float dx, float dy, float dz, float r, float g, float b)
		{
			add(0, r, dx, dy, dz);
			add(4, g, dx, dy, dz);
			add(8, b, dx, dy, dz);
			rays++;
		}

		/** A ray that brought no light still counts toward the average. */
		void addDark()
		{
			rays++;
		}

		private void add(int o, float l, float dx, float dy, float dz)
		{
			sum[o] += l;
			sum[o + 1] += l * dx;
			sum[o + 2] += l * dy;
			sum[o + 3] += l * dz;
		}

		/** Writes the 12 coefficients (e0, ex, ey, ez for r, g, b) at out[offset]. */
		void finish(float[] out, int offset)
		{
			float inv = rays > 0 ? 1f / rays : 0;
			for (int c = 0; c < 3; ++c)
			{
				out[offset + c * 4] = sum[c * 4] * inv;
				out[offset + c * 4 + 1] = 2 * sum[c * 4 + 1] * inv;
				out[offset + c * 4 + 2] = 2 * sum[c * 4 + 2] * inv;
				out[offset + c * 4 + 3] = 2 * sum[c * 4 + 3] * inv;
			}
		}
	}

	/** Irradiance of channel c (0 r, 1 g, 2 b) toward normal n, clamped at 0 like gi.glsl. */
	static float eval(float[] sh, int offset, int c, float nx, float ny, float nz)
	{
		int o = offset + c * 4;
		return Math.max(0, sh[o] + sh[o + 1] * nx + sh[o + 2] * ny + sh[o + 3] * nz);
	}

	/** IEEE half float bits of f, rounded to nearest; for the probe textures. */
	static short toHalf(float f)
	{
		int bits = Float.floatToIntBits(f);
		int sign = bits >>> 16 & 0x8000;
		int exp = (bits >>> 23 & 0xff) - 127 + 15;
		int mant = bits & 0x7fffff;
		if (Float.isNaN(f))
		{
			return (short) (sign | 0x7e00);
		}
		if (exp >= 31)
		{
			return (short) (sign | 0x7c00);
		}
		if (exp <= 0)
		{
			if (exp < -10)
			{
				return (short) sign;
			}
			mant |= 0x800000;
			int shift = 14 - exp;
			int half = mant >> shift;
			if ((mant >> (shift - 1) & 1) != 0)
			{
				half++;
			}
			return (short) (sign | half);
		}
		int half = sign | exp << 10 | mant >> 13;
		if ((mant & 0x1000) != 0)
		{
			// rounding up may carry into the exponent, which is still correct (up to infinity)
			half++;
		}
		return (short) half;
	}

	static float fromHalf(short h)
	{
		int s = (h & 0x8000) << 16;
		int e = h >> 10 & 0x1f;
		int m = h & 0x3ff;
		if (e == 0)
		{
			float v = m / 1024f / 16384f;
			return s != 0 ? -v : v;
		}
		if (e == 31)
		{
			return Float.intBitsToFloat(s | 0x7f800000 | m << 13);
		}
		return Float.intBitsToFloat(s | (e - 15 + 127) << 23 | m << 13);
	}
}
