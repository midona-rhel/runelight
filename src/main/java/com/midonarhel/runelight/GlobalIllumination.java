package com.midonarhel.runelight;

import com.midonarhel.runelight.template.Template;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import net.runelite.api.Scene;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Global illumination from irradiance probes laid over the ground and along the walls of the base scene (GiProbes),
 * each holding the sun or moon light bounced off what its rays hit, as directional irradiance, and how far it sees in
 * each axis direction, so a surface reads the light on its own side from probes it can actually see; interiors and
 * the undersides of things darken (the sky's own light, past roofs, overhangs and walls, comes from the composite)
 * and sunlit ground warms the shade beside it, the foot of a wall in particular.
 *
 * Everything heavy runs on a worker thread. After a region load the zones' triangles (captured as they upload,
 * GiCapture) are voxelized, per zone so reused zones keep theirs (GiVoxels), and every probe traces its rays once
 * (GiProbes). The skylight traces the same voxels (SkyGrid), so with the probes off the builds still voxelize the
 * scene for it and stop there. Relighting the probes from that hit cache (GiRelight) takes tens of milliseconds and
 * happens when the sky cache captures, the sun or moon has moved RELIGHT_DEGREES, the weather or settings change, or
 * every MAX_RELIGHT_MILLIS. The light it needs from the sky is drawn on the GPU into a tiny table (gi_sky_frag.glsl) and
 * read back without stalling. Each new set of probes is uploaded at once (a few
 * megabytes) and blended in over about the time between relights, like the sky cache, so nothing pops.
 *
 * On the GPU the only steady cost is the lookup in the half resolution AO pass (gi.glsl: the pixel's tile has a list
 * of the probes near it, of which it reads the nearest few, at most 57 texture fetches in all), whose result rides
 * along in the AO target to the composite (halfres.glsl). Outside the base scene, underground, and until the first
 * probes are lit, the composite keeps its plain ambient light.
 */
class GlobalIllumination
{
	private static final Logger log = LoggerFactory.getLogger(GlobalIllumination.class);

