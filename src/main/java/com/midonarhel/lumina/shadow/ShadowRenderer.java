/*
 * Copyright (c) 2026, Midona
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
package com.midonarhel.lumina.shadow;

import com.midonarhel.lumina.frame.DrawUniforms;
import com.midonarhel.lumina.frame.LightingUniforms;
import com.midonarhel.lumina.frame.ShaderException;
import com.midonarhel.lumina.frame.Template;
import com.midonarhel.lumina.lighting.Lighting;
import com.midonarhel.lumina.scene.SceneContext;
import com.midonarhel.lumina.sky.Celestial;
import com.midonarhel.lumina.sky.SkySource;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Runs the shadow passes at the frame's shadow hook (after every upload of the frame, before the recorded draws are
 * replayed) and fills the shadow fields of {@link Lighting} the scene shader's receiver reads ({@code shadow.glsl}).
 * The caster comes from {@link SkySource}'s {@link Celestial}; the maps are {@link StaticShadowMap} and
 * {@link DynamicShadowMap}. Nothing runs unless Lumina's lighting is active, the shadow setting is on, and the caster
 * gives light (not in the sun/moon dead band, not underground where the area's sun multiplier is 0): then the maps are
 * left untouched (the static cache keeps) and the receiver is told there are none.
 * <p>
 * The passes save and restore what they change of the scene's state: the draw framebuffer and viewport, the program,
 * the depth function (the scene draws reverse-Z with GEQUAL; the maps are drawn LESS with clear depth 1), blending,
 * the scissor test, and the active texture unit. The maps are bound to texture units {@link LightingUniforms#SHADOW_STATIC_UNIT}
 * and {@link LightingUniforms#SHADOW_DYNAMIC_UNIT}; a unit without a map holds a 1x1 depth texture at the far plane
 * with the compare mode set, since a {@code sampler2DShadow} must find such a texture even when unused.
 * Client thread, GL context current.
 */
@Slf4j
public final class ShadowRenderer
{
	public static final int STATIC_BIT = 1;
	public static final int DYNAMIC_BIT = 2;
	// receiver constants (the old renderer's)
	private static final float NORMAL_OFFSET_TEXELS = 1.5f;
	private static final float BIAS_TEXELS = 2;

	private final Client client;
	private final SkySource sky;
	private final ShadowProgram program = new ShadowProgram();
	private final StaticShadowMap staticMap = new StaticShadowMap();
	private final DynamicShadowMap dynamicMap = new DynamicShadowMap();
	private final ShadowFit freshFit = new ShadowFit();
	private boolean zeroToOne;
	private int emptyTexture;
	private final int[] viewport = new int[4];
	// why the last frame drew nothing, for the dump
	private String skipped = "not started";

	public ShadowRenderer(Client client, SkySource sky)
	{
		this.client = client;
		this.sky = sky;
	}

