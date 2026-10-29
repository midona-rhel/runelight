/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.frame;

import com.google.common.primitives.Ints;
import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.config.AntiAliasingMode;
import com.midonarhel.lumina.config.ColorFilter;
import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.config.LuminaConfig;
import com.midonarhel.lumina.config.LightingSourceKind;
import com.midonarhel.lumina.config.SkySettings;
import com.midonarhel.lumina.config.UIScalingMode;
import com.midonarhel.lumina.lighting.Lighting;
import com.midonarhel.lumina.lighting.LightingSource;
import com.midonarhel.lumina.scene.AreaHiding;
import com.midonarhel.lumina.scene.SceneContext;
import com.midonarhel.lumina.scene.SceneData;
import com.midonarhel.lumina.scene.SceneLoader;
import com.midonarhel.lumina.scene.Zone;
import com.midonarhel.lumina.scene.materials.MaterialManager;
import com.midonarhel.lumina.shading.DiffuseColors;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.FloatProjection;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TextureProvider;
import net.runelite.api.WorldView;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.rlawt.AWTContext;
import static com.midonarhel.lumina.frame.SceneProgram.IDENTITY;
import static com.midonarhel.lumina.frame.SceneProgram.uniEntityProj;
import static com.midonarhel.lumina.frame.SceneProgram.uniEntityTint;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The per-frame render path of the GL plugin: the scene FBO (with MSAA) the client's scene is drawn into, the
 * uniforms of a frame (camera, projection, lighting from the {@link LightingSource}, output adjustments), the sky
 * pass, the blit to the window, the interface texture and the UI pass with its upscaling, the buffer swap and the
 * frame timers. Owns the GL resources this needs; the zones and models themselves are drawn by {@link ZoneDrawer}.
 * All methods run on the client thread with the GL context current.
 */
@Slf4j
public final class FrameRenderer
{
	private static final int UNIFORM_BUFFER_SIZE = 5 * Float.BYTES;

	private final Client client;
	private final ClientUI clientUI;
	private final LuminaConfig config;
	private final TextureManager textureManager;
	private final DrawManager drawManager;
	private final Runnable stopPlugin;
	private final SceneLoader scenes;
	private final AreaHiding areaHiding;
	private final ZoneDrawer zoneDrawer;
	private final RenderThreads renderThreads;

	/**
	 * The scene light, filled each frame by the lighting source and uploaded as the scene shader's lighting uniforms.
	 */
	private final Lighting lighting = new Lighting();
	/**
	 * What decides the scene light: 117 HD's environment, sky and daylight-cycle lighting, or Lumina's sky; the
	 * "Lighting source" setting picks one each frame ({@link #lightingSource()}).
	 */
	private final LightingSource hd117Source;
	private final LightingSource luminaSource;
	// the last frame's inputs to the source, so the developer dump can ask the other source for the same frame
	private SceneData lastScene;
	private float lastElapsedTime;
	private int lastDrawDistance;
	private boolean lastHasSkybox;
	// Lumina's sky pass, drawn after the top-level opaque geometry when the Lumina source is active, and what it
	// needs from this frame's uniform setup
	private final SkyRenderer skyRenderer = new SkyRenderer();
	private float[] frameInvProjection;
	private float frameGammaCorrection;
	private float frameDitherTime;
	// 117 HD's plugin.elapsedTime: seconds since the plugin started
	private final long startNanos;

	private final FrameTiming timing;
	private final SceneProgram programs = new SceneProgram();

	private AWTContext awtContext;
	private Canvas canvas;

	private int interfaceTexture;
	private int interfacePbo;

	private int vaoUiHandle;
	private int vboUiHandle;

	private int fboScene = -1;
	private boolean sceneFboValid;
	private int rboColorBuffer;
	private int rboDepthBuffer;

	private int textureArrayId = -1;

	private final GLBuffer glUniformBuffer = new GLBuffer();
	// 117 HD's material uniform block (see MaterialManager.fillSlots)
	private final GLBuffer glMaterialBuffer = new GLBuffer();
	private final float[] materialData = new float[MaterialManager.MAX_SLOTS * 12];

	private int lastCanvasWidth = -1;
	private int lastCanvasHeight = -1;
	private int lastStretchedCanvasWidth = -1;
	private int lastStretchedCanvasHeight = -1;
	private AntiAliasingMode lastAntiAliasingMode;
	private int lastAnisotropicFilteringLevel = -1;

