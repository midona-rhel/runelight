package com.midonarhel.lumina.weather;

import com.google.gson.Gson;
import com.midonarhel.lumina.sky.EnvironmentManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;
import org.junit.Test;

/**
 * Builds the surface's climate map as a raster of 8-tile chunks from the world's terrain: land or sea from the
 * game's floors (a terrain image, one pixel a tile: water, snow, sand, lava, grass, other; CacheMap writes it from the
 * game's cache), the named regions of weather_regions.json on land only, snowy terrain in a snowy biome, the four
 * volcanoes. Writes the raster, and a map of it over the world with place names for checking it by eye. Run with
 * {@code ./gradlew test --tests '*WeatherRegionsRaster*' -i -Dlumina.raster.terrain=terrain.png
 * -Dlumina.raster.world=world.png -Dlumina.raster.map=map.png -Dlumina.raster.out=weather_biomes.bin.gz}.
 */
public class WeatherRegionsRaster
{
	static final int X0 = 1024, Y0 = 2496, X1 = 3904, Y1 = 4160, CHUNK = 8;
	// the terrain image's classes, by colour (CacheMap.CLASS_RGB)
	static final int WATER = 0x203a70, SNOW = 0xffffff, LAVA = 0xff3010;
	// a chunk is land when this share of its tiles is
	static final float LAND = 0.4f;
	// snowy terrain: this share of the land in the 3 x 3 chunks around
	static final float SNOWY = 0.3f;
	// a land mass of fewer chunks is an island; an island no region names is tropical south of the first latitude,
	// tundra north of the second in the Fremennik seas (west of COLD_WEST), else grassland
	static final int ISLAND = 1500, WARM_SOUTH = 3050, COLD_NORTH = 3750, COLD_WEST = 2700;
	// the volcanoes: world x, y and reach in tiles (land only)
	static final int[][] VOLCANOES = {{3805, 3815, 32}, {2846, 3172, 16}, {2840, 3262, 40}, {1311, 3807, 24}};
	static final String[] VOLCANO_NAMES = {"Fossil Island's volcano", "Karamja's volcano", "Crandor", "Mount Karuulm"};
	static final Object[][] PLACES = {
		{"Lumbridge", 3222, 3218}, {"Varrock", 3213, 3428}, {"Falador", 2965, 3380}, {"Draynor", 3093, 3244},
		{"Al Kharid", 3293, 3174}, {"Port Sarim", 3023, 3208}, {"Edgeville", 3093, 3493}, {"Taverley", 2895, 3440},
		{"Burthorpe", 2899, 3544}, {"Catherby", 2808, 3434}, {"Camelot", 2757, 3477}, {"Ardougne", 2620, 3300},
		{"Yanille", 2605, 3093}, {"Gnome Stronghold", 2465, 3445}, {"Rellekka", 2658, 3657}, {"Trollheim", 2890, 3680},
		{"Ice Mountain", 3007, 3475}, {"White Wolf Mtn", 2850, 3500}, {"Wilderness", 3100, 3750}, {"Canifis", 3495, 3485},
		{"Port Phasmatys", 3680, 3490}, {"Burgh de Rott", 3500, 3210}, {"Darkmeyer", 3620, 3360},
		{"Pollnivneach", 3359, 2970}, {"Nardah", 3428, 2893}, {"Sophanem", 3300, 2780}, {"Menaphos", 3230, 2760},
		{"Brimhaven", 2760, 3178}, {"Musa Point", 2920, 3160}, {"Shilo Village", 2852, 2955},
		{"Tai Bwo Wannai", 2790, 3065}, {"Crandor", 2840, 3262}, {"Entrana", 2840, 3360}, {"Ape Atoll", 2760, 2750},
		{"Feldip Hills", 2560, 2950}, {"Castle Wars", 2440, 3090}, {"Lletya", 2330, 3170}, {"Port Tyras", 2170, 3115},
		{"Zul-Andra", 2200, 3060}, {"Isle of Souls", 2200, 2900}, {"Lunar Isle", 2100, 3900}, {"Neitiznot", 2335, 3800},
		{"Jatizso", 2410, 3800}, {"Miscellania", 2535, 3860}, {"Weiss", 2850, 3940}, {"Fossil Island", 3740, 3800},
		{"Mos Le'Harmless", 3700, 3000}, {"Barbarian Outpost", 2530, 3570}, {"Piscatoris", 2340, 3650},
		{"Kourend Castle", 1640, 3670}, {"Hosidius", 1745, 3550}, {"Shayzien", 1500, 3590}, {"Lovakengj", 1500, 3800},
		{"Arceuus", 1630, 3800}, {"Port Piscarilius", 1810, 3730}, {"Wintertodt", 1630, 3990},
		{"Kebos Lowlands", 1250, 3620}, {"Mount Karuulm", 1311, 3807}, {"Farming Guild", 1250, 3740},
		{"Civitas illa Fortis", 1700, 3110}, {"Aldarin", 1400, 2930}, {"Avium Savannah", 1600, 2980},
		{"Auburnvale", 1400, 3300}, {"Tlati Rainforest", 1250, 3150}, {"the Stranglewood", 1160, 3360},
		{"Cam Torum mountains", 1500, 3200}, {"Corsair Cove", 2570, 2860}, {"Tutorial Island", 3100, 3100},
		{"Mort Myre", 3450, 3400}, {"Lumbridge Swamp", 3170, 3170}, {"Ver Sinhaza", 3660, 3220}};

