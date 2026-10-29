/*
 * Copyright (c) 2025, Adam <Adam@sigterm.info>
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
package com.midonarhel.lumina.frame;

import java.util.Arrays;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import static com.midonarhel.lumina.frame.SceneProgram.uniBase;
import static com.midonarhel.lumina.frame.SceneProgram.uniEntityProj;
import static com.midonarhel.lumina.frame.SceneProgram.uniEntityTint;
import static org.lwjgl.opengl.GL33C.*;

public class VAO
{
	// vert.glsl takes a vertex's corner in its face from gl_VertexID % 3: draws must start and end on a face
	static int onFace(int vertices)
	{
		assert vertices % 3 == 0 : "draw range not on a face boundary: " + vertices;
		return vertices;
	}

	// Temporary vertex format (see ModelUploader.putVertex)
	// index 0: vec3(x, y, z)
	// index 1: int ivec3(abhsl of the face's vertices 0, 1, 2), the same for all three vertices of a face
	// index 2: short ivec2(texture id + 1, flags)
	// index 4: half vec2(u, v)
	// index 3: short vec4(normal x, y, z, material slot); a zero normal marks unlit (vanilla) geometry
	public static final int VERT_SIZE = 40;

	public final VBO vbo;
	public int vao;

	VAO(int size)
	{
		vbo = new VBO(size);
	}

	void init()
	{
		vao = glGenVertexArrays();
		glBindVertexArray(vao);

		vbo.init(GL_DYNAMIC_DRAW);
		glBindBuffer(GL_ARRAY_BUFFER, vbo.bufId);

		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, VERT_SIZE, 0);

		glEnableVertexAttribArray(1);
		glVertexAttribIPointer(1, 3, GL_INT, VERT_SIZE, 12);

		glEnableVertexAttribArray(2);
		glVertexAttribIPointer(2, 2, GL_SHORT, VERT_SIZE, 24);

		glEnableVertexAttribArray(4);
		glVertexAttribPointer(4, 2, GL_HALF_FLOAT, false, VERT_SIZE, 28);

		glEnableVertexAttribArray(3);
		glVertexAttribPointer(3, 4, GL_SHORT, false, VERT_SIZE, 32);

		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
	}

	void destroy()
	{
		vbo.destroy();
		glDeleteVertexArrays(vao);
		vao = 0;
	}

	static class Range
	{
		int endpos;
		float[] projection;
		byte h, s, l, a;
		byte renderMethod;
	}

	Range[] ranges = new Range[4];
	int off;

	{
		for (int i = 0; i < ranges.length; ++i)
		{
			ranges[i] = new Range();
		}
	}

	public void addRange(float[] projection, Scene scene, int renderMode)
	{
		assert vbo.mapped;

		if (off > 0)
		{
			Range r = ranges[off - 1];
			int pos = vbo.vb.position();
			if (r.endpos == pos)
			{
				return;
			}

			if (projection == r.projection && renderMode == r.renderMethod)
			{
				assert pos > r.endpos;
				r.endpos = pos;
				return;
			}
		}

		if (ranges.length == off)
		{
			int l = ranges.length << 1;
			ranges = Arrays.copyOf(ranges, l);
			for (int i = ranges.length >> 1; i < ranges.length; ++i)
			{
				ranges[i] = new Range();
			}
		}

		Range r = ranges[off++];
		r.endpos = vbo.vb.position();
		r.projection = projection;
		r.h = scene.getOverrideHue();
		r.s = scene.getOverrideSaturation();
		r.l = scene.getOverrideLuminance();
		r.a = scene.getOverrideAmount();
		r.renderMethod = (byte) renderMode;
	}

	void draw()
	{
		assert !vbo.mapped;

		glUniform3i(uniBase, 0, 0, 0);

		int start = 0;
		for (int i = 0; i < off; ++i)
		{
			Range range = ranges[i];
			int end = range.endpos;

			int count = end - start;

			glUniformMatrix4fv(uniEntityProj, false, range.projection);
			glUniform4i(uniEntityTint, range.h, range.s, range.l, range.a);

			glBindVertexArray(vao);

			if (range.renderMethod == Renderable.RENDERMODE_SORTED_NO_DEPTH)
			{
				glDepthMask(false);
				glDrawArrays(GL_TRIANGLES, VAO.onFace(start / (VERT_SIZE / 4)), VAO.onFace(count / (VERT_SIZE / 4)));
				glDepthMask(true);

				glColorMask(false, false, false, false);
				glDrawArrays(GL_TRIANGLES, VAO.onFace(start / (VERT_SIZE / 4)), VAO.onFace(count / (VERT_SIZE / 4)));
				glColorMask(true, true, true, true);
			}
			else if (range.renderMethod == Renderable.RENDERMODE_UNSORTED_NO_DEPTH)
			{
				glDepthMask(false);
				glDrawArrays(GL_TRIANGLES, VAO.onFace(start / (VERT_SIZE / 4)), VAO.onFace(count / (VERT_SIZE / 4)));
				glDepthMask(true);
			}
			else
			{
				glDrawArrays(GL_TRIANGLES, VAO.onFace(start / (VERT_SIZE / 4)), VAO.onFace(count / (VERT_SIZE / 4)));
			}

			start = end;
		}
	}

	void reset()
	{
		for (int i = 0; i < off; ++i)
		{
			ranges[i].projection = null;
		}
		off = 0;
	}
}

