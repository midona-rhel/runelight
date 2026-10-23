package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The scene render targets: color, world-space normal and depth, as textures later passes can sample.
 * With MSAA the scene draws into multisampled renderbuffers and is resolved into the textures once per frame;
 * without MSAA it draws into the textures directly.
 */
class SceneTargets
{
	static final int COLOR_FORMAT = GL_RGBA8;
	static final int NORMAL_FORMAT = GL_RGB10_A2;
	static final int DEPTH_FORMAT = GL_DEPTH_COMPONENT32F;
	// surface material per pixel (emissive, transmissive, water), see materials.glsl and water.glsl
	static final int MATERIAL_FORMAT = GL_RGBA8;

	private int width, height, samples;
	private final int[] partTextures = new int[2], partFbos = new int[2];

	/** Full-resolution linear-light intermediates; distinct textures avoid framebuffer feedback. */
	void drawPart(int part, int unit)
	{
		glActiveTexture(GL_TEXTURE0);
		for (int i = 0; i < 2; i++)
		{
			if (partTextures[i] != 0) continue;
			partTextures[i] = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_ARRAY, partTextures[i]);
			glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA16F, width, height, i == 0 ? 3 : 1, 0, GL_RGBA, GL_FLOAT, 0L);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			partFbos[i] = glGenFramebuffers();
		}
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D_ARRAY, part == 4 ? partTextures[0] : part == 5 ? partTextures[1] : 0);
		glActiveTexture(GL_TEXTURE0);
		// Allocation used unit 0; remove that binding too before drawing.
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
		if (part == 5) return;
		int target = part == 4 ? 1 : 0;
		glBindFramebuffer(GL_FRAMEBUFFER, partFbos[target]);
		glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, partTextures[target], 0, part == 4 ? 0 : part - 1);
		glDrawBuffer(GL_COLOR_ATTACHMENT0);
		check("composite part");
		glViewport(0, 0, width, height);
	}

	// what the scene draws into: fboMsaa when multisampled, else fboResolve
	private int fboMsaa;
	private int rboColor, rboNormal, rboDepth, rboAlpha, rboMaterial;

	private int fboResolve;
	private int texColor, texNormal, texDepth;
	// transparent geometry, lit as it is drawn, premultiplied; blended over the lit opaque scene by the composite
	private int texAlpha;
	private int texMaterial;

	private static final int[] OPAQUE_BUFFERS = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_NONE, GL_COLOR_ATTACHMENT3};
	private static final int[] ALPHA_BUFFERS = {GL_NONE, GL_NONE, GL_COLOR_ATTACHMENT2, GL_NONE};
	private static final int[] ALL_BUFFERS = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT3};
	private static final int[] AO_BUFFERS = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1};
	private static final int[] DENOISE_FOG_BUFFERS = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2};
	private static final float[] TRANSPARENT = {0, 0, 0, 0};
	private static final float[] FAR_DEPTH = {0};
	private final float[] clearColor = {0, 0, 0, 0};

	// ambient occlusion at half resolution, upsampled depth-aware in the composite pass
	private int fboAo, texAo;
	// sun and moon shadows at half resolution, drawn by the AO pass and upsampled with it, and the weather's ground
	private int texShadow;
	private int aoWidth, aoHeight;
	private float halfRatio = 2;
	// the same two again, for the first of the denoise's two passes (denoise)
	private int fboAoTemp, texAoTemp, texShadowTemp;
	// the volumetric fog at the same size, which the denoise smooths along with the AO and shadows: made when the fog
	// first runs (fogFbo); fboDenoise holds the final AO, shadow and fog textures together
	private int fboFog, texFog, texFogTemp, fboDenoise;
	// the point lights' glow in the fog at half the fog's resolution, filtered when the fog pass adds it (fog_frag.glsl)
	private int fboFogLamps, texFogLamps, lampWidth, lampHeight;

	/**
	 * @param halfRatio scene pixels per texel of the half resolution passes: 2 at full render scale. Below it those
	 *                  passes stay at half the window's resolution (1 at a render scale of 50% and less, where
	 *                  they are the scene's size): their dither and grain are averaged away by filters sized in
	 *                  those texels, and at a smaller size the upscale would stretch them into visible blocks.
	 */
	void init(int width, int height, int samples, float halfRatio)
	{
		this.halfRatio = halfRatio;
		this.width = width;
		this.height = height;
		this.samples = samples;

		texColor = texture(COLOR_FORMAT, GL_RGBA, GL_UNSIGNED_BYTE);
		texNormal = texture(NORMAL_FORMAT, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV);
		texDepth = texture(DEPTH_FORMAT, GL_DEPTH_COMPONENT, GL_FLOAT);
		texAlpha = texture(COLOR_FORMAT, GL_RGBA, GL_UNSIGNED_BYTE);
		texMaterial = texture(MATERIAL_FORMAT, GL_RGBA, GL_UNSIGNED_BYTE);

		fboResolve = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboResolve);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texColor, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, texNormal, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texDepth, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, texAlpha, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT3, GL_TEXTURE_2D, texMaterial, 0);
		glDrawBuffers(OPAQUE_BUFFERS);
		check("resolve");

		aoWidth = (int) Math.ceil(width / (double) halfRatio);
		aoHeight = (int) Math.ceil(height / (double) halfRatio);
		texAo = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texAo);
		// AO and the global illumination plus the half resolution depth and normal the composite's upsamples read;
		// 32 bit floats, which halfres.glsl packs whole numbers into
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, aoWidth, aoHeight, 0, GL_RGBA, GL_FLOAT, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		texShadow = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texShadow);
		// r shadow; g and b the snow and wetness the composite's surfaces get (weather_ground_ao.glsl)
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, aoWidth, aoHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		fboAo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboAo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texAo, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, texShadow, 0);
		glDrawBuffers(AO_BUFFERS);
		check("ao");
		texAoTemp = halfTexture(GL_RGBA32F, GL_FLOAT);
		texShadowTemp = halfTexture(GL_RGBA8, GL_UNSIGNED_BYTE);
		fboAoTemp = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboAoTemp);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texAoTemp, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, texShadowTemp, 0);
		glDrawBuffers(AO_BUFFERS);
		check("ao temp");

		if (samples > 0)
		{
			fboMsaa = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, fboMsaa);
			rboColor = renderbuffer(COLOR_FORMAT);
			rboNormal = renderbuffer(NORMAL_FORMAT);
			rboDepth = renderbuffer(DEPTH_FORMAT);
			rboAlpha = renderbuffer(COLOR_FORMAT);
			rboMaterial = renderbuffer(MATERIAL_FORMAT);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboColor);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_RENDERBUFFER, rboNormal);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboDepth);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_RENDERBUFFER, rboAlpha);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT3, GL_RENDERBUFFER, rboMaterial);
			glDrawBuffers(OPAQUE_BUFFERS);
			check("msaa");
			glBindRenderbuffer(GL_RENDERBUFFER, 0);
		}
	}

	void free()
	{
		freeFog();
		glDeleteTextures(partTextures);
		glDeleteFramebuffers(partFbos);
		java.util.Arrays.fill(partTextures, 0);
		java.util.Arrays.fill(partFbos, 0);
		if (fboMsaa != 0)
		{
			glDeleteFramebuffers(fboMsaa);
			glDeleteRenderbuffers(new int[]{rboColor, rboNormal, rboDepth, rboAlpha, rboMaterial});
			fboMsaa = rboColor = rboNormal = rboDepth = rboAlpha = rboMaterial = 0;
		}
		if (fboAo != 0)
		{
			glDeleteFramebuffers(new int[]{fboAo, fboAoTemp});
			glDeleteTextures(new int[]{texAo, texShadow, texAoTemp, texShadowTemp});
			fboAo = texAo = texShadow = fboAoTemp = texAoTemp = texShadowTemp = 0;
		}
		if (fboResolve != 0)
		{
			glDeleteFramebuffers(fboResolve);
			glDeleteTextures(new int[]{texColor, texNormal, texDepth, texAlpha, texMaterial});
			fboResolve = texColor = texNormal = texDepth = texAlpha = texMaterial = 0;
		}
	}

	boolean isInitialized()
	{
		return fboResolve != 0;
	}

	int getRenderFbo()
	{
		return samples > 0 ? fboMsaa : fboResolve;
	}

	int getWidth()
	{
		return width;
	}

	int getHeight()
	{
		return height;
	}

	int getAoFbo()
	{
		return fboAo;
	}

	float getHalfRatio()
	{
		return halfRatio;
	}

	int getAoWidth()
	{
		return aoWidth;
	}

	int getAoHeight()
	{
		return aoHeight;
	}

	private int halfTexture(int format, int type)
	{
		int t = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, t);
		glTexImage2D(GL_TEXTURE_2D, 0, format, aoWidth, aoHeight, 0, GL_RGBA, type, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		return t;
	}

	/**
	 * The framebuffer the fog pass renders into, at the AO targets' size; its texture is smoothed by {@link #denoise}.
	 */
	int fogFbo()
	{
		if (fboFog == 0)
		{
			// half float: the scattered light is linear and dim at night, 8 bits would band
			texFog = halfTexture(GL_RGBA16F, GL_HALF_FLOAT);
			texFogTemp = halfTexture(GL_RGBA16F, GL_HALF_FLOAT);
			fboFog = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, fboFog);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texFog, 0);
			glDrawBuffers(GL_COLOR_ATTACHMENT0);
			check("fog");
			glBindFramebuffer(GL_FRAMEBUFFER, fboAoTemp);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, texFogTemp, 0);
			fboDenoise = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, fboDenoise);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texAo, 0);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, texShadow, 0);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, texFog, 0);
			glDrawBuffers(DENOISE_FOG_BUFFERS);
			check("denoise");

			lampWidth = (aoWidth + 1) / 2;
			lampHeight = (aoHeight + 1) / 2;
			texFogLamps = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, texFogLamps);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, lampWidth, lampHeight, 0, GL_RGBA, GL_HALF_FLOAT, 0);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glBindTexture(GL_TEXTURE_2D, 0);
			fboFogLamps = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, fboFogLamps);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texFogLamps, 0);
			glDrawBuffers(GL_COLOR_ATTACHMENT0);
			check("fog lamps");
		}
		return fboFog;
	}

	/** The framebuffer of the fog's point light glow, made with {@link #fogFbo}; and its texture and size. */
	int fogLampsFbo()
	{
		return fboFogLamps;
	}

	int fogLampsTexture()
	{
		return texFogLamps;
	}

	int getFogLampsWidth()
	{
		return lampWidth;
	}

	int getFogLampsHeight()
	{
		return lampHeight;
	}

	private void freeFog()
	{
		if (fboFog != 0)
		{
			glDeleteFramebuffers(new int[]{fboFog, fboDenoise, fboFogLamps});
			glDeleteTextures(new int[]{texFog, texFogTemp, texFogLamps});
			fboFog = fboDenoise = texFog = texFogTemp = fboFogLamps = texFogLamps = 0;
		}
	}

	/**
	 * Smooth the AO pass's dithered AO and shadow, and the fog if it was rendered this frame (denoise_frag.glsl):
	 * across into the temporary targets, then down back into the AO, shadow and fog targets. The denoise program in
	 * use, its axis uniform at axisUniform and fog flag at fogUniform, its sceneAo, sceneShadow and sceneFog samplers
	 * on aoUnit, shadowUnit and fogUnit; a fullscreen triangle's VAO bound. Leaves the units holding the temporary
	 * targets: bind the real ones again.
	 */
	void denoise(int axisUniform, int fogUniform, int aoUnit, int shadowUnit, int fogUnit, boolean fog)
	{
		glViewport(0, 0, aoWidth, aoHeight);
		glUniform1i(fogUniform, fog ? 1 : 0);
		glBindFramebuffer(GL_FRAMEBUFFER, fboAoTemp);
		glDrawBuffers(fog ? DENOISE_FOG_BUFFERS : AO_BUFFERS);
		bind(aoUnit, texAo);
		bind(shadowUnit, texShadow);
		if (fog)
		{
			bind(fogUnit, texFog);
		}
		glUniform2i(axisUniform, 1, 0);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindFramebuffer(GL_FRAMEBUFFER, fog ? fboDenoise : fboAo);
		bind(aoUnit, texAoTemp);
		bind(shadowUnit, texShadowTemp);
		if (fog)
		{
			bind(fogUnit, texFogTemp);
		}
		glUniform2i(axisUniform, 0, 1);
		glDrawArrays(GL_TRIANGLES, 0, 3);
	}

	private static void bind(int unit, int texture)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, texture);
		glActiveTexture(GL_TEXTURE0);
	}

	/** The half resolution fog, for the composite; after {@link #fogFbo}. */
	void bindFog(int unit)
	{
		bind(unit, texFog);
	}

	/** The half resolution shadows, for the composite. */
	void bindShadow(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, texShadow);
		glActiveTexture(GL_TEXTURE0);
	}

	void bindAo(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, texAo);
		glActiveTexture(GL_TEXTURE0);
	}

	/**
	 * Resolve the multisampled scene into the sampleable textures. No-op without MSAA.
	 */
	void resolve()
	{
		if (samples == 0)
		{
			return;
		}
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboMsaa);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboResolve);

		glReadBuffer(GL_COLOR_ATTACHMENT0);
		glDrawBuffers(GL_COLOR_ATTACHMENT0);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

		glReadBuffer(GL_COLOR_ATTACHMENT1);
		glDrawBuffers(GL_COLOR_ATTACHMENT1);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT, GL_NEAREST);

		glReadBuffer(GL_COLOR_ATTACHMENT2);
		glDrawBuffers(GL_COLOR_ATTACHMENT2);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

		glReadBuffer(GL_COLOR_ATTACHMENT3);
		glDrawBuffers(GL_COLOR_ATTACHMENT3);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

		glReadBuffer(GL_COLOR_ATTACHMENT0);
		glDrawBuffers(OPAQUE_BUFFERS);
	}

	/**
	 * Clear every target for a new frame, once each: color to the sky color with alpha 0 (geometry writes 1, so the
	 * resolved alpha is how much of each pixel it covers), the rest to 0, depth to far (reverse-z). Call with the
	 * render framebuffer bound.
	 */
	void clear(float r, float g, float b)
	{
		glDrawBuffers(ALL_BUFFERS);
		clearColor[0] = r;
		clearColor[1] = g;
		clearColor[2] = b;
		glClearBufferfv(GL_COLOR, 0, clearColor);
		// The normal and material at 0: a pixel the geometry only partly covers resolves to that share of its
		// values, which the composite divides back out by the color target's alpha, the coverage.
		glClearBufferfv(GL_COLOR, 1, TRANSPARENT);
		glClearBufferfv(GL_COLOR, 2, TRANSPARENT);
		glClearBufferfv(GL_COLOR, 3, TRANSPARENT);
		glClearBufferfv(GL_DEPTH, 0, FAR_DEPTH);
		glDrawBuffers(OPAQUE_BUFFERS);
	}

	/**
	 * Route drawing to the transparency layer, or back to the opaque color and normal targets.
	 */
	void setAlphaPass(boolean alpha)
	{
		glDrawBuffers(alpha ? ALPHA_BUFFERS : OPAQUE_BUFFERS);
	}

	void bindMaterial(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, texMaterial);
		glActiveTexture(GL_TEXTURE0);
	}

	void bindAlphaLayer(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, texAlpha);
		glActiveTexture(GL_TEXTURE0);
	}

	/**
	 * Bind color, normal and depth to consecutive texture units starting at {@code firstUnit}.
	 */
	void bindTextures(int firstUnit)
	{
		glActiveTexture(GL_TEXTURE0 + firstUnit);
		glBindTexture(GL_TEXTURE_2D, texColor);
		glActiveTexture(GL_TEXTURE0 + firstUnit + 1);
		glBindTexture(GL_TEXTURE_2D, texNormal);
		glActiveTexture(GL_TEXTURE0 + firstUnit + 2);
		glBindTexture(GL_TEXTURE_2D, texDepth);
		glActiveTexture(GL_TEXTURE0);
	}

	void unbindTextures(int firstUnit)
	{
		for (int i = 0; i < 3; ++i)
		{
			glActiveTexture(GL_TEXTURE0 + firstUnit + i);
			glBindTexture(GL_TEXTURE_2D, 0);
		}
		glActiveTexture(GL_TEXTURE0);
	}

	private int texture(int internalFormat, int format, int type)
	{
		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		return tex;
	}

	private int renderbuffer(int internalFormat)
	{
		int rbo = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rbo);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, internalFormat, width, height);
		return rbo;
	}

	private static void check(String which)
	{
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("Scene " + which + " framebuffer incomplete, status 0x" + Integer.toHexString(status));
		}
	}
}
