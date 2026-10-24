package com.midonarhel.runelight;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import net.runelite.api.Client;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Actual reconstructed skylight sample positions, shown through scenery at all heights. */
final class SkyProbeOverlay extends Overlay
{
	private final Client client;
	private final BooleanSupplier enabled;
	private final BiFunction<Float, Float, List<float[]>> source;
	private List<float[]> samples = new ArrayList<>();
	private long nextSnapshot;

	SkyProbeOverlay(Client client, BooleanSupplier enabled, BiFunction<Float, Float, List<float[]>> source)
	{
		this.client = client;
		this.enabled = enabled;
		this.source = source;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!enabled.getAsBoolean()) { samples.clear(); return null; }
		WorldView world = client.getTopLevelWorldView();
		if (world == null || client.getLocalPlayer() == null || world.getCanvasProjection() == null) return null;
		float px = world.getScene().getBaseX() * 128 + client.getLocalPlayer().getLocalLocation().getX();
		float pz = world.getScene().getBaseY() * 128 + client.getLocalPlayer().getLocalLocation().getY();
		long now = System.nanoTime();
		if (now >= nextSnapshot)
		{
			List<float[]> fresh = source.apply(px, pz);
			if (fresh != null) { samples = fresh; nextSnapshot = now + 250_000_000L; }
		}
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			int vx = client.getViewportXOffset(), vy = client.getViewportYOffset();
			int width = client.getViewportWidth(), height = client.getViewportHeight();
			g.clipRect(vx, vy, width, height);
			g.setColor(Color.RED);
			float[] projected = new float[3];
			int nearby = 0, visible = 0;
			for (float[] p : samples)
			{
				if ((p[0]-px)*(p[0]-px) + (p[2]-pz)*(p[2]-pz) > 1280*1280) continue;
				nearby++;
				world.getCanvasProjection().project(p[0] - world.getScene().getBaseX()*128,
					p[1], p[2] - world.getScene().getBaseY()*128, projected);
				if (projected[2] < 50) continue;
				int x = (int) (vx + width*.5f + projected[0]*client.getScale()/projected[2]);
				int y = (int) (vy + height*.5f + projected[1]*client.getScale()/projected[2]);
				if (x < vx || x >= vx+width || y < vy || y >= vy+height) continue;
				g.fillOval(x-2, y-2, 4, 4);
				visible++;
			}
			String label = "Skylight probes: " + visible + " on screen / " + nearby + " within 10 tiles (all heights)";
			int x = vx + (width-g.getFontMetrics().stringWidth(label))/2, y = vy+height-24;
			g.setColor(new Color(0, 0, 0, 190));
			g.fillRect(x-5, y-16, g.getFontMetrics().stringWidth(label)+10, 23);
			g.setColor(Color.WHITE);
			g.drawString(label, x, y);
		}
		finally { g.dispose(); }
		return null;
	}

	/** Called only between tracing jobs; never reads the worker's buffers concurrently. */
	static List<float[]> samples(SkyGrid sky, float px, float pz)
	{
		List<float[]> result = sky.tracedProbes(px - sky.baseX() * 128, pz - sky.baseY() * 128, 11);
		for (float[] p : result)
		{
			p[0] += sky.baseX() * 128;
			p[2] += sky.baseY() * 128;
		}
		return result;
	}
}
