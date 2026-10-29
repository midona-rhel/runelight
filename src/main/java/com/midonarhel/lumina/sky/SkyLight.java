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
 * The sky's light on surfaces: L1 spherical harmonics of the sky's radiance (the {@link SkyFrame}'s gradient plus
 * the sun's glow, times its skyFill scale), integrated on the CPU over {@link #SAMPLES} directions of a Fibonacci
 * sphere and convolved with the cosine lobe, so the shader gets the irradiance for a normal n as
 * {@code c0 + c1 . n}. Below the horizon the sky is black; what the ground reflects onto downward-facing surfaces is
 * a separate {@link #bounce} term, {@link #GROUND_ALBEDO} of the light on flat ground, times {@code max(n.y, 0)}
 * (y points down). The shader clamps the sum at 0, as {@link #evaluate} does.
 * <p>
 * Re-integrated when the sun moved more than {@link #REBAKE_DEGREES} in altitude or azimuth, or the sky it
 * integrates (zenith, horizon, glow, skyFill) changed by more than {@link #REBAKE_FRACTION} in any component since the
 * last integration; during a continuous change (an area fade) at most every {@link #REBAKE_MIN_FRAMES}th frame. 256
 * directions rather than the planned 64 because the glow's narrow core aliases visibly with fewer as the sun moves
 * (SkyLightTest prints the cost: tens of microseconds). The ground {@link #bounce} is cheap and follows every frame.
 */
public final class SkyLight
{
	public static final int SAMPLES = 256;
	public static final float REBAKE_DEGREES = 0.25f;
	public static final float REBAKE_FRACTION = 0.02f;
	public static final int REBAKE_MIN_FRAMES = 4;
	// kept low: at noon the ground gets ~4.4 units, and undersides get this bounce on top of the ambient light (117 HD
	// gives them the ambient only); 5% puts the bounce at about the sky fill on an upward face
	public static final float GROUND_ALBEDO = 0.05f;

	/** The constant term, linear RGB. */
	public final float[] c0 = new float[3];
	/** The linear term: three RGB colours, for n.x, n.y and n.z. */
	public final float[] c1 = new float[9];
	/** The ground's reflected light, for {@code max(n.y, 0)}. */
	public final float[] bounce = new float[3];

	private final float[][] dirs = new float[SAMPLES][3];
	private final float[] radiance = new float[3];
	private final float[] gradient = new float[3];

	private boolean baked;
	private float bakedAltitude;
	private double bakedAzimuth;
	// what the last integration saw: zenith, horizon, glow, skyFill
	private final float[] bakedSky = new float[12];
	private int framesSinceBake;

	public SkyLight()
	{
		// Fibonacci sphere: evenly spread unit directions
		double golden = Math.PI * (3 - Math.sqrt(5));
		for (int i = 0; i < SAMPLES; i++)
		{
			double y = 1 - (i + 0.5) * 2.0 / SAMPLES;
			double r = Math.sqrt(1 - y * y);
			double a = golden * i;
			dirs[i][0] = (float) (Math.cos(a) * r);
			dirs[i][1] = (float) y;
			dirs[i][2] = (float) (Math.sin(a) * r);
		}
	}

	/**
	 * Re-integrates if the sun moved or the sky changed enough (see the class comment); refreshes the ground bounce
	 * every call. Call once per frame.
	 *
	 * @param frame        the sky this frame
	 * @param sunDir       the visual sun's direction (toward the sun, y down)
	 * @param altitude     the visual sun's altitude, degrees
	 * @param azimuth      the sun's azimuth, radians
	 * @param ambient      the frame's ambient light, for the ground bounce
	 * @param casterColour the caster's light (sun or moon, with its strength), for the ground bounce
	 * @param casterDir    the caster's direction (toward the light, y down)
	 * @return whether it re-integrated
	 */
	public boolean update(SkyFrame frame, float[] sunDir, float altitude, double azimuth, float[] ambient,
		float[] casterColour, float[] casterDir)
	{
		framesSinceBake++;
		boolean rebake = !baked;
		if (baked && framesSinceBake >= REBAKE_MIN_FRAMES)
		{
			double azDelta = Math.abs(Math.toDegrees(angleDiff(azimuth, bakedAzimuth)));
			rebake = Math.abs(altitude - bakedAltitude) > REBAKE_DEGREES || azDelta > REBAKE_DEGREES || skyChanged(frame);
		}
		if (rebake)
		{
			baked = true;
			framesSinceBake = 0;
			bakedAltitude = altitude;
			bakedAzimuth = azimuth;
			rememberSky(frame);
			integrate(frame, sunDir);
		}

		// the ground reflects what falls on it (the sky, the ambient light and the caster) onto faces that look down
		float groundSun = Math.max(0, -casterDir[1]);
		for (int i = 0; i < 3; i++)
		{
			float onGround = (c0[i] - c1[3 + i]) + ambient[i] + casterColour[i] * groundSun;
			bounce[i] = GROUND_ALBEDO * Math.max(0, onGround);
		}
		return rebake;
	}

	private void rememberSky(SkyFrame frame)
	{
		System.arraycopy(frame.zenith, 0, bakedSky, 0, 3);
		System.arraycopy(frame.horizon, 0, bakedSky, 3, 3);
		System.arraycopy(frame.glow, 0, bakedSky, 6, 3);
		System.arraycopy(frame.skyFill, 0, bakedSky, 9, 3);
	}

	/** Whether any component the integral reads moved more than REBAKE_FRACTION of its baked value (or of 1e-3). */
	private boolean skyChanged(SkyFrame frame)
	{
		float[][] now = {frame.zenith, frame.horizon, frame.glow, frame.skyFill};
		for (int k = 0; k < 4; k++)
		{
			for (int i = 0; i < 3; i++)
			{
				float was = bakedSky[k * 3 + i];
				if (Math.abs(now[k][i] - was) > REBAKE_FRACTION * Math.max(Math.abs(was), 1e-3f))
				{
					return true;
				}
			}
		}
		return false;
	}

	private void integrate(SkyFrame frame, float[] sunDir)
	{
		java.util.Arrays.fill(c0, 0);
		java.util.Arrays.fill(c1, 0);
		for (float[] w : dirs)
		{
			if (!radianceAt(frame, sunDir, w, radiance))
			{
				continue;
			}
			for (int i = 0; i < 3; i++)
			{
				c0[i] += radiance[i];
				c1[i] += radiance[i] * w[0];
				c1[3 + i] += radiance[i] * w[1];
				c1[6 + i] += radiance[i] * w[2];
			}
		}
		// Irradiance from the L1 projection with solid angle 4 pi / N per sample and the cosine-lobe convolution
		// (pi for the constant band, 2 pi / 3 for the linear one): E(n) = pi/N sum L + 2 pi/N sum L w . n
		float k0 = (float) (Math.PI / SAMPLES), k1 = (float) (2 * Math.PI / SAMPLES);
		for (int i = 0; i < 3; i++)
		{
			c0[i] *= k0;
			c1[i] *= k1;
			c1[3 + i] *= k1;
			c1[6 + i] *= k1;
		}
	}

	/**
	 * The sky's radiance toward a direction (unit, y down), times the frame's skyFill: the gradient from the horizon
	 * to the zenith plus the sun's glow; nothing below the horizon.
	 *
	 * @return false below the horizon (out untouched)
	 */
	boolean radianceAt(SkyFrame frame, float[] sunDir, float[] w, float[] out)
	{
		float elev = -w[1];
		if (elev <= 0)
		{
			return false;
		}
		float t = (float) Math.pow(Math.min(elev, 1), 0.45);
		SkyModel.mix(gradient, frame.horizon, frame.zenith, t);
		float mu = Math.max(0, w[0] * sunDir[0] + w[1] * sunDir[1] + w[2] * sunDir[2]);
		// mu^10 and mu^120 (the old sunGlow)
		float m2 = mu * mu, m4 = m2 * m2, m10 = m4 * m4 * m2;
		float m40 = m10 * m10;
		m40 *= m40;
		float glow = m10 * 0.30f + m40 * m40 * m40 * 0.50f;
		for (int i = 0; i < 3; i++)
		{
			out[i] = (gradient[i] + frame.glow[i] * glow) * frame.skyFill[i];
		}
		return true;
	}

	/** The sky's light on a surface with this normal (unit, y down), as the shader computes it. */
	public float[] evaluate(float[] n, float[] out)
	{
		float down = Math.max(0, n[1]);
		for (int i = 0; i < 3; i++)
		{
			out[i] = Math.max(0, c0[i] + c1[i] * n[0] + c1[3 + i] * n[1] + c1[6 + i] * n[2] + bounce[i] * down);
		}
		return out;
	}

	private static double angleDiff(double a, double b)
	{
		double d = (a - b) % (2 * Math.PI);
		if (d > Math.PI)
		{
			d -= 2 * Math.PI;
		}
		if (d < -Math.PI)
		{
			d += 2 * Math.PI;
		}
		return d;
	}
}
