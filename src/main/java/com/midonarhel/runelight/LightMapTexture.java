package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The light map's texture (LightMap): one RGBA8 3D texture, cells * cells texels in each of its slices: LAYERS of them
 * for each storey's visibility (a channel per slot; the height layers, filtered across), then INDEX_SLICES for each
 * storey's index of the lights in the slots (read as it is): 736 x 736 for the largest scene, 52 MB all told. Zero from
 * the start, so a cell nothing was sent for is dark; blocks are sent into it as LightMap bakes them.
 */
final class LightMapTexture
{
	private static final Logger log = LoggerFactory.getLogger(LightMapTexture.class);

	/** Slices of visibility for a storey: the height layers. */
	static final int LAYERS = LightMapBake.HEIGHT_LAYERS;
	/** Slices in all: the visibility of each layer of each storey, then each storey's index. */
	static final int SLICES = (LAYERS + LightMap.INDEX_SLICES) * WallMap.PLANES;

	private int texture;
	private int cells;
	private final ByteBuffer staging = BufferUtils.createByteBuffer(LAYERS * LightMap.BLOCK * LightMap.BLOCK * 4);
	private int uploads;
	private int generation;

	/** Bytes the texture takes on the GPU. */
	static long bytes(int cells)
	{
		return (long) cells * cells * 4 * SLICES;
	}

	/** Make the texture the size the scene needs; it starts empty. False when there is no scene. */
	boolean ensure(int cells)
	{
		if (cells <= 0)
		{
			return false;
		}
		if (texture != 0 && this.cells == cells)
		{
			return true;
		}
		free();
		this.cells = cells;
		log.info("Light map texture: {} x {} cells, {} slices of RGBA8, {} MB", cells, cells, SLICES, Math.round(bytes(cells) / 1e6));
		texture = glGenTextures();
		++generation;
		glBindTexture(GL_TEXTURE_3D, texture);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		// past the scene there is no light
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
		glTexParameterfv(GL_TEXTURE_3D, GL_TEXTURE_BORDER_COLOR, new float[4]);
		glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA8, cells, cells, SLICES, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
		// glTexImage3D leaves it undefined: zero a slice at a time
		ByteBuffer zero = BufferUtils.createByteBuffer(cells * cells * 4);
		for (int slice = 0; slice < SLICES; ++slice)
		{
			glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, slice, cells, cells, 1, GL_RGBA, GL_UNSIGNED_BYTE, zero);
		}
		glBindTexture(GL_TEXTURE_3D, 0);
		return true;
	}

	/** A block of the light map is sent (LightMap.Sink): null clears it. */
	void block(int plane, int bx, int bz, byte[] vis, byte[] index)
	{
		glBindTexture(GL_TEXTURE_3D, texture);
		send(plane * LAYERS, LAYERS, bx, bz, vis);
		send(LAYERS * WallMap.PLANES + plane * LightMap.INDEX_SLICES, LightMap.INDEX_SLICES, bx, bz, index);
		glBindTexture(GL_TEXTURE_3D, 0);
		uploads++;
	}

	private void send(int slice, int slices, int bx, int bz, byte[] data)
	{
		int bytes = slices * LightMap.BLOCK * LightMap.BLOCK * 4;
		staging.clear();
		if (data != null)
		{
			staging.put(data);
		}
		else
		{
			for (int i = 0; i < bytes; ++i)
			{
				staging.put((byte) 0);
			}
		}
		staging.flip();
		glTexSubImage3D(GL_TEXTURE_3D, 0, bx * LightMap.BLOCK, bz * LightMap.BLOCK, slice, LightMap.BLOCK,
			LightMap.BLOCK, slices, GL_RGBA, GL_UNSIGNED_BYTE, staging);
	}

	/** Changes whenever the texture is made, empty. */
	int generation()
	{
		return generation;
	}

	/** Blocks sent so far. */
	int uploads()
	{
		return uploads;
	}

	boolean isReady()
	{
		return texture != 0;
	}

	void bind(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_3D, texture);
		glActiveTexture(GL_TEXTURE0);
	}

	static void unbind(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_3D, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	void free()
	{
		if (texture != 0)
		{
			glDeleteTextures(texture);
			texture = 0;
		}
		cells = 0;
	}

	/**
	 * What a program that reads the light map needs: the sampler, and the room map's header, which says whether the map is
	 * there and how to find a cell (light_map.glsl).
	 */
	static final class Uniforms
	{
		private int header = -1;

		void locate(int program, int unit)
		{
			header = glGetUniformLocation(program, "roomHeader");
			int sampler = glGetUniformLocation(program, "lightMap");
			if (sampler >= 0)
			{
				glUseProgram(program);
				glUniform1i(sampler, unit);
			}
		}

		/** @param roomHeader LightClusters.getRoomHeader */
		void apply(float[] roomHeader)
		{
			if (header >= 0)
			{
				glUniform3fv(header, roomHeader);
			}
		}
	}
}
