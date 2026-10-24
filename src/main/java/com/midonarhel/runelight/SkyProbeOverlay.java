package com.midonarhel.runelight;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
	static List<float[]> samples(SkyGrid sky, WallMap walls, boolean[] dirty, float px, float pz)
	{
		List<float[]> result = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		float localX = px-sky.baseX()*128, localZ = pz-sky.baseY()*128;
		int x0 = Math.max(0, (int)(localX/sky.spacing)-11*sky.resolution), x1 = Math.min(sky.size, (int)(localX/sky.spacing)+11*sky.resolution);
		int z0 = Math.max(0, (int)(localZ/sky.spacing)-11*sky.resolution), z1 = Math.min(sky.size, (int)(localZ/sky.spacing)+11*sky.resolution);
		for (int level = 0; level < SkyGrid.LEVELS; level++)
		for (int cz = z0; cz <= z1; cz++)
		for (int cx = x0; cx <= x1; cx++)
		{
			int unit = (level*sky.corners+cz)*sky.corners+cx;
			if (dirty[unit] || !sky.active[unit]) continue;
			for (int dz = 0; dz <= 1; dz++)
			for (int dx = 0; dx <= 1; dx++)
			{
				int tx = cx-dx, tz = cz-dz;
				if (tx < 0 || tz < 0 || tx >= sky.size || tz >= sky.size) continue;
				int bits = walls == null || level == SkyGrid.SURFACE_LEVEL ? 0
					: sky.wallBits(level/SkyGrid.HEIGHTS.length, tx, tz);
				int hx = WallMap.half(bits, dx == 1 ? WallMap.EAST : WallMap.WEST);
				int hz = WallMap.half(bits, dz == 1 ? WallMap.NORTH : WallMap.SOUTH);
				for (int half = 0; half < 2; half++)
				{
					if (half != hx && half != hz) continue;
					int index = (((level+1)*sky.textureHeight+2*tz+dz)*sky.textureWidth
						+ (half*4+2)*sky.size+2*tx+dx)*4;
					if (GlobalIllumination.fromHalf(sky.texels.get(index-2*sky.size*4)) < 0) continue;
					float x = cx*sky.spacing + GlobalIllumination.fromHalf(sky.texels.get(index+1));
					float y = GlobalIllumination.fromHalf(sky.texels.get(index+2));
					float z = cz*sky.spacing + GlobalIllumination.fromHalf(sky.texels.get(index+3));
					if ((x-localX)*(x-localX)+(z-localZ)*(z-localZ) > 1280*1280) continue;
					long key = ((long)Math.round(x*16) << 40)
						| ((long)(Math.round(y*16)&0xfffff) << 20) | (Math.round(z*16)&0xfffff);
					if (seen.add(key)) result.add(new float[]{x+sky.baseX()*128, y, z+sky.baseY()*128});
				}
			}
		}
		return result;
	}
}
