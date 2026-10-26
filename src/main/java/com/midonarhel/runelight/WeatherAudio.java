package com.midonarhel.runelight;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The weather's sounds: rain, a light loop at first and a heavy one as it builds, whose level follows how hard it
 * rains around the player, and a rumble of thunder with every lightning strike, the moment its flash begins; both are
 * muffled inside buildings. No wind.
 *
 * One thread mixes (AudioMixer) into one SourceDataLine, opened when there is something to hear and closed after a
 * quiet spell, so the client thread only sets a few numbers and never waits on the device. The sounds are CC0
 * recordings (sounds/CREDITS.txt), made into these files by tools/make_weather_sounds.py.
 */
final class WeatherAudio
{
	private static final Logger log = LoggerFactory.getLogger(WeatherAudio.class);

	private static final String PATH = "/com/midonarhel/runelight/sounds/";
	private static final int THUNDERS = 3;
	// frames mixed at a time, and the device's buffer, in bytes of 16-bit mono: about 23 ms and 93 ms
	private static final int BLOCK = 512;
	private static final int BUFFER_BYTES = 4096;
	// seconds from mixing a block to hearing it: the device's buffer
	private static final float DEVICE_LATENCY = BUFFER_BYTES / 2f / AudioMixer.RATE;
	// seconds of silence before the device is closed
	private static final float IDLE_SECONDS = 5;

	// the rain's level at a loudness of 1, and how the loudness shapes it (a light drizzle is heard more than its share)
	private static final float RAIN_GAIN = 0.8f;
	private static final float RAIN_CURVE = 0.7f;
	// below this loudness the rain fades to nothing
	private static final float RAIN_FLOOR = 0.05f;
	// the sky seen overhead, 0 to 1, at and below which the player is fully indoors, and at and above which outdoors
	static final float FULLY_INDOORS = 0.15f;
	static final float FULLY_OUTDOORS = 0.6f;
	// rain this far away (world units) is not heard; nearer is, the more so the nearer, up to this share of its loudness
	static final float RAIN_REACH = 6400;
	static final float DISTANT_RAIN = 0.8f;
	// thunder: strikes come down this far from the player, world units, and are loudest and brightest at the near end;
	// the level at the near and the far end, and the cutoff (Hz) of its low-pass at either end. Even the nearest is a
	// distant rumble, not a crack overhead: a close clap at full level was far too loud
	static final float NEAR_STRIKE = 2000;
	static final float FAR_STRIKE = 10000;
	private static final float NEAR_THUNDER_GAIN = 0.45f;
	private static final float FAR_THUNDER_GAIN = 0.25f;
	private static final float NEAR_CUTOFF = 2500;
	private static final float FAR_CUTOFF = 700;
	// how much slower far thunder plays, and the random spread of every clap's pitch and level
	private static final float FAR_PITCH = 0.9f;
	private static final float PITCH_SPREAD = 0.06f;
	private static final float GAIN_SPREAD = 0.1f;

	private final Random random = new Random();
	private AudioMixer mixer;
	// each clap's envelope for the light to follow (Rumble), null if the sounds did not load
	private float[][] envelopes;
	private volatile boolean running = true;
	private volatile boolean failed;
	private Thread thread;

	/** Load the sounds. Without them (or without a device later) it stays silent, and says so once. */
	WeatherAudio()
	{
		try
		{
			float[][] claps = loadThunders();
			mixer = new AudioMixer(load("rain_light.wav"), load("rain.wav"), claps);
			envelopes = new float[THUNDERS][];
			for (int i = 0; i < THUNDERS; ++i)
			{
				envelopes[i] = Rumble.envelope(claps[i], AudioMixer.RATE);
			}
		}
		catch (IOException | RuntimeException | javax.sound.sampled.UnsupportedAudioFileException e)
		{
			log.warn("No weather sounds: {}", e.toString());
			failed = true;
		}
	}

	private static float[][] loadThunders() throws IOException, javax.sound.sampled.UnsupportedAudioFileException
	{
		float[][] claps = new float[THUNDERS][];
		for (int i = 0; i < THUNDERS; ++i)
		{
			claps[i] = load("thunder" + (i + 1) + ".wav");
		}
		return claps;
	}

