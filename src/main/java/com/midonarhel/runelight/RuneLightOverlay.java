package com.midonarhel.runelight;

import com.midonarhel.runelight.config.WeatherMode;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

class RuneLightOverlay extends OverlayPanel
{
	private final Client client;
	private final RuneLightPlugin plugin;
	private final RuneLightConfig config;

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
		if (!config.showTimings() && shaderError == null)
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
		if (mode != WeatherMode.AUTOMATIC)
		{
			line("Weather", mode == WeatherMode.OFF ? "off" : mode + " (manual)", Color.WHITE);
			return;
		}
		WeatherForecast forecast = plugin.getWeatherForecast();
		Weather weather = plugin.getWeather();
		line("Weather", forecast.getCurrent().toString(), Color.CYAN);
		String biome = forecast.getBiome();
		line("Region", biome.charAt(0) + biome.substring(1).toLowerCase().replace('_', ' '), Color.WHITE);
		line("Next", forecast.getNext() + " in " + String.format("%.1f min", forecast.getMinutesToNext()), Color.WHITE);
		line("Cover / fog", String.format("%.2f / %.2f", weather.cloudCover(), weather.fogMultiplier()), Color.WHITE);
		line("Fog height", String.format("%.2f", weather.fogHeightMultiplier()), Color.WHITE);
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
