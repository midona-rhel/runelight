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

import lombok.extern.slf4j.Slf4j;

/**
 * The render threads' state ({@link RenderThread}), one entry per client render thread plus one for the client
 * thread at index 0. The VAO lists are created on the client thread once the GL context exists and recreated when
 * the thread count setting changes.
 */
@Slf4j
public final class RenderThreads
{
	private RenderThread[] rts;

	public RenderThreads(int numThreads)
	{
		rts = new RenderThread[numThreads + 1];
		for (int i = 0; i < rts.length; ++i)
		{
			rts[i] = new RenderThread();
		}
	}

	public RenderThread get(int i)
	{
		return rts[i];
	}

	public int count()
	{
		return rts.length;
	}

	void initVaoLists()
	{
		for (int i = 0; i < rts.length; ++i)
		{
			rts[i].vaoO = new VAOList(i > 0);
			rts[i].vaoA = new VAOList(i > 0);
		}
	}

	void freeVaoLists()
	{
		for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
		{
			if (rts[i].vaoO != null)
			{
				rts[i].vaoO.free();
				rts[i].vaoO = null;
			}
			if (rts[i].vaoA != null)
			{
				rts[i].vaoA.free();
				rts[i].vaoA = null;
			}
		}
	}

	/**
	 * The thread count setting changed: frees the VAO lists and starts over with new threads. Client thread.
	 */
	public void reallocate(int numThreads)
	{
		for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
		{
			rts[i].vaoO.free();
			rts[i].vaoA.free();
		}

		rts = new RenderThread[numThreads + 1];
		for (int i = 0; i < rts.length; ++i)
		{
			var rt = new RenderThread();
			rt.vaoO = new VAOList(i > 0);
			rt.vaoA = new VAOList(i > 0);
			rts[i] = rt;
		}
	}

	void map()
	{
		for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
		{
			rts[i].vaoO.map();
			rts[i].vaoA.map();
		}
	}

	/** Unmaps every thread's opaque and alpha VAOs: the frame's uploads are over, the draws can start. */
	void unmap()
	{
		for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
		{
			rts[i].vaoO.unmap();
			rts[i].vaoA.unmap();
		}
	}

	/** Draws every thread's opaque ranges (after {@link #unmap}); repeatable until {@link #resetOpaque}. */
	void drawOpaque(DrawUniforms u)
	{
		for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
		{
			rts[i].vaoO.draw(u);
		}
	}

	void resetOpaque()
	{
		for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
		{
			rts[i].vaoO.reset();
		}
	}

	/**
	 * ::gpumem
	 */
	public void logMemory()
	{
		int totalSzKb = 0;
		for (int i = 0; i < rts.length; ++i)
		{
			RenderThread rt = rts[i];
			int szKb = rt.vaoO.size() + rt.vaoA.size();
			totalSzKb += szKb;
			log.info("RenderThread{}: {}kb", i, szKb);
		}
		log.info("Total: {}kb", totalSzKb);
	}
}
