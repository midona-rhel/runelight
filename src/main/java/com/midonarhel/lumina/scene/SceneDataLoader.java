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
package com.midonarhel.lumina.scene;

import com.google.gson.Gson;
import com.midonarhel.lumina.scene.areas.AreaManager;
import com.midonarhel.lumina.scene.environment.EnvironmentManager;
import com.midonarhel.lumina.scene.materials.MaterialManager;
import com.midonarhel.lumina.scene.overrides.ModelOverrideManager;
import com.midonarhel.lumina.scene.overrides.TileOverrideManager;
import com.midonarhel.lumina.scene.sky.SkyManager;
import com.midonarhel.lumina.scene.terrain.TerrainGenerator;
import com.midonarhel.lumina.util.Gamevals;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;

/**
 * Entry point of the ported 117 HD scene data: loads the data files (areas first, since the others refer to areas by
 * name) and builds the {@link SceneData} of each loaded scene.
 */
@Slf4j
@Singleton
public class SceneDataLoader
{
	private final Gson gson;
	private final TileOverrideManager tileOverrideManager;
	private final ModelOverrideManager modelOverrideManager;
	private final EnvironmentManager environmentManager;
	private final SkyManager skyManager;

	@Inject
	SceneDataLoader(Gson gson, TileOverrideManager tileOverrideManager, ModelOverrideManager modelOverrideManager, EnvironmentManager environmentManager,
		SkyManager skyManager)
	{
		this.skyManager = skyManager;
		this.gson = gson;
		this.tileOverrideManager = tileOverrideManager;
		this.modelOverrideManager = modelOverrideManager;
		this.environmentManager = environmentManager;
	}

	/**
	 * Loads areas, tile overrides, model overrides, sky presets and environments. On failure the remaining data stays empty, so
	 * scenes still load (without overrides).
	 */
	public void load()
	{
		long start = System.nanoTime();
		try
		{
			AreaManager.load(gson);
			tileOverrideManager.load(gson);
			// materials before model overrides, which refer to them
			MaterialManager.load(gson);
			modelOverrideManager.load(gson);
			// sky presets before environments, whose skies refer to them
			skyManager.load(gson);
			environmentManager.load(gson);
		}
		catch (Exception ex)
		{
			log.error("Failed to load the scene data files", ex);
		}
		finally
		{
			Gamevals.clear();
		}
		log.debug("Scene data loaded in {} ms", (System.nanoTime() - start) / 1_000_000);
	}

	/**
	 * Builds the shading data of a scene: tile overrides, terrain normals and colours, and for the top-level scene
	 * the environments that may apply. Call on the map-load thread before the scene's zones are sized and uploaded.
	 */
	public SceneData createSceneData(WorldView worldView, Scene scene, int expandedMapLoadingChunks)
	{
		SceneData data = new SceneData(worldView, scene, expandedMapLoadingChunks);
		new TerrainGenerator(tileOverrideManager).generate(data);
		if (data.topLevel)
		{
			environmentManager.loadSceneEnvironments(data);
		}
		return data;
	}
}
