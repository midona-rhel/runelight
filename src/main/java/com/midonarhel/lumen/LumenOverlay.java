package com.midonarhel.lumen;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

class LumenOverlay extends OverlayPanel
{
	private final Client client;
	private final LumenPlugin plugin;
	private final LumenConfig config;

	@Inject
	LumenOverlay(Client client, LumenPlugin plugin, LumenConfig config)
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
			line("FPS", Integer.toString(client.getFPS()), Color.WHITE);
			line("GPU scene", ms(t.getSceneMs()), Color.WHITE);
			line("GPU UI+present", ms(t.getPresentMs()), Color.WHITE);
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
