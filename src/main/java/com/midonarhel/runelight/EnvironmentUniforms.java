package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The environment.glsl uniforms of one program. Every program that includes environment.glsl must get these
 * set each frame: unset uniforms are 0, which would mean no ambient light and no sun.
 */
final class EnvironmentUniforms
{
	// the values of the last set(), for LookMonitor: ambient, sun, sky tint, fog color and weight, fog depth, haze
	static final int VALUES = 15;
	final float[] values = new float[VALUES];

	private int ambient, sun, fogColor, fogDepth, haze, skyTint, drawDistance, cave, fogCover;

	void locate(int program)
	{
		ambient = glGetUniformLocation(program, "envAmbient");
		sun = glGetUniformLocation(program, "envSun");
		fogColor = glGetUniformLocation(program, "envFogColor");
		fogDepth = glGetUniformLocation(program, "envFogDepth");
		haze = glGetUniformLocation(program, "envHaze");
		skyTint = glGetUniformLocation(program, "envSkyTint");
		drawDistance = glGetUniformLocation(program, "envDrawDistance");
		cave = glGetUniformLocation(program, "envCave");
		fogCover = glGetUniformLocation(program, "envFogCover");
	}

	/**
	 * @param env              null for the neutral look
	 * @param lightStrength    0 neutral, 1 as authored, more exaggerates
	 * @param fogStrength      multiplier on the area's fog density
	 * @param drawDistanceTiles the draw distance in tiles
	 * @param maxFogCover      most of the view around the player the fog may hide, 0 to 1 (fog_cover.glsl)
	 * @param focusX           world x and z of the player, where the limit is centered
	 */
	void set(EnvironmentManager env, float lightStrength, float fogStrength, int drawDistanceTiles, float maxFogCover,
		float focusX, float focusZ)
	{
		glUniform1f(drawDistance, drawDistanceTiles * 128f);
		glUniform3f(fogCover, focusX, focusZ, maxFogCover);
		glUniform1i(cave, env != null && !env.isOverworld() ? 1 : 0);
		if (env == null)
		{
			glUniform3f(ambient, 1, 1, 1);
			glUniform3f(sun, 1, 1, 1);
			glUniform4f(fogColor, 0, 0, 0, 0);
			glUniform1f(fogDepth, 0);
			glUniform1f(haze, 0);
			glUniform3f(skyTint, 1, 1, 1);
			java.util.Arrays.fill(values, 0);
			java.util.Arrays.fill(values, 0, 9, 1);
			return;
		}

		EnvironmentManager.Ambience a = env.getAmbience();
		float k = lightStrength;
		float[] v = values;
		for (int i = 0; i < 3; ++i)
		{
			v[i] = scale(a.ambient[i], k);
			v[3 + i] = scale(a.sun[i], k);
			v[6 + i] = scale(a.skyTint[i], k);
			v[9 + i] = a.fogColor[i];
		}
		v[12] = fogStrength > 0 ? a.fogColorWeight : 0;
		// 117 HD scales its fog depth with the draw distance so the look holds as it changes
		v[13] = a.fogDepth * fogStrength * Math.min(drawDistanceTiles, 90) / 10f;
		v[14] = Math.min(1, a.haze * Math.min(fogStrength, 1));
		glUniform3f(ambient, v[0], v[1], v[2]);
		glUniform3f(sun, v[3], v[4], v[5]);
		glUniform3f(skyTint, v[6], v[7], v[8]);
		glUniform4f(fogColor, v[9], v[10], v[11], v[12]);
		glUniform1f(fogDepth, v[13]);
		glUniform1f(haze, v[14]);
	}

	private static float scale(float v, float k)
	{
		return Math.max(0, 1 + (v - 1) * k);
	}
}
