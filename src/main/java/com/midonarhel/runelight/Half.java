package com.midonarhel.runelight;

/** IEEE half floats, for the sky grid's texels. */
final class Half
{
	private Half()
	{
	}

	/** The half float bits of f, rounded to nearest. */
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
