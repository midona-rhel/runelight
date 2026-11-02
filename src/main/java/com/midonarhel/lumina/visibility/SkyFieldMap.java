package com.midonarhel.lumina.visibility;

import com.midonarhel.lumina.*;
import com.midonarhel.lumina.common.LayerEase;

import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The sky's light under cover as a texture for the skylight (SkyField, skylight_ao.glsl): worked out on a worker
 * thread for the player's plane whenever the walls (a door opens), the rooms, the plane or the reach change, then
 * uploaded. Until the first result the skylight keeps to the traced sky visibility alone (SkyGrid). A field of the
 * same scene and plane (a door opened or shut) goes to a second texture and eases in over the one shown (LayerEase):
 * the light through a door fades from the old to the new instead of changing in one frame.
 */
public final class SkyFieldMap
{
	private static final class Job
	{
		final WallMap walls;
		final RoomMap rooms;
		final byte[] floors;
		final int plane;
		final float falloff;
		float[] texels;

		Job(WallMap walls, RoomMap rooms, byte[] floors, int plane, float falloff)
		{
			this.walls = walls;
			this.rooms = rooms;
			this.floors = floors;
			this.plane = plane;
			this.falloff = falloff;
		}

		boolean sameAs(WallMap w, RoomMap r, byte[] f, int p, float fall)
		{
			return w == walls && r == rooms && f == floors && p == plane && fall == falloff;
		}
	}

	private ExecutorService worker;
	// the newest request (client thread), and the newest finished one, handed over by the worker
	private Job requested;
	private volatile Job done;
	private Job uploaded;
	private int texture;
	// the field shown before the newest, easing out since the newest was uploaded (0: none)
	private int prevTexture;
	private long since;
	private int uniOn, uniMap, uniPlane, uniWeight;

	/**
	 * Once a frame: follow the scene's walls, rooms and floors for the plane. Passing nothing (the skylight is off, or
	 * no scene) frees the texture.
	 */
	public void update(WallMap walls, RoomMap rooms, byte[] floors, int plane, float reachTiles)
	{
		if (walls == null || rooms == null || plane < 0 || plane >= WallMap.PLANES || walls.size != rooms.size)
		{
			free();
			return;
		}
		float falloff = SkyField.falloffOf(reachTiles);
		if (requested == null || !requested.sameAs(walls, rooms, floors, plane, falloff))
		{
			final Job job = new Job(walls, rooms, floors, plane, falloff);
			requested = job;
			if (worker == null)
			{
				worker = Executors.newSingleThreadExecutor(r ->
				{
					Thread t = new Thread(r, "Lumina sky field");
					t.setDaemon(true);
					return t;
				});
			}
			worker.execute(() ->
			{
				boolean[] covered = SkyField.covered(job.rooms, job.floors, job.plane);
				float[] light = SkyField.propagate(job.walls, covered, job.plane, job.falloff);
				int[] groups = SkyField.cornerRoots(job.walls, job.plane);
				job.texels = SkyField.pack(job.walls, job.plane, light, covered,
					SkyField.cornerLight(job.walls, job.plane, covered, job.falloff, groups, null), groups);
				done = job;
			});
		}
		Job d = done;
		if (d != null && d != uploaded && d == requested)
		{
			upload(d);
		}
	}

	private void upload(Job job)
	{
		int w = job.walls.size * SkyField.TEXELS_PER_TILE;
		FloatBuffer buf = BufferUtils.createFloatBuffer(job.texels.length);
		buf.put(job.texels).flip();
		Job shown = uploaded;
		if (texture != 0 && shown != null && shown.walls.size == job.walls.size && shown.walls.offset == job.walls.offset
			&& shown.plane == job.plane)
		{
			// the same scene's field again (a door): the shown one eases out, into the other texture (the one easing
			// out before, if any, is cut short)
			int t = prevTexture != 0 ? prevTexture : glGenTextures();
			prevTexture = texture;
			texture = t;
			since = com.midonarhel.lumina.common.Clock.nanos();
		}
		else
		{
			if (prevTexture != 0)
			{
				glDeleteTextures(prevTexture);
				prevTexture = 0;
			}
			if (texture == 0)
			{
				texture = glGenTextures();
			}
		}
		glBindTexture(GL_TEXTURE_2D, texture);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		// the corners' values, then their gradients (SkyField.pack)
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, 2 * w, 0, GL_RGBA, GL_FLOAT, buf);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		uploaded = job;
	}

	public void free()
	{
		if (worker != null)
		{
			worker.shutdownNow();
			worker = null;
		}
		if (texture != 0)
		{
			glDeleteTextures(texture);
			texture = 0;
		}
		if (prevTexture != 0)
		{
			glDeleteTextures(prevTexture);
			prevTexture = 0;
		}
		requested = null;
		done = null;
		uploaded = null;
	}

	/** Look up the AO program's uniforms; the texture is sampled on {@code unit}, the one easing out on {@code prevUnit}. */
	public void locateAo(int program, int unit, int prevUnit)
	{
		uniOn = glGetUniformLocation(program, "skyFieldOn");
		uniMap = glGetUniformLocation(program, "skyFieldMap");
		uniPlane = glGetUniformLocation(program, "skyFieldPlane");
		uniWeight = glGetUniformLocation(program, "skyFieldWeight");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "skyField"), unit);
		glUniform1i(glGetUniformLocation(program, "skyFieldPrev"), prevUnit);
		glUseProgram(0);
	}

	/**
	 * With the AO program in use: bind the newest uploaded texture on unit (the last one stands in while the next is
	 * worked out), and say where it lies. {@link #unbind} after the pass.
	 */
	public void bindAo(int unit, int prevUnit, float cameraX, float cameraZ)
	{
		boolean on = texture != 0 && uploaded != null;
		glUniform1i(uniOn, on ? 1 : 0);
		if (on)
		{
			// camera-relative world units to texture coordinates: tiles of 128 in a grid of size, past the offset
			float size = uploaded.walls.size;
			float scale = 1 / (128 * size);
			glUniform4f(uniMap, scale, scale, (cameraX / 128 + uploaded.walls.offset) / size,
				(cameraZ / 128 + uploaded.walls.offset) / size);
			glUniform1i(uniPlane, uploaded.plane);
			// LayerEase: the newest field's share since it was uploaded, the one before it the rest
			float w = prevTexture != 0 ? LayerEase.weight(since, com.midonarhel.lumina.common.Clock.nanos()) : 1;
			glUniform1f(uniWeight, w);
			glActiveTexture(GL_TEXTURE0 + unit);
			glBindTexture(GL_TEXTURE_2D, texture);
			glActiveTexture(GL_TEXTURE0 + prevUnit);
			glBindTexture(GL_TEXTURE_2D, w < 1 ? prevTexture : 0);
			glActiveTexture(GL_TEXTURE0);
		}
	}

	public static void unbind(int unit, int prevUnit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + prevUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
	}
}
