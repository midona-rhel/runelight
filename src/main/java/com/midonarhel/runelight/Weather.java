package com.midonarhel.runelight;

import com.midonarhel.runelight.config.WeatherMode;
import com.midonarhel.runelight.template.Template;
import java.util.Collections;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Weather: how cloudy, foggy, windy and stormy it is, and rain and snow as falling particles, rain splashes, and
 * wet or snow covered surfaces, each drawn in its own small pass over the composited scene.
 *
 * Everything follows a few values (cover, rain, snow, wind, fog, storm) that ease toward the targets of the
 * current weather: the automatic forecast's WeatherType, or the manual rain or snow setting. A change of weather or
 * of region therefore always blends; clouds build before rain starts and stay until it has stopped.
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

	// easing time constants, seconds: about three of them for a full change, so a new weather or region blends
	// in over ~30-60 s
	private static final float CLOUD_SECONDS = 15;
	private static final float PRECIP_SECONDS = 8;
	private static final float FOG_SECONDS = 15;
	private static final float WIND_SECONDS = 8;
	private static final float STORM_SECONDS = 10;
	// rain or snow starts once the clouds are this far toward their target cover
	private static final float PRECIP_GATE = 0.8f;
	// below this, rain or snow has stopped
	private static final float PRECIP_EPSILON = 0.003f;
	// the intensity setting at which automatic weather has its own amounts
	private static final float DEFAULT_INTENSITY = 0.6f;
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
	// seconds for wet ground to dry, and for snow cover to melt (rain melts it three times as fast)
	private static final float DRY_SECONDS = 120;
	private static final float MELT_SECONDS = 180;
	// manual rain and snow: fog added at full intensity over clear weather's
	private static final float FOG_RAIN = 0.75f;
	private static final float FOG_SNOW = 0.65f;
	// fog multiplier underground, where there is no weather
	private static final float FOG_CAVE = 0.6f;
	// hours around which morning mist peaks, and its half width
	private static final double MIST_HOUR = 6.5;
	private static final double MIST_WIDTH = 3;
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
			int lightDataUnit, int clusterDataUnit, int materialUnit)
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
			glUniform1i(glGetUniformLocation(p, "occlusionCompare"), occlusionUnit + 1);
			glUniform1i(glGetUniformLocation(p, "shadowCompare"), shadowCompareUnit);
			glUniform1i(glGetUniformLocation(p, "sceneAlpha"), alphaLayerUnit);
			glUniform1i(glGetUniformLocation(p, "lightData"), lightDataUnit);
			glUniform1i(glGetUniformLocation(p, "clusterData"), clusterDataUnit);
			glUniform1i(glGetUniformLocation(p, "sceneMaterial"), materialUnit);
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
	private boolean initialized;
	private boolean active;
	private boolean openSky;
	private float overcastStrength;
	// the eased weather: cloud cover 0-1, rain and snow intensity, wind 0-1, fog multipliers, storm 0-1, and how
	// snowy the overcast looks (0 rain gray, 1 snow white)
	private float cover;
	private float rainAmount, snowAmount;
	private float wind = WeatherType.CLEAR.wind;
	private float fog = WeatherType.CLEAR.fog;
	private float areaFog = WeatherType.CLEAR.areaFog;
	private float distanceFog = WeatherType.CLEAR.distanceFog;
	// FogClimate: multiplier on the fog height (low mist, rising haze) and the region's humidity
	private float fogHeight = 1;
	private float humidity = 1;
	private float storm;
	private float snowiness;
	// rain and snow with this moment's swell or lull, and their slow average that the cloud cover follows
	private float rainEffective, snowEffective;
	private float slowEffective;
	private float gust;
	// wind this frame and how far it has carried the particles, world units; reset when the particle clock wraps
	private float windX, windZ;
	private float windOffsetX, windOffsetZ;
	private long clockWraps;
	// 0-1 buildup of wet ground and of snow cover
	private float wetness, snowCover;
	private final Lightning lightning = new Lightning();
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
		int lightDataUnit, int clusterDataUnit, int materialUnit)
	{
		freePrograms();
		rain.locate(p.rain, sceneUnit, occlusionUnit, occlusionUnit + 1, alphaLayerUnit, lightDataUnit, clusterDataUnit,
			materialUnit);
		snow.locate(p.snow, sceneUnit, occlusionUnit, occlusionUnit + 1, alphaLayerUnit, lightDataUnit, clusterDataUnit,
			materialUnit);
		surface.locate(p.surface, sceneUnit, occlusionUnit, shadowCompareUnit, alphaLayerUnit, lightDataUnit,
			clusterDataUnit, materialUnit);
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

	Lightning getLightning()
	{
		return lightning;
	}

	/**
	 * How overcast the sky is, 0 clear to 1 fully covered. 0 underground. Shared by everything that should follow
	 * the weather (clouds, sky, light).
	 */
	float cloudCover()
	{
		return openSky ? Math.min(1, cover) : 0;
	}

	/**
	 * Wind over open water: the direction it blows toward (x, z, normalized) and its strength 0-1 with gusts.
	 * Still underground.
	 */
	void getWaterWind(float[] out)
	{
		float len = (float) Math.hypot(windX, windZ);
		out[0] = len > 1e-3f ? windX / len : 0.87f;
		out[1] = len > 1e-3f ? windZ / len : 0.49f;
		out[2] = openSky ? Math.min(1, wind + 0.5f * gust) : 0;
	}

	/**
	 * Rain falling on open water, 0-1.
	 */
	float waterRain()
	{
		return openSky ? Math.min(1, rainEffective) : 0;
	}

	/**
	 * How much the weather thickens the volumetric fog, as a multiplier: light in clear weather, thick in fog,
	 * and with FogClimate, misty or dry by region and time of day.
	 */
	float fogMultiplier()
	{
		return openSky ? fog : FOG_CAVE;
	}

	/**
	 * Multiplier on the volumetric fog height: mist lies low in the morning, heat haze rises.
	 */
	float fogHeightMultiplier()
	{
		return openSky ? fogHeight : 1;
	}

	/**
	 * How hazy the air is, 0 to 1, for the stars: damp regions and fog hide the faint ones.
	 */
	float skyHaze()
	{
		return openSky ? FogClimate.haze(humidity, fog) : 0;
	}

	/**
	 * How much of an area's own fog (EnvironmentManager.getFogDepth) the volumetric fog takes on: modest in clear
	 * weather, all of it in fog.
	 */
	float areaFogWeight()
	{
		return openSky ? areaFog : 1;
	}

	/**
	 * Multiplier on the area's distance fog and sky haze.
	 */
	float distanceFogScale()
	{
		return openSky ? distanceFog : 1;
	}

	/**
	 * sky.glsl's weatherOvercast: cover times the overcast setting, how snowy (white) rather than rainy (slate)
	 * the overcast is, and the lightning flash.
	 */
	void getOvercast(float[] out)
	{
		out[0] = cloudCover() * overcastStrength;
		out[1] = snowiness;
		out[2] = openSky ? lightning.getFlash() : 0;
	}

	/**
	 * Set the weatherOvercast uniform of the program in use.
	 */
	void setOvercastUniform(int location)
	{
		glUniform3f(location, cloudCover() * overcastStrength, snowiness, openSky ? lightning.getFlash() : 0);
	}

	/**
	 * Ease the weather toward this frame's targets, advance the buildup, the variation and the lightning, and
	 * decide whether rain or snow is drawn. Underground the weather goes on but nothing is drawn, and the
	 * occlusion map is freed once nothing is left to draw.
	 *
	 * @param mode Automatic follows {@code auto}; Off is clear weather; Rain and Snow are manual
	 * @param auto the forecast's weather for the region, used in Automatic mode
	 * @param gradual false applies every change at once
	 * @param varying whether intensity and wind wander over time
	 * @param overcastPercent how much the cover grays the sky and softens the light
	 * @param lightningEnabled whether storms strike
	 * @param outdoors whether the area has a sky at all
	 * @param hour clock time 0-24, for mist by the time of day
	 * @param biome the region's 3D Weather biome or null, for {@link FogClimate}
	 * @param climate whether fog follows the region and the time of day (FogClimate); otherwise only a little
	 *                morning mist
	 * @param fx player ground position, world units
	 */
	boolean update(WeatherMode mode, WeatherType auto, int intensityPercent, boolean gradual, boolean varying,
		int overcastPercent, boolean lightningEnabled, boolean outdoors, double hour, String biome, boolean climate,
		float fx, float fy, float fz, int plane)
	{
		long now = System.currentTimeMillis();
		float dt = lastMillis == 0 ? 0 : Math.min((now - lastMillis) / 1000f, 0.25f);
		lastMillis = now;
		openSky = outdoors;
		overcastStrength = overcastPercent / 100f;
		double t = (now - startMillis) / 1000.0;
		float scale = intensityPercent / 100f;

		// this frame's targets
		WeatherType type;
		float targetCover, targetRain = 0, targetSnow = 0, targetFog;
		switch (mode)
		{
			case RAIN:
			case SNOW:
			{
				boolean rain = mode == WeatherMode.RAIN;
				type = rain ? WeatherType.RAIN : WeatherType.SNOW;
				targetCover = Math.min(1, COVER_BASE + COVER_SLOPE * scale);
				if (rain)
				{
					targetRain = scale;
				}
				else
				{
					targetSnow = scale;
				}
				targetFog = WeatherType.CLEAR.fog + (rain ? FOG_RAIN : FOG_SNOW) * Math.min(1, scale);
				break;
			}
			case AUTOMATIC:
				type = auto != null ? auto : WeatherType.CLEAR;
				targetCover = type.cover;
				targetRain = type.rain * scale / DEFAULT_INTENSITY;
				targetSnow = type.snow * scale / DEFAULT_INTENSITY;
				targetFog = type.fog;
				break;
			default:
				type = WeatherType.CLEAR;
				targetCover = 0;
				targetFog = type.fog;
				break;
		}
		float targetFogHeight = 1;
		float targetHumidity = 1;
		if (climate)
		{
			// replaces the plain morning mist: each region mists over at its own hours, or not at all
			FogClimate.Profile p = FogClimate.profile(biome);
			float calm = FogClimate.calm(type);
			targetFog = FogClimate.total(targetFog, FogClimate.density(p, hour, calm, targetCover));
			targetFogHeight = FogClimate.height(p, hour, calm, targetCover);
			targetHumidity = p.humidity;
		}
		else
		{
			targetFog *= 1 + type.morningMist * morning(hour);
		}

		float precipitation = rainAmount + snowAmount;
		float baseCover = targetCover;
		// clouds swell with heavy spells, and stay while anything still falls
		if (precipitation > 0 || targetRain + targetSnow > 0)
		{
			targetCover = Math.max(targetCover, Math.min(1, COVER_BASE + COVER_SLOPE * slowEffective));
		}
		float targetWind = mode == WeatherMode.AUTOMATIC ? type.wind : 0.5f;

		if (!initialized || !gradual)
		{
			// at login or without gradual changes, start in the weather as it is
			initialized = true;
			cover = targetCover;
			rainAmount = targetRain;
			snowAmount = targetSnow;
			fog = targetFog;
			fogHeight = targetFogHeight;
			humidity = targetHumidity;
			areaFog = type.areaFog;
			distanceFog = type.distanceFog;
			wind = targetWind;
			storm = type.storm;
			if (targetRain + targetSnow > 0)
			{
				snowiness = targetSnow > targetRain ? 1 : 0;
			}
			wetness = targetRain > 0 ? 1 : gradual ? wetness : 0;
			snowCover = targetSnow > 0 ? 1 : gradual ? snowCover : 0;
		}
		else
		{
			// rain and snow wait for the clouds to build
			if (precipitation == 0 && cover < PRECIP_GATE * baseCover)
			{
				targetRain = targetSnow = 0;
			}
			cover = ease(cover, targetCover, dt, CLOUD_SECONDS);
			rainAmount = settle(ease(rainAmount, targetRain, dt, PRECIP_SECONDS), targetRain);
			snowAmount = settle(ease(snowAmount, targetSnow, dt, PRECIP_SECONDS), targetSnow);
			fog = ease(fog, targetFog, dt, FOG_SECONDS);
			fogHeight = ease(fogHeight, targetFogHeight, dt, FOG_SECONDS);
			humidity = ease(humidity, targetHumidity, dt, FOG_SECONDS);
			areaFog = ease(areaFog, type.areaFog, dt, FOG_SECONDS);
			distanceFog = ease(distanceFog, type.distanceFog, dt, FOG_SECONDS);
			wind = ease(wind, targetWind, dt, WIND_SECONDS);
			storm = ease(storm, type.storm, dt, STORM_SECONDS);
			if (rainAmount + snowAmount > PRECIP_EPSILON)
			{
				snowiness = ease(snowiness, snowAmount / (rainAmount + snowAmount), dt, PRECIP_SECONDS);
			}
			accumulate(dt);
		}

		float variation = varying ? WeatherVariation.intensity(t) : 1;
		gust = varying ? WeatherVariation.gust(t) * Math.min(1, 0.5f + wind) : 0;
		rainEffective = Math.min(rainAmount * variation, MAX_EFFECTIVE);
		snowEffective = Math.min(snowAmount * variation, MAX_EFFECTIVE);
		float effective = rainEffective + snowEffective;
		slowEffective = !gradual ? effective
			: slowEffective + (effective - slowEffective) * (1 - (float) Math.exp(-dt / COVER_SECONDS));
		updateWind(t, varying, dt, now);
		lightning.update(dt, outdoors ? storm : 0, lightningEnabled, fx, fz);

		boolean anything = rainAmount > 0 || snowAmount > 0 || wetness > 0 || snowCover > 0;
		active = anything && outdoors;
		if (!anything)
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
			return false;
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

	/** Wet ground builds in rain and dries after it; snow cover builds in snow and melts after, faster in rain. */
	private void accumulate(float dt)
	{
		if (rainAmount > 0.02f)
		{
			wetness = Math.min(1, wetness + dt / WET_BUILD_SECONDS * Math.min(1, rainAmount * 2));
		}
		else
		{
			wetness = Math.max(0, wetness - dt / DRY_SECONDS);
		}
		if (snowAmount > 0.02f)
		{
			snowCover = Math.min(1, snowCover + dt / SNOW_BUILD_SECONDS * Math.min(1, snowAmount * 2));
		}
		else
		{
			snowCover = Math.max(0, snowCover - dt / MELT_SECONDS * (rainAmount > 0.02f ? 3 : 1));
		}
	}

	/** 0 to 1, peaking at dawn: thicker mist in the morning. */
	static float morning(double hour)
	{
		double d = Math.abs(hour - MIST_HOUR);
		d = Math.min(d, 24 - d);
		double x = Math.max(0, 1 - d / MIST_WIDTH);
		return (float) (x * x * (3 - 2 * x));
	}

	static float ease(float value, float target, float dt, float seconds)
	{
		return value + (target - value) * (1 - (float) Math.exp(-dt / seconds));
	}

	/** Snap to the target once close: exponential easing never quite reaches zero. */
	private static float settle(float value, float target)
	{
		return target == 0 && value < PRECIP_EPSILON ? 0 : value;
	}

	/**
	 * This frame's wind, and how far it has carried the particles. Particles take the distance rather than the
	 * velocity times the time, so a change in wind bends their path instead of moving all of them at once.
	 */
	private void updateWind(double t, boolean varying, float dt, long now)
	{
		// rain and snow share one wind, weighted by how much of each falls; a little slant, stronger in heavy
		// rain and strong wind
		float total = rainEffective + snowEffective;
		float r = total > 0 ? rainEffective / total : 1 - snowiness;
		float strength = 0.6f + 0.8f * wind;
		float x = (r * (90 + 160 * rainEffective) + (1 - r) * (30 + 50 * snowEffective)) * strength;
		float z = (r * (50 + 80 * rainEffective) + (1 - r) * 20) * strength;
		if (varying)
		{
			float scale = WeatherVariation.windScale(t) * (1 + (r * RAIN_GUST + (1 - r) * SNOW_GUST) * gust);
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

		float time = ((System.currentTimeMillis() - startMillis) % CLOCK_WRAP_MILLIS) / 1000f;

		// only the game viewport; gl_FragCoord stays in whole-target pixels, as the scene textures expect
		glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);

		// surfaces first so falling weather draws over them
		if (surfaces)
		{
			float swell = 0.4f + 0.6f * Math.min(1, slowEffective);
			drawSurface(1, wetness * swell, viewProj, invViewProj, viewport, zeroToOne, cameraX, cameraY, cameraZ,
				day, time, shadowProjRel, pointLights);
			drawSurface(2, snowCover * swell, viewProj, invViewProj, viewport, zeroToOne, cameraX, cameraY, cameraZ,
				day, time, shadowProjRel, pointLights);
		}

		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		int drops = Math.round(MAX_RAIN_DROPS * rainEffective);
		int splashCount = splashes ? Math.round(MAX_RAIN_SPLASHES * rainEffective * wetness) : 0;
		if (drops + splashCount > 0)
		{
			glUseProgram(rain.program);
			setParticles(rain, rainAmount, viewProj, invViewProj, viewport, zeroToOne, cameraX, cameraY, cameraZ, day,
				time, pointLights);
			glUniform1i(rain.dropCount, drops);
			glUniform1i(rain.splashCount, splashCount);
			// one quad per particle, everything else comes from gl_InstanceID
			glDrawArraysInstanced(GL_TRIANGLES, 0, 6, drops + splashCount);
		}
		int flakes = Math.round(MAX_SNOW_FLAKES * snowEffective);
		if (flakes > 0)
		{
			glUseProgram(snow.program);
			setParticles(snow, snowAmount, viewProj, invViewProj, viewport, zeroToOne, cameraX, cameraY, cameraZ, day,
				time, pointLights);
			glUniform1i(snow.dropCount, flakes);
			glDrawArraysInstanced(GL_TRIANGLES, 0, 6, flakes);
		}

		glDisable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glBindVertexArray(0);
		glUseProgram(0);
		ShadowMap.unbind(occlusionUnit, occlusionUnit + 1);
	}

	private void drawSurface(int mode, float amount, float[] viewProj, float[] invViewProj, int[] viewport,
		boolean zeroToOne, float cameraX, float cameraY, float cameraZ, DayCycle day, float time, float[] shadowProjRel,
		boolean pointLights)
	{
		if (amount <= 0)
		{
			return;
		}
		glUseProgram(surface.program);
		setCommon(surface, viewProj, invViewProj, viewport, zeroToOne, cameraX, cameraY, cameraZ, day, time);
		glUniform1f(surface.amount, amount);
		glUniform1i(surface.surfaceMode, mode);
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

	private void setParticles(Uniforms u, float kindAmount, float[] viewProj, float[] invViewProj, int[] viewport,
		boolean zeroToOne, float cameraX, float cameraY, float cameraZ, DayCycle day, float time, boolean pointLights)
	{
		setCommon(u, viewProj, invViewProj, viewport, zeroToOne, cameraX, cameraY, cameraZ, day, time);
		// the count already follows the amount; only the last trickle also fades
		glUniform1f(u.amount, Math.min(1, kindAmount / 0.1f));
		glUniform1i(u.pointLights, pointLights ? 1 : 0);
		glUniform3f(u.wind, windX, 0, windZ);
		glUniform3f(u.windOffset, windOffsetX, 0, windOffsetZ);
		glUniform1f(u.gust, gust);
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
