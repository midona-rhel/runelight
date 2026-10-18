package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The weather over the whole loaded scene, so a storm a region over shows from afar: every chunk (8x8 tiles, one
 * zone) takes the forecast for its own region's biome, eases toward it like the player's weather does, and builds
 * up its own wet ground and snow cover. The cells are blurred across region borders and uploaded as two tiny
 * textures (bilinear, one texel per chunk) that the weather passes and the fog sample by world position, plus a
 * coarse array of cloud cover for the composite, which has no texture unit left.
 *
 * Shaders use the field as a difference from its value at the player: there the weather is exactly
 * Weather.java's (which also has manual modes, swells and gusts), and elsewhere it is that plus how much rainier,
 * cloudier or foggier the field says it is there.
 */
final class WeatherField
{
	// world units per cell: one zone
	static final int CELL = 1024;
	// cells per side of the composite's cloud cover array; must match CLOUD_FIELD_SIZE in clouds.glsl
	static final int CLOUD_CELLS = 24;
	// the fog channel holds fog / FOG_RANGE (FogClimate caps fog at 3.2)
	static final float FOG_RANGE = 4;
	// chunks of blur on each side, on top of the bilinear filter: region borders blend over about 40 tiles
	static final int BLUR = 2;
	private static final float[] KERNEL = {1, 2, 3, 2, 1};

	// channels of the eased state
	static final int COVER = 0, RAIN = 1, SNOW = 2, FOG = 3, WET = 4, SNOW_COVER = 5, CHANNELS = 6;

	// biome name of cells without a sky
	static final String NO_SKY = "CAVE";

	/** Uniform locations of one program that samples the field (weather passes). */
	static final class Locations
	{
		int rect, focus, groundFocus, on;
	}

	private int sizeX, sizeZ;
	private float originX, originZ;
	private long[] keys = new long[0];
	private String[] biomes = new String[0];
	private boolean[] sky = new boolean[0];
	// the weather each cell is heading for, null without a sky
	private WeatherType[] types = new WeatherType[0];
	// [channel][cell], cell = z * sizeX + x
	private float[][] state = new float[CHANNELS][0];
	private final float[] target = new float[CHANNELS];
	// this update's targets and weather per biome
	private final Map<String, float[]> byBiome = new HashMap<>();
	private final Map<String, WeatherType> typeByBiome = new HashMap<>();
	private float[] blurTmp = new float[0];
	// cells with weather of their own; new cells start in their weather as it is on the next update
	private boolean[] hasState = new boolean[0];
	// blurred and quantized to 0..255 like the textures, so CPU samples match the GPU's
	private float[][] smooth = new float[CHANNELS][0];
	private byte[] fieldBytes = new byte[0];
	private byte[] groundBytes = new byte[0];
	private final float[] cloudDelta = new float[CLOUD_CELLS * CLOUD_CELLS];
	private int cloudCellsX, cloudCellsZ;
	private boolean enabled;
	private boolean initialized;
	private boolean anything;
	private boolean uploaded;
	private int texField, texGround;
	private int texSizeX, texSizeZ;
	// the field at the player, and the player's own weather values the deltas are added to
	private final float[] focus = new float[CHANNELS];
	private float focusX, focusZ;

	/**
	 * Lay the cells over a newly loaded scene. Cells that cover a chunk the previous layout also covered keep their
	 * weather (the scene reloads as the player walks); new ones start in their weather as it is.
	 *
	 * @param originX world x of cell 0's corner, local units
	 * @param keys    per cell, the world chunk it covers (see {@link #chunkKey}), or Long.MIN_VALUE when unknown
	 * @param biomes  per cell, the region's biome name, null for the default biome, {@link #NO_SKY} for no weather
	 */
	void layout(int sizeX, int sizeZ, float originX, float originZ, long[] keys, String[] biomes)
	{
		int n = sizeX * sizeZ;
		Map<Long, Integer> old = new HashMap<>();
		for (int i = 0; i < this.keys.length; ++i)
		{
			if (this.keys[i] != Long.MIN_VALUE)
			{
				old.put(this.keys[i], i);
			}
		}
		float[][] next = new float[CHANNELS][n];
		boolean[] kept = new boolean[n];
		for (int i = 0; i < n; ++i)
		{
			Integer j = keys[i] != Long.MIN_VALUE ? old.get(keys[i]) : null;
			if (j != null && initialized)
			{
				for (int c = 0; c < CHANNELS; ++c)
				{
					next[c][i] = state[c][j];
				}
				kept[i] = true;
			}
		}
		this.sizeX = sizeX;
		this.sizeZ = sizeZ;
		this.originX = originX;
		this.originZ = originZ;
		this.keys = keys.clone();
		this.biomes = biomes.clone();
		this.sky = new boolean[n];
		this.types = new WeatherType[n];
		for (int i = 0; i < n; ++i)
		{
			sky[i] = !NO_SKY.equals(biomes[i]);
		}
		state = next;
		hasState = kept;
		smooth = new float[CHANNELS][n];
		fieldBytes = new byte[n * 4];
		groundBytes = new byte[n * 2];
		uploaded = false;
	}

