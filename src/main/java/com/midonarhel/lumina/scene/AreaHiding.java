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
package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.scene.areas.Area;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;

/**
 * 117 HD's area hiding at draw time: the current area is the first possible area containing the local player
 * (updateAreaHiding); zones and dynamic or temporary models outside it are not drawn. The upload side (nothing
 * outside the current area is uploaded) is in {@link SceneUploader}, the area selection in {@link SceneData}.
 */
@Slf4j
public final class AreaHiding
{
	private final Client client;
	private final SceneLoader scenes;

	private boolean justChangedArea;

	public AreaHiding(Client client, SceneLoader scenes)
	{
		this.client = client;
		this.scenes = scenes;
	}

	/**
	 * 117 HD's updateAreaHiding: the current area is the first possible area containing the local player; when it
	 * changes, every zone is rebuilt.
	 */
	public void update()
	{
		SceneContext root = scenes.root();
		SceneData data = root.data;
		Player localPlayer = client.getLocalPlayer();
		if (data == null || localPlayer == null || !data.enableAreaHiding || data.sceneBase == null)
		{
			justChangedArea = false;
			return;
		}

		var lp = localPlayer.getLocalLocation();
		int[] worldPos = {
			data.sceneBase[0] + lp.getSceneX(),
			data.sceneBase[1] + lp.getSceneY(),
			data.sceneBase[2] + client.getTopLevelWorldView().getPlane()
		};

		// Check all areas contained in the scene in the order they appear in the list, so lower floors take
		// precedence over higher floors which include tiny portions of the floor beneath around stairs and ladders
		Area newArea = null;
		for (var area : data.possibleAreas)
		{
			if (area.containsPoint(false, worldPos))
			{
				newArea = area;
				break;
			}
		}

		if (newArea != data.currentArea)
		{
			if (justChangedArea)
			{
				// Disable area hiding if it somehow gets stuck in a loop switching areas
				data.enableAreaHiding = false;
				log.error("Disabling area hiding after moving from {} to {} at {}", data.currentArea, newArea, worldPos);
				newArea = null;
			}
			else
			{
				justChangedArea = true;
				for (Zone[] column : root.zones)
				{
					for (Zone zone : column)
					{
						zone.invalidate = true;
					}
				}
			}
			data.currentArea = newArea;
		}
		else
		{
			justChangedArea = false;
		}
	}

	/**
	 * 117 HD's zoneInFrustum area check: zones outside the current area are not drawn.
	 */
	public boolean isZoneHidden(SceneContext ctx, int zx, int zz)
	{
		if (ctx != scenes.root() || ctx.data == null)
		{
			return false;
		}
		Area currentArea = ctx.data.currentArea;
		if (currentArea == null || ctx.data.sceneBase == null)
		{
			return false;
		}
		int[] base = ctx.data.sceneBase;
		int minX = zx * 8 - ctx.data.sceneOffset;
		int minZ = zz * 8 - ctx.data.sceneOffset;
		return !currentArea.intersects(true, base[0] + minX, base[1] + minZ, base[0] + minX + 7, base[1] + minZ + 7);
	}

	/**
	 * 117 HD's area check for dynamic and temporary models of the top-level scene.
	 */
	public boolean isOutsideArea(SceneContext ctx, int x, int z, int plane)
	{
		if (ctx != scenes.root() || ctx.data == null)
		{
			return false;
		}
		Area currentArea = ctx.data.currentArea;
		if (currentArea == null || ctx.data.sceneBase == null)
		{
			return false;
		}
		int[] base = ctx.data.sceneBase;
		return !currentArea.containsPoint(base[0] + (x >> Perspective.LOCAL_COORD_BITS), base[1] + (z >> Perspective.LOCAL_COORD_BITS), base[2] + plane);
	}
}
