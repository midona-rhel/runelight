package com.midonarhel.runelight;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Mixes the weather's sounds into blocks of samples: the rain loop and the thunder claps. Indoors both are muffled by
 * the same low-pass, which closes as the player walks into a building; each clap is also low-passed by how far away
 * it is. No audio device in here (WeatherAudio plays the blocks), so it can be tested.
 *
 * The targets are set from the client thread; render runs on the mixer thread. Everything the mixer changes it
 * moves toward its target smoothly, sample by sample, so nothing clicks.
 */
final class AudioMixer
{
	static final int RATE = 22050;
	// seconds for the rain's level, and the muffling between outdoors and indoors, to cover most of a change
	private static final float RAIN_SECONDS = 0.6f;
	private static final float INDOOR_SECONDS = 0.8f;
	private static final float MASTER_SECONDS = 0.05f;
	// indoors the thunder's cutoff is this share of the outdoor one, and it is this much quieter
	private static final float INDOOR_CUTOFF = 0.3f;
	private static final float INDOOR_THUNDER = 0.65f;
	// the rain's cutoff, Hz: open outdoors, closing to the second indoors (geometrically, so the change sounds even),
	// where it is this much quieter besides
	private static final float RAIN_OPEN = RATE * 0.49f;
	private static final float RAIN_INDOOR_CUTOFF = 1000;
	private static final float INDOOR_RAIN = 0.8f;
	// the most claps heard at once; a new one takes the oldest's place
	private static final int MAX_VOICES = 4;
	// below this the sound counts as silent
	private static final float SILENT = 1e-3f;
	// above this the output is rounded off instead of clipping
	private static final float KNEE = 0.75f;

	private final float[] rain;
	private final float[][] thunder;
	private final ConcurrentLinkedQueue<Voice> incoming = new ConcurrentLinkedQueue<>();
	private final List<Voice> voices = new ArrayList<>();

	// set from any thread
	private volatile float rainTarget, indoorTarget, masterTarget;

	// the mixer thread's: where in the loops it is, and the smoothed levels
	private int position;
	private float rainLevel, indoorMix, master, rainLow1, rainLow2;

	/** A thunder clap playing. */
	private static final class Voice
	{
		final float[] samples;
		final float gain, cutoff, pitch;
		double position;
		float low1, low2;

		Voice(float[] samples, float gain, float cutoff, float pitch)
		{
			this.samples = samples;
			this.gain = gain;
			this.cutoff = cutoff;
			this.pitch = pitch;
		}
	}

	/**
	 * @param rain    the rain loop
	 * @param thunder the claps
	 */
	AudioMixer(float[] rain, float[][] thunder)
	{
		if (rain.length == 0)
		{
			throw new IllegalArgumentException("the rain loop is empty");
		}
		this.rain = rain;
		this.thunder = thunder;
	}

	/**
	 * @param rain   level of the rain, 0 to 1 (already shaped by WeatherAudio.rainGain)
	 * @param indoor 0 outdoors .. 1 indoors
	 * @param volume everything's level, 0 to 1
	 */
	void setTargets(float rain, float indoor, float volume)
	{
		rainTarget = rain;
		indoorTarget = indoor;
		masterTarget = volume;
	}

	/**
	 * Play a clap.
	 *
	 * @param variant which clap
	 * @param gain    its level, 0 to 1
	 * @param cutoff  low-pass cutoff outdoors, Hz
	 * @param pitch   playback speed, 1 as recorded
	 */
	void thunder(int variant, float gain, float cutoff, float pitch)
	{
		incoming.add(new Voice(thunder[variant % thunder.length], gain, cutoff, pitch));
	}

	/** Whether there is anything to hear or still fading: the player may close the device when not. */
	boolean audible()
	{
		return rainTarget > SILENT || rainLevel > SILENT || !voices.isEmpty() || !incoming.isEmpty();
	}

