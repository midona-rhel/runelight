package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The light map's textures (LightMap): one RGBA16F array, two layers per storey (the sums of colour and lead, then the
 * lights' mean place and brightest light; both filtered), cells * cells texels a layer: 736 x 736 for the largest scene, 34.7 MB all told. Zero from the start, so a
 * cell nothing was sent for is dark; blocks are sent into it as LightMap bakes them.
 */
final class LightMapTexture implements LightMap.Sink
{
	/** Layers per storey: the sums of colour, and the lights' places. */
	static final int LAYERS = 2;

	private int texture;
	private int cells;
	private final ShortBuffer staging = BufferUtils.createShortBuffer(LightMap.BLOCK * LightMap.BLOCK * 4);
	private int uploads;

	/** Bytes the texture takes on the GPU. */
	static long bytes(int cells)
	{
		return (long) cells * cells * 8 * LAYERS * WallMap.PLANES;
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
		texture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		// past the scene there is no light
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
		glTexParameterfv(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_BORDER_COLOR, new float[4]);
		int layers = LAYERS * WallMap.PLANES;
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA16F, cells, cells, layers, 0, GL_RGBA, GL_HALF_FLOAT, (ByteBuffer) null);
		// glTexImage3D leaves it undefined: zero a layer at a time
		ByteBuffer zero = BufferUtils.createByteBuffer(cells * cells * 8);
		for (int layer = 0; layer < layers; ++layer)
		{
			glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, cells, cells, 1, GL_RGBA, GL_HALF_FLOAT, zero);
		}
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
		return true;
	}

	@Override
	public void block(int plane, int bx, int bz, short[] a, short[] b)
	{
		glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
		send(plane * LAYERS, bx, bz, a);
		send(plane * LAYERS + 1, bx, bz, b);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
		uploads++;
	}

	private void send(int layer, int bx, int bz, short[] data)
	{
		staging.clear();
		if (data != null)
		{
			staging.put(data);
		}
		else
		{
			for (int i = 0; i < staging.capacity(); ++i)
			{
				staging.put((short) 0);
			}
		}
		staging.flip();
		glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, bx * LightMap.BLOCK, bz * LightMap.BLOCK, layer, LightMap.BLOCK,
			LightMap.BLOCK, 1, GL_RGBA, GL_HALF_FLOAT, staging);
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
		glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
		glActiveTexture(GL_TEXTURE0);
	}

	static void unbind(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
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
				glUniform4fv(header, roomHeader);
			}
		}
	}
}
