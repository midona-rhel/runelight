/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

import com.midonarhel.lumina.util.MathUtils;
import java.nio.IntBuffer;

/**
 * Writes zone vertices into a mapped zone buffer, in the zone vertex format {@code Zone} describes.
 */
public class GpuIntBuffer
{
	private final IntBuffer buffer;

	public GpuIntBuffer(IntBuffer ib)
	{
		buffer = ib;
	}

	/**
	 * Writes one vertex in the zone vertex format (see {@code Zone.VERT_SIZE}).
	 *
	 * @param abhsl0   the alpha, bias and colour of the face's first vertex; abhsl1 and abhsl2 those of the second and
	 *                 third. All three vertices of a face carry the same three values, in the order the face's vertices
	 *                 are written, so the shader can blend between them per fragment (117 HD's face buffer).
	 * @param tex      the vanilla texture id + 1 of the face, 0 if untextured
	 * @param u        texture coordinates, stored as half floats like 117 HD's
	 * @param flags    bits 0-2: whether the face's vertex 0/1/2 is an overlay vertex at an overlay-underlay border
	 *                 (117 HD's MATERIAL_FLAG_IS_OVERLAY), bit 3: upwards normals, bit 4: vanilla UVs
	 * @param material the face material's slot in the shader material table
	 */
	public void putZoneVertex(int x, int y, int z, int abhsl0, int abhsl1, int abhsl2, int tex, float u, float v, int flags,
		int nx, int ny, int nz, int material)
	{
		buffer.put(((y & 0xffff) << 16) | (x & 0xffff));
		buffer.put(z & 0xffff);
		buffer.put(abhsl0);
		buffer.put(abhsl1);
		buffer.put(abhsl2);
		buffer.put(((flags & 0xffff) << 16) | (tex & 0xffff));
		buffer.put(MathUtils.float16(v) << 16 | MathUtils.float16(u));
		buffer.put(((ny & 0xffff) << 16) | (nx & 0xffff));
		buffer.put(((material & 0xffff) << 16) | (nz & 0xffff));
	}
}
