package com.midonarhel.runelight;

import com.midonarhel.runelight.config.WeatherMode;
import com.midonarhel.runelight.template.Template;
import java.util.Collections;
import net.runelite.api.Scene;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Weather: how cloudy, foggy, windy and stormy it is, and rain and snow as falling particles and rain splashes, each
 * drawn in its own small pass over the composited scene. Wet ground and snow cover are part of the surfaces
 * themselves: the AO pass finds how much of each a pixel gets (weather_ground_ao.glsl) and the composite lights them
 * like everything else (weather_ground.glsl).
 *
 * Everything the player sees (cover, rain, snow, wind, fog and its height, storm, the light's strength, haze and
 * tint, the look of the clouds and the particles, the ground) is the weather field's at the player: WeatherField
 * holds the weather of every place around, each cell easing toward its biome's forecast (Climate) or the one forced
 * in the settings, and blended across region borders by position. A change of weather therefore always blends,
 * clouds build before rain starts and stay until it has stopped, and walking into a region where it has been snowing
 * for days shows it snowing, at full strength, on deep snow, with no step in time or space at the border. This class
 * adds what the cells do not have: showers, swells, gusts and the wind (Wind.java), which drives the rain's slant,
 * the snow's drift, the clouds and the water.
 *
 * Shelter comes from an "occlusion map": a top-down orthographic depth map of all static scenery around the
 * player, drawn with every roof and every level, including roofs the game (or a roof hiding plugin) hides from the
 * camera. A point is under the open sky when nothing in its column is above it. The map is only redrawn when the
 * player walks away from its center, the scene loads or a zone changes, so it costs nothing on most frames.
 *
 * Away from the player the weather is the field's there: the passes add its difference from the player's to the
 * player's own, the clouds look it up where the view ray crosses them. Wet ground and snow cover reach over the whole
 * field (past the occlusion map everything counts as under the open sky). The particles fill everything the camera
 * sees out to the draw distance, wherever it rains or snows: in layers of world-anchored boxes, each twice the size
 * of the last and owning a band of distance from the camera, with fewer, larger particles the farther the band
 * (weather_common.glsl). So a change of weather shows over the whole view at once, thinning gradually into the
 * distance.
 */
class Weather
{
	// occlusion map: OCCLUSION_SIZE^2 texels over +-OCCLUSION_RADIUS world units, 1/8 tile per texel, 4 MB
	private static final int OCCLUSION_SIZE = 1024;
	private static final float OCCLUSION_RADIUS = 64 * 128;
	// world units above and below the player's ground that the map captures
	private static final float OCCLUSION_DEPTH_RANGE = 4096;
	// redraw once the player is this far from the map center; past the map, particles fall to the ground (hidden by
	// the scene) instead of stopping on roofs, which is too small to see there
	private static final float OCCLUSION_RECENTER = 24 * 128;
	// Particle layers. The nearest owns distances up to LAYER_NEAR from the camera, each next one twice as far; a
	// layer's box is that distance across each way, centered a little ahead of the camera (LAYER_AHEAD of its half
	// width, as far as the camera looks sideways rather than down), so it covers its band of what the camera sees.
	static final int MAX_LAYERS = 6;
	static final float LAYER_NEAR = 1536;
	private static final float LAYER_AHEAD = 0.4f;
	// per layer outward: particle size (streak length and width, flake size), and the share of particles drawn
	// relative to the layer before: the boxes grow 4 times in area (and taller), so density thins about 6 times per
	// layer while the particles grow, and a typical view (layers 1-4) draws about as many as one box used to
	static final float LAYER_SIZE_GROWTH = 1.6f;
	static final float LAYER_COUNT_FALLOFF = 0.85f;
	// layer crossfade, LAYER_CROSSFADE in weather_common.glsl
	static final float LAYER_CROSSFADE = 0.35f;
	// height of the nearest boxes above and depth below the player's ground, world units; farther boxes are taller
	// (by LAYER_HEIGHT_GROWTH per layer, up to LAYER_HEIGHT_MAX) to reach over hills and to the sky behind them
	private static final float RAIN_ABOVE = 2560, RAIN_BELOW = 768;
	private static final float SNOW_ABOVE = 1792, SNOW_BELOW = 640;
	private static final float LAYER_HEIGHT_GROWTH = 1.35f;
	private static final float LAYER_HEIGHT_MAX = 6144;
	// droplets thrown up by one step (WADE_DROPLETS in rain_vert.glsl)
	private static final int WADE_DROPLETS = 6;
	// half width of the area where splashes land around the player (SPLASH_HALF in rain_vert.glsl)
	private static final float SPLASH_HALF = 2560;
	// straight up: the "light" looks straight down
	private static final float[] UP = {0, -1, 0};

