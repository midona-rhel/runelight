package com.midonarhel.runelight;

import com.midonarhel.runelight.config.WeatherMode;
import com.midonarhel.runelight.template.Template;
import java.util.Collections;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Rain and snow: falling particles, rain splashes, and wet or snow covered surfaces, each drawn in its own small
 * pass over the composited scene.
 *
 * Shelter comes from an "occlusion map": a top-down orthographic depth map of all static scenery around the
 * player, drawn with every roof and every level, including roofs the game (or a roof hiding plugin) hides from the
 * camera. A point is under the open sky when nothing in its column is above it. The map is only redrawn when the
 * player walks away from its center, the scene loads or a zone changes, so it costs nothing on most frames.
 */
class Weather
{
	// occlusion map: OCCLUSION_SIZE^2 texels over +-OCCLUSION_RADIUS world units, 1/8 tile per texel, 4 MB
	private static final int OCCLUSION_SIZE = 1024;
	private static final float OCCLUSION_RADIUS = 64 * 128;
	// world units above and below the player's ground that the map captures
	private static final float OCCLUSION_DEPTH_RANGE = 4096;
	// redraw once the player is this far from the map center; particles reach ~20 tiles, so 24 keeps a margin
	private static final float OCCLUSION_RECENTER = 24 * 128;
	// wet ground and snow cover fade out before the edge of the map, where shelter is unknown
	private static final float SURFACE_FADE = 38 * 128;
	// straight up: the "light" looks straight down
	private static final float[] UP = {0, -1, 0};

	// instance counts at 100% intensity
	static final int MAX_RAIN_DROPS = 24000;
	static final int MAX_RAIN_SPLASHES = 1600;
	static final int MAX_SNOW_FLAKES = 14000;

	// seconds for falling weather to fade in after it is turned on, and for wetness / snow cover to build up
	private static final float FADE_IN_SECONDS = 3;
	private static final float WET_BUILD_SECONDS = 25;
	private static final float SNOW_BUILD_SECONDS = 90;
	// the particle clock wraps so float precision holds; a wrap moves every particle once, unnoticeable in rain
	private static final long CLOCK_WRAP_MILLIS = 600_000;

	static final Shader RAIN_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "rain_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "rain_frag.glsl");

	static final Shader SNOW_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "snow_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "snow_frag.glsl");

