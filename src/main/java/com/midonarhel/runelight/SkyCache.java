package com.midonarhel.runelight;

import com.midonarhel.runelight.template.Template;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Cached sky radiance: the procedural sky and its clouds captured into a small lat-long texture
 * (sky_cache_frag.glsl, layout in atmosphere.glsl) every few in-game minutes, more often around sunrise and sunset
 * when the sky changes fastest, and as soon as the weather, the cloud cover or the camera's place under the clouds
 * changes noticeably. What is shown is the previous capture blended into the latest (sky_cache_blend_frag.glsl),
 * advancing with the game clock, so nothing pops. Each blend takes about as long as the captures come, so the
 * sky moves on steadily instead of in steps, and the composite eases between the cache and the analytic sky light
 * (skyCacheWeight) whenever the cache stops or starts being used, so the light never switches at once.
 *
 * A tiny irradiance map (sky_irradiance_frag.glsl), blended the same way, holds the light the sky sheds on a
 * surface facing each way, so the ground follows the clouds and the sky's colors.
 *
 * A capture is 128x64 pixels marching 12 cloud steps, a few hundredths of a millisecond once every second or few:
 * the irradiance only needs its blurred 32x16 mip. The 32x16 irradiance (32 fetches a texel) is redrawn when the
 * blend has moved on a little or the sun's glow, which it adds live, has moved.
 */
class SkyCache
{
	// the irradiance reads a blurred mip anyway
	static final int LIGHT_WIDTH = 128;
	static final int LIGHT_HEIGHT = 64;
	static final int LIGHT_STEPS = 12;
	static final int IRRADIANCE_WIDTH = 32;
	static final int IRRADIANCE_HEIGHT = 16;
	// the irradiance is redrawn when the blend has moved this far, or this often for the sun's glow
	private static final float IRRADIANCE_BLEND_STEP = 0.02f;
	private static final long IRRADIANCE_MILLIS = 100;

