package com.midonarhel.runelight;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Development aid: tints the rooms detected on the player's plane, each in its own color, labels each with the id
 * the lights and the shaders know it by (R) and its plane (L), marks where their lights hang and outlines the tiles
 * where a hole in the floor links a room to the one above or below it, and says which room the player stands in.
 */
class BuildingOverlay extends Overlay
{
	// only rooms this close to the player are drawn, to keep the overlay cheap in towns
	private static final int RANGE_TILES = 32;
	private static final Color LINK = new Color(0, 220, 255);

	private final Client client;
	private final RuneLightPlugin plugin;
	private final RuneLightConfig config;

	@Inject
	BuildingOverlay(Client client, RuneLightPlugin plugin, RuneLightConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		BuildingLights buildings = plugin.getBuildingLights();
		Player player = client.getLocalPlayer();
		if (!config.showBuildings() || buildings == null || player == null)
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		int plane = wv.getPlane();
		LocalPoint pp = player.getLocalLocation();
		int px = pp.getSceneX() + RuneLightPlugin.SCENE_OFFSET, py = pp.getSceneY() + RuneLightPlugin.SCENE_OFFSET;

		BuildingDetector.Result r = buildings.getResult();
		RoomMap map = buildings.getRooms();
		for (int i = 0; i < r.rooms.size(); ++i)
		{
			BuildingDetector.Room room = r.rooms.get(i);
			if (room.plane != plane || room.maxX < px - RANGE_TILES || room.minX > px + RANGE_TILES
				|| room.maxY < py - RANGE_TILES || room.minY > py + RANGE_TILES)
			{
				continue;
			}
			Color c = Color.getHSBColor((i * 0.618034f) % 1f, 0.8f, 1f);
			Color fill = new Color(c.getRed(), c.getGreen(), c.getBlue(), 50);
			long sumX = 0, sumY = 0;
			for (int t : room.tiles)
			{
				sumX += t >>> 16;
				sumY += t & 0xFFFF;
				int sx = (t >>> 16) - RuneLightPlugin.SCENE_OFFSET, sy = (t & 0xFFFF) - RuneLightPlugin.SCENE_OFFSET;
				if (sx < 0 || sy < 0 || sx >= Constants.SCENE_SIZE || sy >= Constants.SCENE_SIZE)
				{
					continue;
				}
				Polygon poly = Perspective.getCanvasTilePoly(client, LocalPoint.fromScene(sx, sy, wv));
				if (poly != null)
				{
					g.setColor(fill);
					g.fill(poly);
					if (map != null && map.link(room.plane, t >>> 16, t & 0xFFFF) != 0)
					{
						g.setColor(LINK);
						g.draw(poly);
					}
				}
			}
			// the label sits on the room tile nearest the middle of the room
			int mid = nearest(room, (float) sumX / room.tiles.length, (float) sumY / room.tiles.length);
			int mx = (mid >>> 16) - RuneLightPlugin.SCENE_OFFSET, my = (mid & 0xFFFF) - RuneLightPlugin.SCENE_OFFSET;
			if (mx >= 0 && my >= 0 && mx < Constants.SCENE_SIZE && my < Constants.SCENE_SIZE)
			{
				String text = "R" + (i + 1 > RoomMap.MAX_ROOM ? 0 : i + 1) + " L" + room.plane + " (" + room.tiles.length + ")";
				label(g, Perspective.getCanvasTextLocation(client, g, LocalPoint.fromScene(mx, my, wv), text, 40), text);
			}
		}
		if (map != null)
		{
			int room = map.room(plane, px, py);
			int link = map.link(plane, px, py);
			String text = "you: " + (room != 0 ? "R" + room : "outdoors") + " L" + plane + (link != 0 ? ", hole to R" + link : "");
			label(g, player.getCanvasTextLocation(g, text, player.getLogicalHeight() + 40), text);
		}

		List<BuildingLights.Light> lights = buildings.getLights();
		g.setStroke(new BasicStroke(2));
		for (BuildingLights.Light l : lights)
		{
			if (l.plane != plane)
			{
				continue;
			}
			Point p = Perspective.localToCanvas(client, (int) l.x, (int) l.z, (int) l.y);
			if (p == null)
			{
				continue;
			}
			g.setColor(new Color(255, 200, 120));
			g.fillOval(p.getX() - 5, p.getY() - 5, 10, 10);
			g.setColor(Color.BLACK);
			g.drawOval(p.getX() - 5, p.getY() - 5, 10, 10);
		}
		return null;
	}

	private static int nearest(BuildingDetector.Room room, float x, float y)
	{
		int best = room.tiles[0];
		float bestD = Float.MAX_VALUE;
		for (int t : room.tiles)
		{
			float dx = (t >>> 16) + 0.5f - x, dy = (t & 0xFFFF) + 0.5f - y;
			float d = dx * dx + dy * dy;
			if (d < bestD)
			{
				bestD = d;
				best = t;
			}
		}
		return best;
	}

	private static void label(Graphics2D g, Point at, String text)
	{
		if (at != null)
		{
			g.setColor(Color.BLACK);
			g.drawString(text, at.getX() + 1, at.getY() + 1);
			g.setColor(Color.WHITE);
			g.drawString(text, at.getX(), at.getY());
		}
	}
}