	static final Shader SURFACE_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "weather_surface_frag.glsl");

	/**
	 * Freshly compiled programs, so a hot reload can compile everything before replacing anything.
	 */
	static final class Programs
	{
		final int rain, snow, surface;

		Programs(int rain, int snow, int surface)
		{
			this.rain = rain;
			this.snow = snow;
			this.surface = surface;
		}
	}

	/**
	 * Uniform locations of one weather program. All three share weather_common.glsl and frame.glsl.
	 */
	private static final class Uniforms
	{
		int program;
		int viewProj, invViewProj, viewport, depthZeroToOne;
		int occlusionProjRel, occlusionCenterY, occlusionDepthRange;
		int cameraPos, focusPos, time, sunAltitude, casterDir, sunIsCaster, amount;
		int dropCount, splashCount, wind;
		int surfaceMode, fadeDistance, shadowsEnabled, lightProjRel;

		void locate(int p, int sceneUnit, int occlusionUnit, int shadowCompareUnit, int alphaLayerUnit)
		{
			program = p;
			viewProj = glGetUniformLocation(p, "viewProj");
			invViewProj = glGetUniformLocation(p, "invViewProj");
			viewport = glGetUniformLocation(p, "sceneViewport");
			depthZeroToOne = glGetUniformLocation(p, "depthZeroToOne");
			occlusionProjRel = glGetUniformLocation(p, "occlusionProjRel");
			occlusionCenterY = glGetUniformLocation(p, "occlusionCenterY");
			occlusionDepthRange = glGetUniformLocation(p, "occlusionDepthRange");
			cameraPos = glGetUniformLocation(p, "cameraPos");
			focusPos = glGetUniformLocation(p, "focusPos");
			time = glGetUniformLocation(p, "time");
			sunAltitude = glGetUniformLocation(p, "sunAltitude");
			casterDir = glGetUniformLocation(p, "casterDir");
			sunIsCaster = glGetUniformLocation(p, "sunIsCaster");
			amount = glGetUniformLocation(p, "amount");
			dropCount = glGetUniformLocation(p, "dropCount");
			splashCount = glGetUniformLocation(p, "splashCount");
			wind = glGetUniformLocation(p, "wind");
			surfaceMode = glGetUniformLocation(p, "surfaceMode");
			fadeDistance = glGetUniformLocation(p, "fadeDistance");
			shadowsEnabled = glGetUniformLocation(p, "shadowsEnabled");
			lightProjRel = glGetUniformLocation(p, "lightProjRel");

			glUseProgram(p);
			glUniform1i(glGetUniformLocation(p, "sceneColor"), sceneUnit);
			glUniform1i(glGetUniformLocation(p, "sceneNormal"), sceneUnit + 1);
			glUniform1i(glGetUniformLocation(p, "sceneDepth"), sceneUnit + 2);
			glUniform1i(glGetUniformLocation(p, "occlusionMap"), occlusionUnit);
			glUniform1i(glGetUniformLocation(p, "shadowCompare"), shadowCompareUnit);
			glUniform1i(glGetUniformLocation(p, "sceneAlpha"), alphaLayerUnit);
			glUseProgram(0);
		}
	}

	private final Uniforms rain = new Uniforms();
	private final Uniforms snow = new Uniforms();
	private final Uniforms surface = new Uniforms();

	private final ShadowMap occlusion = new ShadowMap();
	// particles have no vertex attributes; an empty VAO keeps drivers from fetching unrelated buffers
	private int vao;
	private final float[] occlusionProjRel = Mat4.identity();
	private boolean occlusionDirty = true;
	private float occlusionX, occlusionY, occlusionZ;
	private int occlusionPlane = -1;

	// per frame
	private WeatherMode activeMode = WeatherMode.OFF;
	private boolean active;
	private float intensity;
	private float fadeIn;
	// fog density multipliers: clear weather, and what full rain or snow adds
	private static final float FOG_CLEAR = 0.6f;
	private static final float FOG_RAIN = 1.6f;
	private static final float FOG_SNOW = 1.0f;
	private float accumulation;
	private long lastMillis;
	private final long startMillis = System.currentTimeMillis();
	private float focusX, focusY, focusZ;
	private int focusPlane;

	Programs compile(Template template) throws ShaderException
	{
		int r = 0, s = 0;
		try
		{
			r = RAIN_PROGRAM.compile(template);
			s = SNOW_PROGRAM.compile(template);
			int f = SURFACE_PROGRAM.compile(template);
			return new Programs(r, s, f);
		}
		catch (ShaderException ex)
		{
			// glDeleteProgram ignores 0
			glDeleteProgram(r);
			glDeleteProgram(s);
			throw ex;
		}
	}

	/**
	 * Replace the current programs, deleting the old ones.
	 */
	void setPrograms(Programs p, int sceneUnit, int occlusionUnit, int shadowCompareUnit, int alphaLayerUnit)
	{
		freePrograms();
		rain.locate(p.rain, sceneUnit, occlusionUnit, occlusionUnit + 1, alphaLayerUnit);
		snow.locate(p.snow, sceneUnit, occlusionUnit, occlusionUnit + 1, alphaLayerUnit);
		surface.locate(p.surface, sceneUnit, occlusionUnit, shadowCompareUnit, alphaLayerUnit);
	}

	void freePrograms()
	{
		glDeleteProgram(rain.program);
		glDeleteProgram(snow.program);
		glDeleteProgram(surface.program);
		rain.program = snow.program = surface.program = 0;
	}

	/**
	 * GL resources other than programs.
	 */
	void free()
	{
		occlusion.free();
		occlusionDirty = true;
		if (vao != 0)
		{
			glDeleteVertexArrays(vao);
			vao = 0;
		}
	}

	/**
	 * Scenery changed (scene load, zone rebuild): redraw the occlusion map before it is next used.
	 */
	void invalidateOcclusion()
	{
		occlusionDirty = true;
	}

	boolean isActive()
	{
		return active;
	}

	/**
	 * How much the weather thickens fog, as a multiplier: thinner in clear weather, thicker as rain or snow
	 * fades in.
	 */
	float fogMultiplier()
	{
		float k = active ? intensity * fadeIn : 0;
		float extra = activeMode == WeatherMode.RAIN ? FOG_RAIN : activeMode == WeatherMode.SNOW ? FOG_SNOW : 0;
		return FOG_CLEAR + extra * k;
	}

	/**
	 * Decide whether weather runs this frame and advance the fade-in and buildup. Off, indoors or underground,
	 * nothing else runs, and turning weather off frees the occlusion map.
	 *
	 * @param outdoors whether the area has a sky at all
	 * @param fx player ground position, world units
	 */
	boolean update(WeatherMode mode, int intensityPercent, boolean gradual, boolean outdoors,
		float fx, float fy, float fz, int plane)
	{
		long now = System.currentTimeMillis();
		float dt = lastMillis == 0 ? 0 : Math.min((now - lastMillis) / 1000f, 0.25f);
		lastMillis = now;

		if (mode != activeMode)
		{
			// changing kind of weather starts over: rain doesn't turn into snow cover
			activeMode = mode;
			fadeIn = 0;
			accumulation = 0;
		}

		active = mode != WeatherMode.OFF && outdoors && intensityPercent > 0;
		if (mode == WeatherMode.OFF)
		{
			if (occlusion.isInitialized())
			{
				occlusion.free();
				occlusionDirty = true;
			}
			return false;
		}
		if (!active)
		{
			// underground: keep buildup so walking out of a cave doesn't reset it
			return false;
		}

		intensity = intensityPercent / 100f;
		if (gradual)
		{
			fadeIn = Math.min(1, fadeIn + dt / FADE_IN_SECONDS);
			float build = mode == WeatherMode.RAIN ? WET_BUILD_SECONDS : SNOW_BUILD_SECONDS;
			accumulation = Math.min(1, accumulation + dt / build);
		}
		else
		{
			fadeIn = 1;
			accumulation = 1;
		}

		focusX = fx;
		focusY = fy;
		focusZ = fz;
		focusPlane = plane;
		if (plane != occlusionPlane
			|| Math.abs(fx - occlusionX) > OCCLUSION_RECENTER
			|| Math.abs(fz - occlusionZ) > OCCLUSION_RECENTER
			|| Math.abs(fy - occlusionY) > OCCLUSION_DEPTH_RANGE / 4)
		{
			occlusionDirty = true;
		}
		return true;
	}

	/**
	 * Redraw the occlusion map if needed, with the shadow program: same vertex formats, and it keeps alpha
	 * tested holes (leaves, grates) open.
	 */
	void renderOcclusion(RuneLightPlugin.SceneContext ctx, int program, int uniLightProj, int uniEntityProj,
		int uniBase, int uniTexturesReady, boolean texturesReady, boolean zeroToOne)
	{
		if (!active || program == 0 || ctx == null)
		{
			return;
		}
		if (!occlusion.isInitialized())
		{
			occlusion.init(OCCLUSION_SIZE);
			occlusionDirty = true;
		}
		if (!occlusionDirty)
		{
			return;
		}
		occlusionDirty = false;
		occlusionX = focusX;
		occlusionY = focusY;
		occlusionZ = focusZ;
		occlusionPlane = focusPlane;
		occlusion.update(UP, focusX, focusY, focusZ, 0, 0, 0, OCCLUSION_RADIUS, OCCLUSION_DEPTH_RANGE, zeroToOne);

		glBindFramebuffer(GL_FRAMEBUFFER, occlusion.getFbo());
		glViewport(0, 0, OCCLUSION_SIZE, OCCLUSION_SIZE);
		glClearDepth(1.0);
		glClear(GL_DEPTH_BUFFER_BIT);
		glEnable(GL_DEPTH_TEST);
		// smaller window depth is higher up: keep the topmost surface in each column
		glDepthFunc(GL_LESS);
		glDepthMask(true);
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);

		glUseProgram(program);
		glUniformMatrix4fv(uniLightProj, false, occlusion.lightProj);
		glUniform1i(uniTexturesReady, texturesReady ? 1 : 0);
		glUniformMatrix4fv(uniEntityProj, false, RuneLightPlugin.IDENTITY);

		// Zone draws through the plugin's shared uniform location
		final int savedBase = RuneLightPlugin.uniBase;
		RuneLightPlugin.uniBase = uniBase;
		try
		{
			int offset = RuneLightPlugin.SCENE_OFFSET >> 3;
			float reach = OCCLUSION_RADIUS + 1024;
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				float cx = ((x - offset) << 10) + 512;
				if (Math.abs(cx - focusX) > reach)
				{
					continue;
				}
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					float cz = ((z - offset) << 10) + 512;
					if (Math.abs(cz - focusZ) > reach)
					{
						continue;
					}
					Zone zone = ctx.zones[x][z];
					if (zone.initialized && zone.sizeO > 0)
					{
						// every level and every roof, whether or not the camera sees it
						zone.renderOpaque(x - offset, z - offset, 0, 0, 3, Collections.emptySet());
					}
				}
			}
		}
		finally
		{
			RuneLightPlugin.uniBase = savedBase;
		}

		glBindVertexArray(0);
		glDepthFunc(GL_GREATER);
		glDisable(GL_DEPTH_TEST);
		glUseProgram(0);
	}

	/**
	 * Draw this frame's weather over the composited scene in the bound framebuffer. The scene textures, the
	 * transparency layer and the shadow map (if any) must still be bound on the units given to setPrograms.
	 *
	 * @param shadowProjRel camera-relative light matrix, or null without shadows
	 */
	void draw(float[] viewProj, float[] invViewProj, int[] viewport, boolean zeroToOne,
		float cameraX, float cameraY, float cameraZ, DayCycle day, float[] shadowProjRel,
		boolean splashes, boolean surfaces, int occlusionUnit)
	{
		if (!active || !occlusion.isInitialized() || rain.program == 0)
		{
			return;
		}
		if (vao == 0)
		{
			vao = glGenVertexArrays();
		}
		relativeTo(occlusion.lightProj, cameraX, cameraY, cameraZ, occlusionProjRel);
		occlusion.bindForSampling(occlusionUnit, occlusionUnit + 1);
		glBindVertexArray(vao);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glEnable(GL_BLEND);

		boolean isRain = activeMode == WeatherMode.RAIN;
		float time = ((System.currentTimeMillis() - startMillis) % CLOCK_WRAP_MILLIS) / 1000f;

		// only the game viewport; gl_FragCoord stays in whole-target pixels, as the scene textures expect
		glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);

		// surfaces first so falling weather draws over them
		if (surfaces && accumulation > 0)
		{
			glUseProgram(surface.program);
			setCommon(surface, viewProj, invViewProj, viewport, zeroToOne, cameraX, cameraY, cameraZ, day, time);
			glUniform1f(surface.amount, accumulation * (0.4f + 0.6f * intensity));
			glUniform1i(surface.surfaceMode, isRain ? 1 : 2);
			glUniform1f(surface.fadeDistance, SURFACE_FADE);
			glUniform1i(surface.shadowsEnabled, shadowProjRel != null ? 1 : 0);
			if (shadowProjRel != null)
			{
				glUniformMatrix4fv(surface.lightProjRel, false, shadowProjRel);
			}
			// out = weather color + scene * weather alpha: darkens for wetness, covers for snow
			glBlendFuncSeparate(GL_ONE, GL_SRC_ALPHA, GL_ZERO, GL_ONE);
			glDrawArrays(GL_TRIANGLES, 0, 3);
		}

		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		Uniforms u = isRain ? rain : snow;
		glUseProgram(u.program);
		setCommon(u, viewProj, invViewProj, viewport, zeroToOne, cameraX, cameraY, cameraZ, day, time);
		glUniform1f(u.amount, fadeIn);
		int count;
		if (isRain)
		{
			int drops = Math.round(MAX_RAIN_DROPS * intensity * fadeIn);
			int splashCount = splashes ? Math.round(MAX_RAIN_SPLASHES * intensity * accumulation) : 0;
			glUniform1i(u.dropCount, drops);
			glUniform1i(u.splashCount, splashCount);
			// a little slant, stronger in heavy rain
			glUniform3f(u.wind, 90 + 160 * intensity, 0, 50 + 80 * intensity);
			count = drops + splashCount;
		}
		else
		{
			count = Math.round(MAX_SNOW_FLAKES * intensity * fadeIn);
			glUniform1i(u.dropCount, count);
			glUniform3f(u.wind, 30 + 50 * intensity, 0, 20);
		}
		if (count > 0)
		{
			// one quad per particle, everything else comes from gl_InstanceID
			glDrawArraysInstanced(GL_TRIANGLES, 0, 6, count);
		}

		glDisable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glBindVertexArray(0);
		glUseProgram(0);
		ShadowMap.unbind(occlusionUnit, occlusionUnit + 1);
	}

	private void setCommon(Uniforms u, float[] viewProj, float[] invViewProj, int[] viewport, boolean zeroToOne,
		float cameraX, float cameraY, float cameraZ, DayCycle day, float time)
	{
		glUniformMatrix4fv(u.viewProj, false, viewProj);
		glUniformMatrix4fv(u.invViewProj, false, invViewProj);
		glUniform4i(u.viewport, viewport[0], viewport[1], viewport[2], viewport[3]);
		glUniform1i(u.depthZeroToOne, zeroToOne ? 1 : 0);
		glUniformMatrix4fv(u.occlusionProjRel, false, occlusionProjRel);
		glUniform1f(u.occlusionCenterY, occlusionY);
		glUniform1f(u.occlusionDepthRange, OCCLUSION_DEPTH_RANGE);
		glUniform3f(u.cameraPos, cameraX, cameraY, cameraZ);
		glUniform3f(u.focusPos, focusX, focusY, focusZ);
		glUniform1f(u.time, time);
		glUniform1f(u.sunAltitude, day.sunAltitude);
		glUniform3fv(u.casterDir, day.casterDir);
		glUniform1i(u.sunIsCaster, day.sunIsCaster ? 1 : 0);
	}

	/**
	 * Camera-relative version of a world space matrix: out * p_rel == m * (p_rel + camera).
	 */
	static void relativeTo(float[] m, float cameraX, float cameraY, float cameraZ, float[] out)
	{
		System.arraycopy(m, 0, out, 0, 16);
		for (int row = 0; row < 4; ++row)
		{
			out[12 + row] = m[12 + row] + m[row] * cameraX + m[4 + row] * cameraY + m[8 + row] * cameraZ;
		}
	}
}
