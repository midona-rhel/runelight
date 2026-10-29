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
package com.midonarhel.lumina.config;

/**
 * The "Sky" section's settings, cached like {@link HdSettings} caches 117 HD's: updated by the plugin at start and on
 * every config change, read per frame by {@code frame.FrameRenderer} (the lighting source) and {@code sky.SkySource}.
 */
public final class SkySettings
{
	public static volatile LightingSourceKind lightingSource = LightingSourceKind.LUMINA;
	public static volatile DayLength dayLength = DayLength.MINUTES_24;
	public static volatile int fixedHour = 12;
	public static volatile boolean showSunMoon = true;

	private SkySettings()
	{
	}

	public static void update(LuminaConfig config)
	{
		lightingSource = config.lightingSource();
		dayLength = config.dayLength();
		fixedHour = Math.max(0, Math.min(24, config.fixedHour()));
		showSunMoon = config.showSunMoon();
	}
}
