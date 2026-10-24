package com.midonarhel.runelight;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.LineComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RuneLightOverlay extends OverlayPanel
{
	private static final Logger log = LoggerFactory.getLogger(RuneLightOverlay.class);
	private final LogLimiter logLimiter = new LogLimiter(10_000);
	private final Client client;
	private final RuneLightPlugin plugin;
	private final RuneLightConfig config;
	// The timings are only built a few times a second and shown again in between: formatting the ~35 lines into new
	// components every frame was steady garbage on the client thread, for numbers too quick to read anyway.
	private static final long TIMINGS_NANOS = 250_000_000L;
	private final List<LayoutableRenderableEntity> timings = new ArrayList<>();
	private long timingsBuilt;

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
			panelComponent.setPreferredSize(new Dimension(310, 0));
			long now = System.nanoTime();
			if (timings.isEmpty() || now - timingsBuilt >= TIMINGS_NANOS)
			{
				timingsBuilt = now;
				timings.clear();
				try
				{
					timingLines();
				}
				catch (RuntimeException ex)
				{
					// an overlay that throws is logged by the client every frame
					logLimiter.warn(log, "overlay " + ex, "Timings overlay failed", ex);
				}
			}
			panelComponent.getChildren().addAll(timings);
		}

		if (shaderError != null)
		{
			panelComponent.getChildren().add(lineComponent("Shaders", "compile error", Color.RED));
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

	private void timingLines()
	{
		GpuTimer gpu = plugin.gpuTimer;
		CpuTimer cpu = plugin.cpuTimer;
		double frame = plugin.getSwapIntervalMs();
		line("FPS / target", client.getFPS() + " / 200", Color.WHITE);
		line("Frame / budget", ms(frame) + " / 5.00 ms", frame > 5 ? Color.ORANGE : Color.GREEN);
		int missing = 0;
		for (int stage = 0; stage < GpuTimer.STAGES; stage++)
			if (!Double.isFinite(gpu.getReadMs(stage))) missing++;
		if (gpu.isEnabled())
			line(missing == 0 ? "GPU measured passes" : "GPU subtotal (" + missing + " missing)", gpuResult(gpu, -1), Color.CYAN);
		// Stable pass order: once measured, keep the last valid result and show its age.
		for (int stage = 0; gpu.isEnabled() && stage < GpuTimer.STAGES; stage++)
		{
			double value = gpu.getMs(stage);
			if (Double.isFinite(value))
				line("  " + (stage == GpuTimer.COMPOSITE && Boolean.parseBoolean(System.getProperty("runelight.compositeCombined", "true"))
					? "lighting + composite" : GpuTimer.NAMES[stage]), gpuResult(gpu, stage), value >= 1 ? Color.ORANGE : Color.WHITE);
		}
		line("CPU elapsed (incl. waits)", ms(plugin.getCpuFrameMs()), Color.CYAN);
		Integer[] stages = new Integer[CpuTimer.STAGES];
		for (int i = 0; i < stages.length; i++) stages[i] = i;
		java.util.Arrays.sort(stages, (a, b) -> Double.compare(cpu.getMs(b), cpu.getMs(a)));
		int shown = 0;
		for (int stage : stages)
		{
			if (stage == CpuTimer.SWAP || stage == CpuTimer.OUTSIDE) continue;
			if (shown++ == 4 || cpu.getMs(stage) < 0.05) break;
			line("  " + CpuTimer.NAMES[stage], ms(cpu.getMs(stage)), Color.WHITE);
		}
		line("  swap wait", ms(cpu.getMs(CpuTimer.SWAP)), Color.WHITE);
		line("  other plugin/driver", ms(cpu.untrackedMs(plugin.getCpuFrameMs())), Color.WHITE);
		line("Between frames", ms(cpu.getMs(CpuTimer.OUTSIDE)), Color.WHITE);
		line("Point lights", Integer.toString(plugin.getPointLightCount()), Color.WHITE);
		line("CPU: largest 4 steps", "CPU/GPU overlap", Color.GRAY);
		if (gpu.isEnabled())
			line("GPU values", "last valid run + age", Color.GRAY);
	}

	private static String gpuResult(GpuTimer timer, int stage)
	{
		double value = stage < 0 ? timer.getTotalMs() : timer.getMs(stage);
		if (!Double.isFinite(value)) return "awaiting valid result";
		double age = timer.ageSeconds(stage);
		return ms(value) + (age >= 0.25 ? String.format(" (%.1fs ago)", age) : "");
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
		timings.add(lineComponent(left, right, color));
	}

	private static LineComponent lineComponent(String left, String right, Color color)
	{
		return LineComponent.builder()
			.left(left)
			.right(right)
			.rightColor(color)
			.build();
	}

	private static String ms(double v)
	{
		return Double.isFinite(v) ? String.format("%.2f ms", v) : "—";
	}
}
