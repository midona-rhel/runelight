/*
 * Copyright (c) 2026, Midona
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.sky;

/**
 * A colour keyframe curve: a monotone cubic (Fritsch-Carlson) through the keys, per channel, so the colour passes
 * through every key, never overshoots between two keys and has no rate stall at a key (the old renderer's
 * per-segment smoothstep stalled at every key). Clamps to the end keys outside the range.
 */
public final class MonotoneCurve
{
	private final float[] x;
	private final float[][] y;
	private final float[][] slope;

	/**
	 * @param keys   the key positions, strictly increasing
	 * @param colours one colour per key
	 */
	public MonotoneCurve(float[] keys, float[][] colours)
	{
		if (keys.length != colours.length || keys.length < 2)
		{
			throw new IllegalArgumentException("one colour per key, at least two keys");
		}
		x = keys.clone();
		int n = keys.length;
		int channels = colours[0].length;
		y = new float[n][];
		for (int i = 0; i < n; i++)
		{
			y[i] = colours[i].clone();
		}
		slope = new float[n][channels];
		for (int c = 0; c < channels; c++)
		{
			float[] delta = new float[n - 1];
			for (int i = 0; i < n - 1; i++)
			{
				delta[i] = (y[i + 1][c] - y[i][c]) / (x[i + 1] - x[i]);
			}
			float[] m = new float[n];
			m[0] = delta[0];
			m[n - 1] = delta[n - 2];
			for (int i = 1; i < n - 1; i++)
			{
				m[i] = delta[i - 1] * delta[i] <= 0 ? 0 : (delta[i - 1] + delta[i]) / 2;
			}
			for (int i = 0; i < n - 1; i++)
			{
				if (delta[i] == 0)
				{
					m[i] = 0;
					m[i + 1] = 0;
					continue;
				}
				float a = m[i] / delta[i], b = m[i + 1] / delta[i];
				float s = a * a + b * b;
				if (s > 9)
				{
					float tau = 3 / (float) Math.sqrt(s);
					m[i] = tau * a * delta[i];
					m[i + 1] = tau * b * delta[i];
				}
			}
			for (int i = 0; i < n; i++)
			{
				slope[i][c] = m[i];
			}
		}
	}

	public int channels()
	{
		return y[0].length;
	}

	public void evaluate(float at, float[] out)
	{
		int n = x.length;
		if (at <= x[0])
		{
			System.arraycopy(y[0], 0, out, 0, out.length);
			return;
		}
		if (at >= x[n - 1])
		{
			System.arraycopy(y[n - 1], 0, out, 0, out.length);
			return;
		}
		int i = 0;
		while (at > x[i + 1])
		{
			i++;
		}
		float h = x[i + 1] - x[i];
		float t = (at - x[i]) / h;
		float t2 = t * t, t3 = t2 * t;
		float h00 = 2 * t3 - 3 * t2 + 1;
		float h10 = t3 - 2 * t2 + t;
		float h01 = -2 * t3 + 3 * t2;
		float h11 = t3 - t2;
		for (int c = 0; c < out.length; c++)
		{
			out[c] = h00 * y[i][c] + h10 * h * slope[i][c] + h01 * y[i + 1][c] + h11 * h * slope[i + 1][c];
		}
	}
}
