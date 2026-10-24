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
	private static final int[] FIRST_BUFFER = {GL_COLOR_ATTACHMENT0};
	private static final int[] FIRST_TWO_BUFFERS = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1};
	private static final float[] TRANSPARENT = {0, 0, 0, 0};
	private static final float[] FAR_DEPTH = {0};
	private final float[] clearColor = {0, 0, 0, 0};

	// the AO pass's geometry and global illumination at half resolution (halfres.glsl), for the upsamples
	private int fboAo, texAo;
	// sun and moon shadows, AO, the weather's ground and sky visibility at half resolution: drawn by the AO pass into
	// texShadowRaw, smoothed by the denoise (denoise) into texShadow and upsampled depth-aware in the composite pass
	private int texShadowRaw, texShadow;
	private int aoWidth, aoHeight;
	// scene pixels per texel of the half resolution passes, at every render scale: they shrink with the scene
	static final float HALF_RATIO = 2;
	// the denoise's framebuffer: the smoothed shadows, and the smoothed fog next to them; the geometry is only read
	private int fboDenoise;
	// whether the denoise's framebuffer draws into the fog target too: draw buffers are framebuffer state, so they are
	// only set again when the fog's denoising is turned on or off
	private boolean denoiseFog;
	// the volumetric fog at the same size, made when the fog first runs (fogFbo): drawn into texFogRaw for the denoise
	// to smooth into texFog along with the AO and shadows, or straight into texFog when it has no grain
	private int fboFog, fboFogRaw, texFog, texFogRaw;
	// the composite's half resolution passes at the AO targets' size, each target made when first drawn: the clouds in
	// front of the sky, filtered by the composite's sky (composite_frag.glsl), on attachment 0, and the water's shaded
	// surface (water.glsl's waterShadeHalf), the program's output 1, on attachment 1
	private int fboHalf, texClouds, texWaterShaded;
	private final int[] halfBuffers = new int[2];
	private static final int[] NO_WATER = {0, 0, 0, 0};
	// no cloud: nothing scattered, all the sky through
	private static final float[] CLEAR_SKY = {0, 0, 0, 1};

	void init(int width, int height, int samples)
	{
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

		aoWidth = (int) Math.ceil(width / (double) HALF_RATIO);
		aoHeight = (int) Math.ceil(height / (double) HALF_RATIO);
		texAo = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texAo);
		// the global illumination plus the half resolution depth and normal the composite's upsamples read; 32 bit
		// floats, which halfres.glsl packs whole numbers into
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, aoWidth, aoHeight, 0, GL_RGBA, GL_FLOAT, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		// r shadow, g AO, b the wetness the composite's surfaces get (weather_ground_ao.glsl), a sky visibility
		texShadowRaw = halfTexture(GL_RGBA8, GL_UNSIGNED_BYTE);
		texShadow = halfTexture(GL_RGBA8, GL_UNSIGNED_BYTE);
		fboAo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboAo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texAo, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, texShadowRaw, 0);
		glDrawBuffers(FIRST_TWO_BUFFERS);
		check("ao");
		fboDenoise = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboDenoise);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texShadow, 0);
		glDrawBuffers(GL_COLOR_ATTACHMENT0);
		check("denoise");
		denoiseFog = false;

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
		freeHalf();
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
			glDeleteFramebuffers(new int[]{fboAo, fboDenoise});
			glDeleteTextures(new int[]{texAo, texShadowRaw, texShadow});
			fboAo = texAo = texShadowRaw = texShadow = fboDenoise = 0;
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
		return HALF_RATIO;
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
	 * Bind the framebuffer of the composite's half resolution pass, with its viewport, drawing the clouds, the water's
	 * shaded surface or both, cleared (the clouds to clear sky, the water to none).
	 */
	void bindHalf(boolean clouds, boolean water)
	{
		if (fboHalf == 0)
		{
			fboHalf = glGenFramebuffers();
		}
		glBindFramebuffer(GL_FRAMEBUFFER, fboHalf);
		boolean made = false;
		if (clouds && texClouds == 0)
		{
			// half float: the clouds' scattered light is linear, and dim at night
			texClouds = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, texClouds);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, aoWidth, aoHeight, 0, GL_RGBA, GL_HALF_FLOAT, 0);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glBindTexture(GL_TEXTURE_2D, 0);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texClouds, 0);
			made = true;
		}
		if (water && texWaterShaded == 0)
		{
			// 128 bits packed (water.glsl's waterPackShaded)
			texWaterShaded = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, texWaterShaded);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32UI, aoWidth, aoHeight, 0, GL_RGBA_INTEGER, GL_UNSIGNED_INT, 0);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glBindTexture(GL_TEXTURE_2D, 0);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, texWaterShaded, 0);
			made = true;
		}
		halfBuffers[0] = clouds ? GL_COLOR_ATTACHMENT0 : GL_NONE;
		halfBuffers[1] = water ? GL_COLOR_ATTACHMENT1 : GL_NONE;
		glDrawBuffers(halfBuffers);
		if (made)
		{
			check("half");
		}
		glViewport(0, 0, aoWidth, aoHeight);
		// cleared first so the GPU starts without loading the old contents: the clouds to clear sky, which the texels
		// outside the rows their pass is scissored to keep (Clouds.skyRows); the water's texels are only read in its
		// rect. The water target is integer: glClear would leave it undefined.
		if (clouds)
		{
			glClearBufferfv(GL_COLOR, 0, CLEAR_SKY);
		}
		if (water)
		{
			glClearBufferuiv(GL_COLOR, 1, NO_WATER);
		}
	}

	int cloudTexture()
	{
		return texClouds;
	}

	int waterShadedTexture()
	{
		return texWaterShaded;
	}

	private void freeHalf()
	{
		if (fboHalf != 0)
		{
			glDeleteFramebuffers(fboHalf);
			glDeleteTextures(new int[]{texClouds, texWaterShaded});
			fboHalf = texClouds = texWaterShaded = 0;
		}
	}

	/**
	 * The framebuffer the fog pass renders into, at the AO targets' size: with denoised, the one {@link #denoise}
	 * smooths into the composite's fog target, else that target itself.
	 */
	int fogFbo(boolean denoised)
	{
		if (fboFog == 0)
		{
			// half float: the scattered light is linear and dim at night, 8 bits would band
			texFogRaw = halfTexture(GL_RGBA16F, GL_HALF_FLOAT);
			texFog = halfTexture(GL_RGBA16F, GL_HALF_FLOAT);
			fboFogRaw = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, fboFogRaw);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texFogRaw, 0);
			glDrawBuffers(GL_COLOR_ATTACHMENT0);
			check("fog raw");
			fboFog = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, fboFog);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texFog, 0);
			glDrawBuffers(GL_COLOR_ATTACHMENT0);
			check("fog");
			glBindFramebuffer(GL_FRAMEBUFFER, fboDenoise);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, texFog, 0);
		}
		return denoised ? fboFogRaw : fboFog;
	}

	private void freeFog()
	{
		if (fboFog != 0)
		{
			glDeleteFramebuffers(new int[]{fboFogRaw, fboFog});
			glDeleteTextures(new int[]{texFogRaw, texFog});
			fboFogRaw = fboFog = texFogRaw = texFog = 0;
		}
	}

	/**
	 * Smooth the AO pass's dithered AO and shadow, and with fog the fog drawn into {@link #fogFbo}(true) this frame
	 * (denoise_frag.glsl), into the shadow and fog targets the composite reads. The denoise program in use, its fog
	 * flag at fogUniform, its sceneAo, sceneShadow and sceneFog samplers on aoUnit, shadowUnit and fogUnit; a
	 * fullscreen triangle's VAO bound. Leaves the shadow and fog units holding the AO pass's and the fog pass's own
	 * targets: bind the smoothed ones for the composite.
	 */
	void denoise(int fogUniform, int aoUnit, int shadowUnit, int fogUnit, boolean fog)
	{
		glViewport(0, 0, aoWidth, aoHeight);
		glUniform1i(fogUniform, fog ? 1 : 0);
		glBindFramebuffer(GL_FRAMEBUFFER, fboDenoise);
		if (fog != denoiseFog)
		{
			glDrawBuffers(fog ? FIRST_TWO_BUFFERS : FIRST_BUFFER);
			denoiseFog = fog;
		}
		// the pass writes every texel: cleared first, the GPU starts it without loading the old contents
		glClear(GL_COLOR_BUFFER_BIT);
		bind(aoUnit, texAo);
		bind(shadowUnit, texShadowRaw);
		// without the fog its unit holds none: texFog may be attached to fboDenoise, unwritten
		bind(fogUnit, fog ? texFogRaw : 0);
		glDrawArrays(GL_TRIANGLES, 0, 3);
	}

	private static void bind(int unit, int texture)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, texture);
		glActiveTexture(GL_TEXTURE0);
	}

	/** The half resolution fog, for the composite; after {@link #fogFbo} (and {@link #denoise} when it smooths it). */
	void bindFog(int unit)
	{
		bind(unit, texFog);
	}

	/** The half resolution shadows smoothed by {@link #denoise}, for the composite. */
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
