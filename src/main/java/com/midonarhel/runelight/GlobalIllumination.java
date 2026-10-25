package com.midonarhel.runelight;

import com.midonarhel.runelight.template.Template;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
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
 * Global illumination as a colour transfer through a volume of tint: every surface of the base scene gives the air in
 * front of it its colour, as much as it is lit by the sun or moon and the sky it sees, and less and less the farther from
 * it (GiEmitters, GiRelight). A pixel looks the volume up a little in front of itself (gi.glsl), so a wall greens at its
 * foot beside sunlit grass and no higher than the grass' colour reaches, and the composite tints its light with what it
 * reads (the light itself, past roofs, overhangs and walls, comes from the composite). The colour goes through air only,
 * so interiors and the far sides of walls stay apart from it.
 *
 * Everything heavy runs on a worker thread. After a region load the zones' triangles (captured as they upload,
 * GiCapture) are voxelized, per zone so reused zones keep theirs (GiVoxels), and the faces that give colour and the
 * voxels they give it to are found once (GiEmitters). The skylight traces the same voxels (SkyGrid), so with the
 * global illumination off the builds still voxelize the scene for it and stop there. Relighting (GiRelight) takes
 * tens of milliseconds and happens when the sky cache captures, the sun or moon has moved RELIGHT_DEGREES, the weather
 * or settings change, or every MAX_RELIGHT_MILLIS. The light it needs is drawn on the GPU into a tiny table
 * (gi_sky_frag.glsl) and read back without stalling. The volume is a 3D texture two fields tall (flat surfaces' colour, then
 * standing ones'), a few megabytes of bytes; each new one is uploaded at once and blended with the last in the lookup over
 * about the time between relights, so nothing pops.
 *
 * On the GPU the only steady cost is the lookup in the half resolution AO pass (gi.glsl: one filtered fetch of the
 * volume for a pixel on a wall or a floor, two where it is on a slope between them, and twice that while a new volume
 * blends in), whose result rides along in the AO target to the composite (halfres.glsl). Outside the base scene,
 * underground, and until the first volume is lit, the composite keeps its plain ambient light.
 */
class GlobalIllumination
{
	private static final Logger log = LoggerFactory.getLogger(GlobalIllumination.class);

	static final Shader SKY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "gi_sky_frag.glsl");

	static final int SKY_ROWS = GiRelight.SKY_H + 1;

	// relight when the light that casts shadows has turned this far
	static final float RELIGHT_DEGREES = 2;
	static final long MIN_RELIGHT_MILLIS = 1000;
	static final long MAX_RELIGHT_MILLIS = 30_000;
	// a new volume blends in over the time since the last, within these bounds
	static final float BLEND_SECONDS = 2, MAX_BLEND_SECONDS = 8;
	// the composite eases between its plain ambient and the volume's tint over this long
	static final float WEIGHT_SECONDS = 1.5f;
	// changed zones (doors, trees cut down) are rebuilt once they have stopped changing for this long, and at most
	// this often
	static final long REBUILD_DELAY_MILLIS = 2000;
	static final long MIN_REBUILD_MILLIS = 10_000;

	/** Freshly compiled programs, so a hot reload can compile everything before replacing anything. */
	static final class Programs
	{
		final int sky;

		Programs(int sky)
		{
			this.sky = sky;
		}
	}

	/** A lit volume from the worker, with what it belongs to. */
	private static final class Lit
	{
		int generation;
		int baseX, baseY;
		boolean instance;
		// the grid's size in voxels and the layer its first holds
		int nx, ny, nz, layer0;
		byte[] texels;
	}

	/** What the worker lights the volume with. */
	private static final class LightInput
	{
		float[] table;
		float[] casterDir;
		boolean bounce;
	}

	private int skyProgram;
	private int uniSkySunDir, uniSkySunAltitude, uniSkySunIsCaster, uniSkyOvercast, uniSkyLight, uniSkyCacheMode,
		uniSkyCacheWeight;
	private int uniAoEnabled = -1, uniAoCamera = -1, uniAoGrid = -1, uniAoLayer0 = -1, uniAoBlend = -1;

	private final int[] volumeTextures = new int[2];
	private int skyTexture, skyFbo, pbo;
	private ByteBuffer staging;
	private final float[] table = new float[GiRelight.TABLE_FLOATS];

	// The volume shown is volumeTextures[shown]; while a newer one blends in over it, volumeTextures[next] is that one and
	// blendT is how far in it is, 0 to 1.
	private int shown = -1, next = -1;
	private boolean blending;
	private float blendT;
	private long blendStart, blendMillis;
	private long lastLitMillis;
	// what the shown volume belongs to
	private int shownGeneration = -1;
	private int shownBaseX, shownBaseY;
	private boolean shownInstance;
	private int shownNx, shownNy, shownNz, shownLayer0;
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
	private GiEmitters emitters;
	private int emittersGeneration = -1;
	private int emittersBaseX, emittersBaseY;
	private boolean emittersInstance;
	private LightInput lastInput;
	// whether the last build submitted finds the emitters, or only voxelizes the scene for the skylight
	private boolean submittedEmitters;
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
		return new Programs(SKY_PROGRAM.compile(template));
	}

	/**
	 * Replace the programs. The sky pass reads the sky cache and its irradiance from skyCacheUnit and
	 * skyCacheUnit + 1.
	 */
	void setPrograms(Programs p, int skyCacheUnit)
	{
		freePrograms();
		skyProgram = p.sky;
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
		glUseProgram(0);
		// a new shader may compute a different sky
		relit = false;
	}

	void freePrograms()
	{
		glDeleteProgram(skyProgram);
		skyProgram = 0;
	}

	/** Look up the AO program's uniforms; the volume goes on volumeUnit, the one blending into it on volumeUnit + 1. */
	void locateAo(int program, int volumeUnit)
	{
		uniAoEnabled = glGetUniformLocation(program, "giEnabled");
		uniAoCamera = glGetUniformLocation(program, "giCamera");
		uniAoGrid = glGetUniformLocation(program, "giGrid");
		uniAoLayer0 = glGetUniformLocation(program, "giLayer0");
		uniAoBlend = glGetUniformLocation(program, "giBlend");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "giVolume"), volumeUnit);
		glUniform1i(glGetUniformLocation(program, "giVolumeTo"), volumeUnit + 1);
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
		if (skyFbo != 0)
		{
			glDeleteFramebuffers(skyFbo);
			glDeleteTextures(volumeTextures);
			glDeleteTextures(skyTexture);
			glDeleteBuffers(pbo);
			skyFbo = skyTexture = pbo = 0;
			volumeTextures[0] = volumeTextures[1] = 0;
		}
		if (fence != 0)
		{
			glDeleteSync(fence);
			fence = 0;
		}
		readbackPending = readbackWanted = false;
		shown = next = -1;
		blending = false;
		shownGeneration = -1;
		weight = 0;
		relit = false;
		lit = null;
		jobs.set(0);
		builtGeneration = -1;
		emitters = null;
		emittersGeneration = -1;
		lastInput = null;
		buildRequested = true;
	}

	/** A new top level scene was swapped in: build its volume. */
	void sceneSwapped()
	{
		buildRequested = true;
		buildAfterMillis = 0;
	}

	/** A zone was rebuilt: rebuild the volume once the changes settle. */
	void zonesChanged()
	{
		buildRequested = true;
		long now = System.currentTimeMillis();
		buildAfterMillis = Math.max(now + REBUILD_DELAY_MILLIS, lastBuildMillis + MIN_REBUILD_MILLIS);
	}

	/** How much of the volume's tint is shown, 0 when it is off. */
	float shownWeight()
	{
		return isActive() ? weight : 0;
	}

	/** Whether the AO pass has a volume to look up. */
	boolean isActive()
	{
		return weight > 0 && shown >= 0;
	}

	/** Builds submitted so far; a change means a build started. */
	int builds()
	{
		return generation;
	}

	/** The build whose volume is on the GPU; a change means an upload happened. */
	int shownBuild()
	{
		return shownGeneration;
	}

	/**
	 * Once a frame before the AO pass, on the client thread with the GL context: start builds, hand finished
	 * readbacks to the worker, upload new volumes and decide whether to relight. Changes the framebuffer, viewport and
	 * program; expects a VAO bound.
	 *
	 * @param enabled whether the volume runs
	 * @param voxelsWanted whether the scene's voxels are wanted without the volume too (the skylight): builds then
	 *                     voxelize the scene and stop there
	 * @param zones the top level scene's zones, [x][z] in extended zone coordinates
	 * @param water the scene's water map, null with none
	 * @param settings hash of what else the volume's light depends on
	 */
	void prepare(boolean enabled, boolean voxelsWanted, boolean outdoors, Scene scene, Zone[][] zones, WaterMap water,
		DayCycle day, int skyCaptures, int settings)
	{
		long now = System.currentTimeMillis();
		float dt = Math.max(0, Math.min(0.1f, (now - weightMillis) / 1000f));
		weightMillis = now;
		// until the new scene's volume is lit the last one stays, moved to where it belongs in it; an instance's
		// coordinates have nothing to do with the last scene's
		boolean fits = scene != null && shownInstance == scene.isInstance()
			&& (!shownInstance || scene.getBaseX() == shownBaseX && scene.getBaseY() == shownBaseY);
		boolean toggled = enabled != wasEnabled;
		if (toggled)
		{
			wasEnabled = enabled;
			if (enabled && !submittedEmitters)
			{
				// the last build only voxelized the scene
				buildRequested = true;
				buildAfterMillis = 0;
			}
			if (enabled && shown >= 0)
			{
				// relight now rather than when the schedule comes round; the light there is shown meanwhile
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
		if (buildRequested && now >= buildAfterMillis && scene != null && zones != null)
		{
			buildRequested = false;
			lastBuildMillis = now;
			submittedEmitters = enabled;
			submitBuild(scene, zones, water, enabled);
		}
		if (!enabled)
		{
			return;
		}
		if (skyFbo == 0)
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
		// a volume that comes while another blends in waits for it, so what is shown never jumps
		if (l != null && (!blending || l.generation != shownGeneration))
		{
			lit = null;
			if (l.generation != shownGeneration)
			{
				// a new volume in a new place: shown as it is, there is nothing to blend from
				log.debug("Look: GI volume replaced, generation {} -> {}, base ({}, {}) -> ({}, {})", shownGeneration,
					l.generation, shownBaseX, shownBaseY, l.baseX, l.baseY);
				int target = shown < 0 ? 0 : 1 - shown;
				uploadVolume(l, target);
				shown = target;
				next = -1;
				blending = false;
				shownGeneration = l.generation;
				shownBaseX = l.baseX;
				shownBaseY = l.baseY;
				shownInstance = l.instance;
				shownNx = l.nx;
				shownNy = l.ny;
				shownNz = l.nz;
				shownLayer0 = l.layer0;
				lastLitMillis = now;
			}
			else
			{
				next = 1 - shown;
				uploadVolume(l, next);
				blending = true;
				blendT = 0;
				blendStart = now;
				long gap = now - lastLitMillis;
				blendMillis = (long) (Math.max(BLEND_SECONDS, Math.min(MAX_BLEND_SECONDS, gap / 1000f)) * 1000);
				lastLitMillis = now;
			}
		}
		if (blending)
		{
			blendT = Math.min(1, (now - blendStart) / (float) blendMillis);
			if (blendT >= 1)
			{
				blending = false;
				shown = next;
				next = -1;
			}
		}

		if (!outdoors || readbackPending || readbackWanted || jobs.get() > 0)
		{
			return;
		}
		boolean newVolume = builtGeneration > shownGeneration && lit == null;
		boolean due = !relit || newVolume || settings != lastSettings || day.sunIsCaster != lastSunIsCaster
			|| skyCaptures != lastSkyCaptures || now - lastRelightMillis >= MAX_RELIGHT_MILLIS
			|| angle(day.casterDir, lastCasterDir) >= RELIGHT_DEGREES;
		if (builtGeneration >= 0 && due && (newVolume || now - lastRelightMillis >= MIN_RELIGHT_MILLIS))
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
	 * Before the AO pass: bind the shown volume and set the AO program's uniforms (with it in use).
	 *
	 * @param volumeUnit, volumeUnit + 1 the units of the volume shown and of the one blending into it
	 * @param sceneBaseX, sceneBaseY the current scene's base, world tiles: the shown volume may be from the last
	 *                   scene until the new one is lit
	 */
	void bindAo(int volumeUnit, float cameraX, float cameraY, float cameraZ, int sceneBaseX, int sceneBaseY)
	{
		boolean on = isActive();
		glUniform1i(uniAoEnabled, on ? 1 : 0);
		if (!on)
		{
			return;
		}
		float cornerX = (shownBaseX - sceneBaseX) * (float) GiGrid.TILE;
		float cornerZ = (shownBaseY - sceneBaseY) * (float) GiGrid.TILE;
		glUniform3f(uniAoCamera, cameraX - cornerX, cameraY, cameraZ - cornerZ);
		glUniform3f(uniAoGrid, shownNx, shownNy, shownNz);
		glUniform1f(uniAoLayer0, shownLayer0);
		glUniform1f(uniAoBlend, blending ? blendT : 1);
		glActiveTexture(GL_TEXTURE0 + volumeUnit);
		glBindTexture(GL_TEXTURE_3D, volumeTextures[shown]);
		glActiveTexture(GL_TEXTURE0 + volumeUnit + 1);
		glBindTexture(GL_TEXTURE_3D, volumeTextures[blending ? next : shown]);
		glActiveTexture(GL_TEXTURE0);
	}

	static void unbind(int volumeUnit)
	{
		glActiveTexture(GL_TEXTURE0 + volumeUnit + 1);
		glBindTexture(GL_TEXTURE_3D, 0);
		glActiveTexture(GL_TEXTURE0 + volumeUnit);
		glBindTexture(GL_TEXTURE_3D, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	/** With a composite program in use: how much of the volume's tint it applies (its locateComposite). */
	void setComposite(int location, float strength)
	{
		glUniform1f(location, isActive() ? weight * strength : 0);
	}

	// ---------------------------------------------------------------------------------------------------------
	// GL

	private void init()
	{
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glGenTextures(volumeTextures);
		for (int tex : volumeTextures)
		{
			glBindTexture(GL_TEXTURE_3D, tex);
			glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
		}
		glBindTexture(GL_TEXTURE_3D, 0);
		skyTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, skyTexture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, GiRelight.SKY_W, SKY_ROWS, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);

		skyFbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, skyFbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, skyTexture, 0);
		glDrawBuffers(GL_COLOR_ATTACHMENT0);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			glBindFramebuffer(GL_FRAMEBUFFER, 0);
			throw new RuntimeException("GI sky framebuffer incomplete, status 0x" + Integer.toHexString(status));
		}
		glBindFramebuffer(GL_FRAMEBUFFER, 0);

		pbo = glGenBuffers();
		glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
		glBufferData(GL_PIXEL_PACK_BUFFER, (long) GiRelight.TABLE_FLOATS * Float.BYTES, GL_STREAM_READ);
		glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
	}

	/** Put the volume in volumeTextures[target], whose size follows the grid's: nx by nz texels, ny layers of each field. */
	private void uploadVolume(Lit l, int target)
	{
		if (staging == null || staging.capacity() < l.texels.length)
		{
			staging = BufferUtils.createByteBuffer(l.texels.length);
		}
		staging.clear();
		staging.put(l.texels);
		staging.flip();
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_3D, volumeTextures[target]);
		glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA8, l.nx, l.nz, 2 * l.ny, 0, GL_RGBA, GL_UNSIGNED_BYTE, staging);
		glBindTexture(GL_TEXTURE_3D, 0);
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

	private GiEmitters.ParallelFor parallel()
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
	private void submitBuild(Scene scene, Zone[][] zones, WaterMap waterMap, boolean withEmitters)
	{
		int g = ++generation;
		int baseZone = RuneLightPlugin.SCENE_OFFSET >> 3;
		int count = GiGrid.SCENE_TILES / 8 + 2;
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
		GiWater water = GiWater.of(waterMap, scene.getTileHeights(), RuneLightPlugin.SCENE_OFFSET);
		int baseX = scene.getBaseX(), baseY = scene.getBaseY();
		boolean instance = scene.isInstance();
		int[][][] heights = scene.getTileHeights();
		SkyTerrain terrain = SkyTerrain.capture(scene, RuneLightPlugin.SCENE_OFFSET);
		int zones2 = n;
		submit(() -> build(g, list, zx, zz, zones2, baseZone, water, baseX, baseY, instance, heights, withEmitters,
			terrain));
	}

	private void build(int g, Zone[] list, int[] zx, int[] zz, int n, int baseZone, GiWater water,
		int baseX, int baseY, boolean instance, int[][][] heights, boolean withEmitters, SkyTerrain terrain)
	{
		if (g != generation)
		{
			// a newer build is already queued
			return;
		}
		long start = System.nanoTime();
		GiVoxels.Zone[] voxels = new GiVoxels.Zone[n];
		AtomicInteger voxelized = new AtomicInteger();
		GiEmitters.ParallelFor parallel = parallel();
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
		if (!withEmitters)
		{
			return;
		}
		GiEmitters e = GiEmitters.build(grid, parallel);
		emitters = e;
		emittersGeneration = g;
		emittersBaseX = baseX;
		emittersBaseY = baseY;
		emittersInstance = instance;
		builtGeneration = g;
		log.debug("GI build {}: voxelized {} zones in {} ms, grid {}x{}x{}, {} faces give colour to {} voxels ({} entries), found in {} ms",
			g, voxelized, (voxelTime - start) / 1_000_000, grid.nx, grid.ny, grid.nz, e.faceCount, e.cellCount,
			e.inFace.length, (System.nanoTime() - gridTime) / 1_000_000);
		if (lastInput != null)
		{
			relight(lastInput);
		}
	}

	private void relight(LightInput in)
	{
		lastInput = in;
		GiEmitters e = emitters;
		if (e == null)
		{
			return;
		}
		long start = System.nanoTime();
		GiRelight.Light light = GiRelight.Light.fromTable(in.table, in.casterDir, in.bounce);
		byte[] texels = GiRelight.relight(e, light, parallel());
		Lit l = new Lit();
		l.generation = emittersGeneration;
		l.baseX = emittersBaseX;
		l.baseY = emittersBaseY;
		l.instance = emittersInstance;
		l.nx = e.grid.nx;
		l.ny = e.grid.ny;
		l.nz = e.grid.nz;
		l.layer0 = e.grid.layer0;
		l.texels = texels;
		lit = l;
		float[] stats = GiRelight.stats(e, texels);
		log.debug("GI relight in {} ms: {} of {} voxels tinted, strongest {}, caster {} {} {}, shadow {} / {}",
			(System.nanoTime() - start) / 1_000_000, (int) stats[0], e.cellCount * 2, stats[1], light.caster[0],
			light.caster[1], light.caster[2], light.shadow0, light.shadow1);
	}

	/** IEEE half float bits of f, rounded to nearest; for the sky grid's texels. */
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
