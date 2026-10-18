package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The environment.glsl uniforms of one program. Every program that includes environment.glsl must get these
 * set each frame: unset uniforms are 0, which would mean no ambient light and no sun.
 */
final class EnvironmentUniforms
{
	private int ambient, sun, fogColor, fogDepth, haze, skyTint, drawDistance, cave;

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
	}

	/**
	 * @param env              null for the neutral look
	 * @param lightStrength    0 neutral, 1 as authored, more exaggerates
	 * @param fogStrength      multiplier on the area's fog density
	 * @param drawDistanceTiles the draw distance in tiles
	 */
	void set(EnvironmentManager env, float lightStrength, float fogStrength, int drawDistanceTiles)
	{
		glUniform1f(drawDistance, drawDistanceTiles * 128f);
		glUniform1i(cave, env != null && !env.isOverworld() ? 1 : 0);
		if (env == null)
		{
			glUniform3f(ambient, 1, 1, 1);
			glUniform3f(sun, 1, 1, 1);
			glUniform4f(fogColor, 0, 0, 0, 0);
			glUniform1f(fogDepth, 0);
			glUniform1f(haze, 0);
			glUniform3f(skyTint, 1, 1, 1);
			return;
		}

		EnvironmentManager.Ambience a = env.getAmbience();
		float k = lightStrength;
		glUniform3f(ambient, scale(a.ambient[0], k), scale(a.ambient[1], k), scale(a.ambient[2], k));
		glUniform3f(sun, scale(a.sun[0], k), scale(a.sun[1], k), scale(a.sun[2], k));
		glUniform3f(skyTint, scale(a.skyTint[0], k), scale(a.skyTint[1], k), scale(a.skyTint[2], k));
		glUniform4f(fogColor, a.fogColor[0], a.fogColor[1], a.fogColor[2], fogStrength > 0 ? a.fogColorWeight : 0);
		// 117 HD scales its fog depth with the draw distance so the look holds as it changes
		glUniform1f(fogDepth, a.fogDepth * fogStrength * Math.min(drawDistanceTiles, 90) / 10f);
		glUniform1f(haze, Math.min(1, a.haze * Math.min(fogStrength, 1)));
	}

	private static float scale(float v, float k)
	{
		return Math.max(0, 1 + (v - 1) * k);
	}
}
