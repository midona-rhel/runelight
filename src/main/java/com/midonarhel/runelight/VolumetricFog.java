package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.util.Random;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Lightweight volumetric fog. A half resolution pass (fog_frag.glsl) marches each view ray a fixed number of
 * steps through a height fog layer around the player and writes in-scattered light and transmittance; the
 * composite upsamples that depth-aware and applies it (fog_apply.glsl).
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

	// Per-area overrides for an environment system: multipliers on the configured values, and a color tint
	// on the fog's ambient light. 1 leaves the config as it is.
	float areaDensity = 1;
	float areaHeight = 1;
	final float[] areaTint = {1, 1, 1};
	// sky.glsl's weatherOvercast for this frame (Weather.java): cover, rain or snow, lightning flash
	final float[] overcast = {0, 0, 0};

	// the values the pass uses this frame, in world units
	private float density;
	private float height;
	private float groundY;
	private float centerX, centerZ;
	private float fadeStart, fadeEnd;
	private float near;
	private float sunScatter;
	private float noiseAmount;
	private float bankAmount;
	private int steps = 12;

	private int program;
	private int uniCameraPos, uniTime, uniOutdoors, uniProceduralSky, uniClientSkyColor, uniCasterDir,
		uniSunIsCaster, uniSunAltitude, uniShadowsEnabled, uniLightProjRel, uniShadowDepthRange,
		uniPointLights, uniDensity, uniHeight, uniGroundY, uniCenter, uniFade, uniNear, uniSunScatter, uniNoiseAmount, uniBankAmount,
		uniTint, uniSteps, uniViewProj, uniInvViewProj, uniViewport, uniDepthZeroToOne, uniOvercast;

	private int uniCompositeEnabled;

	private int fbo, texFog, texNoise;
	private int targetWidth, targetHeight;

	/**
	 * Take ownership of a freshly compiled fog program and look up its uniforms.
	 */
	void setProgram(int program, int sceneDepthUnit, int shadowCompareUnit, int lightDataUnit, int clusterDataUnit,
		int noiseUnit)
	{
		glDeleteProgram(this.program);
		this.program = program;

		uniCameraPos = glGetUniformLocation(program, "cameraPos");
		uniTime = glGetUniformLocation(program, "time");
		uniOutdoors = glGetUniformLocation(program, "outdoors");
		uniProceduralSky = glGetUniformLocation(program, "proceduralSky");
		uniClientSkyColor = glGetUniformLocation(program, "clientSkyColor");
		uniCasterDir = glGetUniformLocation(program, "casterDir");
		uniSunIsCaster = glGetUniformLocation(program, "sunIsCaster");
		uniSunAltitude = glGetUniformLocation(program, "sunAltitude");
		uniOvercast = glGetUniformLocation(program, "weatherOvercast");
		uniShadowsEnabled = glGetUniformLocation(program, "shadowsEnabled");
		uniLightProjRel = glGetUniformLocation(program, "lightProjRel");
		uniShadowDepthRange = glGetUniformLocation(program, "shadowDepthRange");
		uniPointLights = glGetUniformLocation(program, "pointLightsEnabled");
		uniDensity = glGetUniformLocation(program, "fogDensity");
		uniHeight = glGetUniformLocation(program, "fogHeight");
		uniGroundY = glGetUniformLocation(program, "fogGroundY");
		uniCenter = glGetUniformLocation(program, "fogCenter");
		uniFade = glGetUniformLocation(program, "fogFade");
		uniNear = glGetUniformLocation(program, "fogNear");
		uniSunScatter = glGetUniformLocation(program, "fogSunScatter");
		uniNoiseAmount = glGetUniformLocation(program, "fogNoiseAmount");
		uniBankAmount = glGetUniformLocation(program, "fogBankAmount");
		uniTint = glGetUniformLocation(program, "fogTint");
		uniSteps = glGetUniformLocation(program, "fogSteps");
		uniViewProj = glGetUniformLocation(program, "viewProj");
		uniInvViewProj = glGetUniformLocation(program, "invViewProj");
		uniViewport = glGetUniformLocation(program, "sceneViewport");
		uniDepthZeroToOne = glGetUniformLocation(program, "depthZeroToOne");

		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "sceneDepth"), sceneDepthUnit);
		glUniform1i(glGetUniformLocation(program, "shadowCompare"), shadowCompareUnit);
		glUniform1i(glGetUniformLocation(program, "lightData"), lightDataUnit);
		glUniform1i(glGetUniformLocation(program, "clusterData"), clusterDataUnit);
		glUniform1i(glGetUniformLocation(program, "fogNoise"), noiseUnit);
		glUseProgram(0);
	}

	/**
	 * Point the composite program's fog sampler at {@code fogUnit}. Call with each newly compiled composite.
	 */
	void locateComposite(int compositeProgram, int fogUnit)
	{
		uniCompositeEnabled = glGetUniformLocation(compositeProgram, "volumetricFog");
		glUseProgram(compositeProgram);
		glUniform1i(glGetUniformLocation(compositeProgram, "sceneFog"), fogUnit);
		glUseProgram(0);
	}

	/**
	 * With the composite program in use: whether it should apply the fog rendered this frame.
	 */
	void setCompositeEnabled(boolean enabled)
	{
		glUniform1i(uniCompositeEnabled, enabled ? 1 : 0);
	}

	/**
	 * The one place fog parameters are decided. Config values first, then any per-area multipliers.
	 *
	 * @param x player position, where the fog is centered and fades out from
	 * @param groundY ground height under the player; y points down
	 * @param drawDistance world units, the fog never reaches past it
	 */
	void updateParameters(RuneLightConfig config, float x, float groundY, float z, float drawDistance)
	{
		final float tile = 128;
		// 1% is an extinction of 1e-5 per unit: at the default 25% about half the light is lost over 22 tiles
		density = config.volumetricFogDensity() * 1e-5f * areaDensity;
		height = Math.max(config.volumetricFogHeight() / 100f * tile * areaHeight, 16);
		this.groundY = groundY;
		centerX = x;
		centerZ = z;
		fadeEnd = Math.min(config.volumetricFogDistance() * tile, drawDistance);
		// the outer EDGE_FADE_TILES thin out to nothing, and so do the first NEAR_FADE_TILES from the camera
		fadeStart = Math.max(fadeEnd - EDGE_FADE_TILES * tile, 0);
		near = NEAR_FADE_TILES * tile;
		sunScatter = config.volumetricFogSunScattering() / 100f;
		noiseAmount = config.volumetricFogNoise() / 100f;
		bankAmount = config.volumetricFogBanks() / 100f;
		steps = config.fogQuality().pick(6, 8, 12);
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

	/**
	 * Render the fog into its half resolution target and bind it to {@code fogUnit} for the composite. Expects
	 * the scene depth, shadow map and light clusters already bound to the units given to {@link #setProgram},
	 * and a VAO bound.
	 */
	void render(int halfWidth, int halfHeight, int noiseUnit, int fogUnit,
		float[] viewProj, float[] invViewProj, int[] viewport, boolean zeroToOne,
		float cameraX, float cameraY, float cameraZ, float time,
		boolean outdoors, boolean proceduralSky, int skyColor, DayCycle day,
		ShadowMap shadows, boolean pointLights)
	{
		if (program == 0)
		{
			return;
		}
		if (fbo == 0 || targetWidth != halfWidth || targetHeight != halfHeight)
		{
			freeTarget();
			initTarget(halfWidth, halfHeight);
		}
		if (texNoise == 0)
		{
			texNoise = createNoise();
		}

		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glViewport(0, 0, targetWidth, targetHeight);
		glUseProgram(program);

		glUniformMatrix4fv(uniViewProj, false, viewProj);
		glUniformMatrix4fv(uniInvViewProj, false, invViewProj);
		glUniform4i(uniViewport, viewport[0], viewport[1], viewport[2], viewport[3]);
		glUniform1i(uniDepthZeroToOne, zeroToOne ? 1 : 0);

		glUniform3f(uniCameraPos, cameraX, cameraY, cameraZ);
		glUniform1f(uniTime, time);
		glUniform1i(uniOutdoors, outdoors ? 1 : 0);
		glUniform1i(uniProceduralSky, proceduralSky ? 1 : 0);
		glUniform3f(uniClientSkyColor,
			(skyColor >> 16 & 0xFF) / 255f, (skyColor >> 8 & 0xFF) / 255f, (skyColor & 0xFF) / 255f);
		glUniform3fv(uniCasterDir, day.casterDir);
		glUniform1i(uniSunIsCaster, day.sunIsCaster ? 1 : 0);
		glUniform1f(uniSunAltitude, day.sunAltitude);
		glUniform3f(uniOvercast, overcast[0], overcast[1], overcast[2]);
		glUniform1i(uniShadowsEnabled, shadows != null ? 1 : 0);
		if (shadows != null)
		{
			glUniformMatrix4fv(uniLightProjRel, false, shadows.lightProjRel);
			glUniform1f(uniShadowDepthRange, shadows.getDepthRange());
		}
		glUniform1i(uniPointLights, pointLights ? 1 : 0);

		glUniform1f(uniDensity, density);
		glUniform1f(uniHeight, height);
		glUniform1f(uniGroundY, groundY);
		glUniform2f(uniCenter, centerX, centerZ);
		glUniform2f(uniFade, fadeStart, fadeEnd);
		glUniform1f(uniNear, near);
		glUniform1f(uniSunScatter, sunScatter);
		glUniform1f(uniNoiseAmount, noiseAmount);
		glUniform1f(uniBankAmount, bankAmount);
		glUniform3fv(uniTint, areaTint);
		glUniform1i(uniSteps, steps);

		glActiveTexture(GL_TEXTURE0 + noiseUnit);
		glBindTexture(GL_TEXTURE_3D, texNoise);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindTexture(GL_TEXTURE_3D, 0);

		glActiveTexture(GL_TEXTURE0 + fogUnit);
		glBindTexture(GL_TEXTURE_2D, texFog);
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
		freeTarget();
		if (texNoise != 0)
		{
			glDeleteTextures(texNoise);
			texNoise = 0;
		}
		glDeleteProgram(program);
		program = 0;
	}

	private void initTarget(int w, int h)
	{
		targetWidth = w;
		targetHeight = h;
		texFog = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texFog);
		// half float: the scattered light is linear and dim at night, 8 bits would band
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);

		fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texFog, 0);
		glDrawBuffers(GL_COLOR_ATTACHMENT0);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("Fog framebuffer incomplete, status 0x" + Integer.toHexString(status));
		}
	}

	private void freeTarget()
	{
		if (fbo != 0)
		{
			glDeleteFramebuffers(fbo);
			glDeleteTextures(texFog);
			fbo = texFog = 0;
		}
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
