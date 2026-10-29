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
package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.frame.Mat4;
import java.util.Set;
import net.runelite.api.Constants;

/**
 * One loaded scene's zones (the top-level scene's or a world entity's), its shading data and the camera and level
 * range the client passed for the frame being drawn.
 */
public final class SceneContext
{
	/**
	 * Offset of the unextended scene inside the extended one, in tiles (sxy to msxy).
	 */
	public static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
	public static final int NUM_ZONES = Constants.EXTENDED_SCENE_SIZE >> 3;

	public final float[] projection = Mat4.identity();

	public final int sizeX, sizeZ;
	public Zone[][] zones;
	// shading data (tile overrides, terrain normals and colours) of the scene the zones belong to
	public SceneData data;

	public int cameraX, cameraY, cameraZ;
	public int minLevel, level, maxLevel;
	public Set<Integer> hideRoofIds;

	SceneContext(int sizeX, int sizeZ)
	{
		this.sizeX = sizeX;
		this.sizeZ = sizeZ;
		zones = new Zone[sizeX][sizeZ];
		for (int x = 0; x < sizeX; ++x)
		{
			for (int z = 0; z < sizeZ; ++z)
			{
				zones[x][z] = new Zone();
			}
		}
	}

	void free()
	{
		for (int x = 0; x < sizeX; ++x)
		{
			for (int z = 0; z < sizeZ; ++z)
			{
				zones[x][z].free();
			}
		}
	}
}
