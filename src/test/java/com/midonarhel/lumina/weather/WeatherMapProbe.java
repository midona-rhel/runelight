package com.midonarhel.lumina.weather;

import com.google.gson.Gson;
import com.midonarhel.lumina.sky.EnvironmentManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.Test;

/**
 * Probe, not a check: the climate map drawn over a top-down picture of the world (one pixel a tile, x right, y up,
 * from the game's cache), each tile tinted by the biome EnvironmentManager.weatherBiomeAt gives it, tiles no layer
 * holds (the default biome) hatched, each biome's larger patches labelled. Run with
 * {@code ./gradlew test --tests '*WeatherMapProbe*' -i -Dlumina.probe.basemap=world.png -Dlumina.probe.out=map.png
 * -Dlumina.probe.bounds=x0,y0,x1,y1}.
 */
public class WeatherMapProbe
{
	static final String DEFAULT = "(default)";
	static final Map<String, Color> COLORS = new LinkedHashMap<>();

	static
	{
		String[][] c = {{"GRASSLAND", "7fd34e"}, {"FOREST", "1f7a2e"}, {"DESERT", "f2c14e"}, {"BARRENS", "a58b6f"},
			{"SWAMP", "5c6b2e"}, {"MORYTANIA_SWAMP", "6b3f7a"}, {"ARCTIC", "e8f4ff"}, {"MOUNTAIN", "9aa7b8"},
			{"WILDERNESS", "8b1e1e"}, {"TROPICAL", "00b38f"}, {"MEDITERRANEAN", "f08a4b"}, {"VOLCANIC", "ff3b1f"},
			{"WETLAND", "3fa7a0"}, {"MOORLAND", "8e7cc3"}, {"TUNDRA", "b8d8e8"}, {"SAVANNA", "d9b44a"},
			{"JUNGLE", "0b8a3c"}, {"ARCTIC_SEA", "a9c8e8"}, {"TEMPERATE_SEA", "2f6fbf"}, {"WARM_SEA", "2fa7c9"}, {"TROPICAL_SEA", "17c3b2"}};
		for (String[] e : c)
		{
			COLORS.put(e[0], new Color(Integer.parseInt(e[1], 16)));
		}
	}

