package com.midonarhel.runelight;

import com.midonarhel.runelight.config.WeatherMode;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;

class RuneLightOverlay extends OverlayPanel
{
	private final Client client;
	private final RuneLightPlugin plugin;
	private final RuneLightConfig config;
	// static shadow redraws per second, counted over a second
	private int lastBuilds;
	private long lastBuildsMillis;
	private int buildsPerSecond;

	@Inject
	RuneLightOverlay(Client client, RuneLightPlugin plugin, RuneLightConfig config)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		String shaderError = plugin.getShaderError();
		if (!config.showTimings() && shaderError == null && !config.weatherFieldMap())
		{
			return null;
		}

		if (config.showTimings())
		{
			GpuTimer t = plugin.gpuTimer;
			CpuTimer c = plugin.cpuTimer;
			line("FPS", Integer.toString(client.getFPS()), Color.WHITE);
			line("Frame", ms(plugin.getSwapIntervalMs()), Color.WHITE);
			line("GPU total", ms(t.getTotalMs()), Color.CYAN);
			for (int s = 0; s < GpuTimer.STAGES; ++s)
			{
				// passes that are off stay out of the way
				if (t.getMs(s) >= 0.005)
				{
					line("  " + GpuTimer.NAMES[s], ms(t.getMs(s)), Color.WHITE);
				}
			}
			line("CPU frame", ms(plugin.getCpuFrameMs()), Color.CYAN);
			for (int s = 0; s < CpuTimer.STAGES; ++s)
			{
				if (c.getMs(s) >= 0.005)
				{
					line("  " + CpuTimer.NAMES[s], ms(c.getMs(s)), Color.WHITE);
				}
			}
			if (config.shadows())
			{
				long now = System.currentTimeMillis();
				if (now - lastBuildsMillis >= 1000)
				{
					int builds = plugin.getShadowMap().builds;
					buildsPerSecond = (int) Math.round((builds - lastBuilds) * 1000.0 / Math.max(now - lastBuildsMillis, 1));
					lastBuilds = builds;
					lastBuildsMillis = now;
				}
				line("Shadow redraws", buildsPerSecond + " / s", Color.WHITE);
			}
			line("Point lights", Integer.toString(plugin.getPointLightCount()), Color.WHITE);
			BuildingLights buildings = plugin.getBuildingLights();
			if (buildings != null)
			{
				line("Buildings", buildings.getResult().rooms.size() + " rooms, " + buildings.getLights().size() + " lights",
					Color.WHITE);
			}
			double h = plugin.getHourOfDay();
			line("Time", String.format("%02d:%02d", (int) h, (int) (h * 60) % 60), Color.WHITE);
			if (config.areaEnvironments())
			{
				environmentLines(plugin.environmentManager);
			}
			weatherLines();
			if (plugin.isHotReloading())
			{
				line("Shaders", "hot reload", Color.GREEN);
			}
		}

		if (shaderError != null)
		{
			line("Shaders", "compile error", Color.RED);
		}

		if (config.weatherFieldMap())
		{
			BufferedImage map = weatherMap(plugin.getWeather().getField());
			if (map != null)
			{
				panelComponent.getChildren().add(new ImageComponent(map));
			}
		}

