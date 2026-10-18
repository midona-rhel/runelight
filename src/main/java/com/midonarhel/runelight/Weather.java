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
	// seconds for weather to fade out when turned off or changed
	private static final float FADE_OUT_SECONDS = 6;
	// time constant, seconds, of the cloud cover following the intensity's swells: slower than the rain itself
	private static final float COVER_SECONDS = 20;
	// cloud cover at the lightest rain or snow, and what each unit of intensity adds
	private static final float COVER_BASE = 0.35f;
	private static final float COVER_SLOPE = 0.8f;
	// varying intensity may peak this far over 100%, as a share of the instance counts
	private static final float MAX_EFFECTIVE = 1.2f;
	// wind multiplier at a full gust
	private static final float RAIN_GUST = 1.2f;
	private static final float SNOW_GUST = 1.5f;
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
		int dropCount, splashCount, wind, windOffset, gust, overcast, pointLights;
		int surfaceMode, fadeDistance, shadowsEnabled, lightProjRel;

		void locate(int p, int sceneUnit, int occlusionUnit, int shadowCompareUnit, int alphaLayerUnit,
			int lightDataUnit, int clusterDataUnit)
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
			windOffset = glGetUniformLocation(p, "windOffset");
			gust = glGetUniformLocation(p, "gust");
			overcast = glGetUniformLocation(p, "weatherOvercast");
			pointLights = glGetUniformLocation(p, "pointLightsEnabled");
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
			glUniform1i(glGetUniformLocation(p, "lightData"), lightDataUnit);
			glUniform1i(glGetUniformLocation(p, "clusterData"), clusterDataUnit);
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
	// intensity with this moment's swell or lull, and its slow average that the cloud cover follows
	private float effective;
	private float slowEffective = -1;
	private float gust;
	private boolean openSky;
	private float overcastStrength;
	// wind this frame and how far it has carried the particles, world units; reset when the particle clock wraps
	private float windX, windZ;
	private float windOffsetX, windOffsetZ;
	private long clockWraps;
	// fog density multipliers: clear weather, and what full rain or snow adds
	private static final float FOG_CLEAR = 0.6f;
	private static final float FOG_RAIN = 1.0f;
	private static final float FOG_SNOW = 0.7f;
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
	void setPrograms(Programs p, int sceneUnit, int occlusionUnit, int shadowCompareUnit, int alphaLayerUnit,
		int lightDataUnit, int clusterDataUnit)
	{
		freePrograms();
		rain.locate(p.rain, sceneUnit, occlusionUnit, occlusionUnit + 1, alphaLayerUnit, lightDataUnit, clusterDataUnit);
		snow.locate(p.snow, sceneUnit, occlusionUnit, occlusionUnit + 1, alphaLayerUnit, lightDataUnit, clusterDataUnit);
		surface.locate(p.surface, sceneUnit, occlusionUnit, shadowCompareUnit, alphaLayerUnit, lightDataUnit,
			clusterDataUnit);
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
	 * How overcast the sky is, 0 clear to 1 fully covered: rises with rain or snow as it fades in, follows its
	 * swells slowly, and fades out with it. 0 underground. Shared by everything that should follow the weather
	 * (clouds, sky, light).
	 */
	float cloudCover()
	{
		if (!openSky || activeMode == WeatherMode.OFF || slowEffective < 0)
		{
			return 0;
		}
		return fadeIn * Math.min(1, COVER_BASE + COVER_SLOPE * slowEffective);
	}

	/**
	 * How much the weather thickens fog, as a multiplier: thinner in clear weather, thicker as rain or snow
	 * fades in.
	 */
	float fogMultiplier()
	{
		float k = openSky && slowEffective >= 0 ? Math.min(1, slowEffective) * fadeIn : 0;
		float extra = activeMode == WeatherMode.RAIN ? FOG_RAIN : activeMode == WeatherMode.SNOW ? FOG_SNOW : 0;
		return FOG_CLEAR + extra * k;
	}

	/**
	 * sky.glsl's weatherOvercast: cover times the overcast setting, and 0 for rain or 1 for snow.
	 */
	void getOvercast(float[] out)
	{
		out[0] = cloudCover() * overcastStrength;
		out[1] = activeMode == WeatherMode.SNOW ? 1 : 0;
	}

	/**
	 * Set the weatherOvercast uniform of the program in use.
	 */
	void setOvercastUniform(int location)
	{
		glUniform2f(location, cloudCover() * overcastStrength, activeMode == WeatherMode.SNOW ? 1 : 0);
	}

	/**
	 * Decide whether weather runs this frame and advance the fade in or out, the buildup and the variation.
	 * Off, indoors or underground, nothing else runs, and turning weather off frees the occlusion map once it has
	 * faded out.
	 *
	 * @param varying whether intensity and wind wander over time
	 * @param overcastPercent how much the cover grays the sky and softens the light
	 * @param outdoors whether the area has a sky at all
	 * @param fx player ground position, world units
	 */
	boolean update(WeatherMode mode, int intensityPercent, boolean gradual, boolean varying, int overcastPercent,
		boolean outdoors, float fx, float fy, float fz, int plane)
	{
		long now = System.currentTimeMillis();
		float dt = lastMillis == 0 ? 0 : Math.min((now - lastMillis) / 1000f, 0.25f);
		lastMillis = now;
		openSky = outdoors;
		overcastStrength = overcastPercent / 100f;

		WeatherMode target = intensityPercent > 0 ? mode : WeatherMode.OFF;
		if (target != activeMode)
		{
			if (outdoors && gradual && activeMode != WeatherMode.OFF && fadeIn > 0)
			{
				// let the old weather fade out before anything else starts; rain doesn't turn into snow cover
				fadeIn = Math.max(0, fadeIn - dt / FADE_OUT_SECONDS);
				accumulation = Math.max(0, accumulation - dt / FADE_OUT_SECONDS);
			}
			else
			{
				activeMode = target;
				fadeIn = 0;
				accumulation = 0;
				slowEffective = -1;
			}
		}

		active = activeMode != WeatherMode.OFF && outdoors;
		if (activeMode == WeatherMode.OFF)
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

		boolean fadingOut = target != activeMode;
		if (!fadingOut)
		{
			intensity = intensityPercent / 100f;
			if (gradual)
			{
				fadeIn = Math.min(1, fadeIn + dt / FADE_IN_SECONDS);
				float build = activeMode == WeatherMode.RAIN ? WET_BUILD_SECONDS : SNOW_BUILD_SECONDS;
				accumulation = Math.min(1, accumulation + dt / build);
			}
			else
			{
				fadeIn = 1;
				accumulation = 1;
			}
		}

		double t = (now - startMillis) / 1000.0;
		float variation = varying ? WeatherVariation.intensity(t) : 1;
		gust = varying ? WeatherVariation.gust(t) : 0;
		effective = Math.min(intensity * variation, MAX_EFFECTIVE);
		slowEffective = slowEffective < 0 ? effective
			: slowEffective + (effective - slowEffective) * (1 - (float) Math.exp(-dt / COVER_SECONDS));
		updateWind(t, varying, dt, now);

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
	 * This frame's wind, and how far it has carried the particles. Particles take the distance rather than the
	 * velocity times the time, so a change in wind bends their path instead of moving all of them at once.
	 */
	private void updateWind(double t, boolean varying, float dt, long now)
	{
		boolean isRain = activeMode == WeatherMode.RAIN;
		// a little slant, stronger in heavy rain
		float x = isRain ? 90 + 160 * effective : 30 + 50 * effective;
		float z = isRain ? 50 + 80 * effective : 20;
		if (varying)
		{
			float scale = WeatherVariation.windScale(t) * (1 + (isRain ? RAIN_GUST : SNOW_GUST) * gust);
			float turn = WeatherVariation.windTurn(t);
			float c = (float) Math.cos(turn), s = (float) Math.sin(turn);
			float rx = (x * c - z * s) * scale;
			z = (x * s + z * c) * scale;
			x = rx;
		}
		windX = x;
		windZ = z;

		long wraps = (now - startMillis) / CLOCK_WRAP_MILLIS;
		if (wraps != clockWraps)
		{
			// the particle clock wrapped and every particle moved anyway; start the distance over with it
			clockWraps = wraps;
			windOffsetX = windOffsetZ = 0;
		}
		windOffsetX += windX * dt;
		windOffsetZ += windZ * dt;
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
		final int uniFoliageOpen = glGetUniformLocation(program, "foliageOpen");
		glUniform1i(uniFoliageOpen, 1);

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
			glUniform1i(uniFoliageOpen, 0);
		}

		glBindVertexArray(0);
		glDepthFunc(GL_GREATER);
		glDisable(GL_DEPTH_TEST);
		glUseProgram(0);
	}

	/**
	 * Draw this frame's weather over the composited scene in the bound framebuffer. The scene textures, the
	 * transparency layer, the shadow map (if any) and the light clusters (with pointLights) must still be bound on
	 * the units given to setPrograms, as they are right after the composite.
	 *
	 * @param shadowProjRel camera-relative light matrix, or null without shadows
	 * @param pointLights light particles and snow cover with this frame's light clusters
	 */
	void draw(float[] viewProj, float[] invViewProj, int[] viewport, boolean zeroToOne,
		float cameraX, float cameraY, float cameraZ, DayCycle day, float[] shadowProjRel,
		boolean splashes, boolean surfaces, int occlusionUnit, boolean pointLights)
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
			glUniform1f(surface.amount, accumulation * (0.4f + 0.6f * Math.min(1, slowEffective)));
			glUniform1i(surface.surfaceMode, isRain ? 1 : 2);
			glUniform1f(surface.fadeDistance, SURFACE_FADE);
			glUniform1i(surface.pointLights, pointLights ? 1 : 0);
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
		glUniform1i(u.pointLights, pointLights ? 1 : 0);
		glUniform3f(u.wind, windX, 0, windZ);
		glUniform3f(u.windOffset, windOffsetX, 0, windOffsetZ);
		glUniform1f(u.gust, gust);
		int count;
		if (isRain)
		{
			int drops = Math.round(MAX_RAIN_DROPS * effective * fadeIn);
			int splashCount = splashes ? Math.round(MAX_RAIN_SPLASHES * effective * accumulation) : 0;
			glUniform1i(u.dropCount, drops);
			glUniform1i(u.splashCount, splashCount);
			count = drops + splashCount;
		}
		else
		{
			count = Math.round(MAX_SNOW_FLAKES * effective * fadeIn);
			glUniform1i(u.dropCount, count);
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
		setOvercastUniform(u.overcast);
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