	static final Shader SKY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "gi_sky_frag.glsl");

	// the probe blend is the sky cache's: a texel for texel mix of two textures
	static final Shader BLEND_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "sky_cache_blend_frag.glsl");

	// TEXELS texels per probe, side by side, ROW probes to a row: probe i at texels (i % ROW * TEXELS + k, i / ROW). The
	// first three hold the light (GiRelight.SH floats), the next three how far it sees (GiProbes.VISIBILITY floats),
	// the last where it stands (see texels); a slot without a usable probe is all zero. The size is the same for every
	// scene, which is what lets the blend mix two lit sets texel for texel.
	static final int TEXELS = (GiRelight.SH + GiProbes.VISIBILITY) / 4 + 1;
	static final int ROW = 256;
	static final int TEX_W = ROW * TEXELS;
	static final int TEX_H = (GiProbes.MAX_PROBES + ROW) / ROW;
	// a probe's x and z are stored from the corner of the CHUNK-unit square of the base scene it is in, which half
	// floats keep to a half unit, and the square's coordinates (x + z * CHUNKS) with them
	static final int CHUNK = 1024;
	static final int CHUNKS = 16;
	// the tile lists (GiProbes.lists) as texels of LIST / 4 indices, a tile's side by side, the tile rows down
	static final int INDEX_TEXELS = GiProbes.LIST / 4;
	static final int INDEX_W = GiProbes.SIZE * INDEX_TEXELS;
	static final int SKY_ROWS = GiRelight.SKY_H + 1;

	// relight when the light that casts shadows has turned this far
	static final float RELIGHT_DEGREES = 2;
	static final long MIN_RELIGHT_MILLIS = 1000;
	static final long MAX_RELIGHT_MILLIS = 30_000;
	// a new set of probes blends in over the time since the last, within these bounds
	static final float BLEND_SECONDS = 2, MAX_BLEND_SECONDS = 8;
	// the composite eases between its plain ambient and the probes over this long
	static final float WEIGHT_SECONDS = 1.5f;
	// changed zones (doors, trees cut down) are rebuilt once they have stopped changing for this long, and at most
	// this often
	static final long REBUILD_DELAY_MILLIS = 2000;
	static final long MIN_REBUILD_MILLIS = 10_000;
	// light bounces the first relight of a new scene computes, with no previous light to build on
	static final int FIRST_BOUNCES = 3;

	/** Freshly compiled programs, so a hot reload can compile everything before replacing anything. */
	static final class Programs
	{
		final int sky, blend;

		Programs(int sky, int blend)
		{
			this.sky = sky;
			this.blend = blend;
		}
	}

	/** A lit set of probes from the worker. */
	private static final class Lit
	{
		int generation;
		int baseX, baseY;
		boolean instance;
		short[] index;
		short[] texels;
	}

	/** What the worker lights the probes with. */
	private static final class LightInput
	{
		float[] table;
		float[] casterDir;
		boolean bounce;
	}

	private int skyProgram, blendProgram;
	private int uniSkySunDir, uniSkySunAltitude, uniSkySunIsCaster, uniSkyOvercast, uniSkyLight, uniSkyCacheMode,
		uniSkyCacheWeight;
	private int uniBlend;
	private int uniAoEnabled = -1, uniAoCamera = -1;

	private final int[] probeTextures = new int[3];
	private int indexTexture, probeFbo, skyTexture, skyFbo, pbo;
	private ShortBuffer staging;
	private final float[] table = new float[GiRelight.TABLE_FLOATS];

	// roles of the probe textures, as in SkyCache: the latest light, the one blending into it, and the one shown
	private int latest = -1, from = -1, shown = -1;
	private boolean blending;
	private long blendStart, blendMillis;
	private long lastLitMillis;
	// what the shown probes belong to
	private int shownGeneration = -1;
	private int shownBaseX, shownBaseY;
	private boolean shownInstance;
	private float weight;
	private long weightMillis;
	// the config switch last frame
	private boolean wasEnabled;

	// relight scheduling
	private boolean readbackWanted;
	private boolean readbackPending;
	private long fence;
	private final float[] readbackCasterDir = new float[3];
	private boolean readbackBounce;
	private boolean relit;
	private final float[] lastCasterDir = new float[3];
	private boolean lastSunIsCaster;
	private int lastSkyCaptures;
	private int lastSettings;
	private long lastRelightMillis;

	// builds
	private boolean buildRequested;
	private long buildAfterMillis;
	private long lastBuildMillis;
	private volatile int generation;

	// worker
	private ExecutorService worker;
	private ForkJoinPool pool;
	private final AtomicInteger jobs = new AtomicInteger();
	private volatile Lit lit;
	private volatile int builtGeneration = -1;
	// worker-owned
	private GiProbes probes;
	private int probesGeneration = -1;
	private int probesBaseX, probesBaseY;
	private boolean probesInstance;
	private float[] lastLight;
	private int lastLightGeneration = -1;
	private LightInput lastInput;
	// whether the last build submitted traces the probes, or only voxelizes the scene for the skylight
	private boolean submittedProbes;
	private volatile Voxels voxels;

	/** The scene's voxels as a build left them, for the skylight's grid (SkyGrid). */
	static final class Voxels
	{
		final GiGrid grid;
		SkyTerrain terrain;
		final int baseX, baseY;
		final boolean instance;
		// the scene's corner heights, [level][extended x][extended z]
		final int[][][] heights;

		Voxels(GiGrid grid, int baseX, int baseY, boolean instance, int[][][] heights)
		{
			this.grid = grid;
			this.baseX = baseX;
			this.baseY = baseY;
			this.instance = instance;
			this.heights = heights;
		}
	}

	/** The newest voxels, null before the first build; a new object after every build. */
	Voxels getVoxels()
	{
		return voxels;
	}

	Programs compile(Template template) throws ShaderException
	{
		int s = SKY_PROGRAM.compile(template);
		try
		{
			return new Programs(s, BLEND_PROGRAM.compile(template));
		}
		catch (ShaderException ex)
		{
			glDeleteProgram(s);
			throw ex;
		}
	}

	/**
	 * Replace the programs. The sky pass reads the sky cache and its irradiance from skyCacheUnit and
	 * skyCacheUnit + 1.
	 */
	void setPrograms(Programs p, int skyCacheUnit, int blendUnit)
	{
		freePrograms();
		skyProgram = p.sky;
		blendProgram = p.blend;
		uniSkySunDir = glGetUniformLocation(skyProgram, "sunDir");
		uniSkySunAltitude = glGetUniformLocation(skyProgram, "sunAltitude");
		uniSkySunIsCaster = glGetUniformLocation(skyProgram, "sunIsCaster");
		uniSkyOvercast = glGetUniformLocation(skyProgram, "weatherOvercast");
		uniSkyLight = glGetUniformLocation(skyProgram, "weatherLight");
		uniSkyCacheMode = glGetUniformLocation(skyProgram, "skyCacheMode");
		uniSkyCacheWeight = glGetUniformLocation(skyProgram, "skyCacheWeight");
		glUseProgram(skyProgram);
		glUniform1i(glGetUniformLocation(skyProgram, "skyCache"), skyCacheUnit);
		glUniform1i(glGetUniformLocation(skyProgram, "skyIrradiance"), skyCacheUnit + 1);
		uniBlend = glGetUniformLocation(blendProgram, "blend");
		glUseProgram(blendProgram);
		glUniform1i(glGetUniformLocation(blendProgram, "cacheFrom"), blendUnit);
		glUniform1i(glGetUniformLocation(blendProgram, "cacheTo"), blendUnit + 1);
		glUseProgram(0);
		// a new shader may compute a different sky
		relit = false;
	}

	void freePrograms()
	{
		glDeleteProgram(skyProgram);
		glDeleteProgram(blendProgram);
		skyProgram = blendProgram = 0;
	}

	/** Look up the AO program's uniforms; the probes go on probeUnit, the tile lists on probeUnit + 1. */
	void locateAo(int program, int probeUnit)
	{
		uniAoEnabled = glGetUniformLocation(program, "giEnabled");
		uniAoCamera = glGetUniformLocation(program, "giCamera");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "giProbes"), probeUnit);
		glUniform1i(glGetUniformLocation(program, "giIndex"), probeUnit + 1);
		glUseProgram(0);
	}

	/** A composite program's giWeight uniform. */
	static int locateComposite(int program)
	{
		return glGetUniformLocation(program, "giWeight");
	}

	/** GL resources and the worker. */
	void free()
	{
		if (worker != null)
		{
			worker.shutdownNow();
			pool.shutdownNow();
			worker = null;
			pool = null;
		}
		if (probeFbo != 0)
		{
			glDeleteFramebuffers(probeFbo);
			glDeleteFramebuffers(skyFbo);
			glDeleteTextures(probeTextures);
			glDeleteTextures(indexTexture);
			glDeleteTextures(skyTexture);
			glDeleteBuffers(pbo);
			probeFbo = skyFbo = indexTexture = skyTexture = pbo = 0;
			probeTextures[0] = probeTextures[1] = probeTextures[2] = 0;
		}
		if (fence != 0)
		{
			glDeleteSync(fence);
			fence = 0;
		}
		readbackPending = readbackWanted = false;
		latest = from = shown = -1;
		blending = false;
		shownGeneration = -1;
		weight = 0;
		relit = false;
		lit = null;
		jobs.set(0);
		builtGeneration = -1;
		probes = null;
		probesGeneration = -1;
		lastLight = null;
		lastLightGeneration = -1;
		lastInput = null;
		buildRequested = true;
	}

	/** A new top level scene was swapped in: build its probes. */
	void sceneSwapped()
	{
		buildRequested = true;
		buildAfterMillis = 0;
	}

	/** A zone was rebuilt: rebuild the probes once the changes settle. */
	void zonesChanged()
	{
		buildRequested = true;
		long now = System.currentTimeMillis();
		buildAfterMillis = Math.max(now + REBUILD_DELAY_MILLIS, lastBuildMillis + MIN_REBUILD_MILLIS);
	}

	/** How much of the probes' light is shown, 0 when they are off. */
	float shownWeight()
	{
		return isActive() ? weight : 0;
	}

	/** Whether the AO pass has probes to look up. */
	boolean isActive()
	{
		return weight > 0 && shown >= 0;
	}

	/** Builds submitted so far; a change means a build started. */
	int builds()
	{
		return generation;
	}

	/** The build whose probes are on the GPU; a change means an upload happened. */
	int shownBuild()
	{
		return shownGeneration;
	}

	/**
	 * Once a frame before the AO pass, on the client thread with the GL context: start builds, hand finished
	 * readbacks to the worker, upload and blend new probes and decide whether to relight. Changes the framebuffer,
	 * viewport and program; expects a VAO bound.
	 *
	 * @param enabled whether the probes run
	 * @param voxelsWanted whether the scene's voxels are wanted without the probes too (the skylight): builds then
	 *                     voxelize the scene and stop there
	 * @param zones the top level scene's zones, [x][z] in extended zone coordinates
	 * @param water the scene's water map, null with none
	 * @param walls the walls of this very scene, null until they are known (the probes' build waits for them)
	 * @param settings hash of what else the probes' light depends on
	 * @param blendUnit, blendUnit + 1: free units for the blend
	 */
	void prepare(boolean enabled, boolean voxelsWanted, boolean outdoors, Scene scene, Zone[][] zones, WaterMap water,
		WallMap walls, DayCycle day, int skyCaptures, int settings, int blendUnit)
	{
		long now = System.currentTimeMillis();
		float dt = Math.max(0, Math.min(0.1f, (now - weightMillis) / 1000f));
		weightMillis = now;
		// until the new scene's probes are lit the last ones stay, moved to where they belong in it; an instance's
		// coordinates have nothing to do with the last scene's
		boolean fits = scene != null && shownInstance == scene.isInstance()
			&& (!shownInstance || scene.getBaseX() == shownBaseX && scene.getBaseY() == shownBaseY);
		boolean toggled = enabled != wasEnabled;
		if (toggled)
		{
			wasEnabled = enabled;
			if (enabled && !submittedProbes)
			{
				// the last build only voxelized the scene
				buildRequested = true;
				buildAfterMillis = 0;
			}
			if (enabled && shown >= 0)
			{
				// relight now rather than when the schedule comes round; the probes there are show meanwhile
				relit = false;
				lastRelightMillis = 0;
			}
		}
		boolean use = enabled && outdoors && shown >= 0 && fits;
		// the switch takes effect at once, both ways; only a change of place or of the light eases
		weight = toggled ? (use ? 1 : 0)
			: use ? Math.min(1, weight + dt / WEIGHT_SECONDS) : Math.max(0, weight - dt / WEIGHT_SECONDS);
		if (!enabled && !voxelsWanted)
		{
			return;
		}
		if (buildRequested && now >= buildAfterMillis && scene != null && zones != null && (walls != null || !enabled))
		{
			buildRequested = false;
			lastBuildMillis = now;
			submittedProbes = enabled;
			submitBuild(scene, zones, water, walls, enabled);
		}
		if (!enabled)
		{
			return;
		}
		if (probeFbo == 0)
		{
			init();
		}
		glDisable(GL_BLEND);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glDisable(GL_SCISSOR_TEST);
		glColorMask(true, true, true, true);

		pollReadback();

		Lit l = lit;
		if (l != null)
		{
			lit = null;
			if (l.generation != shownGeneration)
			{
				// new probes in a new place: shown as they are, there is nothing to blend from
				log.debug("Look: GI probes replaced, generation {} -> {}, base ({}, {}) -> ({}, {})", shownGeneration,
					l.generation, shownBaseX, shownBaseY, l.baseX, l.baseY);
				int target = shown < 0 ? 0 : (shown + 1) % 3;
				uploadProbes(l, target);
				uploadIndex(l.index);
				latest = from = shown = target;
				blending = false;
				shownGeneration = l.generation;
				shownBaseX = l.baseX;
				shownBaseY = l.baseY;
				shownInstance = l.instance;
				lastLitMillis = now;
			}
			else
			{
				// a blend in progress stops where it is: the shown texture holds it
				int target = (shown + 1) % 3;
				uploadProbes(l, target);
				from = shown;
				latest = target;
				shown = 3 - from - latest;
				blending = true;
				blendStart = now;
				long gap = now - lastLitMillis;
				blendMillis = (long) (Math.max(BLEND_SECONDS, Math.min(MAX_BLEND_SECONDS, gap / 1000f)) * 1000);
				lastLitMillis = now;
			}
		}
		if (blending)
		{
			float t = (now - blendStart) / (float) blendMillis;
			if (t >= 1)
			{
				blending = false;
				shown = latest;
			}
			else
			{
				blend(t, blendUnit);
			}
		}

		if (!outdoors || readbackPending || readbackWanted || jobs.get() > 0)
		{
			return;
		}
		boolean newProbes = builtGeneration > shownGeneration && lit == null;
		boolean due = !relit || newProbes || settings != lastSettings || day.sunIsCaster != lastSunIsCaster
			|| skyCaptures != lastSkyCaptures || now - lastRelightMillis >= MAX_RELIGHT_MILLIS
			|| angle(day.casterDir, lastCasterDir) >= RELIGHT_DEGREES;
		if (builtGeneration >= 0 && due && (newProbes || now - lastRelightMillis >= MIN_RELIGHT_MILLIS))
		{
			readbackWanted = true;
			relit = true;
			lastSettings = settings;
			lastSunIsCaster = day.sunIsCaster;
			lastSkyCaptures = skyCaptures;
			lastRelightMillis = now;
			System.arraycopy(day.casterDir, 0, lastCasterDir, 0, 3);
		}
	}

	static float angle(float[] a, float[] b)
	{
		float d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
		float la = (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
		float lb = (float) Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
		if (la < 1e-6f || lb < 1e-6f)
		{
			return 180;
		}
		return (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, d / (la * lb)))));
	}

	/**
	 * After the sky cache has updated (and is bound on the unit given to setPrograms): draw the sky table for a
	 * relight if one is wanted and start reading it back. Changes the framebuffer, viewport and program.
	 *
	 * @param overcast Weather.getOvercast's 7 values
	 */
	void afterSky(DayCycle day, float[] overcast, float skyCacheWeight, boolean bounce)
	{
		if (!readbackWanted || readbackPending || skyProgram == 0 || skyFbo == 0)
		{
			return;
		}
		readbackWanted = false;
		glBindFramebuffer(GL_FRAMEBUFFER, skyFbo);
		glViewport(0, 0, GiRelight.SKY_W, SKY_ROWS);
		glUseProgram(skyProgram);
		glUniform3fv(uniSkySunDir, day.sunDir);
		glUniform1f(uniSkySunAltitude, day.sunAltitude);
		glUniform1i(uniSkySunIsCaster, day.sunIsCaster ? 1 : 0);
		// no lightning: it flashes far faster than relights come
		glUniform3f(uniSkyOvercast, overcast[0], overcast[1], 0);
		glUniform4f(uniSkyLight, overcast[3], overcast[4], overcast[5], overcast[6]);
		glUniform1i(uniSkyCacheMode, skyCacheWeight > 0 ? 1 : 0);
		glUniform1f(uniSkyCacheWeight, skyCacheWeight);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glReadBuffer(GL_COLOR_ATTACHMENT0);
		glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
		glReadPixels(0, 0, GiRelight.SKY_W, SKY_ROWS, GL_RGBA, GL_FLOAT, 0L);
		glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		readbackPending = true;
		System.arraycopy(day.casterDir, 0, readbackCasterDir, 0, 3);
		readbackBounce = bounce;
	}

	private void pollReadback()
	{
		if (!readbackPending)
		{
			return;
		}
		int r = glClientWaitSync(fence, 0, 0);
		if (r == GL_TIMEOUT_EXPIRED)
		{
			return;
		}
		glDeleteSync(fence);
		fence = 0;
		readbackPending = false;
		if (r == GL_WAIT_FAILED)
		{
			return;
		}
		glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
		ByteBuffer mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, (long) table.length * Float.BYTES, GL_MAP_READ_BIT);
		if (mapped != null)
		{
			FloatBuffer f = mapped.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
			f.get(table);
			glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
		}
		glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
		if (mapped == null)
		{
			return;
		}
		LightInput in = new LightInput();
		in.table = table.clone();
		in.casterDir = readbackCasterDir.clone();
		in.bounce = readbackBounce;
		submit(() -> relight(in));
	}

	/**
	 * Before the AO pass: bind the shown probes and set the AO program's uniforms (with it in use).
	 *
	 * @param sceneBaseX, sceneBaseY the current scene's base, world tiles: the shown probes may be from the last
	 *                   one until the new ones are lit
	 */
	void bindAo(int probeUnit, float cameraX, float cameraY, float cameraZ, int sceneBaseX, int sceneBaseY)
	{
		boolean on = isActive();
		glUniform1i(uniAoEnabled, on ? 1 : 0);
		if (!on)
		{
			return;
		}
		float cornerX = (shownBaseX - sceneBaseX) * (float) GiProbes.TILE;
		float cornerZ = (shownBaseY - sceneBaseY) * (float) GiProbes.TILE;
		glUniform3f(uniAoCamera, cameraX - cornerX, cameraY, cameraZ - cornerZ);
		glActiveTexture(GL_TEXTURE0 + probeUnit);
		glBindTexture(GL_TEXTURE_2D, probeTextures[shown]);
		glActiveTexture(GL_TEXTURE0 + probeUnit + 1);
		glBindTexture(GL_TEXTURE_2D, indexTexture);
		glActiveTexture(GL_TEXTURE0);
	}

	static void unbind(int probeUnit)
	{
		glActiveTexture(GL_TEXTURE0 + probeUnit + 1);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + probeUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	/** With a composite program in use: how much of the probes' bounced light it adds (its locateComposite). */
	void setComposite(int location, float strength)
	{
		glUniform1f(location, isActive() ? weight * strength : 0);
	}

	// ---------------------------------------------------------------------------------------------------------
	// GL

	private void init()
	{
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glGenTextures(probeTextures);
		for (int tex : probeTextures)
		{
			texture(tex, GL_RGBA16F, TEX_W, TEX_H, GL_RGBA, GL_HALF_FLOAT);
		}
		indexTexture = glGenTextures();
		texture(indexTexture, GL_RGBA16UI, INDEX_W, GiProbes.SIZE, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT);
		skyTexture = glGenTextures();
		texture(skyTexture, GL_RGBA32F, GiRelight.SKY_W, SKY_ROWS, GL_RGBA, GL_FLOAT);
		glBindTexture(GL_TEXTURE_2D, 0);

		probeFbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, probeFbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, probeTextures[0], 0);
		glDrawBuffers(GL_COLOR_ATTACHMENT0);
		check("probe");
		skyFbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, skyFbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, skyTexture, 0);
		glDrawBuffers(GL_COLOR_ATTACHMENT0);
		check("sky");
		glBindFramebuffer(GL_FRAMEBUFFER, 0);

		pbo = glGenBuffers();
		glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
		glBufferData(GL_PIXEL_PACK_BUFFER, (long) GiRelight.TABLE_FLOATS * Float.BYTES, GL_STREAM_READ);
		glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
		staging = BufferUtils.createShortBuffer(TEX_W * TEX_H * 4);
	}

	private static void texture(int tex, int internal, int w, int h, int format, int type)
	{
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexImage2D(GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, (ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}

	private static void check(String what)
	{
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			glBindFramebuffer(GL_FRAMEBUFFER, 0);
			throw new RuntimeException("GI " + what + " framebuffer incomplete, status 0x" + Integer.toHexString(status));
		}
	}

	private void uploadProbes(Lit l, int target)
	{
		staging.clear();
		staging.put(l.texels);
		staging.flip();
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, probeTextures[target]);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, TEX_W, TEX_H, GL_RGBA, GL_HALF_FLOAT, staging);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void uploadIndex(short[] index)
	{
		ShortBuffer b = BufferUtils.createShortBuffer(index.length);
		b.put(index).flip();
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, indexTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, INDEX_W, GiProbes.SIZE, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT, b);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void blend(float t, int unit)
	{
		if (blendProgram == 0)
		{
			return;
		}
		glBindFramebuffer(GL_FRAMEBUFFER, probeFbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, probeTextures[shown], 0);
		glViewport(0, 0, TEX_W, TEX_H);
		glUseProgram(blendProgram);
		glUniform1f(uniBlend, t);
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, probeTextures[from]);
		glActiveTexture(GL_TEXTURE0 + unit + 1);
		glBindTexture(GL_TEXTURE_2D, probeTextures[latest]);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	// ---------------------------------------------------------------------------------------------------------
	// worker

	private void submit(Runnable job)
	{
		if (worker == null)
		{
			worker = Executors.newSingleThreadExecutor(r ->
			{
				Thread t = new Thread(r, "RuneLight GI");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			});
			pool = new ForkJoinPool(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
		}
		jobs.incrementAndGet();
		worker.execute(() ->
		{
			try
			{
				job.run();
			}
			catch (Throwable ex)
			{
				log.warn("Global illumination job failed", ex);
			}
			finally
			{
				jobs.decrementAndGet();
			}
		});
	}

	private GiProbes.ParallelFor parallel()
	{
		ForkJoinPool p = pool;
		return (n, body) ->
		{
			try
			{
				p.submit(() -> IntStream.range(0, n).parallel().forEach(body)).get();
			}
			catch (InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				throw new RuntimeException(ex);
			}
			catch (java.util.concurrent.ExecutionException ex)
			{
				throw new RuntimeException(ex.getCause());
			}
		};
	}

	/** Copy what the build needs from the scene, on the client thread, and queue it. */
	private void submitBuild(Scene scene, Zone[][] zones, WaterMap waterMap, WallMap walls, boolean withProbes)
	{
		int g = ++generation;
		int baseZone = RuneLightPlugin.SCENE_OFFSET >> 3;
		int count = GiProbes.SIZE / 8 + 2;
		Zone[] list = new Zone[count * count];
		int[] zx = new int[list.length], zz = new int[list.length];
		int n = 0;
		for (int x = baseZone - 1; x < baseZone - 1 + count; ++x)
		{
			for (int z = baseZone - 1; z < baseZone - 1 + count; ++z)
			{
				if (x >= 0 && z >= 0 && x < zones.length && z < zones[x].length && zones[x][z] != null)
				{
					list[n] = zones[x][z];
					zx[n] = x;
					zz[n] = z;
					n++;
				}
			}
		}
		GiProbes.Input in = new GiProbes.Input();
		GiWater water = GiWater.of(waterMap, scene.getTileHeights(), RuneLightPlugin.SCENE_OFFSET);
		// the rest is for the probes only
		in.ground = withProbes ? GiProbes.groundHeights(scene.getTileHeights(), RuneLightPlugin.SCENE_OFFSET) : null;
		in.walls = walls;
		int baseX = scene.getBaseX(), baseY = scene.getBaseY();
		boolean instance = scene.isInstance();
		int[][][] heights = scene.getTileHeights();
		SkyTerrain terrain = SkyTerrain.capture(scene, RuneLightPlugin.SCENE_OFFSET);
		int zones2 = n;
		submit(() -> build(g, list, zx, zz, zones2, baseZone, in, water, baseX, baseY, instance, heights, withProbes,
			terrain));
	}

	private void build(int g, Zone[] list, int[] zx, int[] zz, int n, int baseZone, GiProbes.Input in, GiWater water,
		int baseX, int baseY, boolean instance, int[][][] heights, boolean withProbes, SkyTerrain terrain)
	{
		if (g != generation)
		{
			// a newer build is already queued
			return;
		}
		long start = System.nanoTime();
		GiVoxels.Zone[] voxels = new GiVoxels.Zone[n];
		AtomicInteger voxelized = new AtomicInteger();
		GiProbes.ParallelFor parallel = parallel();
		parallel.run(n, i ->
		{
			// zones keep their voxels, so a zone reused by the next region load isn't voxelized again
			Zone z = list[i];
			if (z.giVoxels == null && z.giCapture != null)
			{
				z.giVoxels = GiVoxels.voxelize(z.giCapture);
				z.giCapture = null;
				voxelized.incrementAndGet();
			}
			voxels[i] = z.giVoxels;
		});
		long voxelTime = System.nanoTime();
		GiGrid grid = GiGrid.assemble(voxels, zx, zz, baseZone, water);
		Voxels result = new Voxels(grid, baseX, baseY, instance, heights);
		result.terrain = terrain;
		this.voxels = result;
		long gridTime = System.nanoTime();
		log.info("Voxel build {}: {} new / {} zones, voxelize {} ms, assemble {} ms, total {} ms; {}-unit cells, {}x{}x{}, {} MiB cells",
			g, voxelized.get(), n, (voxelTime - start) / 1_000_000.0, (gridTime - voxelTime) / 1_000_000.0,
			(gridTime - start) / 1_000_000.0, GiGrid.VOXEL, grid.nx, grid.ny, grid.nz,
			grid.cells.length * 4L / (1024.0 * 1024.0));
		if (!withProbes)
		{
			return;
		}
		GiProbes p = GiProbes.build(grid, in, parallel);
		probes = p;
		probesGeneration = g;
		probesBaseX = baseX;
		probesBaseY = baseY;
		probesInstance = instance;
		lastLight = null;
		builtGeneration = g;
		int activeProbes = 0;
		for (boolean valid : p.valid)
		{
			if (valid) ++activeProbes;
		}
		log.debug("GI probes: {} of {} candidate spots usable", activeProbes, p.count);
		log.debug("GI build {}: voxelized {} zones in {} ms, grid {}x{}x{}, {} hit voxels, traced in {} ms", g, voxelized,
			(voxelTime - start) / 1_000_000, grid.nx, grid.ny, grid.nz, p.hits, (System.nanoTime() - voxelTime) / 1_000_000);
		if (lastInput != null)
		{
			relight(lastInput);
		}
	}

	private void relight(LightInput in)
	{
		lastInput = in;
		GiProbes p = probes;
		if (p == null)
		{
			return;
		}
		long start = System.nanoTime();
		boolean first = lastLightGeneration != probesGeneration || lastLight == null;
		GiRelight.Light light = GiRelight.Light.fromTable(in.table, in.casterDir, in.bounce);
		GiRelight.Result result = GiRelight.relight(p, light, first ? null : lastLight, first ? FIRST_BOUNCES : 1,
			parallel());
		// the next bounces build on the whole light, whichever part is shown
		lastLight = result.light;
		lastLightGeneration = probesGeneration;
		Lit l = new Lit();
		l.generation = probesGeneration;
		l.baseX = probesBaseX;
		l.baseY = probesBaseY;
		l.instance = probesInstance;
		l.index = indexTexels(p.lists);
		l.texels = texels(p.count, result.shown, p.visibility, p.position);
		lit = l;
		log.trace("GI relight in {} ms", (System.nanoTime() - start) / 1_000_000);
	}


	/**
	 * The probes as the probe texture's half floats. Probe i lies at texels (i % ROW * TEXELS + 0..TEXELS-1, i / ROW):
	 * its light, then its visibility, then where it stands: x and z from the corner of its CHUNK square, y, and the
	 * square (x + z * CHUNKS). Probes that aren't usable have no visibility, and are in no tile's list.
	 */
	static short[] texels(int count, float[] light, float[] visibility, float[] position)
	{
		short[] out = new short[TEX_W * TEX_H * 4];
		for (int i = 0; i < count; ++i)
		{
			int at = ((i / ROW) * TEX_W + i % ROW * TEXELS) * 4;
			for (int k = 0; k < GiRelight.SH; ++k)
			{
				out[at + k] = toHalf(light[i * GiRelight.SH + k]);
			}
			for (int k = 0; k < GiProbes.VISIBILITY; ++k)
			{
				out[at + GiRelight.SH + k] = toHalf(visibility[i * GiProbes.VISIBILITY + k]);
			}
			int p = at + GiRelight.SH + GiProbes.VISIBILITY;
			int cx = (int) Math.floor(position[i * 3] / CHUNK), cz = (int) Math.floor(position[i * 3 + 2] / CHUNK);
			out[p] = toHalf(position[i * 3] - cx * CHUNK);
			out[p + 1] = toHalf(position[i * 3 + 1]);
			out[p + 2] = toHalf(position[i * 3 + 2] - cz * CHUNK);
			out[p + 3] = toHalf(cx + cz * CHUNKS);
		}
		return out;
	}

	/** The tile lists as the index texture's unsigned shorts: the same order, a tile's LIST indices after another's. */
	static short[] indexTexels(int[] lists)
	{
		short[] out = new short[lists.length];
		for (int i = 0; i < lists.length; ++i)
		{
			out[i] = (short) lists[i];
		}
		return out;
	}

	/** IEEE half float bits of f, rounded to nearest; for the probe textures. */
	static short toHalf(float f)
	{
		int bits = Float.floatToIntBits(f);
		int sign = bits >>> 16 & 0x8000;
		int exp = (bits >>> 23 & 0xff) - 127 + 15;
		int mant = bits & 0x7fffff;
		if (Float.isNaN(f))
		{
			return (short) (sign | 0x7e00);
		}
		if (exp >= 31)
		{
			return (short) (sign | 0x7c00);
		}
		if (exp <= 0)
		{
			if (exp < -10)
			{
				return (short) sign;
			}
			mant |= 0x800000;
			int shift = 14 - exp;
			int half = mant >> shift;
			if ((mant >> (shift - 1) & 1) != 0)
			{
				half++;
			}
			return (short) (sign | half);
		}
		int half = sign | exp << 10 | mant >> 13;
		if ((mant & 0x1000) != 0)
		{
			// rounding up may carry into the exponent, which is still correct (up to infinity)
			half++;
		}
		return (short) half;
	}

	static float fromHalf(short h)
	{
		int s = (h & 0x8000) << 16;
		int e = h >> 10 & 0x1f;
		int m = h & 0x3ff;
		if (e == 0)
		{
			float v = m / 1024f / 16384f;
			return s != 0 ? -v : v;
		}
		if (e == 31)
		{
			return Float.intBitsToFloat(s | 0x7f800000 | m << 13);
		}
		return Float.intBitsToFloat(s | (e - 15 + 127) << 23 | m << 13);
	}
}
