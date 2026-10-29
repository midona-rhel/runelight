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

import lombok.RequiredArgsConstructor;

/**
 * The "Day length" setting for {@code sky.GameClock}: a day in real minutes (24 minutes by default, the old
 * renderer's), the real day (the game's time of day is the machine's local time of day), or the day held at the
 * "Fixed hour" setting ({@link #minutes} 0).
 */
@RequiredArgsConstructor
public enum DayLength
{
	MINUTES_24("24 minutes", 24),
	HOUR_1("1 hour", 60),
	HOURS_2("2 hours", 120),
	REAL_DAY("Real time (24 hours)", GameClockMinutes.REAL_DAY),
	FIXED_HOUR("Fixed hour", 0),
	;

	private final String name;
	/** Real minutes per game day: {@link GameClockMinutes#REAL_DAY} follows the local clock, 0 holds the fixed hour. */
	public final int minutes;

	@Override
	public String toString()
	{
		return name;
	}

	/** The day-length values {@code sky.GameClock} gives a meaning beyond "minutes per day". */
	public static final class GameClockMinutes
	{
		public static final int REAL_DAY = 24 * 60;

		private GameClockMinutes()
		{
		}
	}
}
