package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.runelite.api.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The skylight's ray traced sky visibility (SkyGrid) as a 3D texture for the AO pass (skylight_ao.glsl). A worker
 * thread traces RAY_BUDGET rays a frame through the global illumination's voxels (GlobalIllumination.getVoxels),
 * nearest the player first; what changed is uploaded every UPLOAD_FRAMES frames. A new place is shown once the
 * corners within READY_RINGS of the player are traced; until then, and after a move into the next region until its
 * voxels are built, the last texture stays, moved to where it belongs, as the probes do.
 */
final class SkyGridMap
{
	private static final Logger log = LoggerFactory.getLogger(SkyGridMap.class);

	// rays a frame: about half a millisecond of the worker's time (SkyGridTest measures it)
	static final int RAY_BUDGET = 4096;
	static final int UPLOAD_FRAMES = 6;
	// rings of corners around the player traced before a new place is shown
	static final int READY_RINGS = 16;

	private SkyGrid sky;
	private ExecutorService worker;
	private Future<?> job;
	// what the last job was handed, and what it said
	private GlobalIllumination.Voxels handedVoxels;
	private WallMap handedWalls;
	private volatile boolean jobFresh;
	private boolean wanted;
	private int texture;
	// the texture holds a place traced around the player, and where that place is
	private boolean ready;
	private int shownBaseX, shownBaseY;
	private boolean shownInstance;
	// changes not uploaded yet, as SkyGrid.takeChanges; whether they include a move to sky's base
	private int[] pending;
	private boolean pendingMove;
	private int framesSinceUpload;
	private int uniOn, uniCamera;

	/**
	 * Once a frame on the client thread with the GL context: hand the worker the newest voxels and walls and the next
	 * rays, and upload what it traced. Not wanted (no skylight this frame), it does nothing and the AO pass skips it.
	 *
	 * @param voxels the newest voxels, null before the first build
	 * @param worldTileX, worldTileZ the player, in world tiles
	 */
	void update(boolean wanted, GlobalIllumination.Voxels voxels, WallMap walls, float worldTileX, float worldTileZ)
	{
		this.wanted = wanted;
		if (!wanted)
		{
			return;
		}
		framesSinceUpload++;
		if (job != null && !job.isDone())
		{
			return;
		}
		if (job != null)
		{
			job = null;
			collect();
		}
		if (pending != null && (pendingMove || framesSinceUpload >= UPLOAD_FRAMES))
		{
			upload();
		}
		boolean newVoxels = voxels != handedVoxels, newWalls = walls != handedWalls;
		if (voxels == null || !newVoxels && !newWalls && sky.dirtyCount() == 0)
		{
			return;
		}
		if (sky == null)
		{
			sky = new SkyGrid();
			worker = Executors.newSingleThreadExecutor(r ->
			{
				Thread t = new Thread(r, "RuneLight sky grid");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			});
		}
		handedVoxels = voxels;
		handedWalls = walls;
		SkyGrid s = sky;
		job = worker.submit(() ->
		{
			try
			{
				if (newWalls)
				{
					s.setWalls(walls);
				}
				if (newVoxels && s.setGrid(voxels.grid, voxels.heights, RuneLightPlugin.SCENE_OFFSET, voxels.baseX,
					voxels.baseY, voxels.instance))
				{
					jobFresh = true;
				}
				s.trace(RAY_BUDGET, worldTileX, worldTileZ);
			}
			catch (RuntimeException ex)
			{
				log.warn("Sky grid job failed", ex);
			}
		});
	}

	private void collect()
	{
		if (jobFresh)
		{
			jobFresh = false;
			ready = false;
		}
		int[] c = sky.takeChanges();
		if (c != null)
		{
			pendingMove |= c[6] != 0;
			pending = pending == null ? c : new int[]{Math.min(pending[0], c[0]), Math.min(pending[1], c[1]),
				Math.max(pending[2], c[2]), Math.max(pending[3], c[3]), Math.min(pending[4], c[4]), Math.max(pending[5], c[5])};
		}
		if (!ready && sky.clearRings() >= READY_RINGS)
		{
			ready = true;
			// shown now: upload what is traced at once
			framesSinceUpload = UPLOAD_FRAMES;
		}
	}

	private void upload()
	{
		int[] c = pending;
		pending = null;
		framesSinceUpload = 0;
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		if (texture == 0)
		{
			texture = glGenTextures();
			glBindTexture(GL_TEXTURE_3D, texture);
			glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, SkyGrid.TEX_W, SkyGrid.TEX_H, SkyGrid.TEX_D, 0, GL_RGBA, GL_HALF_FLOAT,
				(ByteBuffer) null);
			glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
		}
		else
		{
			glBindTexture(GL_TEXTURE_3D, texture);
		}
		glPixelStorei(GL_UNPACK_ROW_LENGTH, SkyGrid.TEX_W);
		glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, SkyGrid.TEX_H);
		int w = 2 * (c[2] - c[0] + 1), h = 2 * (c[3] - c[1] + 1), d = c[5] - c[4] + 1;
		for (int part = 0; part < 2; ++part)
		{
			int x = part * 2 * SkyGrid.SIZE + 2 * c[0];
			glPixelStorei(GL_UNPACK_SKIP_PIXELS, x);
			glPixelStorei(GL_UNPACK_SKIP_ROWS, 2 * c[1]);
			glPixelStorei(GL_UNPACK_SKIP_IMAGES, c[4]);
			glTexSubImage3D(GL_TEXTURE_3D, 0, x, 2 * c[1], c[4], w, h, d, GL_RGBA, GL_HALF_FLOAT, sky.texels);
		}
		glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
		glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
		glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
		glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
		glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
		glBindTexture(GL_TEXTURE_3D, 0);
		if (pendingMove)
		{
			pendingMove = false;
			shownBaseX = sky.baseX();
			shownBaseY = sky.baseY();
			shownInstance = sky.instance();
		}
	}

	/** Whether the AO pass can look the sky visibility up this frame, in the given scene. */
	boolean isReady(Scene scene)
	{
		return wanted && ready && texture != 0 && scene != null && shownInstance == scene.isInstance()
			&& (!shownInstance || scene.getBaseX() == shownBaseX && scene.getBaseY() == shownBaseY);
	}

	void free()
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
		sky = null;
		job = null;
		jobFresh = false;
		handedVoxels = null;
		handedWalls = null;
		ready = false;
		pending = null;
		pendingMove = false;
	}

	/** Look up the AO program's uniforms; the texture is sampled on {@code unit}. */
	void locateAo(int program, int unit)
	{
		uniOn = glGetUniformLocation(program, "skylightOn");
		uniCamera = glGetUniformLocation(program, "skyGridCamera");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "skyGrid"), unit);
		glUseProgram(0);
	}

	/**
	 * With the AO program in use: switch the skylight on (binding the texture on unit) when it is ready, else off.
	 * {@link #unbind} after the pass.
	 */
	boolean bindAo(int unit, Scene scene, float cameraX, float cameraY, float cameraZ)
	{
		boolean on = isReady(scene);
		glUniform1i(uniOn, on ? 1 : 0);
		if (on)
		{
			// the camera in the texture's place, which lags the scene's for a moment after a move
			glUniform3f(uniCamera, cameraX - (shownBaseX - scene.getBaseX()) * SkyGrid.TILE, cameraY,
				cameraZ - (shownBaseY - scene.getBaseY()) * SkyGrid.TILE);
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
}
