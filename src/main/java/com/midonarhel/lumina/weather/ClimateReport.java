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
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THE
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.weather;

import com.google.gson.Gson;
import com.midonarhel.lumina.sky.GameClock;
import java.io.IOException;

/**
 * The text of {@code ::lumina climate}: the climate under a tile and the forecast of the current and the next slots.
 * Loads the weather data on first use (the areas must already be loaded, which the plugin does at startup). Only
 * the developer command uses this; the render path will get its own owner of the same tables.
 */
public final class ClimateReport
{
	private static final int SLOTS_AHEAD = 6;

	private static ClimateReport loaded;

	private final ClimateMap map;
	private final Climate.Table climates;
	private final Forecast forecast;

	private ClimateReport(ClimateMap map, Climate.Table climates, Forecast forecast)
	{
		this.map = map;
		this.climates = climates;
		this.forecast = forecast;
	}

	/**
	 * @param dayMinutes the day length setting ({@code SkySettings.dayLength.minutes}), for the slot of the moment
	 */
	public static synchronized String describe(int x, int y, int plane, long unixMillis, int dayMinutes)
	{
		if (loaded == null)
		{
			try
			{
				Gson gson = new Gson();
				loaded = new ClimateReport(ClimateMap.load(gson), Climate.Table.load(gson), new Forecast(Pattern.Table.load(gson)));
			}
			catch (IOException ex)
			{
				return "Climate: the weather data failed to load: " + ex.getMessage();
			}
		}
		return loaded.report(x, y, plane, GameClock.gameMillis(unixMillis, dayMinutes));
	}

	String report(int x, int y, int plane, long gameMillis)
	{
		StringBuilder sb = new StringBuilder("Climate at (").append(x).append(", ").append(y).append(", ").append(plane).append("): ");
		String id = map.climateAt(x, y, plane);
		sb.append(id).append(" [environment ").append(map.environmentAt(x, y, plane)).append("]");
		Climate climate = climates.get(id);
		if (climate == null)
		{
			return sb.append(ClimateMap.NO_SKY.equals(id) ? ", no sky, no weather" : ", not in climates.json").toString();
		}
		sb.append(" (").append(climate.analogue).append(", ").append(climate.temperature).append(" C");
		if (climate.permanentSnowCover)
		{
			sb.append(", permanent snow cover");
		}
		if (climate.alwaysDim)
		{
			sb.append(", always dim");
		}
		sb.append(")");
		long now = Forecast.slot(gameMillis);
		for (long slot = now; slot <= now + SLOTS_AHEAD; slot++)
		{
			Forecast.Slot s = forecast.at(slot, climate);
			sb.append(System.lineSeparator()).append("  ");
			sb.append(slot == now ? String.format("now (%.1f h into slot %d, day %d %02d:00): ", Forecast.hourIntoSlot(gameMillis), slot, GameClock.day(slot * Forecast.SLOT_MILLIS), Forecast.startHour(slot))
				: String.format("slot %d, day %d %02d:00: ", slot, GameClock.day(slot * Forecast.SLOT_MILLIS), Forecast.startHour(slot)));
			sb.append(s);
		}
		return sb.toString();
	}
}
