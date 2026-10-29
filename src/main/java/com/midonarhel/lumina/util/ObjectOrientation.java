/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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
package com.midonarhel.lumina.util;

import javax.annotation.Nullable;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;

/**
 * 117 HD's model pre-orientations (HDUtils.getModelPreOrientation, convertWallObjectOrientation, getObjectConfig and
 * ObjectType), which its model-space UV types undo.
 */
public final class ObjectOrientation
{
	// rs117.hd.data.ObjectType ids
	private static final int WALL_DIAGONAL_CORNER = 1;
	private static final int WALL_SQUARE_CORNER = 3;
	private static final int WALL_DECOR_DIAGONAL_OFFSET = 6;
	private static final int WALL_DECOR_DIAGONAL_BOTH = 8;
	private static final int OBJECT_TYPE_COUNT = 23;

	private ObjectOrientation()
	{
	}

	public static int convertWallObjectOrientation(int orientation)
	{
		switch (orientation)
		{
			case 1:
				return 512; // west
			case 2:
				return 1024; // north
			case 4:
				return 1536; // east
			case 8:
			default:
				return 0; // south
			case 16:
				return 768; // north-west
			case 32:
				return 1280; // north-east
			case 64:
				return 1792; // south-east
			case 128:
				return 256; // south-west
		}
	}

	public static int getObjectConfig(@Nullable TileObject tileObject)
	{
		if (tileObject instanceof WallObject)
		{
			return ((WallObject) tileObject).getConfig();
		}
		if (tileObject instanceof DecorativeObject)
		{
			return ((DecorativeObject) tileObject).getConfig();
		}
		if (tileObject instanceof GroundObject)
		{
			return ((GroundObject) tileObject).getConfig();
		}
		if (tileObject instanceof GameObject)
		{
			return ((GameObject) tileObject).getConfig();
		}
		return -1;
	}

	/**
	 * The orientation used when uploading the model, without the extra 45-degree rotation of diagonal models.
	 */
	public static int getModelPreOrientation(int config)
	{
		int type = config & 0x3F;
		if (type >= OBJECT_TYPE_COUNT)
		{
			type = -1; // Unknown
		}
		int orientation = 1024 + 512 * (config >>> 6 & 3);
		switch (type)
		{
			case WALL_DIAGONAL_CORNER:
			case WALL_SQUARE_CORNER:
			case WALL_DECOR_DIAGONAL_OFFSET:
			case WALL_DECOR_DIAGONAL_BOTH:
				orientation += 1024;
		}
		return orientation % 2048;
	}
}