	// instance counts at 100% intensity: drops and flakes in the nearest layer (LAYER_COUNT_FALLOFF less in each
	// next one), and splashes
	static final int LAYER_RAIN_DROPS = 24000;
	static final int MAX_RAIN_SPLASHES = 4400;
	static final int LAYER_SNOW_FLAKES = 12000;

	// easing time constants, seconds: about three of them for a full change, so a new weather or region blends
	// in over ~30-60 s
	static final float CLOUD_SECONDS = 15;
	static final float PRECIP_SECONDS = 8;
	static final float FOG_SECONDS = 15;
	private static final float WIND_SECONDS = 8;
	static final float STORM_SECONDS = 10;
	// rain or snow starts once the clouds are this far toward their target cover
	static final float PRECIP_GATE = 0.8f;
	// below this, rain or snow has stopped
	static final float PRECIP_EPSILON = 0.003f;
	// the intensity setting at which automatic weather has its own amounts
	static final float DEFAULT_INTENSITY = 0.6f;
	// cloud cover at the lightest rain or snow, and what each unit of intensity adds
	static final float COVER_BASE = 0.35f;
	static final float COVER_SLOPE = 0.8f;
	// varying intensity may peak this far over 100%, as a share of the instance counts
	private static final float MAX_EFFECTIVE = 1.2f;
	// wind multiplier at a full gust
	private static final float RAIN_GUST = 1.2f;
	private static final float SNOW_GUST = 1.5f;
	// horizontal speed of rain and snow, world units per second: at the lightest fall, plus per unit of intensity,
	// times DRIFT_CALM + DRIFT_WIND * wind strength (and the type's slant or flake wind)
	private static final float RAIN_DRIFT = 120, RAIN_DRIFT_HEAVY = 140;
	private static final float SNOW_DRIFT = 35, SNOW_DRIFT_HEAVY = 50;
	private static final float DRIFT_CALM = 0.3f, DRIFT_WIND = 1.2f;
	// showers: seconds per noise step of the rain coming and going
	private static final double SHOWER_PERIOD = 40;
	// on average, showers rain this share of the time (for the weather field, which has no swells)
	static final float SHOWER_SHARE = 0.6f;
	static final float WET_BUILD_SECONDS = 25;
	static final float SNOW_BUILD_SECONDS = 240;
	// seconds for wet ground to dry, and for snow cover to melt (rain melts it three times as fast)
	static final float DRY_SECONDS = 120;
	static final float MELT_SECONDS = 180;
	// fog multiplier underground, where there is no weather
	private static final float FOG_CAVE = 0.6f;
	// the particle clock wraps so float precision holds; a wrap moves every particle once, unnoticeable in rain
	private static final long CLOCK_WRAP_MILLIS = 600_000;

	static final Shader RAIN_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "rain_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "rain_frag.glsl");

	static final Shader SNOW_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "snow_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "snow_frag.glsl");

	/**
	 * Freshly compiled programs, so a hot reload can compile everything before replacing anything.
	 */
	static final class Programs
	{
		final int rain, snow;

		Programs(int rain, int snow)
		{
			this.rain = rain;
			this.snow = snow;
		}
	}

	/**
	 * Uniform locations of one weather program. Both share weather_common.glsl and frame.glsl.
	 */
	private static final class Uniforms
	{
		int program;
		int viewProj, invViewProj, viewport, depthZeroToOne;
		int occlusionProjRel, occlusionCenterY, occlusionDepthRange;
		int cameraPos, focusPos, time, sunAltitude, casterDir, sunIsCaster, amount;
		int dropCount, splashCount, wadeDroplets, wadeSteps, waterRect, wind, windOffset, gust, overcast,
			pointLights;
		int particleAmount, layerBox, layerSpan, layerBand, particleReach;
		int light, rainStyle, flakeStyle, flakeColor, fallTime;
		WeatherField.Locations field;
		Wind.Locations windLocations;

		void locate(int p, int sceneUnit, int materialUnit, int occlusionUnit, int shadowCompareUnit, int alphaLayerUnit,
			int lightDataUnit, int clusterDataUnit, int waterUnit, int fieldUnit)
		{
			program = p;
			field = WeatherField.locate(p, fieldUnit, fieldUnit + 1);
			windLocations = Wind.locate(p);
			light = glGetUniformLocation(p, "weatherLight");
			rainStyle = glGetUniformLocation(p, "rainStyle");
			flakeStyle = glGetUniformLocation(p, "flakeStyle");
			flakeColor = glGetUniformLocation(p, "flakeColor");
			fallTime = glGetUniformLocation(p, "fallTime");
			particleAmount = glGetUniformLocation(p, "particleAmount");
			layerBox = glGetUniformLocation(p, "layerBox");
			layerSpan = glGetUniformLocation(p, "layerSpan");
			layerBand = glGetUniformLocation(p, "layerBand");
			particleReach = glGetUniformLocation(p, "particleReach");
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
			wadeDroplets = glGetUniformLocation(p, "wadeDroplets");
			wadeSteps = glGetUniformLocation(p, "wadeSteps");
			waterRect = glGetUniformLocation(p, "waterRect");
			wind = glGetUniformLocation(p, "wind");
			windOffset = glGetUniformLocation(p, "windOffset");
			gust = glGetUniformLocation(p, "gust");
			overcast = glGetUniformLocation(p, "weatherOvercast");
			pointLights = glGetUniformLocation(p, "pointLightsEnabled");

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
			glUniform1i(glGetUniformLocation(p, "waterSurface"), waterUnit);
			glUniform1i(glGetUniformLocation(p, "sceneMaterial"), materialUnit);
			glUseProgram(0);
		}
	}