	/** A 16-bit mono WAV at the mixer's rate, from the bundled sounds, as samples -1 to 1. */
	static float[] load(String name) throws IOException, javax.sound.sampled.UnsupportedAudioFileException
	{
		try (InputStream in = WeatherAudio.class.getResourceAsStream(PATH + name))
		{
			if (in == null)
			{
				throw new IOException("missing " + name);
			}
			AudioInputStream audio = AudioSystem.getAudioInputStream(new BufferedInputStream(in));
			AudioFormat f = audio.getFormat();
			if (f.getSampleSizeInBits() != 16 || f.getChannels() != 1 || (int) f.getSampleRate() != AudioMixer.RATE
				|| f.isBigEndian() || f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED)
			{
				throw new IOException(name + " is " + f + ", not 16-bit mono PCM at " + AudioMixer.RATE + " Hz");
			}
			byte[] bytes = audio.readAllBytes();
			float[] out = new float[bytes.length / 2];
			for (int i = 0; i < out.length; ++i)
			{
				out[i] = (short) ((bytes[2 * i] & 0xFF) | bytes[2 * i + 1] << 8) / 32768f;
			}
			return out;
		}
	}

	/**
	 * Every frame, from the client thread.
	 *
	 * @param loudness how hard it rains for the ears, 0 to 1 (Weather.rainLoudness)
	 * @param indoors  how far indoors the player is, 0 to 1 (indoorMix)
	 * @param volume   everything's level, 0 to 1
	 */
	void update(float loudness, float indoors, float volume)
	{
		if (failed)
		{
			return;
		}
		float rain = rainGain(loudness);
		mixer.setTargets(rain, loudness, indoors, volume);
		if (rain > 0 && volume > 0)
		{
			start();
		}
	}

	/**
	 * A rumble of thunder, now: picks one and, if sound is wanted, plays it. The light follows the returned clip
	 * (Lightning.rumble), whether or not it sounds.
	 *
	 * @param distance from the player to the strike, world units
	 * @param strength the flash's top, 0 to 1
	 * @param sound    whether to play it (not at volume 0)
	 * @return the clip's loudness for the light, null if the sounds did not load
	 */
	Rumble thunder(float distance, float strength, boolean sound)
	{
		if (envelopes == null)
		{
			return null;
		}
		float[] shape = thunderShape(distance, strength);
		float spread = 1 + PITCH_SPREAD * (2 * random.nextFloat() - 1);
		float loud = 1 + GAIN_SPREAD * (2 * random.nextFloat() - 1);
		int variant = random.nextInt(THUNDERS);
		float gain = shape[0] * loud, pitch = shape[2] * spread;
		boolean plays = sound && !failed;
		if (plays)
		{
			mixer.thunder(variant, gain, shape[1], pitch);
			start();
		}
		return new Rumble(envelopes[variant], Math.min(1, gain / NEAR_THUNDER_GAIN), pitch, plays ? DEVICE_LATENCY : 0);
	}

