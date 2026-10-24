package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import net.runelite.api.Scene;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * How much of the sky each spot sees, for the skylight (skylight_ao.glsl): a dense grid of probes, each holding the
 * visibility of the sky over its upper hemisphere as L1 spherical harmonics (four coefficients, 1 for a hemisphere
 * that is all open), worked out on the GPU against the top-down map of the scenery (OcclusionMap, the heightfield).
 *
 * Layout (sky_probe_layout.glsl): a window of PROBES x PROBES columns, SPACING world units apart (half a tile), around
 * the player. It is wrapped like a torus in a 3D RGBA16F texture, so walking only replaces the columns at its far edge
 * and nothing else moves; the lookup fades to open sky over the window's last stretch. Each column has LAYERS probes
 * that follow the ground (the scene's tile heights, a small texture here): the lowest LAYER_BASE above it, the others
 * LAYER_STEP apart, which covers the storeys of a building and its roof. The volume's last slice holds, for each column,
 * its ground and the top of the scenery over it, which the lookup needs to tell the probes inside a roof from the ones
 * above it. Where scenery covers a probe the heightfield blocks all its rays: rooms are lit by the SkyField instead.
 *
 * The update is a fragment pass per slice (sky_probes_frag.glsl), one fragment per probe, so it needs nothing newer
 * than GL 3.3. The scenery is static and the rays are fixed (sky_probes_frag.glsl), so a probe is only worked out
 * again when what it sees may have changed: when the occlusion map is redrawn (a zone rebuilt, a door, the map
 * recentered), every layer, LAYERS_PER_FRAME a frame; and the probes that walk into the window as it follows the
 * player. Otherwise nothing is traced. The columns are redone every frame (a few thousand texels). The volume is only
 * used once every layer has been worked out for the scene.
 */
final class SkyProbes
{
	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "sky_probes_frag.glsl");

	// as sky_probe_layout.glsl
	static final int PROBES = 160;
	static final float SPACING = 64;
	static final int LAYERS = 10;
	static final float LAYER_BASE = 24;
	static final float LAYER_STEP = 80;
	// the layers and the slice of the columns
	private static final int SLICES = LAYERS + 1;
	private static final int COLUMNS = LAYERS;
	// layers worked out each frame while any are out of date
	private static final int LAYERS_PER_FRAME = 2;

	private int program;
	private int uniOrigin, uniKnownOrigin, uniSlice, uniSnap, uniTerrainOffset, uniOcclusionProj,
		uniOcclusionCenterY, uniOcclusionDepthRange;
	private int uniAoOn, uniAoOrigin, uniAoCamera;
	private int terrainUnit, occlusionUnit;

	private int texture, fbo, terrainTexture;
	// the scene the terrain texture is of: its heights, which a new scene replaces, and where it is
	private int[][][] terrainHeights;
	private int sceneBaseX, sceneBaseY;
	// the window, in probe steps (world units / SPACING) of its lowest probe
	private int originX, originZ;
	// per layer: whether it has been worked out for the scene, whether for the occlusion map as now drawn, and the
	// window as of its last update
	private final boolean[] valid = new boolean[LAYERS];
	private final boolean[] current = new boolean[LAYERS];
	private int mapVersion = -1;
	private final int[] knownX = new int[LAYERS], knownZ = new int[LAYERS];
	private boolean ready;

	/** Replace the program. The terrain goes on terrainUnit and the occlusion map on occlusionUnit and the next. */
	void setProgram(int program, int terrainUnit, int occlusionUnit)
	{
		this.program = program;
		this.terrainUnit = terrainUnit;
		this.occlusionUnit = occlusionUnit;
		uniOrigin = glGetUniformLocation(program, "skyOrigin");
		uniKnownOrigin = glGetUniformLocation(program, "skyKnownOrigin");
		uniSlice = glGetUniformLocation(program, "skySlice");
		uniSnap = glGetUniformLocation(program, "skySnap");
		uniTerrainOffset = glGetUniformLocation(program, "terrainOffset");
		uniOcclusionProj = glGetUniformLocation(program, "occlusionProj");
		uniOcclusionCenterY = glGetUniformLocation(program, "occlusionCenterY");
		uniOcclusionDepthRange = glGetUniformLocation(program, "occlusionDepthRange");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "terrainHeights"), terrainUnit);
		glUniform1i(glGetUniformLocation(program, "occlusionMap"), occlusionUnit);
		glUniform1i(uniTerrainOffset, RuneLightPlugin.SCENE_OFFSET);
		glUseProgram(0);
	}

	void freeProgram()
	{
		glDeleteProgram(program);
		program = 0;
	}

	/** Look up the AO program's uniforms; the probes are sampled on {@code unit}. */
	void locateAo(int aoProgram, int unit)
	{
		uniAoOn = glGetUniformLocation(aoProgram, "skylightOn");
		uniAoOrigin = glGetUniformLocation(aoProgram, "skyOrigin");
		uniAoCamera = glGetUniformLocation(aoProgram, "skyCamera");
		glUseProgram(aoProgram);
		glUniform1i(glGetUniformLocation(aoProgram, "skyProbes"), unit);
		glUseProgram(0);
	}

	/**
	 * Once a frame on the client thread with the GL context, after the occlusion map is drawn: follow the player and
	 * work out this frame's share of the probes. Changes the framebuffer, viewport, program and blending, and the
	 * texture units it binds are left empty; expects a VAO bound. Not wanted (no skylight this frame), it frees the
	 * volume.
	 *
	 * @param focusX, focusZ the player, in world units
	 */
	void update(boolean wanted, Scene scene, OcclusionMap occlusion, float focusX, float focusZ)
	{
		if (!wanted || scene == null)
		{
			free();
			return;
		}
		// without the map there is nothing to trace: what is shown stays
		if (program == 0 || !occlusion.isCurrent())
		{
			return;
		}
		glActiveTexture(GL_TEXTURE0 + terrainUnit);
		if (texture == 0)
		{
			create();
		}
		int[][][] heights = scene.getTileHeights();
		if (heights != terrainHeights || scene.getBaseX() != sceneBaseX || scene.getBaseY() != sceneBaseY)
		{
			uploadTerrain(heights);
			terrainHeights = heights;
			sceneBaseX = scene.getBaseX();
			sceneBaseY = scene.getBaseY();
			Arrays.fill(valid, false);
			Arrays.fill(current, false);
			ready = false;
		}
		if (occlusion.version() != mapVersion)
		{
			mapVersion = occlusion.version();
			Arrays.fill(current, false);
		}
		originX = (int) Math.floor(focusX / SPACING) - PROBES / 2;
		originZ = (int) Math.floor(focusZ / SPACING) - PROBES / 2;

		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glViewport(0, 0, PROBES, PROBES);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glDisable(GL_SCISSOR_TEST);
		glColorMask(true, true, true, true);
		glUseProgram(program);
		glUniform2i(uniOrigin, originX, originZ);
		glUniformMatrix4fv(uniOcclusionProj, false, occlusion.lightProj());
		glUniform1f(uniOcclusionCenterY, occlusion.centerY());
		glUniform1f(uniOcclusionDepthRange, OcclusionMap.DEPTH_RANGE);
		glBindTexture(GL_TEXTURE_2D, terrainTexture);
		occlusion.bindForSampling(occlusionUnit, occlusionUnit + 1);
		glDisable(GL_BLEND);
		glUniform1i(uniSnap, 0);
		draw(COLUMNS);
		// the layers out of date, a few a frame; then the probes new to the window in the others
		int redone = 0;
		for (int layer = 0; layer < LAYERS; ++layer)
		{
			if (!current[layer] && redone < LAYERS_PER_FRAME)
			{
				glUniform1i(uniSnap, 0);
				draw(layer);
				current[layer] = valid[layer] = true;
				++redone;
			}
			else if (valid[layer] && (knownX[layer] != originX || knownZ[layer] != originZ))
			{
				glUniform2i(uniKnownOrigin, knownX[layer], knownZ[layer]);
				glUniform1i(uniSnap, 1);
				draw(layer);
			}
			else
			{
				continue;
			}
			knownX[layer] = originX;
			knownZ[layer] = originZ;
		}
		ready = true;
		for (boolean v : valid)
		{
			ready &= v;
		}

		glActiveTexture(GL_TEXTURE0 + terrainUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		ShadowMap.unbind(occlusionUnit, occlusionUnit + 1);
		glUseProgram(0);
	}

	private void draw(int slice)
	{
		glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texture, 0, slice);
		glUniform1i(uniSlice, slice);
		glDrawArrays(GL_TRIANGLES, 0, 3);
	}

	private void create()
	{
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		texture = glGenTextures();
		glBindTexture(GL_TEXTURE_3D, texture);
		glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, PROBES, PROBES, SLICES, 0, GL_RGBA, GL_HALF_FLOAT, (ByteBuffer) null);
		// only fetched texel by texel: the lookup weighs the probes itself
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_3D, 0);

		fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texture, 0, 0);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("Sky probe framebuffer incomplete, status 0x" + Integer.toHexString(status));
		}

		terrainTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, terrainTexture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	/** The ground's height at the corners of the scene's tiles, from the ground level's tile heights. */
	private void uploadTerrain(int[][][] heights)
	{
		int[][] ground = heights[0];
		int w = ground.length, h = ground[0].length;
		FloatBuffer texels = BufferUtils.createFloatBuffer(w * h);
		for (int z = 0; z < h; ++z)
		{
			for (int x = 0; x < w; ++x)
			{
				texels.put(ground[x][z]);
			}
		}
		texels.flip();
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, terrainTexture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, w, h, 0, GL_RED, GL_FLOAT, texels);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	/** Whether the AO pass can look the probes up this frame, in the given scene. */
	boolean isReady(Scene scene)
	{
		return ready && texture != 0 && scene != null && scene.getTileHeights() == terrainHeights
			&& scene.getBaseX() == sceneBaseX && scene.getBaseY() == sceneBaseY;
	}

	/**
	 * With the AO program in use: switch the skylight on (binding the probes on unit) when they are ready, else off.
	 * {@link #unbind} after the pass.
	 *
	 * @param cameraX, cameraY, cameraZ the camera in world units
	 */
	boolean bindAo(int unit, Scene scene, float cameraX, float cameraY, float cameraZ)
	{
		boolean on = isReady(scene);
		glUniform1i(uniAoOn, on ? 1 : 0);
		if (on)
		{
			glUniform2i(uniAoOrigin, originX, originZ);
			glUniform3f(uniAoCamera, cameraX, cameraY, cameraZ);
			glActiveTexture(GL_TEXTURE0 + unit);
			glBindTexture(GL_TEXTURE_3D, texture);
			glActiveTexture(GL_TEXTURE0);
		}
		return on;
	}

	static void unbind(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_3D, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	/** GL resources other than the program. */
	void free()
	{
		if (texture != 0)
		{
			glDeleteFramebuffers(fbo);
			glDeleteTextures(texture);
			glDeleteTextures(terrainTexture);
			texture = fbo = terrainTexture = 0;
		}
		terrainHeights = null;
		Arrays.fill(valid, false);
		Arrays.fill(current, false);
		ready = false;
	}
}