	static long chunkKey(int worldX, int worldY)
	{
		return (long) (worldX >> 3) << 32 | (worldY >> 3) & 0xFFFFFFFFL;
	}

	/**
	 * Ease every cell toward its weather and rebuild the blurred field.
	 *
	 * @param enabled false: uniform weather (manual modes, or the field turned off); shaders see no differences
	 * @param typeFor the current weather of a biome (null: the default biome)
	 * @param scale   intensity setting / 100
	 * @param gradual false applies every change at once
	 * @param climate fog follows FogClimate
	 * @param dt      seconds since the last update
	 */
	void update(boolean enabled, Function<String, WeatherType> typeFor, float scale, boolean gradual, boolean climate,
		double hour, float dt)
	{
		this.enabled = enabled;
		int n = sizeX * sizeZ;
		boolean snap = !initialized || !gradual;
		byBiome.clear();
		anything = false;
		for (int i = 0; i < n; ++i)
		{
			if (sky[i])
			{
				String b = biomes[i];
				String key = b == null ? "" : b;
				float[] t = byBiome.get(key);
				if (t == null)
				{
					WeatherType type = typeFor.apply(b);
					t = new float[CHANNELS];
					targets(type, b, scale, climate, hour, t);
					byBiome.put(key, t);
					typeByBiome.put(key, type);
				}
				types[i] = typeByBiome.get(key);
				System.arraycopy(t, 0, target, 0, FOG + 1);
			}
			else
			{
				types[i] = null;
				target[COVER] = target[RAIN] = target[SNOW] = 0;
				target[FOG] = WeatherType.CLEAR.fog;
			}
			if (snap || !hasState[i])
			{
				for (int c = COVER; c <= FOG; ++c)
				{
					state[c][i] = target[c];
				}
				state[WET][i] = target[RAIN] > 0 ? 1 : snap && gradual ? state[WET][i] : 0;
				state[SNOW_COVER][i] = target[SNOW] > 0 ? 1 : snap && gradual ? state[SNOW_COVER][i] : 0;
				hasState[i] = true;
			}
			else
			{
				step(state, i, target, dt);
			}
			anything |= state[RAIN][i] > 0 || state[SNOW][i] > 0 || state[WET][i] > 0 || state[SNOW_COVER][i] > 0;
		}
		initialized = n > 0;
		blur();
	}

	/**
	 * A cell's targets for a weather, as Weather.update sets them for the player in automatic mode.
	 */
	static void targets(WeatherType type, String biome, float scale, boolean climate, double hour, float[] out)
	{
		float rain = type.rain * scale / Weather.DEFAULT_INTENSITY;
		float snow = type.snow * scale / Weather.DEFAULT_INTENSITY;
		float cover = type.cover;
		if (rain + snow > 0)
		{
			cover = Math.max(cover, Math.min(1, Weather.COVER_BASE + Weather.COVER_SLOPE * (rain + snow)));
		}
		float fog = type.fog;
		if (climate)
		{
			FogClimate.Profile p = FogClimate.profile(biome);
			fog = FogClimate.total(fog, FogClimate.density(p, hour, FogClimate.calm(type), cover));
		}
		else
		{
			fog *= 1 + type.morningMist * Weather.morning(hour);
		}
		out[COVER] = Math.min(cover, 1);
		out[RAIN] = rain;
		out[SNOW] = snow;
		out[FOG] = fog;
	}