	@Test
	public void map() throws Exception
	{
		String base = System.getProperty("lumina.probe.basemap");
		if (base == null)
		{
			return;
		}
		WeatherData.load(new Gson());
		EnvironmentManager env = new EnvironmentManager();
		env.load(new Gson());
		// tiles no layer holds come back as this, not as the default biome
		java.lang.reflect.Field def = WeatherRegions.class.getDeclaredField("defaultBiome");
		def.setAccessible(true);
		String realDefault = (String) def.get(WeatherRegions.get());
		def.set(WeatherRegions.get(), DEFAULT);
		String[] b = System.getProperty("lumina.probe.bounds", "1024,2496,3904,4160").split(",");
		int x0 = Integer.parseInt(b[0]), y0 = Integer.parseInt(b[1]), x1 = Integer.parseInt(b[2]), y1 = Integer.parseInt(b[3]);
		BufferedImage img = ImageIO.read(new File(base));
		int w = x1 - x0, h = y1 - y0, step = 2;
		String[][] grid = new String[h / step][w / step];
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int gy = 0; gy < h / step; ++gy)
		{
			for (int gx = 0; gx < w / step; ++gx)
			{
				int x = x0 + gx * step, y = y1 - 1 - gy * step;
				String s = env.weatherBiomeAt(x, y, 0);
				grid[gy][gx] = s;
				counts.merge(String.valueOf(s), 1, Integer::sum);
			}
		}
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int py = 0; py < h; ++py)
		{
			for (int px = 0; px < w; ++px)
			{
				int c = img.getRGB(px, py);
				String s = grid[Math.min(py / step, grid.length - 1)][Math.min(px / step, grid[0].length - 1)];
				Color t = s == null ? Color.BLACK : COLORS.getOrDefault(DEFAULT.equals(s) ? realDefault : s, Color.MAGENTA);
				float a = s == null ? 0.6f : DEFAULT.equals(s) ? 0.25f : 0.5f;
				int r = (int) ((c >> 16 & 255) * (1 - a) + t.getRed() * a);
				int g = (int) ((c >> 8 & 255) * (1 - a) + t.getGreen() * a);
				int bl = (int) ((c & 255) * (1 - a) + t.getBlue() * a);
				// the default's tiles hatched
				if (DEFAULT.equals(s) && (px + py) % 12 < 2)
				{
					r = g = bl = 240;
				}
				out.setRGB(px, py, r << 16 | g << 8 | bl);
			}
		}
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		// borders
		g.setColor(new Color(0, 0, 0, 200));
		for (int gy = 1; gy < grid.length; ++gy)
		{
			for (int gx = 1; gx < grid[0].length; ++gx)
			{
				if (!java.util.Objects.equals(grid[gy][gx], grid[gy][gx - 1]) || !java.util.Objects.equals(grid[gy][gx], grid[gy - 1][gx]))
				{
					g.fillRect(gx * step, gy * step, step, step);
				}
			}
		}
		// labels at the middle of each large patch
		boolean[][] seen = new boolean[grid.length][grid[0].length];
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
		for (int gy = 0; gy < grid.length; ++gy)
		{
			for (int gx = 0; gx < grid[0].length; ++gx)
			{
				if (seen[gy][gx] || grid[gy][gx] == null)
				{
					continue;
				}
				String s = grid[gy][gx];
				List<int[]> q = new ArrayList<>();
				q.add(new int[]{gx, gy});
				seen[gy][gx] = true;
				long sx = 0, sy = 0;
				for (int k = 0; k < q.size(); ++k)
				{
					int[] p = q.get(k);
					sx += p[0];
					sy += p[1];
					int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
					for (int[] d : nb)
					{
						int nx = p[0] + d[0], ny = p[1] + d[1];
						if (nx >= 0 && ny >= 0 && ny < grid.length && nx < grid[0].length && !seen[ny][nx] && s.equals(grid[ny][nx]))
						{
							seen[ny][nx] = true;
							q.add(new int[]{nx, ny});
						}
					}
				}
				if (q.size() < 1500)
				{
					continue;
				}
				String label = DEFAULT.equals(s) ? "default (" + realDefault + ")" : s;
				int lx = (int) (sx / q.size()) * step, ly = (int) (sy / q.size()) * step;
				int tw = g.getFontMetrics().stringWidth(label);
				g.setColor(new Color(0, 0, 0, 170));
				g.fillRoundRect(lx - tw / 2 - 6, ly - 22, tw + 12, 30, 8, 8);
				g.setColor(DEFAULT.equals(s) ? Color.WHITE : COLORS.getOrDefault(s, Color.MAGENTA).brighter());
				g.drawString(label, lx - tw / 2, ly);
			}
		}
		// legend
		int ly = 40;
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
		for (Map.Entry<String, Integer> e : counts.entrySet())
		{
			String s = e.getKey();
			Color c = "null".equals(s) ? Color.BLACK : COLORS.getOrDefault(DEFAULT.equals(s) ? realDefault : s, Color.MAGENTA);
			g.setColor(new Color(0, 0, 0, 170));
			g.fillRect(14, ly - 22, 420, 30);
			g.setColor(c);
			g.fillRect(20, ly - 18, 22, 22);
			g.setColor(Color.WHITE);
			g.drawString(("null".equals(s) ? "no sky" : DEFAULT.equals(s) ? "no region: default " + realDefault : s)
				+ String.format("  %.1f %%", 100.0 * e.getValue() / (grid.length * grid[0].length)), 50, ly);
			ly += 32;
		}
		g.setStroke(new BasicStroke(1));
		ImageIO.write(out, "png", new File(System.getProperty("lumina.probe.out", "weather-map.png")));
		System.out.println("map written; tiles by biome (2x2 blocks): " + counts);
	}
}
