/*
 * Copyright (c) 2026, Midona
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
package com.midonarhel.lumina.shadow;

import com.midonarhel.lumina.frame.DrawUniforms;
import java.util.function.Consumer;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The frame's moving casters (players, NPCs, animated objects: the dynamic VAO ranges the frame uploaded), drawn
 * every frame into a {@link #SIZE}² depth map at the static map's texel density around the static map's centre
 * (half the radius beside a 4096² static map). Skipped when the frame has no dynamic ranges. Receivers take the darker
 * of this and the static map, so nothing of the static scenery is drawn here.
 */
public final class DynamicShadowMap
{
	public static final int SIZE = 2048;

	private final DepthTarget target = new DepthTarget();
	private final ShadowFit fit = new ShadowFit();
	private boolean drawn;
	private int draws;

	public void free()
	{
		target.free();
		drawn = false;
	}

	/** Whether this frame's map was drawn (so receivers may sample it). */
	public boolean drawn()
	{
		return drawn;
	}

	public ShadowFit fit()
	{
		return fit;
	}

	int texture()
	{
		return target.texture;
	}

	public long bytes()
	{
		return target.bytes();
	}

	/** No casters this frame: receivers don't sample the map. */
	public void skip()
	{
		drawn = false;
	}

	/**
	 * Clears the map and draws the frame's dynamic ranges with the shadow program's per-draw uniforms. Expects the
	 * pass state (depth test on with GL_LESS, clear depth 1); leaves the map's framebuffer and viewport bound.
	 *
	 * @param base        the fit to derive this map's from (the static front map's, or a fresh one)
	 * @param casters     draws the frame's dynamic ranges with the given uniforms
	 * @param onFirstDraw run right before the GL commands (the GPU timer)
	 */
	public void draw(ShadowFit base, ShadowProgram program, Consumer<DrawUniforms> casters, Runnable onFirstDraw)
	{
		if (target.texture == 0)
		{
			target.init(SIZE, "Dynamic shadow map");
		}
		fit.copyScaled(base, SIZE);
		onFirstDraw.run();
		target.bindForDraw();
		glClear(GL_DEPTH_BUFFER_BIT);
		program.use(fit.proj);
		casters.accept(program.uniforms);
		drawn = true;
		draws++;
	}

	public String describe()
	{
		return String.format("dynamic: drawn=%b draws=%d fit=[%s]", drawn, draws, fit.valid ? fit : "none");
	}
}