	/** One cell's easing, with Weather's time constants: clouds build before rain starts, wet ground dries. */
	static void step(float[][] s, int i, float[] t, float dt)
	{
		float tr = t[RAIN], ts = t[SNOW];
		if (s[RAIN][i] + s[SNOW][i] == 0 && s[COVER][i] < Weather.PRECIP_GATE * t[COVER])
		{
			tr = ts = 0;
		}
		s[COVER][i] = Weather.ease(s[COVER][i], t[COVER], dt, Weather.CLOUD_SECONDS);
		s[RAIN][i] = settle(Weather.ease(s[RAIN][i], tr, dt, Weather.PRECIP_SECONDS), tr);
		s[SNOW][i] = settle(Weather.ease(s[SNOW][i], ts, dt, Weather.PRECIP_SECONDS), ts);
		s[FOG][i] = Weather.ease(s[FOG][i], t[FOG], dt, Weather.FOG_SECONDS);
		float rain = s[RAIN][i], snow = s[SNOW][i];
		s[WET][i] = rain > 0.02f ? Math.min(1, s[WET][i] + dt / Weather.WET_BUILD_SECONDS * Math.min(1, rain * 2))
			: Math.max(0, s[WET][i] - dt / Weather.DRY_SECONDS);
		s[SNOW_COVER][i] = snow > 0.02f
			? Math.min(1, s[SNOW_COVER][i] + dt / Weather.SNOW_BUILD_SECONDS * Math.min(1, snow * 2))
			: Math.max(0, s[SNOW_COVER][i] - dt / Weather.MELT_SECONDS * (rain > 0.02f ? 3 : 1));
	}

	private static float settle(float v, float target)
	{
		return target == 0 && v < 0.003f ? 0 : v;
	}

	/** Separable blur of every channel into {@link #smooth}, quantized to the textures' 8 bits. */
	private void blur()
	{
		int n = sizeX * sizeZ;
		if (blurTmp.length != n)
		{
			blurTmp = new float[n];
		}
		float[] tmp = blurTmp;
		for (int c = 0; c < CHANNELS; ++c)
		{
			float[] src = state[c];
			for (int z = 0; z < sizeZ; ++z)
			{
				for (int x = 0; x < sizeX; ++x)
				{
					float sum = 0, w = 0;
					for (int k = -BLUR; k <= BLUR; ++k)
					{
						int xx = Math.max(0, Math.min(sizeX - 1, x + k));
						sum += KERNEL[k + BLUR] * src[z * sizeX + xx];
						w += KERNEL[k + BLUR];
					}
					tmp[z * sizeX + x] = sum / w;
				}
			}
			float range = c == FOG ? FOG_RANGE : 1;
			for (int z = 0; z < sizeZ; ++z)
			{
				for (int x = 0; x < sizeX; ++x)
				{
					float sum = 0, w = 0;
					for (int k = -BLUR; k <= BLUR; ++k)
					{
						int zz = Math.max(0, Math.min(sizeZ - 1, z + k));
						sum += KERNEL[k + BLUR] * tmp[zz * sizeX + x];
						w += KERNEL[k + BLUR];
					}
					int q = Math.round(Math.max(0, Math.min(1, sum / w / range)) * 255);
					smooth[c][z * sizeX + x] = q / 255f * range;
				}
			}
		}
		boolean changed = false;
		for (int i = 0; i < n; ++i)
		{
			changed |= put(fieldBytes, i * 4, smooth[COVER][i], 1);
			changed |= put(fieldBytes, i * 4 + 1, smooth[RAIN][i], 1);
			changed |= put(fieldBytes, i * 4 + 2, smooth[SNOW][i], 1);
			changed |= put(fieldBytes, i * 4 + 3, smooth[FOG][i], FOG_RANGE);
			changed |= put(groundBytes, i * 2, smooth[WET][i], 1);
			changed |= put(groundBytes, i * 2 + 1, smooth[SNOW_COVER][i], 1);
		}
		if (changed)
		{
			uploaded = false;
		}
	}

	private static boolean put(byte[] b, int i, float v, float range)
	{
		byte q = (byte) Math.round(Math.max(0, Math.min(1, v / range)) * 255);
		if (b[i] == q)
		{
			return false;
		}
		b[i] = q;
		return true;
	}

