package com.midonarhel.runelight;

/**
 * The loudness of the thunder clip playing for a strike, which the light follows (Lightning): the clip's envelope,
 * how strongly this strike's clip is heard, how fast it plays and how long the device takes to sound it. It comes
 * from the clip's data, so the light flickers whether or not the sound plays.
 */
final class Rumble
{
	// envelope values per second, the window the loudness is taken over (seconds) and the highest rate the envelope
	// may change at (Hz): a rumble's glow swells and falls a few times a second, it does not strobe
	static final int RATE = 100;
	private static final float WINDOW = 0.03f;
	private static final float BANDWIDTH = 8;

	private final float[] envelope;
	private final float strength, pitch, delay;

	/**
	 * @param envelope the clip's (envelope)
	 * @param strength how strongly this strike's clip is heard, 0 to 1
	 * @param pitch    playback speed, 1 as recorded
	 * @param delay    seconds from starting it to hearing it
	 */
	Rumble(float[] envelope, float strength, float pitch, float delay)
	{
		this.envelope = envelope;
		this.strength = strength;
		this.pitch = pitch;
		this.delay = delay;
	}

	/** How loud the clip is heard this many seconds after it was started, 0 to 1: the envelope times the strength. */
	float level(float seconds)
	{
		float at = (seconds - delay) * pitch * RATE;
		if (at < 0 || at >= envelope.length - 1)
		{
			return 0;
		}
		int i = (int) at;
		return strength * (envelope[i] + (envelope[i + 1] - envelope[i]) * (at - i));
	}

	/** Seconds after it was started that the clip has been heard to its end. */
	float duration()
	{
		return envelope.length / (float) RATE / pitch + delay;
	}

	/**
	 * A clip's loudness over time: the RMS over WINDOW seconds, RATE times a second, smoothed (forward and back, so
	 * it does not lag) to BANDWIDTH Hz, and scaled to peak at 1.
	 */
	static float[] envelope(float[] clip, int sampleRate)
	{
		int n = (int) ((long) clip.length * RATE / sampleRate);
		int half = Math.round(WINDOW * sampleRate / 2);
		float[] out = new float[n];
		for (int i = 0; i < n; ++i)
		{
			int centre = (int) ((long) i * sampleRate / RATE);
			int from = Math.max(0, centre - half), to = Math.min(clip.length, centre + half);
			double sum = 0;
			for (int k = from; k < to; ++k)
			{
				sum += clip[k] * clip[k];
			}
			out[i] = to > from ? (float) Math.sqrt(sum / (to - from)) : 0;
		}
		float a = 1 - (float) Math.exp(-2 * Math.PI * BANDWIDTH / RATE);
		float low = 0;
		for (int i = 0; i < n; ++i)
		{
			low += a * (out[i] - low);
			out[i] = low;
		}
		low = 0;
		for (int i = n - 1; i >= 0; --i)
		{
			low += a * (out[i] - low);
			out[i] = low;
		}
		float peak = 0;
		for (float v : out)
		{
			peak = Math.max(peak, v);
		}
		if (peak > 0)
		{
			for (int i = 0; i < n; ++i)
			{
				out[i] /= peak;
			}
		}
		return out;
	}
}
