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
package com.midonarhel.lumina.scene.overrides;

import com.midonarhel.lumina.scene.terrain.TerrainVertices;
import com.midonarhel.lumina.util.VariableSupplier;
import net.runelite.api.Tile;

/**
 * The variables tile override replacement expressions test: {@code h}, {@code s}, {@code l} of the tile's
 * south-western-most lit colour (117 HD feeds these lit colours, and the replacements are written against them).
 */
public class TileOverrideVariables implements VariableSupplier
{
	private final int[] hsl = new int[3];

	private Tile tile;
	private boolean requiresHslUpdate;

	public void setTile(Tile tile)
	{
		if (tile == this.tile)
		{
			return;
		}
		this.tile = tile;
		requiresHslUpdate = true;
	}

	@Override
	public Object get(String name)
	{
		int i;
		switch (name)
		{
			case "h":
				i = 0;
				break;
			case "s":
				i = 1;
				break;
			case "l":
				i = 2;
				break;
			default:
				throw new IllegalArgumentException("Undefined variable '" + name + "'");
		}

		if (requiresHslUpdate)
		{
			int color = TerrainVertices.southWesternMostTileColor(tile);
			hsl[0] = color >> 10 & 0x3F;
			hsl[1] = color >> 7 & 0x7;
			hsl[2] = color & 0x7F;
			requiresHslUpdate = false;
		}
		return hsl[i];
	}
}
