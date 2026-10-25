package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The baked point lights (LightVolume) as a 3D texture for the shaders (light_volume.glsl), and the lights that are
 * not in it. A few baked blocks are uploaded each frame, the nearest first, each with the epoch the block holds,
 * so that the pixels in it leave the lights it holds out of the loop over the clusters.
 *
 * The texture is RGBA16F, the window's cells (LightVolume.DIM_X, DIM_Z across; the cells of LightBake's a over its b
 * down DIM_Y each), so 16 bytes a cell. It repeats across and in depth, so a block baked for one place in the window
 * is in the same texels when the window has moved.
 */
final class LightVolumeMap
{
	// baked blocks sent to the GPU a frame: each is about 90 KB in two pieces
	static final int UPLOADS_PER_FRAME = 4;
	private static final int ZERO_LAYERS = 8;

	private final LightVolume volume = new LightVolume();
	private int texture;
	private boolean active;
	private final FloatBuffer epochs = BufferUtils.createFloatBuffer(256);
	private final FloatBuffer flicker = BufferUtils.createFloatBuffer(LightBake.SLOTS);
	private final ShortBuffer staging = BufferUtils.createShortBuffer(LightVolume.REGION * LightVolume.REGION
		* LightVolume.REGION * 4);
	private ShortBuffer zero;

	LightVolume getVolume()
	{
		return volume;
	}

	/** Whether there is a volume to look up this frame. */
	boolean isActive()
	{
		return active;
	}

	/**
	 * The uniforms of one program that reads the volume (light_volume.glsl), and its sampler's unit.
	 */
	static final class Uniforms
	{
		private int on, origin, epochs, flicker;

		void locate(int program, int unit)
		{
			on = glGetUniformLocation(program, "lightVolumeOn");
			origin = glGetUniformLocation(program, "lightVolumeOrigin");
			epochs = glGetUniformLocation(program, "lightBlockEpoch");
			flicker = glGetUniformLocation(program, "lightFlicker");
			glUseProgram(program);
			glUniform1i(glGetUniformLocation(program, "lightVolume"), unit);
			glUseProgram(0);
		}
	}

	/** With the program in use: say whether there is a volume, and where. */
	void apply(Uniforms u)
	{
		glUniform1i(u.on, active ? 1 : 0);
		if (active)
		{
			glUniform3f(u.origin, volume.originCellX(), volume.originCellY(), volume.originCellZ());
			glUniform4fv(u.epochs, epochs);
			glUniform4fv(u.flicker, flicker);
		}
	}

	/**
	 * Once a frame, on the client thread with the GL context: take in the lights, bake and upload.
	 *
	 * @param wanted whether the baked lights are on; if not, the volume is dropped
	 * @param voxels the newest voxels, null before the first build
	 * @param focalX, focalY, focalZ where the window is centred, in the scene's local units
	 * @return the epoch each light entered the volume at, in PointLight.bakeEpoch, is set, and the lights it holds
	 * entirely, and those only it was to bake, are taken out of the list
	 */
	void update(boolean wanted, List<PointLight> lights, SceneVoxels voxels, int baseX, int baseY, boolean instance,
		float focalX, float focalY, float focalZ)
	{
		if (!wanted)
		{
			release();
			lights.removeIf(l -> l.bakeOnly);
			return;
		}
		volume.update(lights, voxels, baseX, baseY, instance, focalX, focalY, focalZ);
		if (volume.isPlaced() && voxels != null)
		{
			if (texture == 0)
			{
				texture = create();
			}
			upload(volume.pollFinished(UPLOADS_PER_FRAME));
		}
		active = texture != 0 && volume.isPlaced();
		if (active)
		{
			int[] blocks = volume.blockEpochs();
			epochs.clear();
			for (int i = 0; i < 256; ++i)
			{
				epochs.put(i < blocks.length ? blocks[i] : 0);
			}
			epochs.flip();
			flicker.clear();
			flicker.put(volume.modulation(), 0, LightBake.SLOTS);
			flicker.flip();
		}
		for (int i = 0; i < lights.size(); ++i)
		{
			lights.get(i).bakeEpoch = volume.epochOf(lights.get(i));
		}
		lights.removeIf(l -> l.bakeOnly || volume.resident(l));
	}

	/** Bind the texture on a unit for the programs that read it. */
	void bind(int unit)
	{
		if (active)
		{
			glActiveTexture(GL_TEXTURE0 + unit);
			glBindTexture(GL_TEXTURE_3D, texture);
			glActiveTexture(GL_TEXTURE0);
		}
	}