		return super.render(graphics);
	}

	private void environmentLines(EnvironmentManager env)
	{
		boolean cave = !env.isOverworld();
		line("Area", env.getEnvironment().toString(), cave ? Color.ORANGE : Color.CYAN);
		EnvironmentManager.Environment biome = env.getBiome();
		line("Biome", biome != null ? biome.toString() : "-", Color.WHITE);
		String where = cave ? "cave" : env.getSheltered() > 0.5f ? "outside, sheltered" : "outside";
		line("Where", where, Color.WHITE);
		int[] wp = env.getWorldPoint();
		line("Tile", wp[0] + ", " + wp[1] + ", " + wp[2], Color.WHITE);
	}

	private void weatherLines()
	{
		WeatherMode mode = config.weatherMode();
		WeatherForecast forecast = plugin.getWeatherForecast();
		Weather weather = plugin.getWeather();
		Climate.Region region = plugin.getClimateRegion();
		if (mode == WeatherMode.AUTOMATIC)
		{
			line("Weather", (region != null ? region.type : forecast.getCurrent()).toString(), Color.CYAN);
		}
		else
		{
			line("Weather", mode == WeatherMode.OFF ? "off" : weather.getType() + " (forced)", Color.WHITE);
		}
		line("Season", forecast.getSeason() + String.format(" (day %d/%d)", forecast.getSeasonDay(),
			forecast.getSeasonDays()), Color.WHITE);
		Wind wind = weather.getWind();
		line("Wind", String.format("%s %.2f, gust %.2f", wind.fromCompass(), wind.getStrength(), wind.getGust()),
			Color.WHITE);
		String biome = forecast.getBiome();
		line("Region", biome.charAt(0) + biome.substring(1).toLowerCase().replace('_', ' '), Color.WHITE);
		line("Next", forecast.getNext() + " in " + String.format("%.1f min", forecast.getMinutesToNext()), Color.WHITE);
		if (region != null)
		{
			line("Temperature", String.format("%.1f C", region.temperature), region.temperature < 0 ? Color.CYAN : Color.WHITE);
			line("Snow / wet", String.format("%.1f cm / %.2f", region.snowCm, region.wet), Color.WHITE);
		}
		if (mode != WeatherMode.AUTOMATIC)
		{
			return;
		}
		WeatherField field = weather.getField();
		if (field.isEnabled())
		{
			line("Around", sceneWeather(field), Color.WHITE);
		}
		line("Cover / fog", String.format("%.2f / %.2f", weather.cloudCover(), weather.fogMultiplier()), Color.WHITE);
		line("Fog height", String.format("%.2f", weather.fogHeightMultiplier()), Color.WHITE);
	}

	/** The two most common weathers over the loaded scene, by share of its chunks. */
	private static String sceneWeather(WeatherField field)
	{
		WeatherType[] types = WeatherType.all();
		int[] counts = new int[types.length];
		int total = 0;
		for (int z = 0; z < field.getSizeZ(); ++z)
		{
			for (int x = 0; x < field.getSizeX(); ++x)
			{
				WeatherType t = field.type(x, z);
				if (t != null && t.index < counts.length)
				{
					counts[t.index]++;
					total++;
				}
			}
		}
		if (total == 0)
		{
			return "-";
		}
		StringBuilder sb = new StringBuilder();
		for (int k = 0; k < 2; ++k)
		{
			int best = 0;
			for (int i = 1; i < counts.length; ++i)
			{
				if (counts[i] > counts[best])
				{
					best = i;
				}
			}
			if (counts[best] == 0)
			{
				break;
			}
			if (k > 0)
			{
				sb.append(", ");
			}
			sb.append(types[best]).append(' ').append(100 * counts[best] / total).append('%');
			counts[best] = 0;
		}
		return sb.toString();
	}

	private static final int MAP_CELL = 5;

	/**
	 * The weather over the loaded scene, north up, a few pixels per chunk: green clear, gray clouds, blue rain,
	 * white snow, pale fog, black without a sky, the player in red.
	 */
	private static BufferedImage weatherMap(WeatherField field)
	{
		int w = field.getSizeX(), h = field.getSizeZ();
		if (w == 0 || h == 0)
		{
			return null;
		}
		BufferedImage img = new BufferedImage(w * MAP_CELL, h * MAP_CELL, BufferedImage.TYPE_INT_RGB);
		for (int z = 0; z < h; ++z)
		{
			for (int x = 0; x < w; ++x)
			{
				int rgb;
				if (WeatherField.NO_SKY.equals(field.biome(x, z)))
				{
					rgb = 0;
				}
				else
				{
					float r = 50, g = 105, b = 50;
					float c = field.cell(WeatherField.COVER, x, z);
					r += (150 - r) * c;
					g += (150 - g) * c;
					b += (150 - b) * c;
					float fog = Math.min(1, Math.max(0, field.cell(WeatherField.FOG, x, z) - 0.3f) / 1.5f);
					r += (200 - r) * fog;
					g += (215 - g) * fog;
					b += (200 - b) * fog;
					float rain = Math.min(1, field.cell(WeatherField.RAIN, x, z));
					r += (40 - r) * rain;
					g += (90 - g) * rain;
					b += (230 - b) * rain;
					float snow = Math.min(1, field.cell(WeatherField.SNOW, x, z));
					r += (250 - r) * snow;
					g += (250 - g) * snow;
					b += (255 - b) * snow;
					rgb = (int) r << 16 | (int) g << 8 | (int) b;
				}
				for (int py = 0; py < MAP_CELL; ++py)
				{
					for (int pxl = 0; pxl < MAP_CELL; ++pxl)
					{
						img.setRGB(x * MAP_CELL + pxl, (h - 1 - z) * MAP_CELL + py, rgb);
					}
				}
			}
		}
		int fx = (int) ((field.getFocusX() - field.getOriginX()) / WeatherField.CELL * MAP_CELL);
		int fz = (int) ((field.getFocusZ() - field.getOriginZ()) / WeatherField.CELL * MAP_CELL);
		for (int dy = -1; dy <= 1; ++dy)
		{
			for (int dx = -1; dx <= 1; ++dx)
			{
				int ix = fx + dx, iy = h * MAP_CELL - 1 - (fz + dy);
				if (ix >= 0 && iy >= 0 && ix < img.getWidth() && iy < img.getHeight())
				{
					img.setRGB(ix, iy, 0xFF2020);
				}
			}
		}
		return img;
	}

	private void line(String left, String right, Color color)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(left)
			.right(right)
			.rightColor(color)
			.build());
	}

	private static String ms(double v)
	{
		return String.format("%.2f ms", v);
	}
}
