package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The weather over the world around the player, so a storm a region over shows from afar: every cell (16x16 tiles)
 * takes the forecast for its place's biome (WeatherRegions), eases toward it, and builds up its own wet ground and
 * snow cover. This is the only place the weather is decided: everything the player sees is the field at the player,
 * and everything farther away is the field there. The cells are blurred across region borders, so a border is a ramp
 * of about 80 tiles that nothing steps across, and uploaded as three tiny textures (bilinear, one texel per cell)
 * that the weather passes, the fog and the clouds sample by world position: the weather (cover, rain, snow, fog),
 * the ground (wet, snow cover) and the sky (cover, wispiness, darkness).
 *
 * Shaders use the weather and the ground as a difference from their value at the player: there the weather is
 * exactly Weather.java's (which adds swells and gusts), and elsewhere it is that plus how much rainier or foggier the
 * field says it is there. The sky is looked up as it is.
 */
final class WeatherField
{
	// tiles per cell side, and world units
	static final int CELL_TILES = 16;
	static final int CELL = CELL_TILES * 128;
	// the fog channel holds fog / FOG_RANGE (FogClimate caps fog at 3.2)
	static final float FOG_RANGE = 4;
	// cells of blur on each side, on top of the bilinear filter: region borders blend over about 80 tiles
	static final int BLUR = 2;
	private static final float[] KERNEL = {1, 2, 3, 2, 1};

	// channels of the eased state; the first six are uploaded as textures (8 bits)
	static final int COVER = 0, RAIN = 1, SNOW = 2, FOG = 3, WET = 4, SNOW_COVER = 5, GPU_CHANNELS = 6;
	// the rest only the player's weather reads, at the focus: the fog's height and the region's humidity, the area
	// fog and distance fog weights, storm, how snowy the overcast is, wind and gustiness, showers, then the type's
	// look: clouds (wisp, dark), light (sun - 1, haze, warmth, sky saturation), fog tint, rain drops (length, speed,
	// width, alpha, slant) and flakes (size, speed, sway, wind, color)
	static final int FOG_HEIGHT = 6, HUMIDITY = 7, AREA_FOG = 8, DISTANCE_FOG = 9, STORM = 10, SNOWINESS = 11, WIND = 12,
		GUSTINESS = 13, SHOWERS = 14, CLOUD_WISP = 15, CLOUD_DARK = 16, LIGHT = 17, FOG_TINT = 21, RAIN_STYLE = 24,
		FLAKE_STYLE = 29, CHANNELS = 36;
	// targets past the channels: the type's buildup rates and frost, and how white its sky is (not eased)
	static final int WET_RATE = CHANNELS, SNOW_RATE = CHANNELS + 1, FROST = CHANNELS + 2, SKY_WHITE = CHANNELS + 3,
		TARGETS = CHANNELS + 4;
	// easing time constant per channel past the GPU's, seconds; 0 follows the target at once (Wind eases the wind)
	private static final float[] SECONDS = new float[CHANNELS];

