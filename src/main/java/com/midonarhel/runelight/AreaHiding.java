package com.midonarhel.runelight;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.runelite.api.Constants;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 117 HD's areas that hide the rest of the map (hideOtherAreas in its areas.json, converted by
 * tools/import_117hd_areas.py): while the player is inside one, the scene keeps only its tiles, and those of the
 * boxes it unhides, so instances, cutscene spots and islands don't show the unrelated map around them.
 *
 * The tiles are taken out of the scene as it loads, before anything reads it, so the zones, the shadows, the water,
 * the lights and the global illumination never see them. A scene has to be loaded again when the player walks into
 * another area, which {@link #changed} asks for.
 */
final class AreaHiding
{
	private static final Logger log = LoggerFactory.getLogger(AreaHiding.class);

	// the first tile of the scene's middle chunk: a scene is built around the chunk the player is in
	private static final int CENTER = Constants.SCENE_SIZE / 2 & ~7;

	static final class Area
	{
		String name;
		// [minX, minY, minZ, maxX, maxY, maxZ] in world tiles and planes
		int[][] area, unhide;
		private transient int minX, minY, maxX, maxY;

		Area(String name, int[][] area, int[][] unhide)
		{
			this.name = name;
			this.area = area;
			this.unhide = unhide;
			bounds();
		}

		private void bounds()
		{
			minX = minY = Integer.MAX_VALUE;
			maxX = maxY = Integer.MIN_VALUE;
			for (int[][] boxes : new int[][][]{area, unhide})
			{
				for (int[] b : boxes)
				{
					minX = Math.min(minX, b[0]);
					minY = Math.min(minY, b[1]);
					maxX = Math.max(maxX, b[3]);
					maxY = Math.max(maxY, b[4]);
				}
			}
		}

		/** Whether the tile is in the area; with unhidden, in the boxes it shows as well. */
		boolean contains(boolean unhidden, int x, int y, int plane)
		{
			if (x < minX || x > maxX || y < minY || y > maxY)
			{
				return false;
			}
			return in(area, x, y, plane) || unhidden && in(unhide, x, y, plane);
		}

		private static boolean in(int[][] boxes, int x, int y, int plane)
		{
			for (int[] b : boxes)
			{
				if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && plane >= b[2] && plane <= b[5])
				{
					return true;
				}
			}
			return false;
		}
	}

	// in the order of 117 HD's areas.json: a lower floor comes before the upper floors that include bits of it
	private Area[] areas = new Area[0];
	// the area the loaded scene was cut to, and the one the scene being loaded is
	private volatile Area shown, next;
	private volatile boolean reloading;

	AreaHiding()
	{
	}

	AreaHiding(Area... areas)
	{
		this.areas = areas;
	}

	void load(Gson gson)
	{
		try (InputStream is = AreaHiding.class.getResourceAsStream("area_hiding.json"))
		{
			if (is == null)
			{
				log.warn("area_hiding.json missing");
				return;
			}
			Area[] loaded = gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), Area[].class);
			for (Area a : loaded)
			{
				a.bounds();
			}
			areas = loaded;
		}
		catch (Exception ex)
		{
			log.error("Unable to load area_hiding.json", ex);
		}
	}

	/** The first area the tile is in (not counting what it unhides), null for none. */
	Area locate(int x, int y, int plane)
	{
		for (Area a : areas)
		{
			if (a.contains(false, x, y, plane))
			{
				return a;
			}
		}
		return null;
	}

	/** The area of a scene tile: instances are built from chunks of the real map, whose areas apply. */
	Area locate(Scene scene, int sceneX, int sceneY, int plane)
	{
		int[] world = new int[3];
		return EnvironmentManager.toWorld(scene, sceneX, sceneY, plane, world) ? locate(world[0], world[1], world[2]) : null;
	}

	/**
	 * The area of a scene just built around the player, who stands somewhere in its middle chunk: that of the chunk
	 * if all of it is in one, otherwise none, leaving {@link #changed} to find the player's.
	 */
	private Area locateCenter(Scene scene, int plane)
	{
		Area found = locate(scene, CENTER, CENTER, plane);
		for (int x = CENTER; x < CENTER + 8; ++x)
		{
			for (int y = CENTER; y < CENTER + 8; ++y)
			{
				if (locate(scene, x, y, plane) != found)
				{
					return null;
				}
			}
		}
		return found;
	}

	/**
	 * Cut a scene about to load to the area the player is in.
	 *
	 * @param plane  the player's
	 * @param player the player's scene tile when the scene is the one they are in being loaded again, otherwise null
	 */
	void prepare(Scene scene, boolean enabled, int plane, int[] player)
	{
		Area area = null;
		if (enabled)
		{
			area = player != null ? locate(scene, player[0], player[1], plane) : locateCenter(scene, plane);
			if (area != null)
			{
				log.debug("Hiding everything outside {}", area.name);
				removeOutside(scene, area);
			}
		}
		next = area;
	}

	static void removeOutside(Scene scene, Area area)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		int[] world = new int[3];
		for (int z = 0; z < tiles.length; ++z)
		{
			for (int x = 0; x < tiles[z].length; ++x)
			{
				for (int y = 0; y < tiles[z][x].length; ++y)
				{
					Tile tile = tiles[z][x][y];
					if (tile != null && !(EnvironmentManager.toWorld(scene, x - RuneLightPlugin.SCENE_OFFSET,
						y - RuneLightPlugin.SCENE_OFFSET, z, world) && area.contains(true, world[0], world[1], world[2])))
					{
						scene.removeTile(tile);
					}
				}
			}
		}
	}

	/** Whether the scene being loaded is cut the way the loaded one is, so its zones can be reused. */
	boolean sameAsShown()
	{
		return next == shown;
	}

	/** The scene being loaded is the one on screen now. */
	void swapped()
	{
		shown = next;
		reloading = false;
	}

	/**
	 * Whether the player has walked into another area than the loaded scene is cut to, so that it has to be loaded
	 * again. Asks once, until the new scene is swapped in.
	 */
	boolean changed(Scene scene, boolean enabled, int sceneX, int sceneY, int plane)
	{
		if (reloading)
		{
			return false;
		}
		Area area = enabled ? locate(scene, sceneX, sceneY, plane) : null;
		reloading = area != shown;
		return reloading;
	}
}
