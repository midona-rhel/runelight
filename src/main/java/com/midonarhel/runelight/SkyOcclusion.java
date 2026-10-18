package com.midonarhel.runelight;

import java.util.Collections;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Top-down depth of the scenery around the player, every level and roof included, for the skylight
 * (skylight_ao.glsl): how much of the sky each pixel sees, from how high the scenery rises around it, and whether
 * it is the top of its column, open to the sky (light_occlusion.glsl keeps covered lights off such surfaces above
 * them). The weather's occlusion map, but kept whenever the skylight is on: drawn once and again only after the
 * player moves far or the scenery changes.
 */
class SkyOcclusion
{
	// OCCLUSION_SIZE^2 texels over +-RADIUS world units, 1/8 tile per texel, 4 MB
	static final int SIZE = 1024;
	static final float RADIUS = 64 * 128;
	// world units above and below the player's ground that the map captures
	static final float DEPTH_RANGE = 4096;
	// redraw once the player is this far from the map center
	static final float RECENTER = 24 * 128;
	private static final float[] UP = {0, -1, 0};

	private final ShadowMap map = new ShadowMap();
	private boolean dirty = true;
	private boolean wanted;
	private float focusX, focusY, focusZ;
	private int focusPlane;
	private float mapX, mapY, mapZ;
	private int mapPlane = -1;
	private final float[] projRel = new float[16];

	private int uniOn, uniProjRel, uniDepthRange;

	/**
	 * Once a frame: whether the map is wanted (skylight on, under the open sky) and where the player is. Frees it
	 * when not wanted.
	 */
	void update(boolean wanted, float fx, float fy, float fz, int plane)
	{
		this.wanted = wanted;
		if (!wanted)
		{
			if (map.isInitialized())
			{
				map.free();
				dirty = true;
			}
			return;
		}
		focusX = fx;
		focusY = fy;
		focusZ = fz;
		focusPlane = plane;
		if (plane != mapPlane || Math.abs(fx - mapX) > RECENTER || Math.abs(fz - mapZ) > RECENTER
			|| Math.abs(fy - mapY) > DEPTH_RANGE / 4)
		{
			dirty = true;
		}
	}

	/** The scenery changed: redraw. */
	void invalidate()
	{
		dirty = true;
	}

	/** Whether the AO pass can sample the map this frame. */
	boolean isReady()
	{
		return wanted && map.isInitialized() && !dirty;
	}

	void free()
	{
		map.free();
		dirty = true;
	}

	/** Redraw the map if needed, with the shadow program, as Weather.renderOcclusion does. */
	void render(RuneLightPlugin.SceneContext ctx, int program, int uniLightProj, int uniEntityProj, int uniBase,
		int uniTexturesReady, boolean texturesReady, boolean zeroToOne)
	{
		if (!wanted || program == 0 || ctx == null || ctx.zones == null)
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
		mapX = focusX;
		mapY = focusY;
		mapZ = focusZ;
		mapPlane = focusPlane;
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

	/** Look up the AO program's uniforms; the map is sampled raw on {@code unit} (unit + 1 is taken too). */
	void locateAo(int program, int unit)
	{
		uniOn = glGetUniformLocation(program, "skylightOn");
		uniProjRel = glGetUniformLocation(program, "skyOcclusionProjRel");
		uniDepthRange = glGetUniformLocation(program, "skyOcclusionDepthRange");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "skyOcclusion"), unit);
		glUseProgram(0);
	}

	/**
	 * With the AO program in use: switch the skylight on (binding the map on unit and unit + 1) when the map is
	 * ready, else off. {@link #unbind} after the pass.
	 */
	boolean bindAo(int unit, float cameraX, float cameraY, float cameraZ)
	{
		boolean on = isReady();
		glUniform1i(uniOn, on ? 1 : 0);
		if (on)
		{
			ShadowMap.relative(map.lightProj, projRel, cameraX, cameraY, cameraZ);
			glUniformMatrix4fv(uniProjRel, false, projRel);
			glUniform1f(uniDepthRange, DEPTH_RANGE);
			map.bindForSampling(unit, unit + 1);
		}
		return on;
	}

	static void unbind(int unit)
	{
		ShadowMap.unbind(unit, unit + 1);
	}
}
