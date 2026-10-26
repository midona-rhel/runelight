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

	private int ambient, sun, fogColor, fogDepth, haze, skyTint, drawDistance, cave, fogFocus;

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
		fogFocus = glGetUniformLocation(program, "envFogFocus");
	}

	/**
	 * @param env              null for the neutral look
	 * @param fogStrength      multiplier on the area's fog density
	 * @param drawDistanceTiles the draw distance in tiles
	 * @param focusX           world x and z of the player, where the limit on what the fog may hide is centered
	 */
	void set(EnvironmentManager env, float fogStrength, int drawDistanceTiles, float focusX, float focusZ)
	{
		glUniform1f(drawDistance, drawDistanceTiles * 128f);
		glUniform2f(fogFocus, focusX, focusZ);
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
		float[] v = values;
		for (int i = 0; i < 3; ++i)
		{
			v[i] = Math.max(0, a.ambient[i]);
			v[3 + i] = Math.max(0, a.sun[i]);
			v[6 + i] = Math.max(0, a.skyTint[i]);
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
}