	private final Uniforms rain = new Uniforms();
	private final Uniforms snow = new Uniforms();
	private final WeatherField field = new WeatherField();

	private final ShadowMap occlusion = new ShadowMap();
	// particles have no vertex attributes; an empty VAO keeps drivers from fetching unrelated buffers
	private int vao;
	private final float[] occlusionProjRel = Mat4.identity();
	private boolean occlusionDirty = true;
	private float occlusionX, occlusionY, occlusionZ;
	private int occlusionPlane = -1;

	// per frame
	private boolean active;
	private boolean openSky;
	private float overcastStrength;
	// the eased weather: cloud cover 0-1, rain and snow intensity, wind 0-1, fog multipliers, storm 0-1, and how
	// snowy the overcast looks (0 rain gray, 1 snow white)
	private float cover;
	private float rainAmount, snowAmount;
	private float fog = WeatherType.clear().fog;
	private float areaFog = WeatherType.clear().areaFog;
	private float distanceFog = WeatherType.clear().distanceFog;
	// the field's look at the player: clouds (wisp, dark), light (sun - 1, haze, warmth, sky saturation), fog tint,
	// rain drops (length, speed, width, alpha, slant), flakes (size, speed, sway, wind, then color)
	private float cloudWisp, cloudDark;
	private final float[] light = new float[4];
	private final float[] fogTint = {1, 1, 1};
	private final float[] rainStyle = {1, 1, 1, 1, 1};
	private final float[] flakeStyle = {1, 1, 1, 1, 0.95f, 0.97f, 1.0f};
	private final float[] scratch = new float[8];
	private WeatherType type = WeatherType.clear();
	// FogClimate: multiplier on the fog height (low mist, rising haze) and the region's humidity
	private float fogHeight = 1;
	private float humidity = 1;
	private float storm;
	private float snowiness;
	// rain and snow with this moment's swell or lull
	private float rainEffective, snowEffective;
	private float gust;
	// wind this frame and how far it has carried the particles, world units; reset when the particle clock wraps
	private float windX, windZ;
	private float windOffsetX, windOffsetZ;
	// seconds of fall at the drops' and the flakes' own speed so far: their speed eases with the weather, so the
	// particles take the integral, like the wind's distance
	private float rainFall, snowFall;
	private long clockWraps;
	// 0-1 buildup of wet ground and of snow cover
	private float wetness, snowCover;
	private final Lightning lightning = new Lightning();
	private long lastMillis;
	// the prevailing wind is the same for everyone; it wanders with the clock (GameClock), not the session
	private final Wind windSim = new Wind(1);
	private float focusX, focusY, focusZ;
	private int focusPlane;

