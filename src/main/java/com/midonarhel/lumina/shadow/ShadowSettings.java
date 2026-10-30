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

import com.midonarhel.lumina.config.LuminaConfig;
import net.runelite.client.config.ConfigManager;

/**
 * The shadow settings, cached like {@code config.SkySettings}: updated by the plugin at start and on every config
 * change, read per frame by {@link ShadowRenderer}. Read by key from the config store ({@link #KEY_ENABLED},
 * {@link #KEY_MAP_SIZE} in the {@code lumina} group) with the defaults below when unset, so they work before the
 * matching {@code @ConfigItem}s exist in {@code LuminaConfig}. Shadows only ever draw under Lumina's lighting source;
 * 117 HD's is untouched by them.
 */
public final class ShadowSettings
{
	public static final String KEY_ENABLED = "shadows";
	public static final String KEY_MAP_SIZE = "shadowMapSize";
	public static final int DEFAULT_MAP_SIZE = 2048;

	public static volatile boolean enabled = true;
	/** The static map's side in texels: 2048 or 4096. */
	public static volatile int mapSize = DEFAULT_MAP_SIZE;

	private ShadowSettings()
	{
	}

	public static void update(ConfigManager configManager)
	{
		String on = configManager.getConfiguration(LuminaConfig.GROUP, KEY_ENABLED);
		enabled = on == null || Boolean.parseBoolean(on);
		String size = configManager.getConfiguration(LuminaConfig.GROUP, KEY_MAP_SIZE);
		int n = DEFAULT_MAP_SIZE;
		if (size != null)
		{
			try
			{
				n = Integer.parseInt(size.replaceAll("[^0-9]", ""));
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		mapSize = n >= 4096 ? 4096 : 2048;
	}
}
