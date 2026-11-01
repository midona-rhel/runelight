package com.midonarhel.lumina.light;

import com.midonarhel.lumina.LuminaPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The developer command `tiles [name]`: what stops the lamps around the player, written to
 * ~/.runelite/lumina/captures/name-tiles.txt. Per tile within RADIUS on every storey: the collision flags, the lamp
 * walls' bits and ceilings as the shader reads them, the tile settings and roof id, and the objects with their type
 * and height span; then the lamps within reach with their storey, floor and ceiling.
 */
public final class LampTiles
{
	private static final Logger log = LoggerFactory.getLogger(LampTiles.class);
	private static final int RADIUS = 6;
	private static final Path DIR = Paths.get(System.getProperty("user.home"), ".runelite", "lumina", "captures");

	private LampTiles()
	{
	}

	public static void dump(LuminaPlugin plugin, String name)
	{
		Client client = plugin.client;
		WorldView wv = client.getTopLevelWorldView();
		Scene scene = wv != null ? wv.getScene() : null;
		Player player = client.getLocalPlayer();
		LocalPoint lp = player != null ? player.getLocalLocation() : null;
		if (scene == null || lp == null)
		{
			return;
		}
		int offset = LuminaPlugin.SCENE_OFFSET;
		int px = lp.getSceneX() + offset, py = lp.getSceneY() + offset;
		Tile[][][] tiles = scene.getExtendedTiles();
		byte[][][] settings = scene.getExtendedTileSettings();
		int[][][] roofs = scene.getRoofs();
		int[][][] heights = scene.getTileHeights();
		CollisionData[] maps = wv.getCollisionMaps();
		LampWalls walls = plugin.lampWalls;
		StringBuilder sb = new StringBuilder();
		sb.append("location: ").append(player.getWorldLocation()).append(", extended tile ").append(px).append(',').append(py)
			.append(", player plane ").append(wv.getPlane()).append('\n');
		sb.append("tile: extended x,y (world x,y) plane | los/move flags | lamp bits (N1 E2 S4 W8 /16 \\32) | ceiling bit | "
			+ "settings | roof id | height | objects: kind id type [y min..max]\n");
		for (int plane = 0; plane < 4; ++plane)
		{
			for (int y = py + RADIUS; y >= py - RADIUS; --y)
			{
				for (int x = px - RADIUS; x <= px + RADIUS; ++x)
				{
					if (x < 0 || y < 0 || x >= tiles[plane].length || y >= tiles[plane][x].length)
					{
						continue;
					}
					int lx = x - offset, ly = y - offset;
					int flags = maps != null && plane < maps.length && maps[plane] != null && lx >= 0 && ly >= 0
						&& lx < maps[plane].getFlags().length && ly < maps[plane].getFlags()[lx].length
						? maps[plane].getFlags()[lx][ly] : 0;
					int[] lamp = walls.tileBits(plane, x, y);
					int set = settings != null ? settings[plane][x][y] : 0;
					int roof = roofs != null ? roofs[plane][x][y] : 0;
					StringBuilder objects = new StringBuilder();
					Tile tile = tiles[plane][x][y];
					if (tile != null)
					{
						if (tile.getGameObjects() != null)
						{
							for (GameObject go : tile.getGameObjects())
							{
								if (go != null && go.getSceneMinLocation() != null && go.getSceneMinLocation().getX() + offset == x
									&& go.getSceneMinLocation().getY() + offset == y)
								{
									object(objects, "game", go, go.getConfig(), go.getRenderable());
								}
							}
						}
						if (tile.getWallObject() != null)
						{
							object(objects, "wall", tile.getWallObject(), tile.getWallObject().getConfig(), tile.getWallObject().getRenderable1());
						}
						if (tile.getDecorativeObject() != null)
						{
							object(objects, "deco", tile.getDecorativeObject(), tile.getDecorativeObject().getConfig(),
								tile.getDecorativeObject().getRenderable());
						}
						if (tile.getGroundObject() != null)
						{
							object(objects, "ground", tile.getGroundObject(), tile.getGroundObject().getConfig(),
								tile.getGroundObject().getRenderable());
						}
					}
					if (flags == 0 && lamp[0] == 0 && lamp[1] == 0 && set == 0 && roof == 0 && objects.length() == 0)
					{
						continue;
					}
					sb.append(String.format("%d,%d (%d,%d) p%d | %06x | %d | %d | %d | %d | %d |%s%n", x, y,
						scene.getBaseX() + lx, scene.getBaseY() + ly, plane, flags & 0xFFFFFF, lamp[0], lamp[1], set, roof,
						heights != null ? heights[plane][x][y] : 0, objects));
				}
			}
		}
		sb.append("lamps within ").append(RADIUS + 4).append(" tiles: x z y (local) | plane | origin x z | floorY ceilY | radius\n");
		List<PointLight> lights = plugin.frameLights;
		for (PointLight l : lights)
		{
			if (Math.abs(l.x / 128 + offset - px) > RADIUS + 4 || Math.abs(l.z / 128 + offset - py) > RADIUS + 4)
			{
				continue;
			}
			sb.append(String.format("%.0f %.0f %.0f | %d | %.0f %.0f | %.0f %.0f | %.0f (tile %d,%d)%n", l.x, l.z, l.y, l.plane, l.ox,
				l.oz, l.floorY, l.ceilY, l.radius, (int) Math.floor(l.x / 128) + offset, (int) Math.floor(l.z / 128) + offset));
		}
		try
		{
			Files.createDirectories(DIR);
			Files.write(DIR.resolve(name + "-tiles.txt"), sb.toString().getBytes(StandardCharsets.UTF_8));
			log.info("Wrote {}-tiles.txt", name);
		}
		catch (IOException e)
		{
			log.warn("Tile dump failed", e);
		}
	}

	private static void object(StringBuilder out, String kind, TileObject o, int config, Renderable r)
	{
		Model m = r instanceof Model ? (Model) r : r != null ? r.getModel() : null;
		out.append(' ').append(kind).append(' ').append(o.getId()).append(" t").append(config & 0x1F);
		if (m != null && m.getVerticesCount() > 0)
		{
			float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
			float[] vy = m.getVerticesY();
			for (int i = 0; i < m.getVerticesCount(); ++i)
			{
				min = Math.min(min, vy[i]);
				max = Math.max(max, vy[i]);
			}
			out.append(String.format(" [%.0f..%.0f]", o.getZ() + min, o.getZ() + max));
		}
	}
}