	private GpuFloatBuffer uniformBuffer;

	private boolean materialsUploaded;
	// ::lumina debug: 0 off, 1 base colour, 2 light only, 3 normals
	private int debugView;

	/**
	 * @param stopPlugin stops the plugin, after a fatal buffer swap error
	 */
	public FrameRenderer(Client client, ClientUI clientUI, LuminaConfig config, TextureManager textureManager, DrawManager drawManager,
		Runnable stopPlugin, LightingSource hd117Source, LightingSource luminaSource, SceneLoader scenes, AreaHiding areaHiding,
		ZoneDrawer zoneDrawer, RenderThreads renderThreads)
	{
		this.client = client;
		this.clientUI = clientUI;
		this.config = config;
		this.textureManager = textureManager;
		this.drawManager = drawManager;
		this.stopPlugin = stopPlugin;
		this.hd117Source = hd117Source;
		this.luminaSource = luminaSource;
		this.scenes = scenes;
		this.areaHiding = areaHiding;
		this.zoneDrawer = zoneDrawer;
		this.renderThreads = renderThreads;
		this.timing = new FrameTiming(client);
		startNanos = System.nanoTime();
	}

	public Lighting lighting()
	{
		return lighting;
	}

	/** The source the "Lighting source" setting picks this frame. */
	private LightingSource lightingSource()
	{
		return SkySettings.lightingSource == LightingSourceKind.HD117 ? hd117Source : luminaSource;
	}

	/**
	 * Developer dump: the light a source would give for the last frame's inputs. For the active source this is
	 * {@link #lighting()}; for the other one it is filled now into {@code out}. Client thread only.
	 */
	public Lighting lightingOf(LightingSourceKind kind, Lighting out)
	{
		LightingSource source = kind == LightingSourceKind.HD117 ? hd117Source : luminaSource;
		if (source == lightingSource())
		{
			return lighting;
		}
		source.fill(out, lastScene, lastElapsedTime, lastDrawDistance, lastHasSkybox);
		return out;
	}

	public FrameTiming timing()
	{
		return timing;
	}

	public int stretchedCanvasWidth()
	{
		return lastStretchedCanvasWidth;
	}

	public int stretchedCanvasHeight()
	{
		return lastStretchedCanvasHeight;
	}

	public void setDebugView(int debugView)
	{
		this.debugView = debugView;
	}

	/**
	 * Creates the GL resources once the context exists: buffers, the UI VAO, the programs, the interface texture
	 * and the GPU timer queries.
	 */
	public void init(AWTContext awtContext, Canvas canvas)
	{
		this.awtContext = awtContext;
		this.canvas = canvas;
		// the plugin sets glClipControl ZERO_TO_ONE on GL 4.5: the far plane is NDC depth 0 there, -1 otherwise (macOS)
		skyRenderer.farNdcDepth = GL.getCapabilities().OpenGL45 ? 0 : -1;
		initBuffers();
		initVao();
		compilePrograms();
		initInterfaceTexture();
		timing.init();
	}

	public void free()
	{
		timing.free();
		shutdownInterfaceTexture();
		deletePrograms();
		shutdownVao();
		shutdownBuffers();
		shutdownFbo();
	}

	private Template createTemplate()
	{
		Template template = new Template();
		template.add(key ->
		{
			switch (key)
			{
				case "texture_config":
					return "#define TEXTURE_COUNT " + TextureManager.TEXTURE_COUNT + "\n";
				case "sampling_mode":
					return "#define SAMPLING_MODE " + config.uiScalingMode().ordinal() + "\n";
				case "colorblind_mode":
					return "#define COLORBLIND_MODE " + config.colorBlindMode().ordinal() + "\n";
				case "shading_config":
					return "#define DISABLE_DIRECTIONAL_SHADING " + (DiffuseColors.shadingMode.directionalShading ? 0 : 1) + "\n"
						+ "#define FLAT_SHADING " + (HdSettings.flatShading ? 1 : 0) + "\n"
						+ "#define VANILLA_COLOR_BANDING " + (HdSettings.vanillaColorBanding ? 1 : 0) + "\n"
						+ "#define APPLY_COLOR_FILTER " + (HdSettings.colorFilter != ColorFilter.NONE ? 1 : 0) + "\n";
			}
			return null;
		});
		// the shader files live next to the plugin class
		template.addInclude(LuminaPlugin.class);
		return template;
	}

