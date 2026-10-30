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

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * A render thread's growing list of temporary-model {@link VAO}s: models are appended to the first VAO with room,
 * the VAOs are allocated on the client thread (a render thread only asks for one) and drawn in order.
 */
@Slf4j
public class VAOList
{
	// this needs to be larger than the largest single model
	private static final int VAO_SIZE = 4 * 1024 * 1024;

	private final boolean rt;
	private boolean needAlloc;

	private int curIdx;
	// VAOs unmapped by the last unmap(): the ones draw() draws and reset() resets
	private int unmapped;
	final List<VAO> vaos = new ArrayList<>();

	VAOList(boolean rt)
	{
		this.rt = rt;
	}

	public VAO get(int size)
	{
		assert size <= VAO_SIZE;

		while (curIdx < vaos.size())
		{
			VAO vao = vaos.get(curIdx);
			if (!vao.vbo.mapped)
			{
				if (rt)
				{
					needAlloc = true;
					return null;
				}
				vao.vbo.map();
			}

			int rem = vao.vbo.vb.remaining() * Integer.BYTES;
			if (size <= rem)
			{
				return vao;
			}

			curIdx++;
		}

		if (rt)
		{
			needAlloc = true;
			return null;
		}

		VAO vao = new VAO(VAO_SIZE);
		vao.init();
		vao.vbo.map();
		vaos.add(vao);
		log.debug("Allocated VAO {} request {}", vao.vao, size);
		return vao;
	}

	void map()
	{
		for (VAO vao : vaos)
		{
			assert !vao.vbo.mapped;
			vao.vbo.map();
		}
		if (needAlloc)
		{
			VAO vao = new VAO(VAO_SIZE);
			vao.init();
			vao.vbo.map();
			vaos.add(vao);
			log.debug("Allocated VAO {}", vao.vao);
			needAlloc = false;
		}
	}

	int unmap()
	{
		int sz = 0;
		for (int i = 0; i < vaos.size(); ++i) // NOPMD: ForLoopCanBeForeach
		{
			VAO vao = vaos.get(i);
			if (vao.vbo.mapped)
			{
				++sz;
				vao.vbo.unmap();
			}
		}
		curIdx = 0;
		unmapped = sz;
		return sz;
	}

	void free()
	{
		for (VAO vao : vaos)
		{
			vao.destroy();
		}
		vaos.clear();
		curIdx = 0;
	}

	/**
	 * Draws the ranges of the VAOs the last {@link #unmap} unmapped, with {@code u}'s uniforms. May be called more
	 * than once (a shadow pass and the scene pass draw the same ranges); {@link #reset} ends the frame.
	 */
	void draw(DrawUniforms u)
	{
		for (int i = 0; i < unmapped; ++i)
		{
			vaos.get(i).draw(u);
		}
	}

	/** Forgets the frame's ranges after the last draw of them. */
	void reset()
	{
		for (int i = 0; i < unmapped; ++i)
		{
			vaos.get(i).reset();
		}
		unmapped = 0;
	}

	int size()
	{
		int szKb = 0;
		for (int i = 0; i < vaos.size(); ++i) // NOPMD: ForLoopCanBeForeach
		{
			VAO vao = vaos.get(i);
			szKb += vao.vbo.size >> 10;
		}
		return szKb;
	}
}
