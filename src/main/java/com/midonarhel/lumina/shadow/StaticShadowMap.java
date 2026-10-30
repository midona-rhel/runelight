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

import com.midonarhel.lumina.scene.SceneContext;
import com.midonarhel.lumina.scene.Zone;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Scene;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The static scenery's shadow map: two depth slots of the setting's size. The front slot is what receivers sample; a
 * new map is drawn into the back slot, over {@link #BUILD_FRAMES} frames (in one frame when there is no front yet,
 * after a scene load), then swapped in. A build draws every initialized zone of the top-level scene whose
 * {@link ZoneBounds} box projects onto the map, all levels from the frame's minimum to 3 with no roof hidden (roofs
 * always cast), opaque geometry only.
 * <p>
 * What starts a build: no front map; the {@link ShadowFit} gone stale (the focal point crossed half a chunk, or the
 * caster turned past the texel criterion); the caster kind changed (epoch); the minimum level changed; a scene swap
 * (the front map's fit is shifted by the scene base delta so it keeps showing, and the rebuild runs behind it); the
 * slot size changed. A zone that was rebuilt (a door, a tree, a rock) under the front map is redrawn in place: its
 * light-space rectangle is scissored, cleared and every zone overlapping it is drawn again, the same frame. A zone
 * replaced after a build already drew it restarts that build. The zones are read from {@code ctx.zones} every frame,
 * never kept across frames except as identity for the change detection.
 */
@Slf4j
public final class StaticShadowMap
{
	public static final int BUILD_FRAMES = 4;
	// more rebuilt zones than this in one frame is a full rebuild, not partial redraws
	private static final int MAX_PARTIALS = 12;
	private static final Set<Integer> NO_ROOFS = Collections.emptySet();

	private final DepthTarget[] slots = {new DepthTarget(), new DepthTarget()};
	private final ShadowFit[] fits = {new ShadowFit(), new ShadowFit()};
	private int size;
	// the slot receivers sample and the one being drawn, -1 for none
	private int front = -1;
	private int building = -1;
	private int frontMinLevel, frontEpoch;

	// the scene the front map is of: the zone array (identity: swapped on a scene load), the zones (identity: a
	// rebuilt zone is a new object), the scene base for the shift on a swap
	private Zone[][] frontArray;
	private Zone[] frontZones = new Zone[0];
	private int frontBaseX, frontBaseY;
	private boolean frontInstance;
	private boolean rebuildAll;

	// the build in progress: the zones to draw (index zx * sizeZ + zz), the ones drawn so far
	private int[] buildList = new int[64];
	private Zone[] buildZones = new Zone[64];
	private int buildCount, buildNext, buildPerFrame, buildMinLevel, buildEpoch;

	// the zones rebuilt this frame, for the partial redraw
	private final int[] partials = new int[MAX_PARTIALS];
	private int partialCount;

	private final ZoneBounds bounds = new ZoneBounds();
	private final float[] rect = new float[4];
	private final float[] scratch = new float[4];
	private final int[] texels = new int[4];

	// stats for the developer dump
	private int builds, restarts, partialRedraws, shifts;
	private int lastBuildZones;
	private long lastBuildFrame;
	private long frames;

	/** Frees the slots; the next update allocates again. */
	public void free()
	{
		slots[0].free();
		slots[1].free();
		size = 0;
		front = building = -1;
		frontArray = null;
	}

	/** Whether there is a map to sample. */
	public boolean ready()
	{
		return front >= 0;
	}

	/** The front map's fit, or null. */
	public ShadowFit frontFit()
	{
		return front >= 0 ? fits[front] : null;
	}

	int frontTexture()
	{
		return front >= 0 ? slots[front].texture : 0;
	}

	public long bytes()
	{
		return slots[0].bytes() + slots[1].bytes();
	}

	/** The scene's height bounds, for the dynamic map's fit when there is no front map yet. */
	ZoneBounds bounds()
	{
		return bounds;
	}

	/**
	 * Keeps the map current for this frame and draws this frame's share. Expects the pass state (the shadow program in
	 * use, depth test on with GL_LESS, clear depth 1) and leaves some framebuffer and viewport bound.
	 *
	 * @param onFirstDraw run right before the first GL draw command of the frame, if any (the GPU timer)
	 */
	public void update(SceneContext ctx, float[] casterDir, int casterEpoch, float focalX, float focalZ, int drawDistance, int size,
		boolean zeroToOne, ShadowProgram program, Runnable onFirstDraw)
	{
		frames++;
		if (size != this.size)
		{
			free();
			slots[0].init(size, "Static shadow map");
			slots[1].init(size, "Static shadow map");
			this.size = size;
		}

		Scene scene = ctx.data.scene;
		trackScene(ctx, scene);
		detectZoneChanges(ctx, scene);

		boolean fitStale = front >= 0 && fits[front].stale(casterDir, focalX, focalZ);
		boolean contentStale = front >= 0 && (frontEpoch != casterEpoch || frontMinLevel != ctx.minLevel);
		boolean needBuild = front < 0 || rebuildAll || fitStale || contentStale;
		boolean drew = false;

		if (building >= 0)
		{
			boolean restart = rebuildAll || buildEpoch != casterEpoch || buildMinLevel != ctx.minLevel
				|| fits[building].stale(casterDir, focalX, focalZ);
			if (restart)
			{
				restarts++;
				startBuild(ctx, casterDir, casterEpoch, focalX, focalZ, drawDistance, zeroToOne, program, onFirstDraw);
				drew = true;
			}
		}
		else if (needBuild)
		{
			startBuild(ctx, casterDir, casterEpoch, focalX, focalZ, drawDistance, zeroToOne, program, onFirstDraw);
			drew = true;
		}
		else if (partialCount > 0)
		{
			onFirstDraw.run();
			drawPartials(ctx, program);
			drew = true;
		}
		partialCount = 0;

		if (building >= 0)
		{
			if (!drew)
			{
				onFirstDraw.run();
			}
			drawShare(ctx, program);
		}
	}

	/** A scene swap: the zone array is a new one. Shifts the front map's fit by the base delta and rebuilds behind. */
	private void trackScene(SceneContext ctx, Scene scene)
	{
		if (ctx.zones == frontArray)
		{
			return;
		}
		boolean shifted = false;
		if (front >= 0 && frontArray != null && !frontInstance && !scene.isInstance())
		{
			// a world point's local coordinates drop by the base delta
			float dx = -(scene.getBaseX() - frontBaseX) * 128f;
			float dz = -(scene.getBaseY() - frontBaseY) * 128f;
			fits[front].shift(dx, dz);
			shifted = true;
			shifts++;
		}
		if (!shifted)
		{
			front = -1;
		}
		building = -1;
		rebuildAll = true;
		frontArray = ctx.zones;
		frontBaseX = scene.getBaseX();
		frontBaseY = scene.getBaseY();
		frontInstance = scene.isInstance();
		int n = ctx.sizeX * ctx.sizeZ;
		if (frontZones.length != n)
		{
			frontZones = new Zone[n];
		}
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				frontZones[x * ctx.sizeZ + z] = ctx.zones[x][z];
			}
		}
		bounds.compute(scene.getTileHeights(), ctx.sizeX, ctx.sizeZ, ZoneBounds.topLevelOffset());
		partialCount = 0;
	}

	/** Zones replaced since the last frame: partial redraws of the front, a restart of a build that drew them. */
	private void detectZoneChanges(SceneContext ctx, Scene scene)
	{
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				int i = x * ctx.sizeZ + z;
				Zone zone = ctx.zones[x][z];
				if (zone == frontZones[i])
				{
					continue;
				}
				frontZones[i] = zone;
				bounds.computeZone(scene.getTileHeights(), x, z, ZoneBounds.topLevelOffset());
				if (building >= 0 && buildDrew(i))
				{
					rebuildAll = true;
				}
				if (front >= 0 && !rebuildAll)
				{
					if (partialCount == MAX_PARTIALS)
					{
						rebuildAll = true;
						partialCount = 0;
					}
					else
					{
						partials[partialCount++] = i;
					}
				}
			}
		}
	}

	private boolean buildDrew(int index)
	{
		for (int i = 0; i < buildNext; ++i)
		{
			if (buildList[i] == index)
			{
				return true;
			}
		}
		return false;
	}

	private void startBuild(SceneContext ctx, float[] casterDir, int casterEpoch, float focalX, float focalZ, int drawDistance,
		boolean zeroToOne, ShadowProgram program, Runnable onFirstDraw)
	{
		building = front == 0 ? 1 : 0;
		ShadowFit fit = fits[building];
		float fixedY = (bounds.sceneMinY + bounds.sceneMaxY) / 2;
		fit.fit(casterDir, focalX, focalZ, fixedY, bounds.sceneMinY, bounds.sceneMaxY, ShadowFit.radiusFor(drawDistance), size, zeroToOne);
		buildEpoch = casterEpoch;
		buildMinLevel = ctx.minLevel;
		rebuildAll = false;
		partialCount = 0;

		buildCount = 0;
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				if (ctx.zones[x][z].initialized && bounds.overlapsMap(fit, x, z, scratch))
				{
					if (buildCount == buildList.length)
					{
						buildList = Arrays.copyOf(buildList, buildCount * 2);
						buildZones = Arrays.copyOf(buildZones, buildCount * 2);
					}
					buildList[buildCount++] = x * ctx.sizeZ + z;
				}
			}
		}
		buildNext = 0;
		// the first map after a load draws in one frame; later ones over BUILD_FRAMES while the front keeps showing
		buildPerFrame = front < 0 ? Math.max(1, buildCount) : Math.max(1, (buildCount + BUILD_FRAMES - 1) / BUILD_FRAMES);
		builds++;
		lastBuildZones = buildCount;
		lastBuildFrame = frames;

		onFirstDraw.run();
		slots[building].bindForDraw();
		glClear(GL_DEPTH_BUFFER_BIT);
	}

	private void drawShare(SceneContext ctx, ShadowProgram program)
	{
		ShadowFit fit = fits[building];
		slots[building].bindForDraw();
		program.use(fit.proj);
		int end = Math.min(buildNext + buildPerFrame, buildCount);
		for (; buildNext < end; ++buildNext)
		{
			int i = buildList[buildNext];
			int zx = i / ctx.sizeZ, zz = i % ctx.sizeZ;
			Zone zone = ctx.zones[zx][zz];
			buildZones[buildNext] = zone;
			drawZone(zone, zx, zz, buildMinLevel, program);
		}
		if (buildNext >= buildCount)
		{
			front = building;
			building = -1;
			frontMinLevel = buildMinLevel;
			frontEpoch = buildEpoch;
			Arrays.fill(buildZones, 0, buildCount, null);
		}
	}

	/** Redraws the front map within each rebuilt zone's light-space rectangle. */
	private void drawPartials(SceneContext ctx, ShadowProgram program)
	{
		ShadowFit fit = fits[front];
		slots[front].bindForDraw();
		program.use(fit.proj);
		glEnable(GL_SCISSOR_TEST);
		for (int p = 0; p < partialCount; ++p)
		{
			int i = partials[p];
			int zx = i / ctx.sizeZ, zz = i % ctx.sizeZ;
			bounds.rect(fit, zx, zz, rect);
			if (!ZoneBounds.texelRect(rect, size, texels))
			{
				continue;
			}
			glScissor(texels[0], texels[1], texels[2], texels[3]);
			glClear(GL_DEPTH_BUFFER_BIT);
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];
					if (zone.initialized && bounds.overlapsRect(fit, x, z, rect, scratch))
					{
						drawZone(zone, x, z, frontMinLevel, program);
					}
				}
			}
			partialRedraws++;
		}
		glDisable(GL_SCISSOR_TEST);
	}

	private static void drawZone(Zone zone, int zx, int zz, int minLevel, ShadowProgram program)
	{
		int offset = SceneContext.SCENE_OFFSET >> 3;
		zone.renderOpaque(program.uniforms, zx - offset, zz - offset, minLevel, 3, 3, NO_ROOFS);
	}

	/** Developer dump. */
	public String describe()
	{
		return String.format("static: front=%d building=%d builds=%d restarts=%d partials=%d shifts=%d lastBuild=%d zones @frame %d fit=[%s]",
			front, building, builds, restarts, partialRedraws, shifts, lastBuildZones, lastBuildFrame, front >= 0 ? fits[front] : "none");
	}
}