	/**
	 * Compiles the programs with the current settings as their defines.
	 */
	public void compilePrograms() throws ShaderException
	{
		programs.compile(createTemplate(), vaoUiHandle);
		skyRenderer.init(programs.glLuminaSkyProgram);
	}

	public void deletePrograms()
	{
		programs.delete();
	}

	private void initVao()
	{
		// Create UI VAO
		vaoUiHandle = glGenVertexArrays();
		// Create UI buffer
		vboUiHandle = glGenBuffers();
		glBindVertexArray(vaoUiHandle);

		FloatBuffer vboUiBuf = GpuFloatBuffer.allocateDirect(5 * 4);
		vboUiBuf.put(new float[]{
			// positions     // texture coords
			1f, 1f, 0f, 1f, 0f, // top right
			1f, -1f, 0f, 1f, 1f, // bottom right
			-1f, -1f, 0f, 0f, 1f, // bottom left
			-1f, 1f, 0f, 0f, 0f  // top left
		});
		vboUiBuf.rewind();
		glBindBuffer(GL_ARRAY_BUFFER, vboUiHandle);
		glBufferData(GL_ARRAY_BUFFER, vboUiBuf, GL_STATIC_DRAW);

		// position attribute
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
		glEnableVertexAttribArray(0);

		// texture coord attribute
		glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		glEnableVertexAttribArray(1);

		// unbind VAO/VBO
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	private void shutdownVao()
	{
		glDeleteBuffers(vboUiHandle);
		vboUiHandle = 0;

		glDeleteVertexArrays(vaoUiHandle);
		vaoUiHandle = 0;
	}

	private void initBuffers()
	{
		uniformBuffer = new GpuFloatBuffer(UNIFORM_BUFFER_SIZE);
		initGlBuffer(glUniformBuffer);
		initGlBuffer(glMaterialBuffer);
		glBindBuffer(GL_UNIFORM_BUFFER, glMaterialBuffer.glBufferId);
		glBufferData(GL_UNIFORM_BUFFER, (long) materialData.length * Float.BYTES, GL_DYNAMIC_DRAW);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);
		materialsUploaded = false;
		Zone.initBuffer();

		renderThreads.initVaoLists();
	}

	private void initGlBuffer(GLBuffer glBuffer)
	{
		glBuffer.glBufferId = glGenBuffers();
	}

	private void shutdownBuffers()
	{
		destroyGlBuffer(glUniformBuffer);
		destroyGlBuffer(glMaterialBuffer);
		uniformBuffer = null;
		Zone.freeBuffer();

		renderThreads.freeVaoLists();
	}

	private void destroyGlBuffer(GLBuffer glBuffer)
	{
		if (glBuffer.glBufferId != -1)
		{
			glDeleteBuffers(glBuffer.glBufferId);
			glBuffer.glBufferId = -1;
		}
	}

	private void initInterfaceTexture()
	{
		interfacePbo = glGenBuffers();

		interfaceTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void shutdownInterfaceTexture()
	{
		glDeleteBuffers(interfacePbo);
		glDeleteTextures(interfaceTexture);
		interfaceTexture = -1;
	}

	private void initFbo(int width, int height, int aaSamples)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = getScaledValue(transform.getScaleX(), width);
		height = getScaledValue(transform.getScaleY(), height);

		if (aaSamples > 0)
		{
			glEnable(GL_MULTISAMPLE);
		}
		else
		{
			glDisable(GL_MULTISAMPLE);
		}

		// Create and bind the FBO
		fboScene = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboScene);

