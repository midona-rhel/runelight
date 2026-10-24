package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Snow and rain puddles as part of the surfaces: the half resolution AO pass works out how much cover a pixel gets
 * (weather_ground_ao.glsl: the region's ground from the weather field, times how open to the sky it is) into the
 * shadow target's blue channel, and the composite lays it out as blobs of noise before lighting
 * (weather_ground.glsl), so it takes the AO, the shadows, the global illumination and the point lights like any
 * other surface. This holds the two programs' uniforms.
 */
final class WeatherGround
{
	private int aoOn = -1, aoOcclusionProjRel = -1, aoOcclusionDepthRange = -1, aoFieldRect = -1, aoGroundFocus = -1,
		aoGroundLocal = -1, aoFieldOn = -1;

	/**
	 * @param occlusionUnit the occlusion map's raw view; its compare view is on the next unit
	 * @param fieldUnit     the weather field; its ground is on the next unit
	 */
	void locate(int aoProgram, int occlusionUnit, int fieldUnit)
	{
		aoOn = glGetUniformLocation(aoProgram, "weatherGroundOn");
		aoOcclusionProjRel = glGetUniformLocation(aoProgram, "occlusionProjRel");
		aoOcclusionDepthRange = glGetUniformLocation(aoProgram, "occlusionDepthRange");
		aoFieldRect = glGetUniformLocation(aoProgram, "groundFieldRect");
		aoGroundFocus = glGetUniformLocation(aoProgram, "weatherGroundFocus");
		aoGroundLocal = glGetUniformLocation(aoProgram, "weatherGroundLocal");
		aoFieldOn = glGetUniformLocation(aoProgram, "weatherFieldOn");
		glUseProgram(aoProgram);
		glUniform1i(glGetUniformLocation(aoProgram, "occlusionCompare"), occlusionUnit + 1);
		glUniform1i(glGetUniformLocation(aoProgram, "weatherGround"), fieldUnit + 1);
		glUseProgram(0);
	}

	/** With the AO program in use: off, nothing written. */
	void setAoOff()
	{
		glUniform1i(aoOn, 0);
	}

	/** With the AO program in use (Weather.bindGround). */
	void setAo(float[] occlusionProjRel, float occlusionDepthRange, WeatherField field, float cameraX, float cameraZ,
		float wet, float snow)
	{
		glUniform1i(aoOn, 1);
		glUniformMatrix4fv(aoOcclusionProjRel, false, occlusionProjRel);
		glUniform1f(aoOcclusionDepthRange, occlusionDepthRange);
		// camera-relative, like the AO pass's positions
		glUniform4f(aoFieldRect, field.getOriginX() - cameraX, field.getOriginZ() - cameraZ,
			1f / Math.max(field.getSizeX() * WeatherField.CELL, 1), 1f / Math.max(field.getSizeZ() * WeatherField.CELL, 1));
		glUniform2f(aoGroundFocus, field.getFocus(WeatherField.WET), field.getFocus(WeatherField.SNOW_COVER));
		glUniform2f(aoGroundLocal, wet, snow);
		glUniform1f(aoFieldOn, field.isEnabled() ? 1 : 0);
	}

	/** A composite program's uniforms. */
	static final class CompositeUniforms
	{
		private int on = -1, noiseOrigin = -1, wadeSteps = -1;

		void locate(int program)
		{
			on = glGetUniformLocation(program, "weatherGroundOn");
			noiseOrigin = glGetUniformLocation(program, "weatherNoiseOrigin");
			wadeSteps = glGetUniformLocation(program, "wadeSteps");
		}

		/** With the program in use: the steps in the puddles (WadeSteps.steps). */
		void setWade(float[] steps)
		{
			glUniform4fv(wadeSteps, steps);
		}

		/**
		 * With the program in use.
		 *
		 * @param originX world x of local (scene) x 0, world units, so snow and puddles stay put when the scene reloads
		 */
		void set(boolean enabled, float originX, float originZ)
		{
			glUniform1i(on, enabled ? 1 : 0);
			glUniform2f(noiseOrigin, originX, originZ);
		}
	}
}
