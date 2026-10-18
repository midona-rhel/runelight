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

	private static final int NOISE_SIZE = 32;

	// Per-area overrides for an environment system: multipliers on the configured values, and a color tint
	// on the fog's ambient light. 1 leaves the config as it is.
	float areaDensity = 1;
	float areaHeight = 1;
	final float[] areaTint = {1, 1, 1};

	// the values the pass uses this frame, in world units
	private float density;
	private float height;
	private float groundY;
	private float centerX, centerZ;
	private float fadeStart, fadeEnd;
	private float sunScatter;
	private float noiseAmount;

	private int program;
	private int uniCameraPos, uniTime, uniOutdoors, uniProceduralSky, uniClientSkyColor, uniCasterDir,
		uniSunIsCaster, uniSunAltitude, uniShadowsEnabled, uniLightProjRel, uniShadowDepthRange,
		uniPointLights, uniDensity, uniHeight, uniGroundY, uniCenter, uniFade, uniSunScatter, uniNoiseAmount,
		uniTint, uniViewProj, uniInvViewProj, uniViewport, uniDepthZeroToOne;

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
		uniShadowsEnabled = glGetUniformLocation(program, "shadowsEnabled");
		uniLightProjRel = glGetUniformLocation(program, "lightProjRel");
		uniShadowDepthRange = glGetUniformLocation(program, "shadowDepthRange");
		uniPointLights = glGetUniformLocation(program, "pointLightsEnabled");
		uniDensity = glGetUniformLocation(program, "fogDensity");
		uniHeight = glGetUniformLocation(program, "fogHeight");
		uniGroundY = glGetUniformLocation(program, "fogGroundY");
		uniCenter = glGetUniformLocation(program, "fogCenter");
		uniFade = glGetUniformLocation(program, "fogFade");
		uniSunScatter = glGetUniformLocation(program, "fogSunScatter");
		uniNoiseAmount = glGetUniformLocation(program, "fogNoiseAmount");
		uniTint = glGetUniformLocation(program, "fogTint");
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
		// a long, soft fade so there is no visible edge
		fadeStart = fadeEnd * 0.35f;
		sunScatter = config.volumetricFogSunScattering() / 100f;
		noiseAmount = config.volumetricFogNoise() / 100f;
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
		glUniform1f(uniSunScatter, sunScatter);
		glUniform1f(uniNoiseAmount, noiseAmount);
		glUniform3fv(uniTint, areaTint);

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
		ByteBuffer buf = BufferUtils.createByteBuffer(data.length);
		buf.put(data).flip();

		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_3D, tex);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, NOISE_SIZE, NOISE_SIZE, NOISE_SIZE, 0, GL_RED, GL_UNSIGNED_BYTE, buf);
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
	 * Tiling fractal value noise, normalized to 0..255: smooth blobs at a quarter of the size plus finer detail.
	 * Built once on the CPU so the shader needs a single filtered fetch per step.
	 */
	static byte[] noise(int size, long seed)
	{
		float[] out = new float[size * size * size];
		Random rng = new Random(seed);
		float amp = 1;
		for (int cells = 4; cells <= 16; cells *= 2, amp *= 0.5f)
		{
			float[] lattice = new float[cells * cells * cells];
			for (int i = 0; i < lattice.length; ++i)
			{
				lattice[i] = rng.nextFloat();
			}
			float scale = (float) cells / size;
			for (int z = 0; z < size; ++z)
			{
				for (int y = 0; y < size; ++y)
				{
					for (int x = 0; x < size; ++x)
					{
						out[(z * size + y) * size + x] += amp * sampleLattice(lattice, cells, x * scale, y * scale, z * scale);
					}
				}
			}
		}

		float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
		for (float v : out)
		{
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		byte[] bytes = new byte[out.length];
		for (int i = 0; i < out.length; ++i)
		{
			bytes[i] = (byte) Math.round((out[i] - min) / (max - min) * 255);
		}
		return bytes;
	}

	private static float sampleLattice(float[] l, int n, float x, float y, float z)
	{
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
		float fx = smooth(x - x0), fy = smooth(y - y0), fz = smooth(z - z0);
		float c00 = lerp(at(l, n, x0, y0, z0), at(l, n, x0 + 1, y0, z0), fx);
		float c10 = lerp(at(l, n, x0, y0 + 1, z0), at(l, n, x0 + 1, y0 + 1, z0), fx);
		float c01 = lerp(at(l, n, x0, y0, z0 + 1), at(l, n, x0 + 1, y0, z0 + 1), fx);
		float c11 = lerp(at(l, n, x0, y0 + 1, z0 + 1), at(l, n, x0 + 1, y0 + 1, z0 + 1), fx);
		return lerp(lerp(c00, c10, fy), lerp(c01, c11, fy), fz);
	}

	private static float at(float[] l, int n, int x, int y, int z)
	{
		return l[((Math.floorMod(z, n) * n + Math.floorMod(y, n)) * n) + Math.floorMod(x, n)];
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