	/**
	 * The blurred field at a world position (local units), bilinear between cell centers and clamped at the edges,
	 * like the GPU's sampling of the textures. 0 for an empty field.
	 */
	float sample(int channel, float x, float z)
	{
		if (sizeX == 0 || sizeZ == 0)
		{
			return 0;
		}
		float cx = (x - originX) / CELL - 0.5f;
		float cz = (z - originZ) / CELL - 0.5f;
		cx = Math.max(0, Math.min(sizeX - 1, cx));
		cz = Math.max(0, Math.min(sizeZ - 1, cz));
		int x0 = Math.min((int) cx, sizeX - 1), z0 = Math.min((int) cz, sizeZ - 1);
		int x1 = Math.min(x0 + 1, sizeX - 1), z1 = Math.min(z0 + 1, sizeZ - 1);
		float fx = cx - x0, fz = cz - z0;
		float[] s = smooth[channel];
		float a = s[z0 * sizeX + x0] + (s[z0 * sizeX + x1] - s[z0 * sizeX + x0]) * fx;
		float b = s[z1 * sizeX + x0] + (s[z1 * sizeX + x1] - s[z1 * sizeX + x0]) * fx;
		return a + (b - a) * fz;
	}

	/**
	 * Highest value of a channel over the cells within {@code half} world units (square) of a point: how much rain
	 * or snow there is anywhere the particles reach.
	 */
	float max(int channel, float x, float z, float half)
	{
		float best = 0;
		int x0 = Math.max(0, (int) Math.floor((x - half - originX) / CELL));
		int x1 = Math.min(sizeX - 1, (int) Math.floor((x + half - originX) / CELL));
		int z0 = Math.max(0, (int) Math.floor((z - half - originZ) / CELL));
		int z1 = Math.min(sizeZ - 1, (int) Math.floor((z + half - originZ) / CELL));
		for (int cz = z0; cz <= z1; ++cz)
		{
			for (int cx = x0; cx <= x1; ++cx)
			{
				best = Math.max(best, smooth[channel][cz * sizeX + cx]);
			}
		}
		return best;
	}

	/** Highest value of a channel anywhere in the scene. */
	float max(int channel)
	{
		float best = 0;
		for (float v : smooth[channel])
		{
			best = Math.max(best, v);
		}
		return best;
	}

	/**
	 * Remember the field at the player, which the shaders subtract, and lay out the clouds' cover differences.
	 */
	void focus(float x, float z)
	{
		focusX = x;
		focusZ = z;
		for (int c = 0; c < CHANNELS; ++c)
		{
			focus[c] = sample(c, x, z);
		}
		// the cloud array: cover differences at its own cell centers, at most CLOUD_CELLS a side over the scene
		cloudCellsX = Math.min(sizeX, CLOUD_CELLS);
		cloudCellsZ = Math.min(sizeZ, CLOUD_CELLS);
		java.util.Arrays.fill(cloudDelta, 0);
		if (!enabled)
		{
			return;
		}
		float stepX = (float) sizeX * CELL / Math.max(cloudCellsX, 1);
		float stepZ = (float) sizeZ * CELL / Math.max(cloudCellsZ, 1);
		for (int j = 0; j < cloudCellsZ; ++j)
		{
			for (int i = 0; i < cloudCellsX; ++i)
			{
				float v = sample(COVER, originX + (i + 0.5f) * stepX, originZ + (j + 0.5f) * stepZ);
				cloudDelta[j * CLOUD_CELLS + i] = v - focus[COVER];
			}
		}
	}

	boolean isEnabled()
	{
		return enabled && sizeX > 0;
	}

	/** Whether any cell has rain, snow, wet ground or snow cover. */
	boolean hasAnything()
	{
		return enabled && anything;
	}

	float getFocus(int channel)
	{
		return focus[channel];
	}

	float getFocusX()
	{
		return focusX;
	}

	float getFocusZ()
	{
		return focusZ;
	}

	int getSizeX()
	{
		return sizeX;
	}

	int getSizeZ()
	{
		return sizeZ;
	}

	float getOriginX()
	{
		return originX;
	}

	float getOriginZ()
	{
		return originZ;
	}

	/** The eased (unblurred) value of a cell, for the overlay's map. */
	float cell(int channel, int x, int z)
	{
		return state[channel][z * sizeX + x];
	}

	String biome(int x, int z)
	{
		return biomes[z * sizeX + x];
	}

	/** The weather a cell is heading for, null without a sky. */
	WeatherType type(int x, int z)
	{
		return types[z * sizeX + x];
	}

	// ---------------------------------------------------------------------------------------------------------
	// GL