	static void unbind(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_3D, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	/** Drop the texture and everything baked: the lights are the clusters' again. */
	private void release()
	{
		active = false;
		volume.free();
		if (texture != 0)
		{
			glDeleteTextures(texture);
			texture = 0;
		}
	}

	void free()
	{
		release();
	}

	private int create()
	{
		int t = glGenTextures();
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_3D, t);
		glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, LightVolume.DIM_X, 2 * LightVolume.DIM_Y, LightVolume.DIM_Z, 0, GL_RGBA,
			GL_HALF_FLOAT, (ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_REPEAT);
		// what a new texture holds is not defined: it is zero, so the filtering at the edge of what is baked reads none
		ShortBuffer layers = ByteBuffer.allocateDirect(LightVolume.DIM_X * 2 * LightVolume.DIM_Y * ZERO_LAYERS * 4
			* Short.BYTES).order(ByteOrder.nativeOrder()).asShortBuffer();
		for (int z = 0; z < LightVolume.DIM_Z; z += ZERO_LAYERS)
		{
			glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, z, LightVolume.DIM_X, 2 * LightVolume.DIM_Y, ZERO_LAYERS, GL_RGBA,
				GL_HALF_FLOAT, layers);
		}
		glBindTexture(GL_TEXTURE_3D, 0);
		return t;
	}

	private void upload(List<LightVolume.Result> results)
	{
		if (results.isEmpty())
		{
			return;
		}
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_3D, texture);
		glPixelStorei(GL_UNPACK_ROW_LENGTH, LightVolume.REGION);
		glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, LightVolume.REGION);
		for (LightVolume.Result r : results)
		{
			int x0 = r.bx * LightVolume.BLOCK - LightVolume.APRON, y0 = r.by * LightVolume.BLOCK - LightVolume.APRON,
				z0 = r.bz * LightVolume.BLOCK - LightVolume.APRON;
			for (int part = 0; part < 2; ++part)
			{
				short[] cells = part == 0 ? r.a : r.b;
				ShortBuffer source;
				if (cells == null)
				{
					source = zeros();
				}
				else
				{
					staging.clear();
					staging.put(cells).flip();
					source = staging;
				}
				uploadRegion(source, part * LightVolume.DIM_Y, x0, y0, z0);
			}
			volume.committed(r);
		}
		glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
		glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
		glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
		glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
		glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
		glBindTexture(GL_TEXTURE_3D, 0);
	}

	private ShortBuffer zeros()
	{
		if (zero == null)
		{
			zero = ByteBuffer.allocateDirect(staging.capacity() * Short.BYTES).order(ByteOrder.nativeOrder()).asShortBuffer();
		}
		return zero;
	}

	/**
	 * Send the cells of a region (REGION^3 from cell (gx, gy, gz)) that lie in the window to one half of the texture,
	 * in pieces where they cross the edge across which it repeats.
	 */
	private void uploadRegion(ShortBuffer source, int rowOffset, int gx, int gy, int gz)
	{
		int ox = volume.originCellX(), oy = volume.originCellY(), oz = volume.originCellZ();
		int x0 = Math.max(gx, ox), x1 = Math.min(gx + LightVolume.REGION, ox + LightVolume.DIM_X);
		int y0 = Math.max(gy, oy), y1 = Math.min(gy + LightVolume.REGION, oy + LightVolume.DIM_Y);
		int z0 = Math.max(gz, oz), z1 = Math.min(gz + LightVolume.REGION, oz + LightVolume.DIM_Z);
		if (x0 >= x1 || y0 >= y1 || z0 >= z1)
		{
			return;
		}
		for (int z = z0; z < z1; )
		{
			int tz = Math.floorMod(z, LightVolume.DIM_Z);
			int d = Math.min(z1 - z, LightVolume.DIM_Z - tz);
			for (int x = x0; x < x1; )
			{
				int tx = Math.floorMod(x, LightVolume.DIM_X);
				int w = Math.min(x1 - x, LightVolume.DIM_X - tx);
				glPixelStorei(GL_UNPACK_SKIP_PIXELS, x - gx);
				glPixelStorei(GL_UNPACK_SKIP_ROWS, y0 - gy);
				glPixelStorei(GL_UNPACK_SKIP_IMAGES, z - gz);
				glTexSubImage3D(GL_TEXTURE_3D, 0, tx, rowOffset + y0 - oy, tz, w, y1 - y0, d, GL_RGBA, GL_HALF_FLOAT, source);
				x += w;
			}
			z += d;
		}
	}
}