	@Test
	public void build() throws Exception
	{
		String terrainPath = System.getProperty("lumina.raster.terrain");
		if (terrainPath == null)
		{
			return;
		}
		WeatherData.load(new Gson());
		WeatherRegions.withoutRaster();
		EnvironmentManager env = new EnvironmentManager();
		env.load(new Gson());
		java.lang.reflect.Field def = WeatherRegions.class.getDeclaredField("defaultBiome");
		def.setAccessible(true);
		def.set(WeatherRegions.get(), WeatherMapProbe.DEFAULT);
		BufferedImage terrain = ImageIO.read(new File(terrainPath));
		int cw = (X1 - X0) / CHUNK, ch = (Y1 - Y0) / CHUNK;
		float[][] land = new float[ch][cw], snow = new float[ch][cw];
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				int n = 0, s = 0;
				for (int ty = 0; ty < CHUNK; ++ty)
				{
					for (int tx = 0; tx < CHUNK; ++tx)
					{
						int c = terrain.getRGB(cx * CHUNK + tx, Y1 - Y0 - 1 - (cy * CHUNK + ty)) & 0xFFFFFF;
						n += c != WATER ? 1 : 0;
						s += c == SNOW ? 1 : 0;
					}
				}
				land[cy][cx] = n / (float) (CHUNK * CHUNK);
				snow[cy][cx] = n > 0 ? s / (float) n : 0;
			}
		}
		// sea is the water the open ocean reaches: water chunks enclosed by land (lakes, swamps) are land
		boolean[][] sea = new boolean[ch][cw];
		java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				if ((cy == 0 || cx == 0 || cy == ch - 1 || cx == cw - 1) && land[cy][cx] < LAND)
				{
					sea[cy][cx] = true;
					queue.add(new int[]{cx, cy});
				}
			}
		}
		while (!queue.isEmpty())
		{
			int[] p = queue.poll();
			for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
			{
				int nx = p[0] + d[0], ny = p[1] + d[1];
				if (nx >= 0 && ny >= 0 && nx < cw && ny < ch && !sea[ny][nx] && land[ny][nx] < LAND)
				{
					sea[ny][nx] = true;
					queue.add(new int[]{nx, ny});
				}
			}
		}
		// land masses: the mainland, Zeah and Varlamore are large; islands outside any named region take the climate of
		// their latitude
		int[][] comp = new int[ch][cw];
		List<Integer> compSize = new ArrayList<>();
		compSize.add(0);
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				if (sea[cy][cx] || comp[cy][cx] != 0)
				{
					continue;
				}
				int id = compSize.size(), n = 0;
				comp[cy][cx] = id;
				queue.add(new int[]{cx, cy});
				while (!queue.isEmpty())
				{
					int[] p = queue.poll();
					n++;
					for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
					{
						int nx = p[0] + d[0], ny = p[1] + d[1];
						if (nx >= 0 && ny >= 0 && nx < cw && ny < ch && !sea[ny][nx] && comp[ny][nx] == 0)
						{
							comp[ny][nx] = id;
							queue.add(new int[]{nx, ny});
						}
					}
				}
				compSize.add(n);
			}
		}
		String[][] biome = new String[ch][cw];
		boolean[][] city = new boolean[ch][cw];
		Map<String, Integer> fallback = new LinkedHashMap<>();
		int fallbackChunks = 0, landChunks = 0;
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				if (sea[cy][cx])
				{
					continue;
				}
				landChunks++;
				int x = X0 + cx * CHUNK + CHUNK / 2, y = Y0 + cy * CHUNK + CHUNK / 2;
				String b = env.weatherBiomeAt(x, y, 0);
				city[cy][cx] = WeatherRegions.isCity(b);
				b = WeatherRegions.climate(b);
				if (b == null || WeatherMapProbe.DEFAULT.equals(b) || "SEA".equals(b))
				{
					boolean island = compSize.get(comp[cy][cx]) < ISLAND;
					b = !island ? "GRASSLAND" : y < WARM_SOUTH ? "TROPICAL"
						: y > COLD_NORTH && x < COLD_WEST ? "TUNDRA" : "GRASSLAND";
					fallbackChunks++;
					fallback.merge(b.toLowerCase() + (island ? " island" : " mainland") + " near " + nearest(x, y), 1,
						Integer::sum);
				}
				biome[cy][cx] = b;
			}
		}
		snap(biome, sea, ImageIO.read(new File(System.getProperty("lumina.raster.world"))), terrain, cw, ch);
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				if (sea[cy][cx])
				{
					continue;
				}
				int x = X0 + cx * CHUNK + CHUNK / 2, y = Y0 + cy * CHUNK + CHUNK / 2;
				String b = biome[cy][cx];
				// snowy terrain is snowy weather
				float sn = 0, ln = 0;
				for (int dy = -1; dy <= 1; ++dy)
				{
					for (int dx = -1; dx <= 1; ++dx)
					{
						int yy = cy + dy, xx = cx + dx;
						if (yy >= 0 && xx >= 0 && yy < ch && xx < cw)
						{
							sn += snow[yy][xx] * land[yy][xx];
							ln += land[yy][xx];
						}
					}
				}
				if (sn / Math.max(ln, 1e-3f) >= SNOWY && !b.equals("ARCTIC") && !b.equals("TUNDRA") && !b.equals("MOUNTAIN"))
				{
					b = "MOUNTAIN";
				}
				for (int[] v : VOLCANOES)
				{
					if (Math.hypot(x - v[0], y - v[1]) <= v[2])
					{
						b = "VOLCANIC";
					}
				}
				biome[cy][cx] = b;
			}
		}
		seas(biome, sea, cw, ch);
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				if (city[cy][cx] && !sea[cy][cx])
				{
					biome[cy][cx] = WeatherRegions.CITY + biome[cy][cx];
				}
			}
		}
		// the raster: names, then a byte per chunk, south row first
		List<String> names = new ArrayList<>();
		String out = System.getProperty("lumina.raster.out");
		if (out != null)
		{
			try (DataOutputStream o = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(out))))
			{
				for (String[] row : biome)
				{
					for (String b : row)
					{
						if (!names.contains(b))
						{
							names.add(b);
						}
					}
				}
				o.writeInt(1);
				o.writeInt(X0);
				o.writeInt(Y0);
				o.writeInt(cw);
				o.writeInt(ch);
				o.writeInt(CHUNK);
				o.writeInt(names.size());
				for (String n : names)
				{
					o.writeUTF(n);
				}
				for (String[] row : biome)
				{
					for (String b : row)
					{
						o.writeByte(names.indexOf(b));
					}
				}
			}
		}
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String[] row : biome)
		{
			for (String b : row)
			{
				counts.merge(b, 1, Integer::sum);
			}
		}
		System.out.println("land chunks " + landChunks + ", of them in no named region " + fallbackChunks + ":");
		fallback.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue())
			.forEach(e -> System.out.printf("  %5d chunks (%5.1f k tiles): %s%n", e.getValue(), e.getValue() * 0.064, e.getKey()));
		System.out.println("chunks by biome " + counts);
		String mapPath = System.getProperty("lumina.raster.map");
		if (mapPath != null)
		{
			draw(ImageIO.read(new File(System.getProperty("lumina.raster.world"))), biome, fallback, mapPath);
		}
	}

	// how far (chunks) a land border drawn as a rectangle may move to meet the terrain's own edge
	static final int SNAP = 14;
	// where borders snap (world x0, y0, x1, y1): the rectangles that showed on the approved map, around the Gnome
	// Stronghold, Tirannwn (Lletya, Port Tyras, Castle Wars), Hosidius and Corsair Cove; borders elsewhere stay as drawn
	static final int[][] SNAP_AREAS = {{2300, 3330, 2640, 3600}, {2100, 3000, 2460, 3520}, {1640, 3360, 1900, 3640},
		{2280, 2700, 2720, 2940}};
	// the borders that stay where the regions file puts them: the Wilderness ditch, the River Salve
	static final List<String> FIXED = List.of("WILDERNESS", "MORYTANIA_SWAMP");
	// what crossing from one chunk to the next costs on top of the step: a change of ground colour (0..1, the largest
	// channel's, over 3 x 3 chunks, above what a ground's own texture leaves), and water
	static final float COLOUR_COST = 60, COLOUR_NOISE = 0.02f, WATER_COST = 8;

	/**
	 * The regions file draws its regions as rectangles, so their borders run straight across the land. Within SNAP
	 * chunks of a border, every land chunk is given again to whichever region reaches it most cheaply from that
	 * region's chunks further in, where a step costs more across a change of ground colour or water: the border
	 * settles on the strongest edge between them (the forest's end, the river), and the regions keep their extent
	 * elsewhere. Only within SNAP_AREAS. A region narrower than twice SNAP keeps its innermost chunks, so none vanish.
	 */
	private static void snap(String[][] biome, boolean[][] sea, BufferedImage world, BufferedImage terrain, int cw, int ch)
	{
		float[][][] rgb = new float[ch][cw][3];
		float[][] water = new float[ch][cw];
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				int w = 0;
				for (int ty = 0; ty < CHUNK; ++ty)
				{
					for (int tx = 0; tx < CHUNK; ++tx)
					{
						int px = cx * CHUNK + tx, py = Y1 - Y0 - 1 - (cy * CHUNK + ty);
						int c = world.getRGB(px, py);
						rgb[cy][cx][0] += (c >> 16 & 255) / 255f / (CHUNK * CHUNK);
						rgb[cy][cx][1] += (c >> 8 & 255) / 255f / (CHUNK * CHUNK);
						rgb[cy][cx][2] += (c & 255) / 255f / (CHUNK * CHUNK);
						w += (terrain.getRGB(px, py) & 0xFFFFFF) == WATER ? 1 : 0;
					}
				}
				water[cy][cx] = w / (float) (CHUNK * CHUNK);
			}
		}
		// each chunk's distance in chunks (8 ways) from a border it may move across
		int[][] dist = new int[ch][cw];
		java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				dist[cy][cx] = Integer.MAX_VALUE;
				if (movable(biome, sea, cx, cy))
				{
					for (int dy = -1; dy <= 1 && dist[cy][cx] != 0; ++dy)
					{
						for (int dx = -1; dx <= 1; ++dx)
						{
							int yy = cy + dy, xx = cx + dx;
							if (yy >= 0 && xx >= 0 && yy < ch && xx < cw && movable(biome, sea, xx, yy)
								&& !biome[yy][xx].equals(biome[cy][cx]))
							{
								dist[cy][cx] = 0;
								q.add(new int[]{cx, cy});
								break;
							}
						}
					}
				}
			}
		}
		while (!q.isEmpty())
		{
			int[] p = q.poll();
			for (int dy = -1; dy <= 1; ++dy)
			{
				for (int dx = -1; dx <= 1; ++dx)
				{
					int nx = p[0] + dx, ny = p[1] + dy;
					if (nx >= 0 && ny >= 0 && nx < cw && ny < ch && movable(biome, sea, nx, ny)
						&& dist[ny][nx] == Integer.MAX_VALUE)
					{
						dist[ny][nx] = dist[p[1]][p[0]] + 1;
						q.add(new int[]{nx, ny});
					}
				}
			}
		}
		// each region's (connected, same biome) innermost distance, so a narrow one keeps a core
		int[][] part = new int[ch][cw];
		List<Integer> deepest = new ArrayList<>();
		deepest.add(0);
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				if (!movable(biome, sea, cx, cy) || part[cy][cx] != 0)
				{
					continue;
				}
				int id = deepest.size(), m = 0;
				part[cy][cx] = id;
				q.add(new int[]{cx, cy});
				while (!q.isEmpty())
				{
					int[] p = q.poll();
					m = Math.max(m, dist[p[1]][p[0]]);
					for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
					{
						int nx = p[0] + d[0], ny = p[1] + d[1];
						if (nx >= 0 && ny >= 0 && nx < cw && ny < ch && movable(biome, sea, nx, ny) && part[ny][nx] == 0
							&& biome[ny][nx].equals(biome[cy][cx]))
						{
							part[ny][nx] = id;
							q.add(new int[]{nx, ny});
						}
					}
				}
				deepest.add(m);
			}
		}
		// the ground's colour over 3 x 3 chunks, so a forest's texture is not an edge but the forest's end is
		float[][][] smooth = new float[ch][cw][3];
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				int n = 0;
				for (int dy = -1; dy <= 1; ++dy)
				{
					for (int dx = -1; dx <= 1; ++dx)
					{
						int yy = cy + dy, xx = cx + dx;
						if (yy >= 0 && xx >= 0 && yy < ch && xx < cw && !sea[yy][xx])
						{
							for (int k = 0; k < 3; ++k)
							{
								smooth[cy][cx][k] += rgb[yy][xx][k];
							}
							n++;
						}
					}
				}
				for (int k = 0; k < 3; ++k)
				{
					smooth[cy][cx][k] /= Math.max(n, 1);
				}
			}
		}
		// every region spreads from its core at a cost of a step plus the edges it crosses, so regions meet on the
		// strongest edge between their cores, or halfway where the ground shows none
		String[][] label = new String[ch][cw];
		java.util.PriorityQueue<float[]> open = new java.util.PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				if (movable(biome, sea, cx, cy)
					&& (dist[cy][cx] >= Math.min(SNAP, deepest.get(part[cy][cx])) || !snaps(cx, cy)))
				{
					label[cy][cx] = biome[cy][cx];
				}
			}
		}
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				if (label[cy][cx] != null)
				{
					edges(open, smooth, water, biome, sea, label, 0, cx, cy, cw, ch);
				}
			}
		}
		while (!open.isEmpty())
		{
			float[] p = open.poll();
			int fx = (int) p[1], fy = (int) p[2], nx = (int) p[3], ny = (int) p[4];
			if (label[ny][nx] != null)
			{
				continue;
			}
			label[ny][nx] = label[fy][fx];
			edges(open, smooth, water, biome, sea, label, p[0], nx, ny, cw, ch);
		}
		int moved = 0;
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				if (snaps(cx, cy) && label[cy][cx] != null && !label[cy][cx].equals(biome[cy][cx]))
				{
					biome[cy][cx] = label[cy][cx];
					moved++;
				}
			}
		}
		System.out.println("borders snapped to the terrain: " + moved + " chunks moved");
	}

	private static void edges(java.util.PriorityQueue<float[]> open, float[][][] colour, float[][] water,
		String[][] biome, boolean[][] sea, String[][] label, float cost, int cx, int cy, int cw, int ch)
	{
		for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
		{
			int nx = cx + d[0], ny = cy + d[1];
			if (nx < 0 || ny < 0 || nx >= cw || ny >= ch || !movable(biome, sea, nx, ny) || label[ny][nx] != null)
			{
				continue;
			}
			float e = 0;
			for (int k = 0; k < 3; ++k)
			{
				e = Math.max(e, Math.abs(colour[ny][nx][k] - colour[cy][cx][k]));
			}
			open.add(new float[]{cost + 1 + COLOUR_COST * Math.max(0, e - COLOUR_NOISE) + WATER_COST * water[ny][nx],
				cx, cy, nx, ny});
		}
	}

	private static boolean snaps(int cx, int cy)
	{
		int x = X0 + cx * CHUNK + CHUNK / 2, y = Y0 + cy * CHUNK + CHUNK / 2;
		for (int[] a : SNAP_AREAS)
		{
			if (x >= a[0] && y >= a[1] && x < a[2] && y < a[3])
			{
				return true;
			}
		}
		return false;
	}

	private static boolean movable(String[][] biome, boolean[][] sea, int cx, int cy)
	{
		return !sea[cy][cx] && !FIXED.contains(biome[cy][cx]);
	}

	// how far (chunks) the seas' borders are smoothed each way
	static final int SEA_SMOOTH = 4;

	/**
	 * Every sea chunk takes the sea of the nearest coast's climate: arctic off arctic, tundra and northern mountain
	 * coasts, tropical off tropical, jungle and southern volcanic ones, warm off desert, savanna and mediterranean ones,
	 * else temperate; then the most common sea within SEA_SMOOTH chunks, so they meet in broad bands rather than along
	 * every headland. The weather field's blur blends them further.
	 */
	private static void seas(String[][] biome, boolean[][] sea, int cw, int ch)
	{
		String[][] near = new String[ch][cw];
		java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				if (!sea[cy][cx])
				{
					near[cy][cx] = seaOf(biome[cy][cx], Y0 + cy * CHUNK);
					q.add(new int[]{cx, cy});
				}
			}
		}
		while (!q.isEmpty())
		{
			int[] p = q.poll();
			for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
			{
				int nx = p[0] + d[0], ny = p[1] + d[1];
				if (nx >= 0 && ny >= 0 && nx < cw && ny < ch && near[ny][nx] == null)
				{
					near[ny][nx] = near[p[1]][p[0]];
					q.add(new int[]{nx, ny});
				}
			}
		}
		for (int cy = 0; cy < ch; ++cy)
		{
			for (int cx = 0; cx < cw; ++cx)
			{
				if (!sea[cy][cx])
				{
					continue;
				}
				Map<String, Integer> votes = new java.util.HashMap<>();
				for (int dy = -SEA_SMOOTH; dy <= SEA_SMOOTH; ++dy)
				{
					for (int dx = -SEA_SMOOTH; dx <= SEA_SMOOTH; ++dx)
					{
						int yy = cy + dy, xx = cx + dx;
						if (yy >= 0 && xx >= 0 && yy < ch && xx < cw)
						{
							votes.merge(near[yy][xx], 1, Integer::sum);
						}
					}
				}
				biome[cy][cx] = votes.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
			}
		}
	}

	static String seaOf(String land, int y)
	{
		switch (land)
		{
			case "ARCTIC":
			case "TUNDRA":
				return "ARCTIC_SEA";
			case "MOUNTAIN":
				return y > 3600 ? "ARCTIC_SEA" : "TEMPERATE_SEA";
			case "TROPICAL":
			case "JUNGLE":
				return "TROPICAL_SEA";
			case "VOLCANIC":
				return y < 3400 ? "TROPICAL_SEA" : "TEMPERATE_SEA";
			case "DESERT":
			case "SAVANNA":
			case "MEDITERRANEAN":
				return "WARM_SEA";
			default:
				return "TEMPERATE_SEA";
		}
	}

	private static String nearest(int x, int y)
	{
		String best = "";
		double d = Double.MAX_VALUE;
		for (Object[] p : PLACES)
		{
			double e = Math.hypot(x - (Integer) p[1], y - (Integer) p[2]);
			if (e < d)
			{
				d = e;
				best = (String) p[0];
			}
		}
		return best;
	}

	private static void draw(BufferedImage world, String[][] biome, Map<String, Integer> fallback, String path)
		throws Exception
	{
		int w = world.getWidth(), h = world.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int py = 0; py < h; ++py)
		{
			for (int px = 0; px < w; ++px)
			{
				int cy = (h - 1 - py) / CHUNK, cx = px / CHUNK;
				String b = biome[Math.min(cy, biome.length - 1)][Math.min(cx, biome[0].length - 1)];
				int c = world.getRGB(px, py);
				Color t = WeatherMapProbe.COLORS.getOrDefault(WeatherRegions.climate(b), Color.MAGENTA);
				float a = b.endsWith("_SEA") ? 0.35f : 0.5f;
				// cities: stippled
				if (WeatherRegions.isCity(b) && (px + py) % 6 == 0)
				{
					t = Color.WHITE;
					a = 0.9f;
				}
				int r = (int) ((c >> 16 & 255) * (1 - a) + t.getRed() * a);
				int g = (int) ((c >> 8 & 255) * (1 - a) + t.getGreen() * a);
				int bl = (int) ((c & 255) * (1 - a) + t.getBlue() * a);
				out.setRGB(px, py, r << 16 | g << 8 | bl);
			}
		}
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// region borders
		g.setColor(new Color(0, 0, 0, 220));
		for (int cy = 0; cy < biome.length; ++cy)
		{
			for (int cx = 0; cx < biome[0].length; ++cx)
			{
				int px = cx * CHUNK, py = h - (cy + 1) * CHUNK;
				if (cx > 0 && !WeatherRegions.climate(biome[cy][cx]).equals(WeatherRegions.climate(biome[cy][cx - 1])))
				{
					g.fillRect(px - 1, py, 2, CHUNK);
				}
				if (cy > 0 && !WeatherRegions.climate(biome[cy][cx]).equals(WeatherRegions.climate(biome[cy - 1][cx])))
				{
					g.fillRect(px, py + CHUNK - 1, CHUNK, 2);
				}
			}
		}
		// place names, with the biome under them
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
		for (Object[] p : PLACES)
		{
			int x = (Integer) p[1], y = (Integer) p[2];
			int px = x - X0, py = Y1 - 1 - y;
			int cy = (y - Y0) / CHUNK, cx = (x - X0) / CHUNK;
			String b = biome[cy][cx];
			String label = p[0] + " · " + WeatherRegions.climate(b).toLowerCase().replace('_', ' ')
				+ (WeatherRegions.isCity(b) ? " city" : "");
			int tw = g.getFontMetrics().stringWidth(label);
			g.setColor(Color.WHITE);
			g.fillOval(px - 4, py - 4, 8, 8);
			g.setColor(new Color(0, 0, 0, 185));
			g.fillRoundRect(px + 7, py - 15, tw + 10, 22, 6, 6);
			g.setColor(Color.WHITE);
			g.drawString(label, px + 12, py + 1);
		}
		// legend
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String[] row : biome)
		{
			for (String b : row)
			{
				counts.merge(WeatherRegions.climate(b), 1, Integer::sum);
				if (WeatherRegions.isCity(b))
				{
					counts.merge("city (haze on 40 % of clear days)", 1, Integer::sum);
				}
			}
		}
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
		int ly = 34, total = biome.length * biome[0].length;
		Map<String, Color> legend = new LinkedHashMap<>(WeatherMapProbe.COLORS);
		legend.put("city (haze on 40 % of clear days)", Color.WHITE);
		for (Map.Entry<String, Color> e : legend.entrySet())
		{
			Integer n = counts.get(e.getKey());
			if (n == null)
			{
				continue;
			}
			g.setColor(new Color(0, 0, 0, 185));
			g.fillRect(10, ly - 20, 420, 28);
			g.setColor(e.getValue());
			g.fillRect(16, ly - 16, 20, 20);
			g.setColor(Color.WHITE);
			g.drawString(e.getKey().toLowerCase().replace('_', ' ') + String.format("  %.1f %%", 100.0 * n / total), 44, ly);
			ly += 30;
		}
		ImageIO.write(out, "png", new File(path));
	}
}