	static
	{
		for (int c = FOG_HEIGHT; c < CHANNELS; ++c)
		{
			SECONDS[c] = Weather.CLOUD_SECONDS;
		}
		SECONDS[FOG_HEIGHT] = SECONDS[HUMIDITY] = SECONDS[AREA_FOG] = SECONDS[DISTANCE_FOG] = Weather.FOG_SECONDS;
		SECONDS[STORM] = Weather.STORM_SECONDS;
		SECONDS[SHOWERS] = Weather.PRECIP_SECONDS;
		SECONDS[WIND] = SECONDS[GUSTINESS] = 0;
	}

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
	private final float[] target = new float[TARGETS];
	private final float[] noSky = new float[TARGETS];
	// cell key -> index in the current layout
	private final Map<Long, Integer> index = new HashMap<>();
	// this update's targets and weather per biome
	private final Map<String, float[]> byBiome = new HashMap<>();
	private final Map<String, WeatherType> typeByBiome = new HashMap<>();
	private final Map<String, float[]> groundByBiome = new HashMap<>();
	private float[] blurTmp = new float[0];
	// cells with weather of their own; new cells start in their weather as it is on the next update
	private boolean[] hasState = new boolean[0];
	// blurred and quantized to 0..255 like the textures, so CPU samples match the GPU's
	private float[][] smooth = new float[CHANNELS][0];
	private byte[] fieldBytes = new byte[0];
	private byte[] groundBytes = new byte[0];
	private byte[] skyBytes = new byte[0];
	private boolean initialized;
	private boolean anything;
	private boolean uploaded;
	private int texField, texGround, texSky;
	private int texSizeX, texSizeZ;
	// the field at the player, and the player's own weather values the deltas are added to
	private final float[] focus = new float[CHANNELS];
	private float focusX, focusZ;
	// the weather most of the field at the player is, for what is shown by name
	private WeatherType focusType;
	private final int[] corner = new int[4];
	private final float[] cornerWeight = new float[4];

	/**
	 * Lay the cells over the world around the player. Cells that cover a place the previous layout also covered keep
	 * their weather (the window moves as the player walks); new ones start in their weather as it is.
	 *
	 * @param originX world x of cell 0's corner, local units
	 * @param keys    per cell, the place it covers (see {@link #chunkKey}), or Long.MIN_VALUE when unknown
	 * @param biomes  per cell, the region's biome name, null for the default biome, {@link #NO_SKY} for no weather
	 */
	void layout(int sizeX, int sizeZ, float originX, float originZ, long[] keys, String[] biomes)
	{
		int n = sizeX * sizeZ;
		Map<Long, Integer> old = new HashMap<>(index);
		index.clear();
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
		skyBytes = new byte[n * 3];
		uploaded = false;
		for (int i = 0; i < n; ++i)
		{
			if (keys[i] != Long.MIN_VALUE)
			{
				index.put(keys[i], i);
			}
		}
	}

	/** The key of the cell that holds a world tile. */
	static long chunkKey(int worldX, int worldY)
	{
		return (long) Math.floorDiv(worldX, CELL_TILES) << 32 | Math.floorDiv(worldY, CELL_TILES) & 0xFFFFFFFFL;
	}