	static final Shader CAPTURE_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "sky_cache_frag.glsl");

	static final Shader BLEND_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "sky_cache_blend_frag.glsl");

	static final Shader IRRADIANCE_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "sky_irradiance_frag.glsl");

	// composite's skyCacheMode: the sky light comes from the cache
	static final int MODE_LIGHT = 1;

	/**
	 * When to capture and how far the blend has come. Pure logic on the clock and the sky's inputs.
	 */
	static final class Schedule
	{
		// with the clock standing still, clouds still drift: capture at least this often, real seconds
		static final float MAX_SECONDS = 30;
		// a capture blends in over at least this long, real seconds, whatever the clock does; at most this long when
		// captures come further apart than that
		static final float BLEND_SECONDS = 2;
		static final float MAX_BLEND_SECONDS = 8;
		// changes that capture early: cloud cover and overcast, how snowy the overcast is, sun altitude (degrees),
		// camera altitude and cloud drift relative to the camera (world units)
		static final float COVER_STEP = 0.04f;
		static final float SNOW_STEP = 0.1f;
		static final float ALTITUDE_STEP = 2;
		static final float CAMERA_ALTITUDE_STEP = 600;
		static final double DRIFT_STEP = 2500;
		// around sunrise and sunset captures come this much more often
		static final double TWILIGHT_INTERVAL = 0.35;

		private boolean valid;
		private double gameMinutes;
		private long millis;
		private float cover, overcast, snow, altitude, cameraAltitude;
		private double driftX, driftZ;
		private int settings;
		// real time the latest capture blends over: the time between the last two captures, so a blend ends about
		// when the next capture comes and the sky keeps moving at a steady pace rather than in steps
		private long blendMillis = (long) (BLEND_SECONDS * 1000);

		/**
		 * In-game minutes between captures at this sun altitude: shorter while the sun is near the horizon.
		 */
		static double interval(double minutes, float sunAltitude)
		{
			double t = Math.max(0, Math.min(1, (Math.abs(sunAltitude) - 4) / 14.0));
			t = t * t * (3 - 2 * t);
			return minutes * (TWILIGHT_INTERVAL + (1 - TWILIGHT_INTERVAL) * t);
		}

		/**
		 * @param gameMinutes the game clock in minutes, NaN while it stands still
		 * @param settings anything else that changes the sky at once (config), hashed
		 */
		boolean due(double gameMinutes, long millis, double interval, float cover, float overcast, float snow,
			float altitude, float cameraAltitude, double driftX, double driftZ, int settings)
		{
			if (!valid || settings != this.settings)
			{
				return true;
			}
			if (!Double.isNaN(gameMinutes) && (Double.isNaN(this.gameMinutes)
				|| gameMinutes - this.gameMinutes >= interval || gameMinutes < this.gameMinutes))
			{
				return true;
			}
			if (Double.isNaN(gameMinutes) && !Double.isNaN(this.gameMinutes))
			{
				return true;
			}
			return millis - this.millis >= MAX_SECONDS * 1000
				|| Math.abs(cover - this.cover) >= COVER_STEP
				|| Math.abs(overcast - this.overcast) >= COVER_STEP
				|| Math.abs(snow - this.snow) >= SNOW_STEP
				|| Math.abs(altitude - this.altitude) >= ALTITUDE_STEP
				|| Math.abs(cameraAltitude - this.cameraAltitude) >= CAMERA_ALTITUDE_STEP
				|| Math.hypot(driftX - this.driftX, driftZ - this.driftZ) >= DRIFT_STEP;
		}

		void captured(double gameMinutes, long millis, float cover, float overcast, float snow, float altitude,
			float cameraAltitude, double driftX, double driftZ, int settings)
		{
			long gap = valid ? millis - this.millis : 0;
			blendMillis = (long) (Math.max(BLEND_SECONDS, Math.min(MAX_BLEND_SECONDS, gap / 1000f)) * 1000);
			valid = true;
			this.gameMinutes = gameMinutes;
			this.millis = millis;
			this.cover = cover;
			this.overcast = overcast;
			this.snow = snow;
			this.altitude = altitude;
			this.cameraAltitude = cameraAltitude;
			this.driftX = driftX;
			this.driftZ = driftZ;
			this.settings = settings;
		}

		/**
		 * How far the previous capture has blended into the latest, 0 to 1: by the game clock over the interval, or
		 * in real time over about the time between captures, whichever is further. Linear: the blends follow one
		 * another, and easing each in and out would make the sky's changes pulse.
		 */
		float blend(double gameMinutes, long millis, double interval)
		{
			if (!valid)
			{
				return 1;
			}
			double byGame = Double.isNaN(gameMinutes) || Double.isNaN(this.gameMinutes) ? 0
				: (gameMinutes - this.gameMinutes) / interval;
			double byReal = (millis - this.millis) / (double) blendMillis;
			return (float) Math.max(0, Math.min(1, Math.max(byGame, byReal)));
		}

		void invalidate()
		{
			valid = false;
		}
	}

	/** Freshly compiled programs, so a hot reload can compile everything before replacing anything. */
	static final class Programs
	{
		final int capture, blend, irradiance;

		Programs(int capture, int blend, int irradiance)
		{
			this.capture = capture;
			this.blend = blend;
			this.irradiance = irradiance;
		}
	}

	private final Schedule schedule = new Schedule();
	private final Clouds.Locations captureClouds = new Clouds.Locations();
	private int captureProgram, blendProgram, irradianceProgram;
	private int uniCameraPos, uniSunDir, uniMoonDir, uniSunAltitude, uniCacheSize, uniOvercast, uniSteps;
	private int uniBlend;
	private int uniIrradianceSize, uniIrradianceBlend, uniIrradianceLod, uniIrradianceSunDir, uniIrradianceSunAltitude,
		uniIrradianceOvercast;
	// how much of the composite's sky light comes from the cache, eased toward 1 while it is used and 0 when not
	private float weight;
	private long weightMillis;
	// real seconds over which the sky light goes from the analytic one to the cache's and back
	static final float WEIGHT_SECONDS = 1.5f;

	private int width, height;
	private float irradianceBlend = -1;
	private long irradianceMillis;

	private final int[] textures = new int[3];
	private int irradiance;
	private int fbo;
	// indices into textures: the latest capture, the one blending into it, and the one shown (the blend's target
	// while blending, else the latest); -1 before the first capture
	private int latest = -1, from = -1, shown = -1;
	private boolean blending;
	// how far the blend has come, for the blend a new capture starts from
	private float lastBlend;
	private double interval = 1;
	// real time of the last update; after a pause (underground, sky light off) the old capture is stale and the
	// next one is shown at once instead of blended from it
	private long lastUpdateMillis;
	private static final long STALE_MILLIS = 1000;

	Programs compile(Template template) throws ShaderException
	{
		int c = 0, b = 0;
		try
		{
			c = CAPTURE_PROGRAM.compile(template);
			b = BLEND_PROGRAM.compile(template);
			return new Programs(c, b, IRRADIANCE_PROGRAM.compile(template));
		}
		catch (ShaderException ex)
		{
			// glDeleteProgram ignores 0
			glDeleteProgram(c);
			glDeleteProgram(b);
			throw ex;
		}
	}

	/**
	 * Replace the programs. The blend program samples its two captures from fromUnit and fromUnit + 1, the
	 * irradiance pass the shown cache from fromUnit.
	 */
	void setPrograms(Programs p, int cloudNoiseUnit, int fromUnit)
	{
		freePrograms();
		captureProgram = p.capture;
		blendProgram = p.blend;
		irradianceProgram = p.irradiance;
		uniCameraPos = glGetUniformLocation(captureProgram, "cameraPos");
		uniSunDir = glGetUniformLocation(captureProgram, "sunDir");
		uniMoonDir = glGetUniformLocation(captureProgram, "moonDir");
		uniSunAltitude = glGetUniformLocation(captureProgram, "sunAltitude");
		uniCacheSize = glGetUniformLocation(captureProgram, "cacheSize");
		uniOvercast = glGetUniformLocation(captureProgram, "weatherOvercast");
		uniLight = glGetUniformLocation(captureProgram, "weatherLight");
		uniSteps = glGetUniformLocation(captureProgram, "captureSteps");
		Clouds.locate(captureClouds, captureProgram, cloudNoiseUnit);

		uniBlend = glGetUniformLocation(blendProgram, "blend");
		glUseProgram(blendProgram);
		glUniform1i(glGetUniformLocation(blendProgram, "cacheFrom"), fromUnit);
		glUniform1i(glGetUniformLocation(blendProgram, "cacheTo"), fromUnit + 1);
		uniIrradianceSize = glGetUniformLocation(irradianceProgram, "irradianceSize");
		uniIrradianceBlend = glGetUniformLocation(irradianceProgram, "blend");
		uniIrradianceLod = glGetUniformLocation(irradianceProgram, "radianceLod");
		uniIrradianceSunDir = glGetUniformLocation(irradianceProgram, "sunDir");
		uniIrradianceSunAltitude = glGetUniformLocation(irradianceProgram, "sunAltitude");
		uniIrradianceOvercast = glGetUniformLocation(irradianceProgram, "weatherOvercast");
		uniIrradianceLight = glGetUniformLocation(irradianceProgram, "weatherLight");
		glUseProgram(irradianceProgram);
		glUniform1i(glGetUniformLocation(irradianceProgram, "radiance"), fromUnit);
		glUniform1i(glGetUniformLocation(irradianceProgram, "radianceTo"), fromUnit + 1);
		glUseProgram(0);
		// a new shader may draw a different sky
		schedule.invalidate();
	}

	void freePrograms()
	{
		glDeleteProgram(captureProgram);
		glDeleteProgram(blendProgram);
		glDeleteProgram(irradianceProgram);
		captureProgram = blendProgram = irradianceProgram = 0;
	}

	/** GL resources other than programs. */
	void free()
	{
		if (fbo != 0)
		{
			glDeleteFramebuffers(fbo);
			glDeleteTextures(textures);
			glDeleteTextures(irradiance);
			fbo = irradiance = 0;
			textures[0] = textures[1] = textures[2] = 0;
		}
		latest = from = shown = -1;
		blending = false;
		irradianceBlend = -1;
		schedule.invalidate();
	}

	/** A composite program's atmosphere uniforms. */
	static final class CompositeUniforms
	{
		private int mode = -1, haze = -1, stars = -1, weight = -1;

		/**
		 * Look them up and point the program's cache sampler at {@code unit}, its irradiance sampler at
		 * {@code unit + 1}.
		 */
		void locate(int program, int unit)
		{
			mode = glGetUniformLocation(program, "skyCacheMode");
			haze = glGetUniformLocation(program, "skyHaze");
			stars = glGetUniformLocation(program, "starBrightness");
			weight = glGetUniformLocation(program, "skyCacheWeight");
			glUseProgram(program);
			glUniform1i(glGetUniformLocation(program, "skyCache"), unit);
			glUniform1i(glGetUniformLocation(program, "skyIrradiance"), unit + 1);
			glUseProgram(0);
		}
	}

	/**
	 * Once a frame, before the composite: how it uses the cache (MODE_* bits, 0 when it was not updated this frame).
	 * The sky light keeps coming partly from the last capture while it eases back to the analytic one; {@link #bind}
	 * must have been called either way.
	 */
	void easeComposite(int mode)
	{
		long now = System.currentTimeMillis();
		// a long frame (loading) must not jump it
		float dt = Math.max(0, Math.min(0.1f, (now - weightMillis) / 1000f));
		weightMillis = now;
		boolean light = (mode & MODE_LIGHT) != 0 && shown >= 0;
		weight = ease(weight, light ? 1 : 0, dt);
		if (shown < 0)
		{
			weight = 0;
		}
	}

	/**
	 * With a composite program in use, after {@link #easeComposite}: this frame's use of the cache, how hazy the air
	 * is and how bright the stars are.
	 */
	void setComposite(CompositeUniforms u, float haze, float stars)
	{
		glUniform1i(u.mode, weight > 0 ? MODE_LIGHT : 0);
		glUniform1f(u.weight, weight);
		glUniform1f(u.haze, haze);
		glUniform1f(u.stars, stars);
	}

	// finished captures, for the global illumination, which relights with the sky
	private int captures;

	int getCaptures()
	{
		return captures;
	}

	/** How much of the sky light comes from the cache, as the composite last used it. */
	float getLightWeight()
	{
		return shown >= 0 ? weight : 0;
	}

	/** Linear ease of the cache weight toward target over WEIGHT_SECONDS. */
	static float ease(float weight, float target, float dt)
	{
		float step = dt / WEIGHT_SECONDS;
		return weight < target ? Math.min(target, weight + step) : Math.max(target, weight - step);
	}

	/**
	 * Capture the sky if it is due and advance the blend. Changes the framebuffer, viewport and program; expects a
	 * VAO bound and the cloud noise bound on the unit given to setPrograms if clouds are enabled.
	 *
	 * @param gameMinutes the game clock in minutes, NaN while it stands still
	 * @param minutes configured in-game minutes between captures
	 * @param overcast sky.glsl's weatherOvercast; the flash is left out
	 * @param settings hash of the config the sky depends on
	 * @param fromUnit, fromUnit + 1: free units for the blend
	 */
	void update(Clouds clouds, double gameMinutes, int minutes, float cameraX, float cameraY, float cameraZ,
		DayCycle day, float[] overcast, int settings, int fromUnit)
	{
		if (captureProgram == 0 || blendProgram == 0 || irradianceProgram == 0)
		{
			return;
		}
		if (fbo == 0)
		{
			init(LIGHT_WIDTH, LIGHT_HEIGHT);
		}
		// whatever an earlier pass left enabled would blend into the captures (or with an uninitialized texture,
		// NaN into them) or clip them
		glDisable(GL_BLEND);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glDisable(GL_SCISSOR_TEST);
		glColorMask(true, true, true, true);
		long now = System.currentTimeMillis();
		if (now - lastUpdateMillis > STALE_MILLIS)
		{
			// After a pause the old capture is stale: capture at once. While the composite still shows it (a
			// hitch), blend from it as usual; once the light has eased away from it, start over unseen.
			if (weight <= 0)
			{
				shown = -1;
				blending = false;
			}
			schedule.invalidate();
		}
		lastUpdateMillis = now;
		// the clouds are seen from above the focal point (Clouds.setEye) at a fixed height, whatever the camera does
		double driftX = clouds.getOriginX() + clouds.getEyeX(), driftZ = clouds.getOriginZ() + clouds.getEyeZ();
		float cover = clouds.isEnabled() ? clouds.getCoverage() : 0;
		boolean changed = false;
		if (schedule.due(gameMinutes, now, interval, cover, overcast[0], overcast[1], day.sunAltitude, 0, driftX, driftZ,
			settings))
		{
			if (blending)
			{
				// the next blend starts from what is on screen, so it has to exist as a texture
				blend(lastBlend, fromUnit);
			}
			latest = roles(shown)[0];
			capture(clouds, cameraX, cameraY, cameraZ, day, overcast);
			mipmaps(latest);
			int[] r = roles(shown);
			latest = r[0];
			from = r[1];
			shown = r[2];
			blending = from != latest;
			interval = Schedule.interval(minutes, day.sunAltitude);
			schedule.captured(gameMinutes, now, cover, overcast[0], overcast[1], day.sunAltitude, 0, driftX, driftZ,
				settings);
			changed = true;
			captures++;
		}
		if (shown < 0)
		{
			return;
		}

		float t = 1;
		if (blending)
		{
			t = schedule.blend(gameMinutes, now, interval);
			lastBlend = t;
			if (t >= 1)
			{
				blending = false;
				shown = latest;
				changed = true;
			}
		}
		float irrBlend = blending ? t : 0;
		if (changed || Math.abs(irrBlend - irradianceBlend) >= IRRADIANCE_BLEND_STEP
			|| now - irradianceMillis >= IRRADIANCE_MILLIS)
		{
			irradiance(blending ? from : shown, blending ? latest : shown, irrBlend, fromUnit, day, overcast);
			irradianceBlend = irrBlend;
			irradianceMillis = now;
		}
	}

	/**
	 * Which texture each role takes after a capture, {latest, from, shown}, given the one on screen now (-1 for
	 * none). The capture blends in from what is on screen, whether that was a capture or a blend toward one, into
	 * the third texture; the very first capture is shown as it is.
	 */
	static int[] roles(int onScreen)
	{
		if (onScreen < 0)
		{
			return new int[]{0, 0, 0};
		}
		return new int[]{(onScreen + 1) % 3, onScreen, (onScreen + 2) % 3};
	}

	/** Bind the shown cache to {@code unit} and its irradiance to {@code unit + 1} for the composite. */
	void bind(int unit)
	{
		if (shown < 0)
		{
			return;
		}
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, textures[shown]);
		glActiveTexture(GL_TEXTURE0 + unit + 1);
		glBindTexture(GL_TEXTURE_2D, irradiance);
		glActiveTexture(GL_TEXTURE0);
	}

	static void unbind(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit + 1);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	/** Capture the sky into the latest texture. */
	private void capture(Clouds clouds, float cameraX, float cameraY, float cameraZ, DayCycle day, float[] overcast)
	{
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[latest], 0);
		glViewport(0, 0, width, height);
		glUseProgram(captureProgram);
		glUniform3f(uniCameraPos, cameraX, cameraY, cameraZ);
		glUniform3fv(uniSunDir, day.sunDir);
		glUniform3fv(uniMoonDir, day.moonDir);
		glUniform1f(uniSunAltitude, day.sunAltitude);
		glUniform2f(uniCacheSize, width, height);
		glUniform1i(uniSteps, LIGHT_STEPS);
		// no lightning: it flashes far faster than captures come
		glUniform3f(uniOvercast, overcast[0], overcast[1], 0);
		setLight(uniLight, overcast);
		clouds.apply(captureClouds);
		glDrawArrays(GL_TRIANGLES, 0, 3);
	}

	// sky.glsl's weatherLight, after weatherOvercast in the same array when there (Weather.getOvercast)
	private int uniLight = -1, uniIrradianceLight = -1;

	private static void setLight(int location, float[] overcast)
	{
		if (overcast.length >= 7)
		{
			glUniform4f(location, overcast[3], overcast[4], overcast[5], overcast[6]);
		}
		else
		{
			glUniform4f(location, 0, 0, 0, 0);
		}
	}

	private void blend(float t, int fromUnit)
	{
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[shown], 0);
		glViewport(0, 0, width, height);
		glUseProgram(blendProgram);
		glUniform1f(uniBlend, t);
		glActiveTexture(GL_TEXTURE0 + fromUnit);
		glBindTexture(GL_TEXTURE_2D, textures[from]);
		glActiveTexture(GL_TEXTURE0 + fromUnit + 1);
		glBindTexture(GL_TEXTURE_2D, textures[latest]);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + fromUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		mipmaps(shown);
	}

	private void irradiance(int a, int b, float t, int unit, DayCycle day, float[] overcast)
	{
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, irradiance, 0);
		glViewport(0, 0, IRRADIANCE_WIDTH, IRRADIANCE_HEIGHT);
		glUseProgram(irradianceProgram);
		glUniform2f(uniIrradianceSize, IRRADIANCE_WIDTH, IRRADIANCE_HEIGHT);
		glUniform1f(uniIrradianceBlend, t);
		// the mip about as wide as the irradiance: clouds between the sample directions still count
		glUniform1f(uniIrradianceLod, (float) (Math.log((double) width / IRRADIANCE_WIDTH) / Math.log(2)));
		glUniform3fv(uniIrradianceSunDir, day.sunDir);
		glUniform1f(uniIrradianceSunAltitude, day.sunAltitude);
		glUniform3f(uniIrradianceOvercast, overcast[0], overcast[1], 0);
		setLight(uniIrradianceLight, overcast);
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, textures[a]);
		glActiveTexture(GL_TEXTURE0 + unit + 1);
		glBindTexture(GL_TEXTURE_2D, textures[b]);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	private void mipmaps(int index)
	{
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, textures[index]);
		glGenerateMipmap(GL_TEXTURE_2D);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void init(int width, int height)
	{
		this.width = width;
		this.height = height;
		glGenTextures(textures);
		for (int tex : textures)
		{
			glBindTexture(GL_TEXTURE_2D, tex);
			// half float: linear light, dim at night
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_HALF_FLOAT, 0);
			glGenerateMipmap(GL_TEXTURE_2D);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			// around the azimuth it wraps; the zenith and the bottom row clamp
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		}
		irradiance = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, irradiance);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, IRRADIANCE_WIDTH, IRRADIANCE_HEIGHT, 0, GL_RGBA, GL_HALF_FLOAT, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);

		fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[0], 0);
		glDrawBuffers(GL_COLOR_ATTACHMENT0);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("Sky cache framebuffer incomplete, status 0x" + Integer.toHexString(status));
		}
	}
}