	Programs compile(Template template) throws ShaderException
	{
		int r = 0, s = 0;
		try
		{
			r = RAIN_PROGRAM.compile(template);
			s = SNOW_PROGRAM.compile(template);
			return new Programs(r, s);
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
	void setPrograms(Programs p, int sceneUnit, int materialUnit, int occlusionUnit, int shadowCompareUnit, int alphaLayerUnit,
		int lightDataUnit, int clusterDataUnit, int waterUnit, int fieldUnit)
	{
		freePrograms();
		rain.locate(p.rain, sceneUnit, materialUnit, occlusionUnit, occlusionUnit + 1, alphaLayerUnit, lightDataUnit, clusterDataUnit,
			waterUnit, fieldUnit);
		snow.locate(p.snow, sceneUnit, materialUnit, occlusionUnit, occlusionUnit + 1, alphaLayerUnit, lightDataUnit, clusterDataUnit,
			waterUnit, fieldUnit);
	}

	void freePrograms()
	{
		glDeleteProgram(rain.program);
		glDeleteProgram(snow.program);
		rain.program = snow.program = 0;
	}

	/**
	 * GL resources other than programs.
	 */
	void free()
	{
		occlusion.free();
		field.free();
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

	WeatherField getField()
	{
		return field;
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
		out[0] = windSim.getDirX();
		out[1] = windSim.getDirZ();
		out[2] = openSky ? Math.min(1, windSim.getStrength() + 0.5f * windSim.getGust()) : 0;
	}

	/**
	 * The wind, for anything that moves with it (Wind.apply sets a program's wind uniforms).
	 */
	Wind getWind()
	{
		return windSim;
	}

	/**
	 * Whether the air is still here: underground the wind uniforms read calm.
	 */
	boolean isWindStill()
	{
		return !openSky;
	}

	/** The weather the player's weather is heading for. */
	WeatherType getType()
	{
		return type;
	}

	/** clouds.glsl's cloudType: how wispy and high (0..1) and the darkness offset (-1..1). */
	float cloudWisp()
	{
		return openSky ? cloudWisp : 0;
	}

	float cloudDark()
	{
		return openSky ? cloudDark : 0;
	}

	/** Multiplier on the fog's color (dust, ash), 1 1 1 underground. */
	float[] getFogTint()
	{
		return openSky ? fogTint : NEUTRAL_TINT;
	}

	private static final float[] NEUTRAL_TINT = {1, 1, 1};

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
	 * the overcast is, and the lightning flash; with room for seven, also weatherLight (see {@link #getLight}).
	 */
	void getOvercast(float[] out)
	{
		out[0] = cloudCover() * overcastStrength;
		out[1] = snowiness;
		out[2] = openSky ? lightning.getFlash() : 0;
		if (out.length >= 7)
		{
			getLight(out, 3);
		}
	}

	/**
	 * sky.glsl's weatherLight from {@code offset}: sun strength - 1, haze, warmth, sky saturation offset, all 0 in
	 * neutral weather and underground. The overcast setting scales it like the cover.
	 */
	void getLight(float[] out, int offset)
	{
		float k = openSky ? Math.max(overcastStrength, 0.0f) : 0;
		for (int i = 0; i < 4; ++i)
		{
			out[offset + i] = light[i] * Math.min(k, 1);
		}
	}

	/**
	 * Set the weatherOvercast uniform of the program in use.
	 */
	void setOvercastUniform(int location)
	{
		glUniform3f(location, cloudCover() * overcastStrength, snowiness, openSky ? lightning.getFlash() : 0);
	}

	/**
	 * Set the weatherLight uniform of the program in use.
	 */
	void setLightUniform(int location)
	{
		getLight(scratch, 0);
		glUniform4f(location, scratch[0], scratch[1], scratch[2], scratch[3]);
	}

	/**
	 * Take this frame's weather from the field at the player, advance the variation, the wind and the lightning, and
	 * decide whether rain or snow is drawn. Underground the weather goes on but nothing is drawn, and the occlusion
	 * map is freed once nothing is left to draw. Call after {@link #updateField}, with the same focus.
	 *
	 * @param varying whether intensity and wind wander over time
	 * @param overcastPercent how much the cover grays the sky and changes the light
	 * @param lightningEnabled whether storms strike
	 * @param outdoors whether the area has a sky at all
	 * @param fx player ground position, world units
	 */
	boolean update(boolean gradual, boolean varying, int overcastPercent, boolean lightningEnabled, boolean outdoors,
		float fx, float fy, float fz, int plane)
	{
		long now = System.currentTimeMillis();
		float dt = lastMillis == 0 ? 0 : Math.min((now - lastMillis) / 1000f, 0.25f);
		lastMillis = now;
		openSky = outdoors;
		overcastStrength = overcastPercent / 100f;
		// real seconds since the clock's epoch: showers, swells and the wind's wander are the same whenever the
		// client started
		double t = GameClock.realSeconds(now);

		float windMean = type.wind, gustiness = type.gustiness;
		if (field.isEnabled())
		{
			takeField(t, varying);
			windMean = field.getFocus(WeatherField.WIND);
			gustiness = field.getFocus(WeatherField.GUSTINESS);
		}

		float variation = varying ? WeatherVariation.intensity(t) : 1;
		rainEffective = Math.min(rainAmount * variation, MAX_EFFECTIVE);
		snowEffective = Math.min(snowAmount * variation, MAX_EFFECTIVE);
		windSim.update(t, dt, windMean, gustiness, varying, gradual, fx, fz);
		gust = windSim.getGust();
		updateWind(dt, now);
		lightning.update(dt, outdoors ? storm : 0, lightningEnabled, fx, fz);

		boolean anything = rainAmount > 0 || snowAmount > 0 || wetness > 0 || snowCover > 0 || field.hasAnything();
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

	/**
	 * Take everything the weather looks like from the field at the player: its cells ease when their own weather
	 * changes and blend across region borders, so crossing one is a walk through a gradient, not a change in time.
	 * Showers (the rain coming and going) follow the blended share of showery weather.
	 */
	private void takeField(double t, boolean varying)
	{
		type = field.getFocusType() != null ? field.getFocusType() : WeatherType.clear();
		float showery = field.getFocus(WeatherField.SHOWERS);
		float showerK = varying && showery > 0 ? 1 - showery * (1 - showers(t)) : 1;
		// the cells hold the rain as it is on average over showers
		float k = showerK / Math.max(1 - showery * (1 - SHOWER_SHARE), 0.05f);
		cover = Math.min(1, field.getFocus(WeatherField.COVER));
		rainAmount = settle(field.getFocus(WeatherField.RAIN) * k, 0);
		snowAmount = settle(field.getFocus(WeatherField.SNOW) * k, 0);
		wetness = field.getFocus(WeatherField.WET);
		snowCover = field.getFocus(WeatherField.SNOW_COVER);
		fog = field.getFocus(WeatherField.FOG);
		fogHeight = field.getFocus(WeatherField.FOG_HEIGHT);
		humidity = field.getFocus(WeatherField.HUMIDITY);
		areaFog = field.getFocus(WeatherField.AREA_FOG);
		distanceFog = field.getFocus(WeatherField.DISTANCE_FOG);
		storm = field.getFocus(WeatherField.STORM);
		snowiness = field.getFocus(WeatherField.SNOWINESS);
		cloudWisp = field.getFocus(WeatherField.CLOUD_WISP);
		cloudDark = field.getFocus(WeatherField.CLOUD_DARK);
		field.getFocus(WeatherField.LIGHT, light);
		field.getFocus(WeatherField.FOG_TINT, fogTint);
		field.getFocus(WeatherField.RAIN_STYLE, rainStyle);
		field.getFocus(WeatherField.FLAKE_STYLE, flakeStyle);
	}

	/** wet ground and snow cover at the player, 0-1 */
	float getWetness()
	{
		return wetness;
	}

	float getSnowCover()
	{
		return snowCover;
	}

	/** The weather a manual mode asks for: clear for Off, else the forced type. */
	static WeatherType resolve(WeatherMode mode)
	{
		WeatherType t = mode == WeatherMode.OFF ? null : WeatherType.get(mode.name());
		return t != null ? t : WeatherType.clear();
	}

	/** 0 between showers to 1 in one: smooth noise, on about SHOWER_SHARE of the time. */
	static float showers(double t)
	{
		float n = WeatherVariation.noise(t / SHOWER_PERIOD, 13);
		float lo = 1 - SHOWER_SHARE - 0.1f, hi = 1 - SHOWER_SHARE + 0.1f;
		float x = Math.max(0, Math.min(1, (n - lo) / (hi - lo)));
		return x * x * (3 - 2 * x);
	}

	/**
	 * Keep the weather field laid over the world around the player and ease every cell toward its place's weather.
	 * The cells are anchored to the world, not the scene: the window re-centers on the player whenever the scene
	 * loads, and cells that stay inside it keep their weather. Call before {@link #update}, with the same focus.
	 *
	 * @param scene    the top-level scene, or null when there is none
	 * @param mode     Automatic: every place has its climate's forecast and ground (Climate); Off is clear weather
	 *                 everywhere; any other mode is that weather everywhere
	 * @param areas    whether area data (biomes) is on; without it every cell has the default biome
	 * @param biome    the player's biome, for cells whose place on the map is unknown (instances)
	 * @param hour     clock time 0-24, for mist by the time of day
	 * @param fx       player ground position, local units
	 */
	void updateField(Scene scene, int plane, EnvironmentManager env, boolean areas, String biome, WeatherMode mode,
		Climate climate, int intensityPercent, boolean gradual, double hour, float fx, float fz)
	{
		long now = System.currentTimeMillis();
		float dt = fieldMillis == 0 ? 0 : Math.min((now - fieldMillis) / 1000f, 0.25f);
		fieldMillis = now;
		if (scene == null)
		{
			fieldScene = null;
			return;
		}
		boolean relaid = false;
		if (scene != fieldScene || scene.getBaseX() != fieldBaseX || scene.getBaseY() != fieldBaseY
			|| areas != fieldAreas || plane != fieldPlane || !java.util.Objects.equals(biome, fieldBiome))
		{
			fieldBiome = biome;
			relaid = true;
			fieldScene = scene;
			fieldBaseX = scene.getBaseX();
			fieldBaseY = scene.getBaseY();
			fieldAreas = areas;
			fieldPlane = plane;
			layoutField(scene, plane, env, areas, biome, fx, fz);
		}
		// a tenth of a second is plenty for weather that changes over minutes
		fieldDt += dt;
		if (fieldDt >= FIELD_SECONDS || relaid)
		{
			boolean automatic = mode == WeatherMode.AUTOMATIC;
			WeatherType manual = automatic ? null : resolve(mode);
			field.update(automatic ? climate::type : b -> manual, automatic ? climate::ground : null,
				intensityPercent / 100f, gradual, hour, fieldDt);
			fieldDt = 0;
		}
		field.focus(fx, fz);
	}

	/** The cells around the player's world position, FIELD_RADIUS each way. */
	private void layoutField(Scene scene, int plane, EnvironmentManager env, boolean areas, String biome, float fx, float fz)
	{
		int n = 2 * FIELD_RADIUS + 1;
		int cx0 = Math.floorDiv(fieldBaseX + ((int) fx >> 7), WeatherField.CELL_TILES) - FIELD_RADIUS;
		int cz0 = Math.floorDiv(fieldBaseY + ((int) fz >> 7), WeatherField.CELL_TILES) - FIELD_RADIUS;
		long[] keys = new long[n * n];
		String[] biomes = new String[n * n];
		int[] wp = new int[3];
		for (int z = 0; z < n; ++z)
		{
			for (int x = 0; x < n; ++x)
			{
				// the cell's middle tile
				int wx = (cx0 + x) * WeatherField.CELL_TILES + WeatherField.CELL_TILES / 2;
				int wy = (cz0 + z) * WeatherField.CELL_TILES + WeatherField.CELL_TILES / 2;
				boolean known = scene.isInstance()
					? EnvironmentManager.toWorld(scene, wx - fieldBaseX, wy - fieldBaseY, plane, wp)
					: wp(wp, wx, wy);
				int i = z * n + x;
				keys[i] = known ? WeatherField.chunkKey(wp[0], wp[1]) : Long.MIN_VALUE;
				biomes[i] = !areas ? null : !known ? biome : env.weatherBiomeAt(wp[0], wp[1], plane);
			}
		}
		field.layout(n, n, (cx0 * WeatherField.CELL_TILES - fieldBaseX) * 128f,
			(cz0 * WeatherField.CELL_TILES - fieldBaseY) * 128f, keys, biomes);
	}

	private static boolean wp(int[] out, int x, int y)
	{
		out[0] = x;
		out[1] = y;
		return true;
	}

	// cells on each side of the player's: the window is 2 * FIELD_RADIUS + 1 across, 528 tiles
	private static final int FIELD_RADIUS = 16;
	private Scene fieldScene;
	private int fieldBaseX, fieldBaseY, fieldPlane;
	private boolean fieldAreas;
	private String fieldBiome;
	private float fieldDt;
	private static final float FIELD_SECONDS = 0.1f;
	private long fieldMillis;

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
	 * This frame's wind on the rain and snow, and how far it has carried the particles. Particles take the distance
	 * rather than the velocity times the time, so a change in wind bends their path instead of moving all of them
	 * at once. The velocity is the steady wind; gusts (at the player) only add to the distance, and the particle
	 * shaders bend each drop by the gust where it is.
	 */
	private void updateWind(float dt, long now)
	{
		// rain and snow share one wind, weighted by how much of each falls; a little slant, stronger in heavy
		// rain and strong wind
		float total = rainEffective + snowEffective;
		float r = total > 0 ? rainEffective / total : 1 - snowiness;
		float strength = DRIFT_CALM + DRIFT_WIND * windSim.getStrength();
		float rain = (RAIN_DRIFT + RAIN_DRIFT_HEAVY * rainEffective) * rainStyle[4];
		float snow = (SNOW_DRIFT + SNOW_DRIFT_HEAVY * snowEffective) * flakeStyle[3];
		float speed = (r * rain + (1 - r) * snow) * strength;
		windX = windSim.getDirX() * speed;
		windZ = windSim.getDirZ() * speed;

		long wraps = (now - GameClock.EPOCH_MILLIS) / CLOCK_WRAP_MILLIS;
		if (wraps != clockWraps)
		{
			// the particle clock wrapped and every particle moved anyway; start the distance over with it
			clockWraps = wraps;
			windOffsetX = windOffsetZ = 0;
			rainFall = snowFall = 0;
		}
		rainFall += rainStyle[1] * dt;
		snowFall += flakeStyle[1] * dt;
		float g = 1 + (r * RAIN_GUST + (1 - r) * SNOW_GUST) * gust;
		windOffsetX += windX * g * dt;
		windOffsetZ += windZ * g * dt;
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
	 * Draw this frame's falling weather over the composited scene in the bound framebuffer. The scene textures, the
	 * transparency layer, the water surface, the light clusters (with pointLights) and the weather field
	 * ({@link #bindField}) must still be bound on the units given to setPrograms, as they are right after the
	 * composite.
	 *
	 * @param splashes   draw the rain's impacts on the ground, not on water (it has its own rings)
	 * @param wadeSteps  the steps of those wading through puddles (WadeSteps.steps): a few droplets leap from each
	 * @param pointLights light particles with this frame's light clusters
	 * @param drawDistance world units from the player: how far the particles reach
	 */
	void draw(float[] viewProj, float[] invViewProj, int[] viewport, boolean zeroToOne,
		float cameraX, float cameraY, float cameraZ, DayCycle day, boolean splashes, Water water,
		RuneLightConfig config, int occlusionUnit, boolean pointLights, float drawDistance, float[] wadeSteps)
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

		float time = ((System.currentTimeMillis() - GameClock.EPOCH_MILLIS) % CLOCK_WRAP_MILLIS) / 1000f;

		// only the game viewport; gl_FragCoord stays in whole-target pixels, as the scene textures expect
		glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);

		// how much rain and snow there is anywhere the particles reach, and anywhere at all: the field adds its
		// differences to the player's weather
		boolean f = field.isEnabled();
		float rainShown = f ? fieldPeak(WeatherField.RAIN, rainEffective, drawDistance) : rainEffective;
		float snowShown = f ? fieldPeak(WeatherField.SNOW, snowEffective, drawDistance) : snowEffective;
		float wetShown = f ? fieldPeak(WeatherField.WET, wetness, SPLASH_HALF) : wetness;

		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		// where the camera looks: the clip w row is the view axis, its horizontal part says how far the layers
		// reach ahead of the camera rather than around it
		float vx = viewProj[3], vy = viewProj[7], vz = viewProj[11];
		float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
		float aheadX = vl > 0 ? vx / vl * LAYER_AHEAD : 0, aheadZ = vl > 0 ? vz / vl * LAYER_AHEAD : 0;
		// the camera's height over the player's ground (y points down), and the farthest particle it can see
		float height = focusY - cameraY;
		float out = (float) Math.hypot(drawDistance + Math.hypot(cameraX - focusX, cameraZ - focusZ),
			Math.max(height, 0));

		if (rainShown > 0)
		{
			glUseProgram(rain.program);
			setParticles(rain, Math.max(rainAmount, rainShown), viewProj, invViewProj, viewport, zeroToOne, cameraX,
				cameraY, cameraZ, day, time, pointLights);
			glUniform1f(rain.fallTime, rainFall);
			glUniform2f(rain.particleAmount, rainEffective, rainShown);
			glUniform1f(rain.particleReach, drawDistance);
			glUniform1i(rain.splashCount, 0);
			water.setRect(config, rain.waterRect);
			drawLayers(rain, LAYER_RAIN_DROPS * rainShown, RAIN_ABOVE, RAIN_BELOW, out, height, cameraX, cameraZ,
				aheadX, aheadZ);
			float impacts = rainShown * Math.min(1, wetShown);
			int splashCount = splashes ? Math.round(MAX_RAIN_SPLASHES * impacts) : 0;
			if (splashCount > 0)
			{
				// splashes are only seen up close: around the player, where the camera looks
				glUniform4f(rain.layerBox, focusX, focusY, focusZ, SPLASH_HALF);
				glUniform1i(rain.dropCount, 0);
				glUniform1i(rain.splashCount, splashCount);
				glDrawArraysInstanced(GL_TRIANGLES, 0, 6, splashCount);
				// and the droplets thrown up by steps: the same sprites, one range of instances for every step
				glUniform4fv(rain.wadeSteps, wadeSteps);
				glUniform1i(rain.wadeDroplets, 1);
				glDrawArraysInstanced(GL_TRIANGLES, 0, 6, WadeSteps.MAX_STEPS * WADE_DROPLETS);
				glUniform1i(rain.wadeDroplets, 0);
			}
		}
		if (snowShown > 0)
		{
			glUseProgram(snow.program);
			setParticles(snow, Math.max(snowAmount, snowShown), viewProj, invViewProj, viewport, zeroToOne, cameraX,
				cameraY, cameraZ, day, time, pointLights);
			glUniform1f(snow.fallTime, snowFall);
			glUniform2f(snow.particleAmount, snowEffective, snowShown);
			glUniform1f(snow.particleReach, drawDistance);
			drawLayers(snow, LAYER_SNOW_FLAKES * snowShown, SNOW_ABOVE, SNOW_BELOW, out, height, cameraX, cameraZ,
				aheadX, aheadZ);
		}

		glDisable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glBindVertexArray(0);
		glUseProgram(0);
		ShadowMap.unbind(occlusionUnit, occlusionUnit + 1);
	}

	/**
	 * Draw the particle layers that reach what the camera sees, with the program in use: one instanced draw each,
	 * one quad per particle, everything else from gl_InstanceID.
	 *
	 * @param nearest particles in the nearest layer at this frame's amount
	 * @param out     how far from the camera the farthest visible particle is
	 * @param height  the camera's height over the player's ground
	 */
	private void drawLayers(Uniforms u, float nearest, float above, float below, float out, float height,
		float cameraX, float cameraZ, float aheadX, float aheadZ)
	{
		int last = lastLayer(out);
		boolean first = true;
		for (int k = 0; k <= last; ++k)
		{
			float band = layerReach(k);
			float a = layerHeight(above, k);
			int count = Math.round(nearest * (float) Math.pow(LAYER_COUNT_FALLOFF, k));
			if (height - a >= band || count <= 0)
			{
				// every particle of the layer is past its band: the camera is too far above them
				continue;
			}
			glUniform4f(u.layerBox, cameraX + aheadX * band, focusY, cameraZ + aheadZ * band, band);
			glUniform4f(u.layerSpan, a, layerHeight(below, k), (float) Math.pow(LAYER_SIZE_GROWTH, k), k * 7919f);
			// the nearest layer drawn takes everything closer too: the layers before it had nothing there
			glUniform2f(u.layerBand, first ? 0 : layerReach(k - 1), band);
			glUniform1i(u.dropCount, count);
			glDrawArraysInstanced(GL_TRIANGLES, 0, 6, count);
			first = false;
		}
	}

	/** How far from the camera layer k's band reaches. */
	static float layerReach(int k)
	{
		return LAYER_NEAR * (1 << k);
	}

	/** A layer's height above or depth below the ground, growing outward. */
	static float layerHeight(float nearest, int k)
	{
		return Math.min(nearest * (float) Math.pow(LAYER_HEIGHT_GROWTH, k), Math.max(nearest, LAYER_HEIGHT_MAX));
	}

	/** The outermost layer needed for particles out to {@code out} from the camera, at full strength. */
	static int lastLayer(float out)
	{
		int k = 0;
		while (k < MAX_LAYERS - 1 && layerReach(k) * (1 - LAYER_CROSSFADE) < out)
		{
			++k;
		}
		return k;
	}

	/**
	 * The most of a channel anywhere within a particle box around the player: the player's own amount plus the
	 * field's largest difference there, never less than the player's.
	 */
	private float fieldPeak(int channel, float local, float half)
	{
		float peak = local + field.max(channel, focusX, focusZ, half) - field.getFocus(channel);
		return Math.max(local, Math.min(peak, MAX_EFFECTIVE));
	}

	/**
	 * Whether the AO pass shades wet ground and snow cover this frame: there is some anywhere in the scene and the
	 * occlusion map is there to tell what is sheltered.
	 */
	boolean groundActive()
	{
		if (!active || !occlusion.isInitialized() || occlusionDirty)
		{
			return false;
		}
		boolean f = field.isEnabled();
		float wet = f ? wetness + field.max(WeatherField.WET) - field.getFocus(WeatherField.WET) : wetness;
		float snowy = f ? snowCover + field.max(WeatherField.SNOW_COVER) - field.getFocus(WeatherField.SNOW_COVER)
			: snowCover;
		return wet > 0.002f || snowy > 0.002f;
	}

	/**
	 * With the AO program in use: bind the occlusion map on {@code occlusionUnit} and the next unit and the weather
	 * field on {@code fieldUnit} and the next, and set weather_ground_ao.glsl's uniforms. {@link #groundActive}
	 * first; {@link ShadowMap#unbind} the occlusion units after the pass, they have sampler objects bound.
	 */
	void bindGround(WeatherGround g, int occlusionUnit, int fieldUnit, float cameraX, float cameraY, float cameraZ)
	{
		relativeTo(occlusion.lightProj, cameraX, cameraY, cameraZ, occlusionProjRel);
		occlusion.bindForSampling(occlusionUnit, occlusionUnit + 1);
		field.bind(fieldUnit, fieldUnit + 1);
		g.setAo(occlusionProjRel, OCCLUSION_DEPTH_RANGE, field, cameraX, cameraZ, wetness, snowCover);
	}

	/**
	 * Upload the weather field if it changed and bind it on two units from {@code unit}, for the weather passes and
	 * the fog, and its sky on {@code skyUnit}, for the clouds.
	 */
	void bindField(int unit, int skyUnit)
	{
		field.bind(unit, unit + 1);
		field.bindSky(skyUnit);
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
		setLightUniform(u.light);
		field.apply(u.field);
		windSim.apply(u.windLocations, !openSky);
		glUniform4f(u.rainStyle, rainStyle[0], rainStyle[1], rainStyle[2], rainStyle[3]);
		glUniform4f(u.flakeStyle, flakeStyle[0], flakeStyle[1], flakeStyle[2], flakeStyle[3]);
		glUniform3f(u.flakeColor, flakeStyle[4], flakeStyle[5], flakeStyle[6]);
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
