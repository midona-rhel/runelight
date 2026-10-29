/*
 * Color utility functions
 * Written in 2023 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.midonarhel.lumina.util;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.awt.Color;
import java.io.IOException;

/**
 * The colour helpers from 117 HD's ColorUtils that the environment data needs.
 */
public final class ColorUtils
{
	public static final float[] LINEAR_SRGB_LUMINANCE_COEFFICIENTS = {.2126f, .7152f, .0722f};

	// Approximate mesopic vision
	public static final float[] MESOPIC_TINT = {.85f, .95f, 1.15f};

	private static final float HAZE_OPTICAL_DEPTH = .08f;
	// Reference Rayleigh extinction plus neutral haze; independent of authored environment lighting.
	public static final float[] ATMOSPHERIC_OPTICAL_DEPTH = {
		(float) Math.log(1 + 2 * .026f) + HAZE_OPTICAL_DEPTH,
		(float) Math.log(1 + 2 * .053f) + HAZE_OPTICAL_DEPTH,
		(float) Math.log(1 + 2 * .136f) + HAZE_OPTICAL_DEPTH
	};

	private ColorUtils()
	{
	}

	// Conversion functions to and from sRGB and linear color space.
	// The implementation is based on the sRGB EOTF given in the Khronos Data Format Specification.
	// Source: https://web.archive.org/web/20220808015852/https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.pdf
	// Page number 130 (146 in the PDF)
	public static float srgbToLinear(float c)
	{
		return c <= 0.04045f ?
			c / 12.92f :
			(float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
	}

	public static float[] srgbToLinear(float... c)
	{
		float[] result = new float[c.length];
		for (int i = 0; i < c.length; i++)
		{
			result[i] = srgbToLinear(c[i]);
		}
		return result;
	}

	/**
	 * Calculate the linear luminance of a linear sRGB color.
	 */
	public static float linearSrgbLuminance(float[] linearSrgb)
	{
		return linearSrgb[0] * LINEAR_SRGB_LUMINANCE_COEFFICIENTS[0]
			+ linearSrgb[1] * LINEAR_SRGB_LUMINANCE_COEFFICIENTS[1]
			+ linearSrgb[2] * LINEAR_SRGB_LUMINANCE_COEFFICIENTS[2];
	}

	/**
	 * Apply an approximate color shift due to mesopic vision, in linear sRGB, preserving luminance.
	 */
	public static void applyMesopicShift(float[] out)
	{
		float luminance = linearSrgbLuminance(out);
		for (int i = 0; i < 3; i++)
		{
			out[i] = out[i] * MESOPIC_TINT[i];
		}
		float shiftedLuminance = linearSrgbLuminance(out);
		if (shiftedLuminance > 0)
		{
			MathUtils.multiply(out, out, luminance / shiftedLuminance);
		}
	}

	/**
	 * Approximately invert the color shift due to mesopic vision, in linear sRGB, preserving luminance.
	 */
	public static void invertMesopicShift(float[] out)
	{
		float luminance = linearSrgbLuminance(out);
		for (int i = 0; i < 3; i++)
		{
			out[i] = out[i] / MESOPIC_TINT[i];
		}
		float shiftedLuminance = linearSrgbLuminance(out);
		if (shiftedLuminance > 0)
		{
			MathUtils.multiply(out, out, luminance / shiftedLuminance);
		}
	}

	private static final float[] XYZ_TO_RGB_MATRIX = {
		3.24097f, -1.5373832f, -.49861076f,
		-.96924365f, 1.8759675f, .041555058f,
		.05563008f, -.20397696f, 1.0569715f
	};

	/**
	 * Approximate linear RGB from a color temperature in degrees Kelvin (Krystek 1985, via CIE 1960 UCS).
	 */
	public static float[] colorTemperatureToLinearRgb(double kelvin)
	{
		// UV coordinates in CIE 1960 UCS color space
		double[] uv = new double[]{
			(0.860117757 + 1.54118254e-4 * kelvin + 1.28641212e-7 * kelvin * kelvin)
				/ (1 + 8.42420235e-4 * kelvin + 7.08145163e-7 * kelvin * kelvin),
			(0.317398726 + 4.22806245e-5 * kelvin + 4.20481691e-8 * kelvin * kelvin)
				/ (1 - 2.89741816e-5 * kelvin + 1.61456053e-7 * kelvin * kelvin)
		};

		// xy coordinates in CIES 1931 xyY space
		double divisor = 2 * uv[0] - 8 * uv[1] + 4;
		double[] xy = new double[]{3 * uv[0] / divisor, 2 * uv[1] / divisor};

		// CIE XYZ space
		float y = 1;
		float[] xyz = {(float) (xy[0] * y / xy[1]), y, (float) ((1 - xy[0] - xy[1]) * y / xy[1])};

		float[] m = XYZ_TO_RGB_MATRIX;
		return new float[]{
			m[0] * xyz[0] + m[1] * xyz[1] + m[2] * xyz[2],
			m[3] * xyz[0] + m[4] * xyz[1] + m[5] * xyz[2],
			m[6] * xyz[0] + m[7] * xyz[1] + m[8] * xyz[2]
		};
	}

	/**
	 * Approximate downward single scattering relative to overhead, already-attenuated directional light.
	 */
	public static void deriveAmbientLight(float[] out, float[] directionalColor)
	{
		for (int i = 0; i < 3; i++)
		{
			out[i] = directionalColor[i] * .5f * ((float) Math.exp(ATMOSPHERIC_OPTICAL_DEPTH[i]) - 1);
		}
	}

	/**
	 * Convert a hex color from sRGB to linear RGB in the range 0-1.
	 */
	public static float[] rgb(String hex)
	{
		return srgbToLinear(srgb(hex));
	}

	/**
	 * Convert a hex color to non-linear sRGB in the range 0-1.
	 */
	public static float[] srgb(String hex)
	{
		Color color = Color.decode(hex);
		return new float[]{color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f};
	}

	/**
	 * Reads a hex colour code or an array of 0-255 channels as non-linear sRGB in the range 0-1.
	 */
	public static class SrgbAdapter extends TypeAdapter<float[]>
	{
		@Override
		public float[] read(JsonReader in) throws IOException
		{
			var token = in.peek();
			if (token == JsonToken.NULL)
			{
				in.nextNull();
				return null;
			}

			if (token == JsonToken.STRING)
			{
				return srgb(in.nextString());
			}

			if (token != JsonToken.BEGIN_ARRAY)
			{
				throw new IOException("Expected hex color code or array of color channels at " + GsonUtils.location(in));
			}

			in.beginArray();
			float[] rgba = {0, 0, 0, 1};
			int i = 0;
			while (in.hasNext() && in.peek() != JsonToken.END_ARRAY)
			{
				if (in.peek() == JsonToken.NULL || i > 3)
				{
					in.skipValue();
					continue;
				}
				rgba[i++] = (float) in.nextDouble();
			}
			in.endArray();

			if (i < 3)
			{
				throw new IOException("Too few elements in color array: " + i + " at " + GsonUtils.location(in));
			}

			for (int j = 0; j < i; j++)
			{
				rgba[j] /= 255;
			}

			return i == 4 ? rgba : new float[]{rgba[0], rgba[1], rgba[2]};
		}

		@Override
		public void write(JsonWriter out, float[] src)
		{
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * Reads an sRGB colour (see {@link SrgbAdapter}) and converts it to linear RGB.
	 */
	public static class SrgbToLinearAdapter extends SrgbAdapter
	{
		@Override
		public float[] read(JsonReader in) throws IOException
		{
			float[] srgb = super.read(in);
			return srgb == null ? null : srgbToLinear(srgb);
		}
	}
}
