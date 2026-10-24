package com.midonarhel.runelight;

import java.util.Collections;
import static org.lwjgl.opengl.GL33C.*;

/**
 * A top-down orthographic depth map of all static scenery around the player (the heightfield), drawn with every roof
 * and every level, including roofs the game (or a roof hiding plugin) hides from the camera. A point is under the open
 * sky when nothing in its column is above it. The weather (Weather) takes its shelter from it, the sky probes
 * (SkyProbes) march their rays through it.
 *
 * The map covers +-RADIUS world units around a center and is only redrawn when the player walks RECENTER away from
 * that center, the plane or height changes, the scene loads or a zone changes, so it costs nothing on most frames.
 * It exists while one of its users wants it (follow) and is freed once none does.
 */
final class OcclusionMap
{
	// SIZE^2 texels over +-RADIUS world units, 1/8 tile per texel, 4 MB
	private static final int SIZE = 1024;
	static final float RADIUS = 64 * 128;
	// world units above and below the player's ground that the map captures
	static final float DEPTH_RANGE = 4096;
	// redraw once the player is this far from the map center; past the map, the weather's particles fall to the ground
	// (hidden by the scene) instead of stopping on roofs, which is too small to see there
	static final float RECENTER = 24 * 128;
	private static final float[] UP = {0, -1, 0};

	private final ShadowMap map = new ShadowMap();
	private boolean wanted;
	private boolean dirty = true;
	// the center the map was drawn for, and the player as of the last follow
	private float centerX, centerY, centerZ;
	private int centerPlane = -1;
	private float focusX, focusY, focusZ;
	private int focusPlane;

	/**
	 * Once a frame: whether anything wants the map, and where the player is (world units, y down). Not wanted, the map
	 * is freed.
	 */
	void follow(boolean wanted, float fx, float fy, float fz, int plane)
	{
		this.wanted = wanted;
		if (!wanted)
		{
			free();
			return;
		}
		focusX = fx;
		focusY = fy;
		focusZ = fz;
		focusPlane = plane;
		if (plane != centerPlane
			|| Math.abs(fx - centerX) > RECENTER
			|| Math.abs(fz - centerZ) > RECENTER
			|| Math.abs(fy - centerY) > DEPTH_RANGE / 4)
		{
			dirty = true;
		}
	}

	/** Scenery changed (scene load, zone rebuild): redraw the map before it is next used. */
	void invalidate()
	{
		dirty = true;
	}

	boolean isInitialized()
	{
		return map.isInitialized();
	}

	/** Whether the map is drawn for the latest follow and can be read. */
	boolean isCurrent()
	{
		return map.isInitialized() && !dirty;
	}

	/** World to clip matrix of the map as drawn. */
	float[] lightProj()
	{
		return map.lightProj;
	}

	/** World y of the center the map was drawn for: depth is measured from it. */
	float centerY()
	{
		return centerY;
	}

	void bindForSampling(int rawUnit, int compareUnit)
	{
		map.bindForSampling(rawUnit, compareUnit);
	}

	void free()
	{
		map.free();
		dirty = true;
	}

	/**
	 * Redraw the map if needed, with the shadow program: same vertex formats, and it keeps alpha tested holes
	 * (leaves, grates) open.
	 */
	void render(RuneLightPlugin.SceneContext ctx, int program, int uniLightProj, int uniEntityProj,
		int uniBase, int uniTexturesReady, boolean texturesReady, boolean zeroToOne)
	{
		if (!wanted || program == 0 || ctx == null)
		{
			return;
		}
		if (!map.isInitialized())
		{
			map.init(SIZE);
			dirty = true;
		}
		if (!dirty)
		{
			return;
		}
		dirty = false;
		centerX = focusX;
		centerY = focusY;
		centerZ = focusZ;
		centerPlane = focusPlane;
		map.update(UP, focusX, focusY, focusZ, 0, 0, 0, RADIUS, DEPTH_RANGE, zeroToOne);

		glBindFramebuffer(GL_FRAMEBUFFER, map.getFbo());
		glViewport(0, 0, SIZE, SIZE);
		glClearDepth(1.0);
		glClear(GL_DEPTH_BUFFER_BIT);
		glEnable(GL_DEPTH_TEST);
		// smaller window depth is higher up: keep the topmost surface in each column
		glDepthFunc(GL_LESS);
		glDepthMask(true);
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);

		glUseProgram(program);
		glUniformMatrix4fv(uniLightProj, false, map.lightProj);
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
			float reach = RADIUS + 1024;
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
}