	/**
	 * @param zeroToOne whether glClipControl maps clip depth to [0,1] (GL 4.5), else [-1,1]
	 */
	public void init(boolean zeroToOne)
	{
		this.zeroToOne = zeroToOne;
		emptyTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, emptyTexture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT16, 1, 1, 0, GL_DEPTH_COMPONENT, GL_FLOAT, new float[]{1});
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
		glBindTexture(GL_TEXTURE_2D, 0);
		bindMaps(0, 0);
	}

	public void compile(Template template, int vao) throws ShaderException
	{
		program.compile(template, vao);
	}

	public void deletePrograms()
	{
		program.delete();
	}

	public void free()
	{
		staticMap.free();
		dynamicMap.free();
		program.delete();
		if (emptyTexture != 0)
		{
			glDeleteTextures(emptyTexture);
			emptyTexture = 0;
		}
	}

	/**
	 * The shadow passes of this frame, and the receiver's values into {@code lighting}.
	 *
	 * @param root           the top-level scene (its zones, data and the frame's minimum level)
	 * @param drawDistance   the frame's draw distance, tiles
	 * @param hasDynamic     whether the frame uploaded any dynamic opaque range
	 * @param dynamicCasters draws the frame's dynamic opaque ranges with the given per-draw uniforms
	 * @param beginStatic    starts the GPU timer of the static build share; run only when it draws
	 * @param beginDynamic   the same for the dynamic pass
	 * @param sceneFbo       the scene framebuffer to rebind for the replay
	 * @param sceneProgram   the scene program to put back in use
	 */
	public void render(Lighting lighting, SceneContext root, int drawDistance, boolean hasDynamic, Consumer<DrawUniforms> dynamicCasters,
		Runnable beginStatic, Runnable beginDynamic, int sceneFbo, int sceneProgram)
	{
		lighting.shadowsOn = 0;
		if (!lighting.luminaLighting)
		{
			skipped = "117 HD lighting";
			bindMaps(0, 0);
			return;
		}
		if (!ShadowSettings.enabled || !program.ready())
		{
			skipped = ShadowSettings.enabled ? "no program" : "setting off";
			bindMaps(0, 0);
			return;
		}
		if (root == null || root.data == null || root.zones == null)
		{
			skipped = "no scene";
			bindMaps(0, 0);
			return;
		}
		Celestial celestial = sky.celestial();
		float light = Math.max(lighting.lightColor[0], Math.max(lighting.lightColor[1], lighting.lightColor[2])) * lighting.lightStrength;
		if (celestial.casterStrength <= 0 || light < 1e-3f)
		{
			// the handoff dead band, or an area whose sun multiplier is 0 (caves): nothing to shadow, the cache keeps
			skipped = "no caster light";
			bindMaps(0, 0);
			return;
		}
		skipped = null;

		float focalX = (float) client.getCameraFocalPointX();
		float focalZ = (float) client.getCameraFocalPointZ();

		// the pass state
		glGetIntegerv(GL_VIEWPORT, viewport);
		glDepthFunc(GL_LESS);
		glClearDepth(1.0);
		glDisable(GL_BLEND);

		staticMap.update(root, celestial.casterDir, celestial.casterEpoch, focalX, focalZ, drawDistance, ShadowSettings.mapSize,
			zeroToOne, program, beginStatic);

		if (hasDynamic)
		{
			ShadowFit base = staticMap.frontFit();
			if (base == null)
			{
				ZoneBounds b = staticMap.bounds();
				float fixedY = (b.sceneMinY + b.sceneMaxY) / 2;
				freshFit.fit(celestial.casterDir, focalX, focalZ, fixedY, b.sceneMinY, b.sceneMaxY, ShadowFit.radiusFor(drawDistance),
					ShadowSettings.mapSize, zeroToOne);
				base = freshFit;
			}
			dynamicMap.draw(base, program, dynamicCasters, beginDynamic);
		}
		else
		{
			dynamicMap.skip();
		}

		// the scene's state back
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sceneFbo);
		glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
		glUseProgram(sceneProgram);
		glDepthFunc(GL_GEQUAL);
		glEnable(GL_BLEND);
		glDisable(GL_SCISSOR_TEST);

		// the receiver
		ShadowFit fit = null;
		if (staticMap.ready())
		{
			fit = staticMap.frontFit();
			lighting.shadowsOn |= STATIC_BIT;
			System.arraycopy(fit.ref, 0, lighting.shadowStaticProj, 0, 16);
			lighting.shadowTexel[0] = 1f / fit.size;
			lighting.shadowBias[0] = fit.depthBias(BIAS_TEXELS);
		}
		if (dynamicMap.drawn())
		{
			ShadowFit d = dynamicMap.fit();
			lighting.shadowsOn |= DYNAMIC_BIT;
			System.arraycopy(d.ref, 0, lighting.shadowDynamicProj, 0, 16);
			lighting.shadowTexel[1] = 1f / d.size;
			lighting.shadowBias[1] = d.depthBias(BIAS_TEXELS);
			if (fit == null)
			{
				fit = d;
			}
		}
		lighting.shadowNormalOffset = fit == null ? 0 : NORMAL_OFFSET_TEXELS * fit.texelWorld();
		bindMaps(staticMap.ready() ? staticMap.frontTexture() : 0, dynamicMap.drawn() ? dynamicMap.texture() : 0);
	}

	/** Binds the maps (0: the empty far-plane texture) to their units and leaves unit 0 active. */
	private void bindMaps(int staticTexture, int dynamicTexture)
	{
		glActiveTexture(GL_TEXTURE0 + LightingUniforms.SHADOW_STATIC_UNIT);
		glBindTexture(GL_TEXTURE_2D, staticTexture != 0 ? staticTexture : emptyTexture);
		glActiveTexture(GL_TEXTURE0 + LightingUniforms.SHADOW_DYNAMIC_UNIT);
		glBindTexture(GL_TEXTURE_2D, dynamicTexture != 0 ? dynamicTexture : emptyTexture);
		glActiveTexture(GL_TEXTURE0);
	}

	/** Developer dump: the fit, the build stats and the memory. */
	public String describe()
	{
		return "Shadows: " + (skipped != null ? "skipped (" + skipped + ") " : "")
			+ "enabled=" + ShadowSettings.enabled + " size=" + ShadowSettings.mapSize
			+ " memory=" + ((staticMap.bytes() + dynamicMap.bytes()) >> 20) + "MB"
			+ System.lineSeparator() + "  " + staticMap.describe()
			+ System.lineSeparator() + "  " + dynamicMap.describe();
	}
}