	/** Fill out[0..frames) with the next samples, -1 to 1. */
	void render(float[] out, int frames)
	{
		Voice v;
		while ((v = incoming.poll()) != null)
		{
			if (voices.size() >= MAX_VOICES)
			{
				voices.remove(0);
			}
			voices.add(v);
		}
		final float rainK = smoothing(RAIN_SECONDS), indoorK = smoothing(INDOOR_SECONDS), masterK = smoothing(MASTER_SECONDS);
		final float rainGoal = rainTarget, indoorGoal = indoorTarget, masterGoal = masterTarget;
		final int length = rain.length;
		final float masterFrom = master;
		// the rain's low-pass follows how far indoors the player is at the start of the block, as the claps' do
		final float rainA = lowpassCoefficient(rainCutoff(indoorMix), RATE);
		final float rainGain = 1 - (1 - INDOOR_RAIN) * indoorMix;
		for (int i = 0; i < frames; ++i)
		{
			rainLevel += (rainGoal - rainLevel) * rainK;
			indoorMix += (indoorGoal - indoorMix) * indoorK;
			master += (masterGoal - master) * masterK;
			// the filter runs on silence too, so it is at rest when the rain comes back
			rainLow1 += rainA * (rain[position] - rainLow1);
			rainLow2 += rainA * (rainLow1 - rainLow2);
			out[i] = rainLevel > SILENT / 4 ? rainLevel * rainGain * rainLow2 : 0;
			position = position + 1 == length ? 0 : position + 1;
		}
		// the claps: the cutoff follows how far indoors the player is now
		for (int k = voices.size() - 1; k >= 0; --k)
		{
			Voice c = voices.get(k);
			float cutoff = c.cutoff * (1 - (1 - INDOOR_CUTOFF) * indoorMix);
			float a = lowpassCoefficient(cutoff, RATE);
			float gain = c.gain * (1 - (1 - INDOOR_THUNDER) * indoorMix);
			boolean done = false;
			for (int i = 0; i < frames; ++i)
			{
				int at = (int) c.position;
				if (at + 1 >= c.samples.length)
				{
					done = true;
					break;
				}
				float frac = (float) (c.position - at);
				float x = c.samples[at] + (c.samples[at + 1] - c.samples[at]) * frac;
				c.low1 += a * (x - c.low1);
				c.low2 += a * (c.low1 - c.low2);
				out[i] += c.low2 * gain;
				c.position += c.pitch;
			}
			if (done)
			{
				voices.remove(k);
			}
		}
		// everything at the master level, which moves along the block; rounded off at the top
		for (int i = 0; i < frames; ++i)
		{
			out[i] = softClip(out[i] * (masterFrom + (master - masterFrom) * (i + 1) / frames));
		}
	}

	/** Per-sample step of a one-pole smoother that covers most of a change in this many seconds. */
	static float smoothing(float seconds)
	{
		return 1 - (float) Math.exp(-3.0 / (seconds * RATE));
	}

	/** The rain's low-pass cutoff, Hz, when the indoor mix is 0 to 1: open outdoors, RAIN_INDOOR_CUTOFF indoors. */
	static float rainCutoff(float indoor)
	{
		return RAIN_OPEN * (float) Math.pow(RAIN_INDOOR_CUTOFF / RAIN_OPEN, indoor);
	}

	/** One-pole low-pass coefficient (y += a * (x - y)) for a cutoff at a sample rate. */
	static float lowpassCoefficient(float cutoff, int rate)
	{
		return 1 - (float) Math.exp(-2 * Math.PI * Math.min(cutoff, rate * 0.49f) / rate);
	}

	/** x, with what passes KNEE bent smoothly toward 1 instead of cut off. */
	static float softClip(float x)
	{
		float a = Math.abs(x);
		if (a <= KNEE)
		{
			return x;
		}
		float bent = KNEE + (1 - KNEE) * (float) Math.tanh((a - KNEE) / (1 - KNEE));
		return x < 0 ? -bent : bent;
	}
}