	/**
	 * Ease every cell toward its weather and rebuild the blurred field.
	 *
	 * @param typeFor   the current weather of a biome (null: the default biome)
	 * @param groundFor wet ground and snow cover of a biome (Climate), which the cells then hold as they are; null
	 *                  eases them here from the weather instead
	 * @param scale     intensity setting / 100
	 * @param gradual   false applies every change at once
	 * @param dt        seconds since the last update
	 */
	void update(Function<String, WeatherType> typeFor, Function<String, float[]> groundFor, float scale, boolean gradual,
		double hour, float dt)
	{
		int n = sizeX * sizeZ;
		boolean snap = !initialized || !gradual;
		targets(WeatherType.clear(), null, scale, hour, noSky);
		noSky[COVER] = noSky[RAIN] = noSky[SNOW] = noSky[FROST] = noSky[SHOWERS] = 0;
		byBiome.clear();
		groundByBiome.clear();
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
					t = new float[TARGETS];
					targets(type, b, scale, hour, t);
					byBiome.put(key, t);
					typeByBiome.put(key, type);
				}
				types[i] = typeByBiome.get(key);
				System.arraycopy(t, 0, target, 0, TARGETS);
			}
			else
			{
				types[i] = null;
				System.arraycopy(noSky, 0, target, 0, TARGETS);
			}
			if (snap || !hasState[i])
			{
				for (int c = 0; c < CHANNELS; ++c)
				{
					if (c != WET && c != SNOW_COVER && c != SNOWINESS)
					{
						state[c][i] = target[c];
					}
				}
				state[SNOWINESS][i] = WeatherType.whiteness(target[SKY_WHITE], target[RAIN], target[SNOW], state[SNOWINESS][i]);
				state[WET][i] = target[RAIN] > 0 ? 1 : snap && gradual ? state[WET][i] : 0;
				float kept = snap && gradual ? state[SNOW_COVER][i] : 0;
				state[SNOW_COVER][i] = target[SNOW] > 0 && target[SNOW_RATE] > 0 ? 1 : Math.max(kept, target[FROST]);
				hasState[i] = true;
			}
			else
			{
				step(state, i, target, dt);
			}
			if (groundFor != null)
			{
				// the ground has a history of its own (Climate): snow stays until it thaws
				String gb = biomes[i];
				float[] g = sky[i] ? groundByBiome.computeIfAbsent(gb == null ? "" : gb, k -> groundFor.apply(gb)) : null;
				state[WET][i] = g != null ? g[0] : 0;
				state[SNOW_COVER][i] = g != null ? g[1] : 0;
			}
			anything |= state[RAIN][i] > 0 || state[SNOW][i] > 0 || state[WET][i] > 0 || state[SNOW_COVER][i] > 0;
		}
		initialized = n > 0;
		blur();
	}

	/**
	 * A cell's targets for a weather in a biome, with showers as their average. {@code out} holds
	 * {@link #TARGETS}.
	 */
	static void targets(WeatherType type, String biome, float scale, double hour, float[] out)
	{
		float showers = 1 - type.showers * (1 - Weather.SHOWER_SHARE);
		float rain = type.rain * scale / Weather.DEFAULT_INTENSITY * showers;
		float snow = type.snow * scale / Weather.DEFAULT_INTENSITY * showers;
		float cover = type.cover;
		if (rain + snow > 0 && type.coverSwell > 0)
		{
			cover = Math.max(cover, type.coverSwell * Math.min(1, Weather.COVER_BASE + Weather.COVER_SLOPE * (rain + snow)));
		}
		FogClimate.Profile p = FogClimate.profile(biome);
		float calm = FogClimate.calm(type);
		float fog = FogClimate.total(type.fog, FogClimate.density(p, hour, calm, cover));
		out[COVER] = Math.min(cover, 1);
		out[RAIN] = rain;
		out[SNOW] = snow;
		out[FOG] = fog;
		out[FOG_HEIGHT] = FogClimate.height(p, hour, calm, cover);
		out[HUMIDITY] = p.humidity;
		out[AREA_FOG] = type.areaFog;
		out[DISTANCE_FOG] = type.distanceFog;
		out[STORM] = type.lightning;
		out[WIND] = type.wind;
		out[GUSTINESS] = type.gustiness;
		out[SHOWERS] = type.showers;
		out[CLOUD_WISP] = type.cloudWisp;
		out[CLOUD_DARK] = type.cloudDark;
		set(out, LIGHT, type.sun - 1, type.haze, type.warmth, type.skySaturation);
		set(out, FOG_TINT, type.fogTint[0], type.fogTint[1], type.fogTint[2]);
		set(out, RAIN_STYLE, type.dropLength, type.dropSpeed, type.dropWidth, type.dropAlpha, type.slant);
		set(out, FLAKE_STYLE, type.flakeSize, type.flakeSpeed, type.flakeSway, type.flakeWind, type.flakeColor[0],
			type.flakeColor[1], type.flakeColor[2]);
		out[WET_RATE] = type.wetRate;
		out[SNOW_RATE] = type.snowRate;
		out[FROST] = type.frost;
		out[SKY_WHITE] = type.skyWhite;
	}

	private static void set(float[] out, int first, float... values)
	{
		System.arraycopy(values, 0, out, first, values.length);
	}

	/**
	 * One cell's easing, with Weather's time constants: clouds build before rain starts, wet ground dries, snow
	 * cover builds at the type's rate (none for dust and ash) and frost holds a thin cover.
	 */
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
		for (int c = FOG_HEIGHT; c < CHANNELS; ++c)
		{
			if (c != SNOWINESS)
			{
				s[c][i] = SECONDS[c] > 0 ? Weather.ease(s[c][i], t[c], dt, SECONDS[c]) : t[c];
			}
		}
		// the overcast whitens with what falls, and keeps its color when nothing does
		if (t[SKY_WHITE] >= 0 || rain + snow > Weather.PRECIP_EPSILON)
		{
			s[SNOWINESS][i] = Weather.ease(s[SNOWINESS][i], WeatherType.whiteness(t[SKY_WHITE], rain, snow, s[SNOWINESS][i]),
				dt, Weather.PRECIP_SECONDS);
		}
		float wetRate = t[WET_RATE], snowRate = t[SNOW_RATE], frost = t[FROST];
		s[WET][i] = rain > 0.02f
			? Math.min(1, s[WET][i] + dt / Weather.WET_BUILD_SECONDS * Math.min(1, rain * 2) * wetRate)
			: Math.max(0, s[WET][i] - dt / Weather.DRY_SECONDS);
		float c = s[SNOW_COVER][i];
		if (snow > 0.02f && snowRate > 0.01f)
		{
			c = Math.min(1, c + dt / Weather.SNOW_BUILD_SECONDS * Math.min(1, snow * 2) * snowRate);
		}
		else if (c < frost - 0.01f)
		{
			c = Math.min(frost, c + dt / (Weather.SNOW_BUILD_SECONDS * 2));
		}
		else
		{
			c = Math.max(Math.min(frost, c), c - dt / Weather.MELT_SECONDS * (rain > 0.02f ? 3 : 1));
		}
		s[SNOW_COVER][i] = c;
	}

	private static float settle(float v, float target)
	{
		return target == 0 && v < 0.003f ? 0 : v;
	}

	/** Separable blur of every channel into {@link #smooth}; the textures' channels quantized to their 8 bits. */
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
					float v = sum / w;
					if (c < GPU_CHANNELS)
					{
						v = Math.round(Math.max(0, Math.min(1, v / range)) * 255) / 255f * range;
					}
					smooth[c][z * sizeX + x] = v;
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
			changed |= put(skyBytes, i * 3, smooth[COVER][i], 1);
			changed |= put(skyBytes, i * 3 + 1, smooth[CLOUD_WISP][i], 1);
			// the darkness offset is -1 to 1
			changed |= put(skyBytes, i * 3 + 2, (smooth[CLOUD_DARK][i] + 1) / 2, 1);
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
	 * The weather with the largest share of the bilinear blend at a position: the same four cells and weights as
	 * {@link #sample}. Null without a sky or a field.
	 */
	private WeatherType dominant(float x, float z)
	{
		if (sizeX == 0 || sizeZ == 0)
		{
			return null;
		}
		float cx = Math.max(0, Math.min(sizeX - 1, (x - originX) / CELL - 0.5f));
		float cz = Math.max(0, Math.min(sizeZ - 1, (z - originZ) / CELL - 0.5f));
		int x0 = Math.min((int) cx, sizeX - 1), z0 = Math.min((int) cz, sizeZ - 1);
		int x1 = Math.min(x0 + 1, sizeX - 1), z1 = Math.min(z0 + 1, sizeZ - 1);
		float fx = cx - x0, fz = cz - z0;
		corner[0] = z0 * sizeX + x0;
		corner[1] = z0 * sizeX + x1;
		corner[2] = z1 * sizeX + x0;
		corner[3] = z1 * sizeX + x1;
		cornerWeight[0] = (1 - fx) * (1 - fz);
		cornerWeight[1] = fx * (1 - fz);
		cornerWeight[2] = (1 - fx) * fz;
		cornerWeight[3] = fx * fz;
		WeatherType best = null;
		float bestWeight = -1;
		for (int i = 0; i < 4; ++i)
		{
			WeatherType t = types[corner[i]];
			if (t == null)
			{
				continue;
			}
			float w = 0;
			for (int j = 0; j < 4; ++j)
			{
				if (types[corner[j]] == t)
				{
					w += cornerWeight[j];
				}
			}
			if (w > bestWeight)
			{
				bestWeight = w;
				best = t;
			}
		}
		return best;
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

	/** Remember the field at the player, which the shaders subtract. */
	void focus(float x, float z)
	{
		focusX = x;
		focusZ = z;
		for (int c = 0; c < CHANNELS; ++c)
		{
			focus[c] = sample(c, x, z);
		}
		focusType = dominant(x, z);
	}

	/** Whether there are cells to look up. */
	boolean isEnabled()
	{
		return initialized && sizeX > 0;
	}

	/** Whether any cell has rain, snow, wet ground or snow cover. */
	boolean hasAnything()
	{
		return anything;
	}

	float getFocus(int channel)
	{
		return focus[channel];
	}

	/** {@code out.length} channels from {@code first}: a vector of the look (light, tint, styles). */
	void getFocus(int first, float[] out)
	{
		System.arraycopy(focus, first, out, 0, out.length);
	}

	/** The weather that dominates at the player, null when none of the nearby cells has a sky. */
	WeatherType getFocusType()
	{
		return focusType;
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
		ensureUploaded();
		glActiveTexture(GL_TEXTURE0 + fieldUnit);
		glBindTexture(GL_TEXTURE_2D, texField);
		glActiveTexture(GL_TEXTURE0 + groundUnit);
		glBindTexture(GL_TEXTURE_2D, texGround);
		glActiveTexture(GL_TEXTURE0);
	}

	/** Upload the field if it changed and bind the sky (cover, wispiness, darkness) on {@code skyUnit}. */
	void bindSky(int skyUnit)
	{
		if (sizeX == 0 || sizeZ == 0)
		{
			return;
		}
		ensureUploaded();
		glActiveTexture(GL_TEXTURE0 + skyUnit);
		glBindTexture(GL_TEXTURE_2D, texSky);
		glActiveTexture(GL_TEXTURE0);
	}

	private void ensureUploaded()
	{
		if (texField == 0 || texSizeX != sizeX || texSizeZ != sizeZ)
		{
			freeTextures();
			texField = createTexture(GL_RGBA8, sizeX, sizeZ);
			texGround = createTexture(GL_RG8, sizeX, sizeZ);
			texSky = createTexture(GL_RGB8, sizeX, sizeZ);
			texSizeX = sizeX;
			texSizeZ = sizeZ;
			uploaded = false;
		}
		if (!uploaded)
		{
			upload(texField, GL_RGBA, fieldBytes);
			upload(texGround, GL_RG, groundBytes);
			upload(texSky, GL_RGB, skyBytes);
			uploaded = true;
		}
	}

	static void unbind(int... units)
	{
		for (int unit : units)
		{
			glActiveTexture(GL_TEXTURE0 + unit);
			glBindTexture(GL_TEXTURE_2D, 0);
		}
		glActiveTexture(GL_TEXTURE0);
	}

	private static int createTexture(int format, int w, int h)
	{
		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexImage2D(GL_TEXTURE_2D, 0, format, w, h, 0, format == GL_RGBA8 ? GL_RGBA : format == GL_RGB8 ? GL_RGB : GL_RG,
			GL_UNSIGNED_BYTE, (ByteBuffer) null);
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
			glDeleteTextures(texSky);
			texField = texGround = texSky = 0;
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

	/** clouds.glsl's cloudSkyRect: where the sky lies, like weatherFieldRect; all zero when there is none. */
	void setSkyRect(int location)
	{
		if (isEnabled())
		{
			setRect(location);
		}
		else
		{
			glUniform4f(location, 0, 0, 0, 0);
		}
	}
}
