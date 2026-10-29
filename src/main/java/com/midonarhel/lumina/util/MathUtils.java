/*
 * Math utility functions.
 * Written in 2025 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.midonarhel.lumina.util;

/**
 * The subset of 117 HD's MathUtils that the ported sky and lighting code uses, with the same definitions (array
 * functions write {@code out.length} elements and wrap their inputs).
 */
public final class MathUtils
{
	public static final float PI = (float) Math.PI;
	public static final float TWO_PI = PI * 2;
	public static final float DEG_TO_RAD = PI / 180;
	public static final float RAD_TO_DEG = 180 / PI;

	private MathUtils()
	{
	}

	public static float sin(float v)
	{
		return (float) Math.sin(v);
	}

	public static float cos(float v)
	{
		return (float) Math.cos(v);
	}

	public static float acos(float v)
	{
		return (float) Math.acos(v);
	}

	public static float sqrt(float v)
	{
		return (float) Math.sqrt(v);
	}

	public static float exp(float v)
	{
		return (float) Math.exp(v);
	}

	public static float pow(float v, float e)
	{
		return (float) Math.pow(v, e);
	}

	public static float pow2(float v)
	{
		return v * v;
	}

	public static float abs(float v)
	{
		return Math.abs(v);
	}

	public static float max(float a, float b)
	{
		return Math.max(a, b);
	}

	public static float min(float a, float b)
	{
		return Math.min(a, b);
	}

	public static float floor(float v)
	{
		return (float) Math.floor(v);
	}

	public static double fract(double v)
	{
		return v - Math.floor(v);
	}

	public static float fract(float v)
	{
		return v - floor(v);
	}

	public static float mod(float x, float modulus)
	{
		return (x % modulus + modulus) % modulus;
	}

	public static float clamp(float v, float min, float max)
	{
		return Math.min(Math.max(v, min), max);
	}

	public static float saturate(float v)
	{
		return clamp(v, 0, 1);
	}

	public static float mix(float v0, float v1, float factor)
	{
		return v0 * (1 - factor) + v1 * factor;
	}

	public static float[] mix(float[] out, float[] v0, float[] v1, float factor)
	{
		for (int i = 0; i < out.length; i++)
		{
			out[i] = mix(v0[i % v0.length], v1[i % v1.length], factor);
		}
		return out;
	}

	public static float smoothstep(float v0, float v1, float factor)
	{
		float t = saturate((factor - v0) / (v1 - v0));
		return t * t * (3 - 2 * t);
	}

	public static float angleDiff(float a, float b)
	{
		return mod(a - b + PI, TWO_PI) - PI;
	}

	public static float[] copy(float[] v)
	{
		return v.clone();
	}

	public static float[] copyTo(float[] out, float[] v)
	{
		System.arraycopy(v, 0, out, 0, Math.min(out.length, v.length));
		return out;
	}

	public static float[] multiply(float[] v, float factor)
	{
		return multiply(new float[v.length], v, factor);
	}

	public static float[] multiply(float[] out, float[] v, float factor)
	{
		for (int i = 0; i < out.length; i++)
		{
			out[i] = v[i % v.length] * factor;
		}
		return out;
	}

	public static float[] multiply(float[] out, float[] a, float[] b)
	{
		for (int i = 0; i < out.length; i++)
		{
			out[i] = a[i % a.length] * b[i % b.length];
		}
		return out;
	}

	public static float[] divide(float[] out, float[] v, float divisor)
	{
		for (int i = 0; i < out.length; i++)
		{
			// as 117 HD: dividing by zero gives zero, e.g. an environment without sun light
			out[i] = divisor == 0 ? 0 : v[i % v.length] / divisor;
		}
		return out;
	}

	public static float[] add(float[] out, float[] a, float[] b)
	{
		for (int i = 0; i < out.length; i++)
		{
			out[i] = a[i % a.length] + b[i % b.length];
		}
		return out;
	}

	public static float dot(float[] a, float[] b)
	{
		float sum = 0;
		for (int i = 0; i < Math.min(a.length, b.length); i++)
		{
			sum += a[i] * b[i];
		}
		return sum;
	}

	public static float[] normalize(float[] out, float[] v)
	{
		float length = sqrt(dot(v, v));
		for (int i = 0; i < out.length; i++)
		{
			out[i] = v[i % v.length] / length;
		}
		return out;
	}

	public static float[] cross(float[] out, float[] a, float[] b)
	{
		float x = a[1] * b[2] - a[2] * b[1];
		float y = a[2] * b[0] - a[0] * b[2];
		float z = a[0] * b[1] - a[1] * b[0];
		out[0] = x;
		out[1] = y;
		out[2] = z;
		return out;
	}

	public static float[] ensureArrayLength(float[] array, int targetLength)
	{
		if (array.length == targetLength)
		{
			return array;
		}
		float[] result = new float[targetLength];
		System.arraycopy(array, 0, result, 0, Math.min(targetLength, array.length));
		return result;
	}

	/**
	 * 117 HD's MathUtils.float16: a float as IEEE half-float bits, rounding to nearest even.
	 */
	public static int float16(float value)
	{
		if (value == 0)
		{
			return 0;
		}
		// float32: (-1)^sign * 2^(exponent - 127) * (1.mantissa)
		// float16: (-1)^sign * 2^(exponent -  15) * (1.mantissa)
		int f = Float.floatToRawIntBits(value);
		int sign = (f >>> 16) & 0x8000;
		int exponent = ((f >>> 23) & 0xFF) - 127 + 15;
		int mantissa = f & 0x7FFFFF;

		if (exponent <= 0)
		{
			// Too small, subnormal
			if (exponent < -10)
			{
				// To small to represent, return signed zero
				return sign;
			}
			mantissa |= 0x800000; // Add the leading 1 back in
			mantissa >>= 1 - exponent; // Shift to represent the smaller exponent
			// Round based on the last bit, before shifting it away
			if ((mantissa & 0x1000) != 0)
			{
				mantissa += 0x2000;
			}
			return sign | mantissa >> 13;
		}

		if (exponent >= 0x1F)
		{
			// Too large to represent
			if (mantissa == 0)
			{
				return sign | 0x7C00; // Infinity
			}
			return 0x7E00; // NaN
		}

		// Round based on the last bit, before shifting it away
		if ((mantissa & 0x1000) != 0)
		{
			// Round to nearest even
			mantissa += 0x2000;
			// If rounding up caused the mantissa to overflow, increment the exponent
			if ((mantissa & 0x800000) != 0)
			{
				mantissa = 0;
				exponent += 1;
				if (exponent >= 0x1F)
				{
					// Return infinity if it's too large to represent again
					return sign | 0x7C00;
				}
				return sign | exponent << 10;
			}
		}

		return sign | exponent << 10 | mantissa >> 13;
	}
}