	/** Stop the mixer thread and close the device; waits briefly for it. */
	void shutDown()
	{
		running = false;
		Thread t = thread;
		if (t != null)
		{
			t.interrupt();
			try
			{
				t.join(1000);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}
	}

	private synchronized void start()
	{
		if (thread == null && running)
		{
			thread = new Thread(this::run, "RuneLight weather audio");
			thread.setDaemon(true);
			thread.start();
		}
	}

	private void run()
	{
		SourceDataLine line = null;
		float[] samples = new float[BLOCK];
		byte[] bytes = new byte[BLOCK * 2];
		long silentSince = 0;
		try
		{
			while (running)
			{
				if (!mixer.audible())
				{
					long now = System.nanoTime();
					if (silentSince == 0)
					{
						silentSince = now;
					}
					if (line != null && now - silentSince > IDLE_SECONDS * 1e9)
					{
						line.drain();
						line.close();
						line = null;
					}
					Thread.sleep(50);
					continue;
				}
				silentSince = 0;
				if (line == null)
				{
					line = open();
				}
				mixer.render(samples, BLOCK);
				toBytes(samples, BLOCK, bytes);
				line.write(bytes, 0, BLOCK * 2);
			}
		}
		catch (InterruptedException e)
		{
			// shut down
		}
		catch (Exception e)
		{
			log.warn("Weather sounds stopped: {}", e.toString());
			failed = true;
		}
		finally
		{
			if (line != null)
			{
				line.stop();
				line.flush();
				line.close();
			}
		}
	}

	private static SourceDataLine open() throws javax.sound.sampled.LineUnavailableException
	{
		AudioFormat format = new AudioFormat(AudioMixer.RATE, 16, 1, true, false);
		SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
		line.open(format, BUFFER_BYTES);
		line.start();
		return line;
	}

	/** 16-bit little-endian samples. */
	static void toBytes(float[] samples, int frames, byte[] out)
	{
		for (int i = 0; i < frames; ++i)
		{
			int v = Math.round(Math.max(-1, Math.min(1, samples[i])) * 32767);
			out[2 * i] = (byte) v;
			out[2 * i + 1] = (byte) (v >> 8);
		}
	}

	/** The rain's level for a loudness: nothing below RAIN_FLOOR, then rising quickly at first. */
	static float rainGain(float loudness)
	{
		float l = Math.max(0, Math.min(1, loudness));
		return RAIN_GAIN * (float) Math.pow(l, RAIN_CURVE) * Math.min(1, l / RAIN_FLOOR);
	}

	/**
	 * How hard it rains for the ears: where the player is, or, if more, the rain somewhere around that is heard from
	 * the distance, fainter the farther it is.
	 *
	 * @param here    the rain at the player
	 * @param distant the most of (the rain at a place times {@link #distantWeight}) over the places around
	 */
	static float rainLoudness(float here, float distant)
	{
		return Math.min(1, Math.max(here, DISTANT_RAIN * distant));
	}

	/** How much of the rain at this distance (world units) from the player is heard: all of it at 0, none at RAIN_REACH. */
	static float distantWeight(float distance)
	{
		float x = Math.max(0, Math.min(1, distance / RAIN_REACH));
		return (1 - x) * (1 - x);
	}

	/**
	 * A clap's level, low-pass cutoff (Hz) and pitch for a strike at this distance and strength: far is quieter,
	 * duller and a little lower, near is loud, with the crack of its top end.
	 */
	static float[] thunderShape(float distance, float strength)
	{
		float x = Math.max(0, Math.min(1, (distance - NEAR_STRIKE) / (FAR_STRIKE - NEAR_STRIKE)));
		float near = 1 - x * x * (3 - 2 * x);
		float gain = (FAR_THUNDER_GAIN + (NEAR_THUNDER_GAIN - FAR_THUNDER_GAIN) * near) * Math.max(0, Math.min(1, strength));
		float cutoff = FAR_CUTOFF * (float) Math.pow(NEAR_CUTOFF / FAR_CUTOFF, near);
		float pitch = FAR_PITCH + (1 - FAR_PITCH) * near;
		return new float[]{gain, cutoff, pitch};
	}

	/**
	 * How far indoors the player is, 0 to 1, from the share of the sky seen straight up at their head: a room sees
	 * none and is fully indoors, a gateway or a tree's canopy some and muffles in part, open ground is outdoors.
	 * Without that (the skylight is off or not traced here: a negative visibility) it is the room map's: in a room
	 * or not.
	 *
	 * @param visibility the sky overhead, 0 to 1, or negative if unknown
	 * @param rooms      the room map, null if there is none
	 * @param y          a little above the floor, for the room map
	 */
	static float indoorMix(float visibility, RoomMap rooms, float x, float y, float z)
	{
		if (visibility < 0)
		{
			return rooms != null && rooms.roomAt(x, y, z) > 0 ? 1 : 0;
		}
		float t = Math.max(0, Math.min(1, (visibility - FULLY_INDOORS) / (FULLY_OUTDOORS - FULLY_INDOORS)));
		return 1 - t * t * (3 - 2 * t);
	}
}
