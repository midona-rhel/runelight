package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Random;
import net.runelite.api.Scene;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Lightweight volumetric fog. A half resolution pass (fog_frag.glsl) marches each view ray a fixed number of steps
 * through a height fog layer around the player, its height measured from the terrain ({@link FogGround}), and writes
 * in-scattered light and transmittance into {@link SceneTargets}' fog target; the denoise smooths its sun shafts'
 * grain with the AO and shadows (denoise_frag.glsl) and the composite upsamples it depth-aware and applies it
 * (fog_apply.glsl).
 *
 * All fog parameters are set in {@link #updateParameters}, so a later per-area environment system only has to
 * change the values there (or the {@code area*} multipliers).
 */
class VolumetricFog
{
	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "fog_frag.glsl");

	private static final int NOISE_SIZE = 64;
	private static final float EDGE_FADE_TILES = 7;
	private static final float NEAR_FADE_TILES = 7;
	// density left inside building rooms with "No fog indoors": a trace, so a doorway doesn't cut it off sharply
	static final float INDOOR_DENSITY = 0.03f;
	// and in rooms whose roof is hidden, open to the sky: enough for their lamps to glow in it
	static final float OPEN_INDOOR_DENSITY = 0.25f;
	// fog_frag.glsl's FOG_TOP, FOG_BELOW, FOG_FIELD_MAX, FOG_POINT_LIGHT_SCALE and FOG_GLOW_CORE
	private static final float TOP = 5, BELOW = 1, FIELD_MAX = 6, GLOW_SCALE = 0.6f, GLOW_CORE = 0.2f;
	// The most a point light's glow kernel (fog_frag.glsl pointGlow) integrates to along a ray, per unit of radius:
	// through its center, over the whole diameter
	private static final float GLOW_INTEGRAL;
	static
	{
		float c = GLOW_CORE, m = c * c / (c * c + 1);
		GLOW_INTEGRAL = (float) (2 * c * Math.atan(1 / c) - 2 * m) / (1 - m);
	}
	// a light's glow is left out where it can't add half an 8-bit step even over black (linear, through sRGB's slope
	// of 12.92 there)
	private static final float GLOW_VISIBLE = 0.5f / 255 / 12.92f;

	// Per-area overrides for an environment system: multipliers on the configured values, and a color tint
	// on the fog's ambient light. 1 leaves the config as it is.
	float areaDensity = 1;
	float areaHeight = 1;
	final float[] areaTint = {1, 1, 1};
	// sky.glsl's weatherOvercast for this frame (Weather.java): cover, rain or snow, lightning flash; then weatherLight
	final float[] overcast = {0, 0, 0, 0, 0, 0, 0};
	// the weather field, for fog by region, and the weather's fog multiplier at the player it is relative to
	WeatherField weatherField;
	float weatherFog = 1;
	private int waterUnit, groundUnit;
	// the terrain the fog lies on: built when a scene loads (any thread), taken when it swaps in
	private FogGround pendingGround;
	private FogGround ground;
	private int texGround;
	// density left inside building rooms (fog_frag.glsl), and the scene's water to stop the rays at
	float indoorFactor = 1;
	// the building rooms (null: none), and those whose roof is hidden (OpenRooms): lit by their own lamps and
	// thicker than the other rooms. Both go into the ground texture (FogGround.texels), sent again when they change.
	RoomMap rooms;
	OpenRooms openRooms;
	int waterTexture;
	private static final int[] NONE_OPEN = new int[OpenRooms.WORDS];
	private FogGround sentGround;
	private RoomMap sentRooms;
	private final int[] sentOpen = new int[OpenRooms.WORDS];
	private float[] groundTexels;
	private FloatBuffer groundBuffer;

	// the values the pass uses this frame, in world units
	private float density;
	private float height;
	// world y of the highest ground within the fog's reach: the layer ends a few heights above it
	private float highestY;
	private int plane;
	private float centerX, centerZ;
	private float fadeStart, fadeEnd;
	private float near;
	private float sunScatter;
	private float noiseAmount;
	private float bankAmount;
	private float maxCover = 1;
	private int steps = 12;

	private Locations fog;

	private int texNoise;

	/** A fog program and its uniforms' locations. */
	private static final class Locations
	{
		final int program;
		final LightVolumeMap.Uniforms lightVolume = new LightVolumeMap.Uniforms();
		int uniBankAmount, uniCameraPos, uniCasterDir, uniCenter, uniClientSkyColor, uniDensity, uniDepthZeroToOne,
			uniFade, uniFieldFog, uniFieldRect, uniGroundGrid, uniHalfRatio, uniHeight, uniHighestY, uniIndoor,
			uniIndoorOpen, uniInvViewProj, uniLight, uniLightProjRel, uniMaxCover, uniNear, uniNoiseAmount,
			uniOutdoors, uniOvercast, uniPointLights, uniProceduralSky,
			uniShadowDepthRange, uniShadowsEnabled, uniSteps, uniStrikeLight, uniSunAltitude, uniSunIsCaster,
			uniSunScatter, uniTime, uniTint, uniViewProj, uniViewport, uniWaterEnabled, uniWaterGrid;

		Locations(int program)
		{
			this.program = program;
		}
	}

	/**
	 * Take ownership of the freshly compiled fog program (PROGRAM) and look up its uniforms.
	 */
	void setProgram(int program, int sceneDepthUnit, int shadowCompareUnit, int lightDataUnit,
		int clusterDataUnit, int noiseUnit, int weatherFieldUnit, int waterUnit, int groundUnit, int lightVolumeUnit)
	{
		this.waterUnit = waterUnit;
		this.groundUnit = groundUnit;
		free(fog);
		fog = locate(program, sceneDepthUnit, shadowCompareUnit, lightDataUnit, clusterDataUnit, noiseUnit,
			weatherFieldUnit, waterUnit, groundUnit, lightVolumeUnit);
	}

	private static void free(Locations u)
	{
		if (u != null)
		{
			glDeleteProgram(u.program);
		}
	}

	private static Locations locate(int program, int sceneDepthUnit, int shadowCompareUnit, int lightDataUnit,
		int clusterDataUnit, int noiseUnit, int weatherFieldUnit, int waterUnit, int groundUnit, int lightVolumeUnit)
	{
		Locations u = new Locations(program);
		u.uniCameraPos = glGetUniformLocation(u.program, "cameraPos");
		u.uniTime = glGetUniformLocation(u.program, "time");
		u.uniOutdoors = glGetUniformLocation(u.program, "outdoors");
		u.uniProceduralSky = glGetUniformLocation(u.program, "proceduralSky");
		u.uniClientSkyColor = glGetUniformLocation(u.program, "clientSkyColor");
		u.uniCasterDir = glGetUniformLocation(u.program, "casterDir");
		u.uniSunIsCaster = glGetUniformLocation(u.program, "sunIsCaster");
		u.uniStrikeLight = glGetUniformLocation(u.program, "strikeLight");
		u.uniSunAltitude = glGetUniformLocation(u.program, "sunAltitude");
		u.uniOvercast = glGetUniformLocation(u.program, "weatherOvercast");
		u.uniLight = glGetUniformLocation(u.program, "weatherLight");
		u.uniShadowsEnabled = glGetUniformLocation(u.program, "shadowsEnabled");
		u.uniLightProjRel = glGetUniformLocation(u.program, "lightProjRel");
		u.uniShadowDepthRange = glGetUniformLocation(u.program, "shadowDepthRange");
		u.uniPointLights = glGetUniformLocation(u.program, "pointLightsEnabled");
		u.uniDensity = glGetUniformLocation(u.program, "fogDensity");
		u.uniHeight = glGetUniformLocation(u.program, "fogHeight");
		u.uniHighestY = glGetUniformLocation(u.program, "fogHighestY");
		u.uniGroundGrid = glGetUniformLocation(u.program, "fogGroundGrid");
		u.uniCenter = glGetUniformLocation(u.program, "fogCenter");
		u.uniFade = glGetUniformLocation(u.program, "fogFade");
		u.uniNear = glGetUniformLocation(u.program, "fogNear");
		u.uniSunScatter = glGetUniformLocation(u.program, "fogSunScatter");
		u.uniNoiseAmount = glGetUniformLocation(u.program, "fogNoiseAmount");
		u.uniBankAmount = glGetUniformLocation(u.program, "fogBankAmount");
		u.uniMaxCover = glGetUniformLocation(u.program, "fogMaxCover");
		u.uniTint = glGetUniformLocation(u.program, "fogTint");
		u.uniSteps = glGetUniformLocation(u.program, "fogSteps");
		u.uniViewProj = glGetUniformLocation(u.program, "viewProj");
		u.uniInvViewProj = glGetUniformLocation(u.program, "invViewProj");
		u.uniViewport = glGetUniformLocation(u.program, "sceneViewport");
		u.uniDepthZeroToOne = glGetUniformLocation(u.program, "depthZeroToOne");
		u.uniHalfRatio = glGetUniformLocation(u.program, "halfRatio");
		u.lightVolume.locate(u.program, lightVolumeUnit);

		glUseProgram(u.program);
		glUniform1i(glGetUniformLocation(u.program, "sceneDepth"), sceneDepthUnit);
		glUniform1i(glGetUniformLocation(u.program, "shadowCompare"), shadowCompareUnit);
		glUniform1i(glGetUniformLocation(u.program, "lightData"), lightDataUnit);
		glUniform1i(glGetUniformLocation(u.program, "clusterData"), clusterDataUnit);
		glUniform1i(glGetUniformLocation(u.program, "fogNoise"), noiseUnit);
		u.uniFieldRect = glGetUniformLocation(u.program, "weatherFieldRect");
		u.uniFieldFog = glGetUniformLocation(u.program, "weatherFieldFog");
		glUniform1i(glGetUniformLocation(u.program, "weatherField"), weatherFieldUnit);
		u.uniIndoor = glGetUniformLocation(u.program, "fogIndoor");
		u.uniIndoorOpen = glGetUniformLocation(u.program, "fogIndoorOpen");
		u.uniWaterEnabled = glGetUniformLocation(u.program, "waterEnabled");
		u.uniWaterGrid = glGetUniformLocation(u.program, "waterGrid");
		glUniform1i(glGetUniformLocation(u.program, "waterData"), waterUnit);
		glUniform1i(glGetUniformLocation(u.program, "fogGround"), groundUnit);
		glUseProgram(0);
		return u;
	}

	/**
	 * Point a composite program's fog sampler at {@code fogUnit}, and return the location of its switch, which says
	 * whether it applies the fog rendered this frame. Call with each newly compiled composite.
	 */
	static int locateComposite(int compositeProgram, int fogUnit)
	{
		glUseProgram(compositeProgram);
		glUniform1i(glGetUniformLocation(compositeProgram, "sceneFog"), fogUnit);
		glUseProgram(0);
		return glGetUniformLocation(compositeProgram, "volumetricFog");
	}

	/**
	 * The one place fog parameters are decided. Config values first, then any per-area multipliers.
	 *
	 * @param x player position, where the fog is centered and fades out from
	 * @param plane the plane the player is on: which floor the fog lies on
	 * @param drawDistance world units, the fog never reaches past it
	 */
	void updateParameters(RuneLightConfig config, float x, float z, int plane, float drawDistance)
	{
		final float tile = 128;
		// 1% is an extinction of 1e-5 per unit: at the default 25% about half the light is lost over 22 tiles
		density = config.volumetricFogDensity() * 1e-5f * areaDensity;
		height = Math.max(config.volumetricFogHeight() / 100f * tile * areaHeight, 16);
		this.plane = plane;
		centerX = x;
		centerZ = z;
		fadeEnd = Math.min(config.volumetricFogDistance() * tile, drawDistance);
		// the outer EDGE_FADE_TILES thin out to nothing, and so do the first NEAR_FADE_TILES from the camera
		fadeStart = Math.max(fadeEnd - EDGE_FADE_TILES * tile, 0);
		near = NEAR_FADE_TILES * tile;
		highestY = ground.highest(plane, x - fadeEnd, z - fadeEnd, x + fadeEnd, z + fadeEnd);
		sunScatter = config.volumetricFogSunScattering() / 100f;
		noiseAmount = config.volumetricFogNoise() / 100f;
		bankAmount = config.volumetricFogBanks() / 100f;
		maxCover = config.volumetricFogMaxCover() / 100f;
		steps = config.fogQuality().pick(6, 8, 12);
	}

	/**
	 * The terrain of a scene being loaded (any thread). Takes effect at {@link #swap}.
	 */
	void sceneLoaded(Scene scene)
	{
		FogGround g = FogGround.build(scene);
		synchronized (this)
		{
			pendingGround = g;
		}
	}

	/**
	 * The loaded scene became the current one: take its terrain. GL thread.
	 */
	void swap()
	{
		FogGround g;
		synchronized (this)
		{
			g = pendingGround;
			pendingGround = null;
		}
		if (g != null)
		{
			ground = g;
		}
	}

	/**
	 * Send the ground with the rooms (FogGround.texels) when any of them changed: a new scene, the rooms rebuilt as
	 * doors open and close, roofs hidden or shown. GL thread.
	 */
	private void sendGround()
	{
		FogGround g = ground;
		RoomMap r = rooms;
		OpenRooms open = openRooms;
		int[] bits = open != null ? open.bits : NONE_OPEN;
		if (g == null || g == sentGround && r == sentRooms && java.util.Arrays.equals(bits, sentOpen))
		{
			return;
		}
		sentGround = g;
		sentRooms = r;
		System.arraycopy(bits, 0, sentOpen, 0, sentOpen.length);
		if (groundTexels == null)
		{
			groundTexels = new float[FogGround.WIDTH * FogGround.CORNERS * FogGround.CHANNELS];
			groundBuffer = BufferUtils.createFloatBuffer(groundTexels.length);
		}
		g.texels(r, open, groundTexels);
		groundBuffer.clear();
		groundBuffer.put(groundTexels).flip();
		if (texGround == 0)
		{
			texGround = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, texGround);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		}
		else
		{
			glBindTexture(GL_TEXTURE_2D, texGround);
		}
		// full floats: heights reach thousands of units, where half floats step by 2 to 4
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, FogGround.WIDTH, FogGround.CORNERS, 0, GL_RGBA, GL_FLOAT, groundBuffer);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	/** Whether a scene's terrain has been uploaded: the fog has nothing to lie on before. */
	boolean hasGround()
	{
		return ground != null;
	}

	/** World x of the fog's center: the player. */
	float getCenterX()
	{
		return centerX;
	}

	float getCenterZ()
	{
		return centerZ;
	}

	/** Horizontal distance from the center where the fog has faded out, world units. */
	float getRadius()
	{
		return fadeEnd;
	}

	/** World y of the top of the fog's layer (fog_frag.glsl clips the rays to below it); y points down. */
	float getTop()
	{
		return highestY - height * TOP;
	}

	/**
	 * The least brightest color channel times radius a point light needs for its glow in the fog to possibly show
	 * (GLOW_VISIBLE). Its glow is at most its color times GLOW_SCALE * GLOW_INTEGRAL * radius times the march's
	 * largest scattering per unit length, which is at most the fog's extinction: the density, e^BELOW at most from the
	 * height, the open rooms' share over the plain rooms', the weather field's most and the noise and banks' peaks.
	 */
	float getMinGlow()
	{
		float field = 1;
		WeatherField f = weatherField;
		if (f != null && f.isEnabled())
		{
			// the field's alpha is at most 1, its fog FOG_RANGE (weatherFogFactor)
			float local = fieldReference();
			field = Math.min(Math.max((WeatherField.FOG_RANGE + local - f.getFocus(WeatherField.FOG)) / local, 0), FIELD_MAX);
		}
		float sigma = density * (float) Math.exp(BELOW) * (1 + OPEN_INDOOR_DENSITY) * field
			* (1 + noiseAmount) * (1 + bankAmount);
		return GLOW_VISIBLE / (GLOW_SCALE * GLOW_INTEGRAL * sigma);
	}

	// the weather's fog at the player, which the field's is taken relative to
	private float fieldReference()
	{
		return Math.max(weatherFog, 0.05f);
	}

	/**
	 * Whether {@link #render} with these outdoors and shadows draws the sun shafts (fog_frag.glsl's shafts), the only
	 * part of the fog with grain: the shadow map is sampled at jittered points along each ray. The shader also leaves
	 * them out while the caster's light is nil, which the day cycle never quite reaches: at its switch from the sun to
	 * the moon both are faint, but not 0.
	 */
	boolean hasShafts(boolean outdoors, ShadowMap shadows)
	{
		return outdoors && shadows != null && sunScatter > 0;
	}

	/**
	 * Render the fog into the half resolution framebuffer {@code fbo}. Expects the scene depth, shadow map and light
	 * clusters already bound to the units given to {@link #setProgram}, and a VAO bound.
	 */
	void render(int fbo, int halfWidth, int halfHeight, float halfRatio, int noiseUnit, float[] viewProj,
		float[] invViewProj, int[] viewport, boolean zeroToOne,
		float cameraX, float cameraY, float cameraZ, float time,
		boolean outdoors, boolean proceduralSky, int skyColor, DayCycle day,
		ShadowMap shadows, boolean pointLights, LightVolumeMap lightVolume)
	{
		Locations u = fog;
		if (u == null)
		{
			return;
		}
		if (texNoise == 0)
		{
			texNoise = createNoise();
		}

		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glViewport(0, 0, halfWidth, halfHeight);
		// the march writes every texel: cleared first, the GPU starts the pass without loading the old contents
		glClear(GL_COLOR_BUFFER_BIT);
		glUseProgram(u.program);

		glUniformMatrix4fv(u.uniViewProj, false, viewProj);
		glUniformMatrix4fv(u.uniInvViewProj, false, invViewProj);
		glUniform4i(u.uniViewport, viewport[0], viewport[1], viewport[2], viewport[3]);
		glUniform1i(u.uniDepthZeroToOne, zeroToOne ? 1 : 0);
		glUniform1f(u.uniHalfRatio, halfRatio);

		glUniform3f(u.uniCameraPos, cameraX, cameraY, cameraZ);
		glUniform1f(u.uniTime, time);
		glUniform1i(u.uniOutdoors, outdoors ? 1 : 0);
		glUniform1i(u.uniProceduralSky, proceduralSky ? 1 : 0);
		glUniform3f(u.uniClientSkyColor,
			(skyColor >> 16 & 0xFF) / 255f, (skyColor >> 8 & 0xFF) / 255f, (skyColor & 0xFF) / 255f);
		glUniform3fv(u.uniCasterDir, day.casterDir);
		glUniform1i(u.uniSunIsCaster, day.sunIsCaster ? 1 : 0);
		glUniform2fv(u.uniStrikeLight, day.strikeLight);
		glUniform1f(u.uniSunAltitude, day.sunAltitude);
		glUniform3f(u.uniOvercast, overcast[0], overcast[1], overcast[2]);
		// sky.glsl's weatherLight follows weatherOvercast (Weather.getOvercast)
		glUniform4f(u.uniLight, overcast[3], overcast[4], overcast[5], overcast[6]);
		glUniform1i(u.uniShadowsEnabled, shadows != null ? 1 : 0);
		if (shadows != null)
		{
			glUniformMatrix4fv(u.uniLightProjRel, false, shadows.lightProjRel);
			glUniform1f(u.uniShadowDepthRange, shadows.getDepthRange());
		}
		glUniform1i(u.uniPointLights, pointLights ? 1 : 0);
		lightVolume.apply(u.lightVolume);

		glUniform1f(u.uniDensity, density);
		glUniform1f(u.uniHeight, height);
		glUniform1f(u.uniHighestY, highestY);
		glUniform3f(u.uniGroundGrid, RuneLightPlugin.SCENE_OFFSET, FogGround.CORNERS, plane * FogGround.CORNERS);
		glUniform2f(u.uniCenter, centerX, centerZ);
		glUniform2f(u.uniFade, fadeStart, fadeEnd);
		glUniform1f(u.uniNear, near);
		glUniform1f(u.uniSunScatter, sunScatter);
		glUniform1f(u.uniNoiseAmount, noiseAmount);
		glUniform1f(u.uniBankAmount, bankAmount);
		glUniform1f(u.uniMaxCover, maxCover);
		glUniform3fv(u.uniTint, areaTint);
		glUniform1i(u.uniSteps, steps);
		WeatherField field = weatherField;
		if (field != null && field.isEnabled())
		{
			// density times (fog here) / (fog at the player), with the player's own multiplier as the reference
			float local = fieldReference();
			field.setRect(u.uniFieldRect);
			glUniform3f(u.uniFieldFog, local - field.getFocus(WeatherField.FOG), 1 / local, 1);
		}
		else
		{
			glUniform3f(u.uniFieldFog, 0, 0, 0);
		}

		glUniform1f(u.uniIndoor, indoorFactor);
		glUniform1f(u.uniIndoorOpen, Math.max(indoorFactor, OPEN_INDOOR_DENSITY));
		glUniform1i(u.uniWaterEnabled, waterTexture != 0 ? 1 : 0);
		if (waterTexture != 0)
		{
			glUniform2f(u.uniWaterGrid, RuneLightPlugin.SCENE_OFFSET, WaterMap.CORNERS);
			glActiveTexture(GL_TEXTURE0 + waterUnit);
			glBindTexture(GL_TEXTURE_2D, waterTexture);
		}

		glActiveTexture(GL_TEXTURE0 + groundUnit);
		sendGround();
		glBindTexture(GL_TEXTURE_2D, texGround);
		glActiveTexture(GL_TEXTURE0 + noiseUnit);
		glBindTexture(GL_TEXTURE_3D, texNoise);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glActiveTexture(GL_TEXTURE0 + noiseUnit);
		glBindTexture(GL_TEXTURE_3D, 0);
		glActiveTexture(GL_TEXTURE0 + groundUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		if (waterTexture != 0)
		{
			glActiveTexture(GL_TEXTURE0 + waterUnit);
			glBindTexture(GL_TEXTURE_2D, 0);
		}
		glActiveTexture(GL_TEXTURE0);
	}

	static void unbind(int fogUnit)
	{
		glActiveTexture(GL_TEXTURE0 + fogUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	void free()
	{
		if (texNoise != 0)
		{
			glDeleteTextures(texNoise);
			texNoise = 0;
		}
		if (texGround != 0)
		{
			glDeleteTextures(texGround);
			texGround = 0;
		}
		ground = null;
		sentGround = null;
		free(fog);
		fog = null;
	}

	private static int createNoise()
	{
		byte[] data = noise(NOISE_SIZE, 1234);
		// r: patches, g: banks
		ByteBuffer buf = BufferUtils.createByteBuffer(data.length);
		buf.put(data).flip();

		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_3D, tex);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage3D(GL_TEXTURE_3D, 0, GL_RG8, NOISE_SIZE, NOISE_SIZE, NOISE_SIZE, 0, GL_RG, GL_UNSIGNED_BYTE, buf);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_REPEAT);
		glBindTexture(GL_TEXTURE_3D, 0);
		return tex;
	}

	/**
	 * Two tiling fractal value noises, interleaved and each normalized to 0..255, built once on the CPU so the shader
	 * needs a single filtered fetch per step:
	 * r: patches, blobs at an eighth of the size plus finer detail;
	 * g: banks, a few large blobs per repeat that do not change with height, so whole stretches of ground lie under
	 * thicker or thinner fog as they drift by.
	 */
	static byte[] noise(int size, long seed)
	{
		Random rng = new Random(seed);
		float[] patches = new float[size * size * size];
		float[] banks = new float[size * size * size];
		fractal(patches, size, rng, 8, 32, 8);
		fractal(banks, size, rng, 2, 4, 1);
		normalize(patches);
		normalize(banks);
		byte[] bytes = new byte[size * size * size * 2];
		for (int i = 0; i < patches.length; ++i)
		{
			bytes[i * 2] = (byte) Math.round(patches[i] * 255);
			bytes[i * 2 + 1] = (byte) Math.round(banks[i] * 255);
		}
		return bytes;
	}

	/** Octaves from minCells to maxCells per repeat across, with up to maxCellsY cells vertically. */
	private static void fractal(float[] out, int size, Random rng, int minCells, int maxCells, int maxCellsY)
	{
		float amp = 1;
		for (int cells = minCells; cells <= maxCells; cells *= 2, amp *= 0.5f)
		{
			int cellsY = Math.min(cells, maxCellsY);
			float[] lattice = new float[cells * cellsY * cells];
			for (int i = 0; i < lattice.length; ++i)
			{
				lattice[i] = rng.nextFloat();
			}
			float scale = (float) cells / size, scaleY = (float) cellsY / size;
			for (int z = 0; z < size; ++z)
			{
				for (int y = 0; y < size; ++y)
				{
					for (int x = 0; x < size; ++x)
					{
						out[(z * size + y) * size + x] += amp * sampleLattice(lattice, cells, cellsY, x * scale, y * scaleY, z * scale);
					}
				}
			}
		}
	}

	private static void normalize(float[] v)
	{
		float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
		for (float f : v)
		{
			min = Math.min(min, f);
			max = Math.max(max, f);
		}
		for (int i = 0; i < v.length; ++i)
		{
			v[i] = (v[i] - min) / (max - min);
		}
	}

	private static float sampleLattice(float[] l, int n, int ny, float x, float y, float z)
	{
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
		float fx = smooth(x - x0), fy = smooth(y - y0), fz = smooth(z - z0);
		float c00 = lerp(at(l, n, ny, x0, y0, z0), at(l, n, ny, x0 + 1, y0, z0), fx);
		float c10 = lerp(at(l, n, ny, x0, y0 + 1, z0), at(l, n, ny, x0 + 1, y0 + 1, z0), fx);
		float c01 = lerp(at(l, n, ny, x0, y0, z0 + 1), at(l, n, ny, x0 + 1, y0, z0 + 1), fx);
		float c11 = lerp(at(l, n, ny, x0, y0 + 1, z0 + 1), at(l, n, ny, x0 + 1, y0 + 1, z0 + 1), fx);
		return lerp(lerp(c00, c10, fy), lerp(c01, c11, fy), fz);
	}

	private static float at(float[] l, int n, int ny, int x, int y, int z)
	{
		return l[((Math.floorMod(z, n) * ny + Math.floorMod(y, ny)) * n) + Math.floorMod(x, n)];
	}

	private static float smooth(float t)
	{
		return t * t * (3 - 2 * t);
	}

	private static float lerp(float a, float b, float t)
	{
		return a + (b - a) * t;
	}
}