		// Color render buffer
		rboColorBuffer = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboColorBuffer);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, aaSamples, GL_RGBA, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboColorBuffer);

		// Depth render buffer
		rboDepthBuffer = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboDepthBuffer);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, aaSamples, GL_DEPTH_COMPONENT32F, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboDepthBuffer);

		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("FBO is incomplete. status: " + status);
		}

		// Reset
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glBindRenderbuffer(GL_RENDERBUFFER, 0);
	}

	private void shutdownFbo()
	{
		if (fboScene != -1)
		{
			glDeleteFramebuffers(fboScene);
			fboScene = -1;
		}

		if (rboColorBuffer != 0)
		{
			glDeleteRenderbuffers(rboColorBuffer);
			rboColorBuffer = 0;
		}

		if (rboDepthBuffer != 0)
		{
			glDeleteRenderbuffers(rboDepthBuffer);
			rboDepthBuffer = 0;
		}
	}

	/**
	 * Frees the texture array so the next frame rebuilds it (a texture setting changed).
	 */
	public void freeTextureArray()
	{
		if (textureArrayId != -1)
		{
			textureManager.freeTextureArray(textureArrayId);
			textureArrayId = -1;
			lastAnisotropicFilteringLevel = -1;
		}
	}

	/**
	 * The client is starting over (GameState.STARTING): its textures will be reloaded.
	 */
	public void resetTextureArray()
	{
		if (textureArrayId != -1)
		{
			textureManager.freeTextureArray(textureArrayId);
		}
		textureArrayId = -1;
		lastAnisotropicFilteringLevel = -1;
	}

	/**
	 * The scene FBO holds no scene to blit (the game state dropped below loading).
	 */
	public void invalidateSceneFbo()
	{
		sceneFboValid = false;
	}

	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		timing.beginScene();

		SceneContext ctx = scenes.context(scene);
		if (ctx == null)
		{
			return;
		}

		ctx.cameraX = (int) cameraX;
		ctx.cameraY = (int) cameraY;
		ctx.cameraZ = (int) cameraZ;
		ctx.minLevel = minLevel;
		ctx.level = level;
		ctx.maxLevel = maxLevel;
		ctx.hideRoofIds = hideRoofIds;

		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			renderThreads.map();

			zoneDrawer.cameraYaw = client.getCameraYaw();
			zoneDrawer.cameraPitch = client.getCameraPitch();
			preSceneDrawToplevel(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		}
		else
		{
			System.arraycopy(((FloatProjection) entityProjection).getProjection(), 0, ctx.projection, 0, 16);
			glUniformMatrix4fv(uniEntityProj, false, ctx.projection);
			glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
		}
	}

	private void preSceneDrawToplevel(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw)
	{
		scene.setDrawDistance(getDrawDistance());

		// UBO
		uniformBuffer.clear();
		uniformBuffer
			.put(cameraYaw)
			.put(cameraPitch)
			.put(cameraX)
			.put(cameraY)
			.put(cameraZ);
		uniformBuffer.flip();

		glBindBuffer(GL_UNIFORM_BUFFER, glUniformBuffer.glBufferId);
		glBufferData(GL_UNIFORM_BUFFER, uniformBuffer.getBuffer(), GL_DYNAMIC_DRAW);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);
		uniformBuffer.clear();

		glBindBufferBase(GL_UNIFORM_BUFFER, 0, glUniformBuffer.glBufferId);

		// material table: uploaded when materials were added
		if (!materialsUploaded || MaterialManager.slotsDirty())
		{
			TextureProvider tp = client.getTextureProvider();
			int count = MaterialManager.fillSlots(materialData, tp == null ? null : tp.getTextures());
			glBindBuffer(GL_UNIFORM_BUFFER, glMaterialBuffer.glBufferId);
			glBufferSubData(GL_UNIFORM_BUFFER, 0, java.util.Arrays.copyOf(materialData, count * 12));
			glBindBuffer(GL_UNIFORM_BUFFER, 0);
			materialsUploaded = true;
		}
		glBindBufferBase(GL_UNIFORM_BUFFER, 1, glMaterialBuffer.glBufferId);

		checkGLErrors();

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		final int viewportHeight = client.getViewportHeight();
		final int viewportWidth = client.getViewportWidth();

		// Setup FBO and anti-aliasing
		{
			final AntiAliasingMode antiAliasingMode = config.antiAliasingMode();
			final Dimension stretchedDimensions = client.getStretchedDimensions();

			final int stretchedCanvasWidth = client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;
			final int stretchedCanvasHeight = client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

			// Re-create fbo
			if (lastStretchedCanvasWidth != stretchedCanvasWidth
				|| lastStretchedCanvasHeight != stretchedCanvasHeight
				|| lastAntiAliasingMode != antiAliasingMode)
			{
				shutdownFbo();

				// Bind default FBO to check whether anti-aliasing is forced
				glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
				final int forcedAASamples = glGetInteger(GL_SAMPLES);
				final int maxSamples = glGetInteger(GL_MAX_SAMPLES);
				final int samples = forcedAASamples != 0 ? forcedAASamples :
					Math.min(antiAliasingMode.getSamples(), maxSamples);

				log.debug("AA samples: {}, max samples: {}, forced samples: {}", samples, maxSamples, forcedAASamples);

				initFbo(stretchedCanvasWidth, stretchedCanvasHeight, samples);

				lastStretchedCanvasWidth = stretchedCanvasWidth;
				lastStretchedCanvasHeight = stretchedCanvasHeight;
				lastAntiAliasingMode = antiAliasingMode;
			}

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboScene);
		}

		// Setup anisotropic filtering
		final int anisotropicFilteringLevel = config.anisotropicFilteringLevel();

		if (textureArrayId != -1 && lastAnisotropicFilteringLevel != anisotropicFilteringLevel)
		{
			textureManager.setAnisotropicFilteringLevel(textureArrayId, anisotropicFilteringLevel);
			lastAnisotropicFilteringLevel = anisotropicFilteringLevel;
		}

		// Setup viewport
		int renderWidthOff = client.getViewportXOffset();
		int renderHeightOff = client.getViewportYOffset();
		int renderCanvasHeight = canvasHeight;
		int renderViewportHeight = viewportHeight;
		int renderViewportWidth = viewportWidth;
		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			renderCanvasHeight = dim.height;

			double scaleFactorY = dim.getHeight() / canvasHeight;
			double scaleFactorX = dim.getWidth() / canvasWidth;

			// Pad the viewport a little because having ints for our viewport dimensions can introduce off-by-one errors.
			final int padding = 1;

			// Ceil the sizes because even if the size is 599.1 we want to treat it as size 600 (i.e. render to the x=599 pixel).
			renderViewportHeight = (int) Math.ceil(scaleFactorY * (renderViewportHeight)) + padding * 2;
			renderViewportWidth = (int) Math.ceil(scaleFactorX * (renderViewportWidth)) + padding * 2;

			// Floor the offsets because even if the offset is 4.9, we want to render to the x=4 pixel anyway.
			renderHeightOff = (int) Math.floor(scaleFactorY * (renderHeightOff)) - padding;
			renderWidthOff = (int) Math.floor(scaleFactorX * (renderWidthOff)) - padding;
		}

		glDpiAwareViewport(renderWidthOff, renderCanvasHeight - renderViewportHeight - renderHeightOff, renderViewportWidth, renderViewportHeight);

		glUseProgram(programs.glProgram);

		// Setup uniforms
		final int drawDistance = getDrawDistance();
		final float elapsedTime = (float) ((System.nanoTime() - startNanos) / 1e9);
		final float gammaCorrection = HdSettings.getGammaCorrection();
		// 117 HD's SceneContext: instances load no extra map chunks
		final SceneContext root = scenes.root();
		final int expandedChunks = root.data != null && root.data.scene.isInstance() ? 0 : client.getExpandedMapLoading();

		areaHiding.update();

		// Scene light, fog and sky: the lighting source decides them, the shaders read them as is
		lastScene = root.data;
		lastElapsedTime = elapsedTime;
		lastDrawDistance = drawDistance;
		lastHasSkybox = scene.getSkybox() != null;
		lightingSource().fill(lighting, lastScene, lastElapsedTime, lastDrawDistance, lastHasSkybox);
		programs.sceneLightingUniforms.upload(lighting);
		glUniform1f(programs.uniDrawDistance, (float) drawDistance);
		glUniform1i(programs.uniExpandedMapLoadingChunks, expandedChunks);
		glUniform1f(programs.uniColorblindIntensity, config.colorBlindIntensity());
		glUniform1f(programs.uniGammaCorrection, gammaCorrection);
		programs.sceneOutputUniforms.upload();
		// 117 HD's dither offset, elapsedTime modulo the largest float with 1/128 precision
		final float ditherTime = elapsedTime % 65536f;
		glUniform1f(programs.uniElapsedTime, ditherTime);
		glUniform1i(programs.uniDebugView, debugView);

		// Brightness happens to also be stored in the texture provider, so we use that
		TextureProvider textureProvider = client.getTextureProvider();
		glUniform1f(programs.uniBrightness, (float) textureProvider.getBrightness());
		glUniform1f(programs.uniTextureLightMode, config.brightTextures() ? 1f : 0f);

		// Calculate projection matrix
		float[] projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
		Mat4.mul(projectionMatrix, Mat4.projection(viewportWidth, viewportHeight, 50));
		Mat4.mul(projectionMatrix, Mat4.rotateX(cameraPitch));
		Mat4.mul(projectionMatrix, Mat4.rotateY(cameraYaw));
		Mat4.mul(projectionMatrix, Mat4.translate(-cameraX, -cameraY, -cameraZ));
		glUniformMatrix4fv(programs.uniWorldProj, false, projectionMatrix);
		float[] invProjection = Mat4.inverse(projectionMatrix);
		// for Lumina's sky pass after the opaque geometry
		frameInvProjection = invProjection;
		frameGammaCorrection = gammaCorrection;
		frameDitherTime = ditherTime;

		glUniformMatrix4fv(uniEntityProj, false, IDENTITY);

		glUniform4i(uniEntityTint, 0, 0, 0, 0);

		// Bind uniforms
		glUniformBlockBinding(programs.glProgram, programs.uniBlockMain, 0);
		glUniformBlockBinding(programs.glProgram, programs.uniBlockMaterials, 1);
		glUniform1i(programs.uniTextures, 1); // texture sampler array is bound to texture1

		// Enable face culling
		glEnable(GL_CULL_FACE);

		// Enable blending
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE);

		// Enable depth testing (117 HD: GL_GEQUAL, so later geometry at equal depth wins)
		glDepthFunc(GL_GEQUAL);
		glEnable(GL_DEPTH_TEST);

		drawSky(scene, invProjection, gammaCorrection, ditherTime, cameraX, cameraY, cameraZ);

		checkGLErrors();
	}

	/**
	 * Clears the scene and draws its background: 117 HD's sky (SkyRenderer.clear and the sky pass) unless a vanilla
	 * skybox is shown, which 117 HD draws over black.
	 */
	private void drawSky(Scene scene, float[] invProjection, float gammaCorrection, float ditherTime, float cameraX, float cameraY, float cameraZ)
	{
		Model skybox = scene.getSkybox();
		if (!lighting.vanillaSkybox || skybox == null)
		{
			glClearColor(0f, 0f, 0f, 1f);
			glClearDepth(0d);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

			if (lighting.luminaLighting)
			{
				// Lumina's sky is drawn after the opaque geometry (drawSkyAfterOpaque)
				return;
			}

			// 117 HD draws the sky behind the scene (at depth 0, after it); drawn first here without depth
			glUseProgram(programs.glSkyProgram);
			programs.skyLightingUniforms.upload(lighting);
			glUniformMatrix4fv(programs.uniSkyInvProjection, false, invProjection);
			glUniform1f(programs.uniSkyGammaCorrection, gammaCorrection);
			programs.skyOutputUniforms.upload();
			glUniform1f(programs.uniSkyElapsedTime, ditherTime);
			glUniform1f(programs.uniSkyColorblindIntensity, config.colorBlindIntensity());
			glDisable(GL_BLEND);
			glDisable(GL_DEPTH_TEST);
			glDepthMask(false);
			glBindVertexArray(vaoUiHandle);
			glDrawArrays(GL_TRIANGLES, 0, 3);
			glDepthMask(true);
			glEnable(GL_DEPTH_TEST);
			glEnable(GL_BLEND);
			glUseProgram(programs.glProgram);
			return;
		}

		glClearColor(0f, 0f, 0f, 1f);
		glClearDepth(0d);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		int size = skybox.getFaceCount() * 3 * VAO.VERT_SIZE;
		RenderThread rt = renderThreads.get(0);
		VAO o = rt.vaoO.get(size);
		rt.modelUploader.uploadTempModel(skybox, null, 0, 0, 0, 0, 0,
			(int) cameraX, (int) cameraY, (int) cameraZ, o.vbo.vb);

		float[] skyboxProjection = Mat4.translate(cameraX, cameraY, cameraZ);
		o.addRange(skyboxProjection, scene, Renderable.RENDERMODE_UNSORTED_NO_DEPTH);

		rt.vaoO.draw();

		glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
	}

	/**
	 * Lumina's sky pass: called by the plugin after the top-level scene's opaque pass (DrawCallbacks.PASS_OPAQUE), as
	 * 117 HD places its sky. Draws where nothing has been drawn yet, at the far depth with the scene's GEQUAL test and
	 * no depth write, so the opaque geometry drawn later still wins and the alpha geometry blends over it. Nothing
	 * under 117 HD's source or with a vanilla skybox shown.
	 */
	public void drawSkyAfterOpaque(Scene scene)
	{
		if (!lighting.luminaLighting || (lighting.vanillaSkybox && scene.getSkybox() != null) || frameInvProjection == null)
		{
			return;
		}
		skyRenderer.draw(lighting, frameInvProjection, frameGammaCorrection, frameDitherTime, config.colorBlindIntensity(), vaoUiHandle);
		glUseProgram(programs.glProgram);
		checkGLErrors();
	}

	public void postSceneDraw(Scene scene)
	{
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			postDrawToplevel();
		}
		else
		{
			glUniform4i(uniEntityTint, 0, 0, 0, 0);
			glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
		}

		timing.endScene();
	}

	private void postDrawToplevel()
	{
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_DEPTH_TEST);

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
		sceneFboValid = true;
	}

	private void blitSceneFbo()
	{
		int width = lastStretchedCanvasWidth;
		int height = lastStretchedCanvasHeight;

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = getScaledValue(transform.getScaleX(), width);
		height = getScaledValue(transform.getScaleY(), height);

		int defaultFbo = awtContext.getFramebuffer(false);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboScene);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, defaultFbo);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
			GL_COLOR_BUFFER_BIT, GL_NEAREST);

		// Reset
		glBindFramebuffer(GL_READ_FRAMEBUFFER, defaultFbo);

		checkGLErrors();
	}

	private void prepareInterfaceTexture(int canvasWidth, int canvasHeight)
	{
		if (canvasWidth != lastCanvasWidth || canvasHeight != lastCanvasHeight)
		{
			lastCanvasWidth = canvasWidth;
			lastCanvasHeight = canvasHeight;

			glBindTexture(GL_TEXTURE_2D, interfaceTexture);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, canvasWidth, canvasHeight, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
			glBindTexture(GL_TEXTURE_2D, 0);
		}

		final BufferProvider bufferProvider = client.getBufferProvider();
		final int[] pixels = bufferProvider.getPixels();
		final int width = bufferProvider.getWidth();
		final int height = bufferProvider.getHeight();

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		glBufferData(GL_PIXEL_UNPACK_BUFFER, (long) width * height * Integer.BYTES, GL_STREAM_DRAW);
		ByteBuffer interfaceBuf = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		if (interfaceBuf != null)
		{
			interfaceBuf
				.asIntBuffer()
				.put(pixels, 0, width * height);
			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		}
		else
		{
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
			return;
		}
		// after the CPU copy above, so the query holds only the GPU upload
		timing.gpuTimer.begin(GpuTimer.UI_UPLOAD);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	public void draw(int overlayColor)
	{
		final GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING)
		{
			return;
		}

		final TextureProvider textureProvider = client.getTextureProvider();
		if (textureArrayId == -1 && textureProvider != null)
		{
			// lazy init textures as they may not be loaded at plugin start.
			// this will return -1 and retry if not all textures are loaded yet, too.
			textureArrayId = textureManager.initTextureArray(textureProvider);
		}

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		timing.cpuTimer.end(CpuTimer.CLIENT_UI);
		timing.cpuTimer.begin(CpuTimer.DRAW);
		prepareInterfaceTexture(canvasWidth, canvasHeight);

		timing.gpuTimer.begin(GpuTimer.BLIT);
		glClearColor(0, 0, 0, 1);
		glClear(GL_COLOR_BUFFER_BIT);

		if (sceneFboValid)
		{
			blitSceneFbo();
		}

		// Texture on UI
		timing.gpuTimer.begin(GpuTimer.UI);
		drawUi(overlayColor, canvasHeight, canvasWidth);
		timing.gpuTimer.end();
		timing.cpuTimer.end(CpuTimer.DRAW);

		try
		{
			timing.cpuTimer.begin(CpuTimer.SWAP);
			awtContext.swapBuffers();
			timing.cpuTimer.end(CpuTimer.SWAP);
		}
		catch (RuntimeException ex)
		{
			// this is always fatal
			if (!canvas.isValid())
			{
				// this might be AWT shutting down on VM shutdown, ignore it
				return;
			}

			log.error("error swapping buffers", ex);

			// try to stop the plugin
			stopPlugin.run();
			return;
		}

		drawManager.processDrawComplete(this::screenshot);

		timing.endFrame();

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		checkGLErrors();
	}

	private void drawUi(final int overlayColor, final int canvasHeight, final int canvasWidth)
	{
		glEnable(GL_BLEND);
		glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);

		// Use the texture bound in the first pass
		final UIScalingMode uiScalingMode = config.uiScalingMode();
		glUseProgram(programs.glUiProgram);
		glUniform1i(programs.uniTex, 0);
		glUniform2i(programs.uniTexSourceDimensions, canvasWidth, canvasHeight);
		glUniform4f(programs.uniUiAlphaOverlay,
			(overlayColor >> 16 & 0xFF) / 255f,
			(overlayColor >> 8 & 0xFF) / 255f,
			(overlayColor & 0xFF) / 255f,
			(overlayColor >>> 24) / 255f
		);
		glUniform1f(programs.uniUiColorblindIntensity, config.colorBlindIntensity());

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			glDpiAwareViewport(0, 0, dim.width, dim.height);
			glUniform2i(programs.uniTexTargetDimensions, getScaledValue(t.getScaleX(), dim.width), getScaledValue(t.getScaleY(), dim.height));
		}
		else
		{
			glDpiAwareViewport(0, 0, canvasWidth, canvasHeight);
			glUniform2i(programs.uniTexTargetDimensions, getScaledValue(t.getScaleX(), canvasWidth), getScaledValue(t.getScaleY(), canvasHeight));
		}

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear/hybrid isn't
		final int function = uiScalingMode == UIScalingMode.LINEAR || uiScalingMode == UIScalingMode.HYBRID ? GL_LINEAR : GL_NEAREST;
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, function);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, function);

		// Texture on UI
		glBindVertexArray(vaoUiHandle);
		glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

		// Reset
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glUseProgram(0);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL_BLEND);
	}

	/**
	 * Convert the front framebuffer to an Image
	 *
	 * @return
	 */
	private Image screenshot()
	{
		int width = client.getCanvasWidth();
		int height = client.getCanvasHeight();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			width = dim.width;
			height = dim.height;
		}

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		width = getScaledValue(t.getScaleX(), width);
		height = getScaledValue(t.getScaleY(), height);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		glReadBuffer(awtContext.getBufferMode());
		glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);

		// glReadPixels returns rows bottom-up, flip them to top-down
		int[] row = new int[width];
		for (int y0 = 0, y1 = height - 1; y0 < y1; ++y0, --y1)
		{
			System.arraycopy(pixels, y0 * width, row, 0, width);
			System.arraycopy(pixels, y1 * width, pixels, y0 * width, width);
			System.arraycopy(row, 0, pixels, y1 * width, width);
		}

		return image;
	}

	private int getScaledValue(final double scale, final int value)
	{
		return (int) (value * scale);
	}

	private void glDpiAwareViewport(final int x, final int y, final int width, final int height)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		glViewport(
			getScaledValue(t.getScaleX(), x),
			getScaledValue(t.getScaleY(), y),
			getScaledValue(t.getScaleX(), width),
			getScaledValue(t.getScaleY(), height));
	}

	public int getDrawDistance()
	{
		return Ints.constrainToRange(config.drawDistance(), 0, LuminaConfig.MAX_DISTANCE);
	}

	/**
	 * Logs any pending GL errors, with debug logging only.
	 */
	public static void checkGLErrors()
	{
		if (!log.isDebugEnabled())
		{
			return;
		}

		for (; ; )
		{
			int err = glGetError();
			if (err == GL_NO_ERROR)
			{
				return;
			}

			String errStr;
			switch (err)
			{
				case GL_INVALID_ENUM:
					errStr = "INVALID_ENUM";
					break;
				case GL_INVALID_VALUE:
					errStr = "INVALID_VALUE";
					break;
				case GL_INVALID_OPERATION:
					errStr = "INVALID_OPERATION";
					break;
				case GL_INVALID_FRAMEBUFFER_OPERATION:
					errStr = "INVALID_FRAMEBUFFER_OPERATION";
					break;
				default:
					errStr = "" + err;
					break;
			}

			log.debug("glGetError:", new Exception(errStr));
		}
	}
}