	/**
	 * Upload the field if it changed and bind it: the weather (cover, rain, snow, fog) on {@code fieldUnit}, the
	 * ground (wet, snow cover) on {@code groundUnit}.
	 */
	void bind(int fieldUnit, int groundUnit)
	{
		if (sizeX == 0 || sizeZ == 0)
		{
			return;
		}
		if (texField == 0 || texSizeX != sizeX || texSizeZ != sizeZ)
		{
			freeTextures();
			texField = createTexture(GL_RGBA8, sizeX, sizeZ);
			texGround = createTexture(GL_RG8, sizeX, sizeZ);
			texSizeX = sizeX;
			texSizeZ = sizeZ;
			uploaded = false;
		}
		if (!uploaded)
		{
			upload(texField, GL_RGBA, fieldBytes);
			upload(texGround, GL_RG, groundBytes);
			uploaded = true;
		}
		glActiveTexture(GL_TEXTURE0 + fieldUnit);
		glBindTexture(GL_TEXTURE_2D, texField);
		glActiveTexture(GL_TEXTURE0 + groundUnit);
		glBindTexture(GL_TEXTURE_2D, texGround);
		glActiveTexture(GL_TEXTURE0);
	}

	static void unbind(int fieldUnit, int groundUnit)
	{
		glActiveTexture(GL_TEXTURE0 + fieldUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + groundUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	private static int createTexture(int format, int w, int h)
	{
		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexImage2D(GL_TEXTURE_2D, 0, format, w, h, 0, format == GL_RGBA8 ? GL_RGBA : GL_RG, GL_UNSIGNED_BYTE,
			(ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		return tex;
	}

	private void upload(int tex, int format, byte[] data)
	{
		ByteBuffer buf = BufferUtils.createByteBuffer(data.length);
		buf.put(data).flip();
		glBindTexture(GL_TEXTURE_2D, tex);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, sizeX, sizeZ, format, GL_UNSIGNED_BYTE, buf);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void freeTextures()
	{
		if (texField != 0)
		{
			glDeleteTextures(texField);
			glDeleteTextures(texGround);
			texField = texGround = 0;
		}
	}

	void free()
	{
		freeTextures();
	}

	static Locations locate(int program, int fieldUnit, int groundUnit)
	{
		Locations l = new Locations();
		l.rect = glGetUniformLocation(program, "weatherFieldRect");
		l.focus = glGetUniformLocation(program, "weatherFieldFocus");
		l.groundFocus = glGetUniformLocation(program, "weatherGroundFocus");
		l.on = glGetUniformLocation(program, "weatherFieldOn");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "weatherField"), fieldUnit);
		glUniform1i(glGetUniformLocation(program, "weatherGround"), groundUnit);
		glUseProgram(0);
		return l;
	}

	/** With a program that samples the field in use: where the field lies and its value at the player. */
	void apply(Locations l)
	{
		setRect(l.rect);
		glUniform4f(l.focus, focus[COVER], focus[RAIN], focus[SNOW], focus[FOG]);
		glUniform2f(l.groundFocus, focus[WET], focus[SNOW_COVER]);
		glUniform1f(l.on, isEnabled() ? 1 : 0);
	}

	/** weatherFieldRect: xy the world xz of the field's corner, zw one over its size, world units. */
	void setRect(int location)
	{
		glUniform4f(location, originX, originZ, 1f / Math.max(sizeX * CELL, 1), 1f / Math.max(sizeZ * CELL, 1));
	}

	/** clouds.glsl's cloudField uniforms: the cover differences and where they lie. */
	void applyClouds(int fieldLocation, int rectLocation, int cellsLocation)
	{
		glUniform4fv(fieldLocation, cloudDelta);
		glUniform2f(cellsLocation, Math.max(cloudCellsX, 1), Math.max(cloudCellsZ, 1));
		float w = (float) sizeX * CELL, h = (float) sizeZ * CELL;
		// cells per world unit in zw; 0 turns the lookup off
		glUniform4f(rectLocation, originX, originZ, isEnabled() && w > 0 ? cloudCellsX / w : 0,
			isEnabled() && h > 0 ? cloudCellsZ / h : 0);
	}

	int getCloudCellsX()
	{
		return cloudCellsX;
	}

	int getCloudCellsZ()
	{
		return cloudCellsZ;
	}

	float cloudDelta(int i, int j)
	{
		return cloudDelta[j * CLOUD_CELLS + i];
	}
}
