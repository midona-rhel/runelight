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

import com.midonarhel.lumina.scene.AreaHiding;
import com.midonarhel.lumina.scene.ModelUploader;
import com.midonarhel.lumina.scene.SceneContext;
import com.midonarhel.lumina.scene.SceneData;
import com.midonarhel.lumina.scene.SceneLoader;
import com.midonarhel.lumina.scene.Zone;
import com.midonarhel.lumina.scene.overrides.ModelOverride;
import com.midonarhel.lumina.scene.overrides.ModelOverrideManager;
import com.midonarhel.lumina.util.ObjectOrientation;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.RenderCallbackManager;
import static com.midonarhel.lumina.frame.FrameRenderer.checkGLErrors;
import static com.midonarhel.lumina.frame.SceneProgram.IDENTITY;
import static com.midonarhel.lumina.frame.SceneProgram.uniEntityProj;
import static com.midonarhel.lumina.frame.SceneProgram.uniEntityTint;
import static com.midonarhel.lumina.scene.SceneContext.SCENE_OFFSET;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The draw callbacks the client makes while it traverses the scene: the zones' opaque and alpha geometry (drawn
 * from their uploaded buffers, alpha sorted per frame) and the dynamic and temporary models (uploaded into the
 * render threads' VAO lists per frame, alpha faces sorted into the zones). Area hiding is checked here at draw time.
 */
@Slf4j
public final class ZoneDrawer
{
	private static final int ALPHA_ZSORT_CLOSE = 2048;

	private final RenderCallbackManager renderCallbackManager;
	private final ModelOverrideManager modelOverrideManager;
	private final RenderThreads renderThreads;
	private final SceneLoader scenes;
	private final AreaHiding areaHiding;

	// the client's camera of the frame, set by the frame before the top-level scene is drawn
	int cameraYaw, cameraPitch;

	public ZoneDrawer(RenderCallbackManager renderCallbackManager, ModelOverrideManager modelOverrideManager,
		RenderThreads renderThreads, SceneLoader scenes, AreaHiding areaHiding)
	{
		this.renderCallbackManager = renderCallbackManager;
		this.modelOverrideManager = modelOverrideManager;
		this.renderThreads = renderThreads;
		this.scenes = scenes;
		this.areaHiding = areaHiding;
	}

	public void drawZoneOpaque(Scene scene, int zx, int zz)
	{
		SceneContext ctx = scenes.context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized || areaHiding.isZoneHidden(ctx, zx, zz))
		{
			return;
		}

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		// 117 HD draws opaque zones without blending
		glDisable(GL_BLEND);
		z.renderOpaque(zx - offset, zz - offset, ctx.minLevel, ctx.level, ctx.maxLevel, ctx.hideRoofIds);
		if (ctx.minLevel == 0)
		{
			z.renderGapFillers(zx - offset, zz - offset);
		}
		glEnable(GL_BLEND);

		checkGLErrors();
	}

	public void drawZoneAlpha(Scene scene, int level, int zx, int zz)
	{
		SceneContext ctx = scenes.context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized || areaHiding.isZoneHidden(ctx, zx, zz))
		{
			return;
		}

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		int dx = ctx.cameraX - ((zx - offset) << 10);
		int dz = ctx.cameraZ - ((zz - offset) << 10);
		boolean close = dx * dx + dz * dz < ALPHA_ZSORT_CLOSE * ALPHA_ZSORT_CLOSE;

		if (level == 0)
		{
			z.alphaSort(zx - offset, zz - offset, ctx.cameraX, ctx.cameraY, ctx.cameraZ);
			z.multizoneLocs(scene, zx - offset, zz - offset, ctx.cameraX, ctx.cameraZ, ctx.zones);
		}

		RenderThread rt = renderThreads.get(0);
		boolean unsorted = !close || (scene.getOverrideAmount() > 0);
		// 117 HD: colour without depth writes, then (top level only) depth without colour, so opaque models drawn
		// later on higher planes test against alpha
		z.renderAlpha(rt.modelUploader, zx - offset, zz - offset, cameraYaw, cameraPitch, ctx.minLevel, ctx.level, ctx.maxLevel, level, ctx.hideRoofIds, unsorted, false);
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			glColorMask(false, false, false, false);
			z.renderAlpha(rt.modelUploader, zx - offset, zz - offset, cameraYaw, cameraPitch, ctx.minLevel, ctx.level, ctx.maxLevel, level, ctx.hideRoofIds, unsorted, true);
			glColorMask(true, true, true, true);
		}

		checkGLErrors();
	}

	public void drawPass(Scene scene, int pass)
	{
		SceneContext ctx = scenes.context(scene);
		if (ctx == null)
		{
			return;
		}

		if (pass == DrawCallbacks.PASS_OPAQUE)
		{
			if (scene.getWorldViewId() == WorldView.TOPLEVEL)
			{
				renderThreads.drawOpaque();
			}
			else
			{
				glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
			}
		}
		else if (pass == DrawCallbacks.PASS_ALPHA)
		{
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];
					zone.removeTemp();
				}
			}
		}
		else if (pass == DrawCallbacks.PRE_PASS_ALPHA)
		{
			renderThreads.unmapAlpha();

			glUniformMatrix4fv(uniEntityProj, false, ctx.projection);
			glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
		}

		checkGLErrors();
	}

	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m, int orient, int x, int y, int z)
	{
		SceneContext ctx = scenes.context(scene);
		if (ctx == null)
		{
			return;
		}

		if (!renderCallbackManager.drawObject(scene, tileObject) || areaHiding.isOutsideArea(ctx, x, z, tileObject.getPlane()))
		{
			return;
		}

		int objectId = tileObject.getId();
		if (r instanceof DynamicObject && ((DynamicObject) r).getRecordedObjectComposition() != null)
		{
			objectId = ((DynamicObject) r).getRecordedObjectComposition().getId();
		}
		ModelOverride override = dynamicModelOverride(ctx, ModelOverrideManager.TYPE_OBJECT, objectId, x, z, tileObject.getPlane());
		if (override.hide)
		{
			return;
		}

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		// faces can also become alpha through vanilla texture materials and override materials (117 HD's
		// mightHaveTransparency)
		if (m.getFaceTransparencies() == null && m.getFaceTextures() == null && !override.mightHaveTransparency)
		{
			RenderThread rt = renderThreads.get(renderThreadId + 1);
			VAO o = rt.vaoO.get(size);
			if (o == null)
			{
				return;
			}

			rt.modelUploader.uploadTempModel(m, override, ObjectOrientation.getModelPreOrientation(ObjectOrientation.getObjectConfig(tileObject)), orient, x, y, z,
				ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb, skyVisibility(ctx, x, y, z, tileObject.getPlane()));
			o.addRange(ctx.projection, scene, 0);
		}
		else
		{
			m.calculateBoundsCylinder();

			RenderThread rt = renderThreads.get(renderThreadId + 1);
			VAO o = rt.vaoO.get(size);
			VAO a = rt.vaoA.get(size);
			if (o == null || a == null)
			{
				return;
			}

			ModelUploader sorter = rt.modelUploader;

			int start = a.vbo.vb.position();
			try
			{
				sorter.uploadSortedModel(rt, worldProjection, m, override, ObjectOrientation.getModelPreOrientation(ObjectOrientation.getObjectConfig(tileObject)), orient, x, y, z,
					ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb, a.vbo.vb, false, skyVisibility(ctx, x, y, z, tileObject.getPlane()));
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int end = a.vbo.vb.position();

			o.addRange(ctx.projection, scene, 0);

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET : 0;
				int zx = (x >> 10) + (offset >> 3);
				int zz = (z >> 10) + (offset >> 3);
				Zone zone = ctx.zones[zx][zz];

				// level is checked prior to this callback being run, in order to cull clickboxes, but
				// tileObject.getPlane()>maxLevel if visbelow is set - lower the object to the max level
				int plane = Math.min(ctx.maxLevel, tileObject.getPlane());
				// renderable modelheight is typically not set here because DynamicObject doesn't compute it on the returned model
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y, z & 1023);
			}
		}
	}

	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		SceneContext ctx = scenes.context(scene);
		if (ctx == null)
		{
			return;
		}

		if (!renderCallbackManager.drawObject(scene, gameObject) || areaHiding.isOutsideArea(ctx, x, z, gameObject.getPlane()))
		{
			return;
		}

		Renderable renderable = gameObject.getRenderable();
		ModelOverride override = tempModelOverride(ctx, gameObject, renderable, x, z);
		if (override.hide)
		{
			return;
		}

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		int renderMode = renderable.getRenderMode();
		if (renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH || m.getFaceTransparencies() != null || m.getTransparency() != 0
			|| m.getFaceTextures() != null || override.mightHaveTransparency)
		{
			RenderThread rt = renderThreads.get(0);
			VAO o = rt.vaoO.get(size);
			VAO a = rt.vaoA.get(size);
			ModelUploader uploader = rt.modelUploader;

			int start = a.vbo.vb.position();
			m.calculateBoundsCylinder();
			try
			{
				uploader.uploadSortedModel(rt, worldProjection, m, override, ObjectOrientation.getModelPreOrientation(gameObject.getConfig()), orient, x, y, z,
					ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb, a.vbo.vb,
					renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH, skyVisibility(ctx, x, y, z, gameObject.getPlane()));
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int end = a.vbo.vb.position();

			o.addRange(ctx.projection, scene, renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH ? renderMode : 0);

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
				int zx = (gameObject.getX() >> 10) + offset;
				int zz = (gameObject.getY() >> 10) + offset;
				Zone zone = ctx.zones[zx][zz];
				int plane = Math.min(ctx.maxLevel, gameObject.getPlane());
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y - renderable.getModelHeight() /* to render players over locs */, z & 1023);
			}
		}
		else
		{
			RenderThread rt = renderThreads.get(0);
			VAO o = rt.vaoO.get(size);
			ModelUploader uploader = rt.modelUploader;
			uploader.uploadTempModel(m, override, ObjectOrientation.getModelPreOrientation(gameObject.getConfig()), orient, x, y, z,
				ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb, skyVisibility(ctx, x, y, z, gameObject.getPlane()));
			o.addRange(ctx.projection, scene, 0);
		}
	}

	/**
	 * The sky visibility of a dynamic or temporary model, one value at its base: its tile's quadrants on the level
	 * its height belongs to (a model on a bridge deck or an upper storey takes that floor's level, whatever its plane).
	 */
	private static int skyVisibility(SceneContext ctx, int x, int y, int z, int plane)
	{
		SceneData data = ctx.data;
		if (data == null)
		{
			return 255;
		}
		return data.skyVisibility(x, y, z, plane, 2);
	}

	/**
	 * The model override of a temporary model (players, NPCs, projectiles, graphics objects and spawned locs).
	 */
	private ModelOverride tempModelOverride(SceneContext ctx, GameObject gameObject, Renderable renderable, int x, int z)
	{
		int plane = gameObject.getPlane();
		if (renderable instanceof NPC)
		{
			return dynamicModelOverride(ctx, ModelOverrideManager.TYPE_NPC, ((NPC) renderable).getId(), x, z, plane);
		}
		if (renderable instanceof GraphicsObject)
		{
			return dynamicModelOverride(ctx, ModelOverrideManager.TYPE_GRAPHICS_OBJECT, ((GraphicsObject) renderable).getId(), x, z, plane);
		}
		if (renderable instanceof Projectile)
		{
			return dynamicModelOverride(ctx, ModelOverrideManager.TYPE_PROJECTILE, ((Projectile) renderable).getId(), x, z, plane);
		}
		if (renderable instanceof DynamicObject)
		{
			var composition = ((DynamicObject) renderable).getRecordedObjectComposition();
			int id = composition != null ? composition.getId() : gameObject.getId();
			return dynamicModelOverride(ctx, ModelOverrideManager.TYPE_OBJECT, id, x, z, plane);
		}
		if (renderable instanceof Model)
		{
			return dynamicModelOverride(ctx, ModelOverrideManager.TYPE_OBJECT, gameObject.getId(), x, z, plane);
		}
		return ModelOverride.NONE;
	}

	/**
	 * Looks up a model override, resolving area-restricted overrides from the model's local position.
	 */
	private ModelOverride dynamicModelOverride(SceneContext ctx, int type, int id, int x, int z, int plane)
	{
		ModelOverride override = modelOverrideManager.getOverride(type, id);
		if (override.areaOverrides != null && ctx.data != null)
		{
			override = ModelOverrideManager.resolveArea(override, ctx.data.localToWorld(x, z, plane));
		}
		return override;
	}
}
