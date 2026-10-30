/*
 * Copyright (c) 2026, Midona
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.scene.terrain;

import com.midonarhel.lumina.scene.SceneData;
import com.midonarhel.lumina.shading.DiffuseColors;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.IndexDataBase;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import static com.midonarhel.lumina.scene.SceneData.HIDDEN_HSL;
import static net.runelite.api.Constants.MAX_Z;

/**
 * The unlit colour of every tile's underlay and overlay, recomputed exactly as the client computes its tile colours
 * before lighting them (deob rev 223, class36.method709), so Vanilla colour mode can light the game's own ground
 * colours without the game's baked shading:
 * <ul>
 * <li>underlay: the floor definitions of the tiles in a 10x10 window around the tile (columns x-4..x+5 and rows
 * y-4..y+5, clipped to the scene; the client's sliding sums add column x+5 and drop column x-5) are averaged:
 * {@code hue = sum(hue) * 256 / sum(hueMultiplier)}, {@code saturation = sum / n}, {@code lightness = sum / n},
 * packed with {@link #packHsl};</li>
 * <li>overlay: its definition's colour packed the same way, {@link #TEXTURE_HSL} (white) when it is textured,
 * {@link SceneData#HIDDEN_HSL} when its colour is 0xFF00FF;</li>
 * <li>the client's random per-scene hue and lightness offsets only affect the minimap colours, not the tiles.</li>
 * </ul>
 * The client then lights each corner as {@code (base & 0xFF80) | clamp(baseLightness * I / 128, 2, 126)} (textured:
 * {@code clamp(I, 2, 126)}), with the light {@code I = I_h - S} from the terrain heights (the formula in
 * {@link #heightLight}) minus the shadow bytes of nearby walls and objects, which the client does not expose. The unlit
 * base is constant per tile and layer; the per-corner variation of the GPU plugin's colours is this lighting, which
 * Lumina's light replaces, so no corner averaging is done.
 * <p>
 * The floor definitions come from the config index (underlays archive 1, overlays archive 4; opcodes as the deob's
 * FloorUnderlayDefinition and FloorOverlayDefinition) and must be loaded on the client thread
 * ({@link #loadDefinitions}); they are cached for the session. {@link #compute} and {@link #selfCheck} run anywhere.
 * The self-check verifies the recompute against the client's lit colours of the scene once per scene: the hue and
 * saturation bits must match on every coloured corner, and where a textured corner at the same vertex gives the light
 * exactly, the lightness must match too; no corner may be brighter than the heights allow (the shadow is never
 * negative).
 */
@Slf4j
public final class UnlitTerrainColors
{
	/**
	 * The unlit colour of a textured overlay: white, the texture carries the colour.
	 */
	public static final int TEXTURE_HSL = DiffuseColors.VANILLA_TEXTURED_COLOR;

	/**
	 * No layer of that kind on the tile.
	 */
	public static final int NONE = -1;

	// the client's underlay window: columns x-4..x+5 and rows y-4..y+5
	static final int WINDOW_BEHIND = 4;
	static final int WINDOW_AHEAD = 5;

	private static final int CONFIG_UNDERLAYS = 1;
	private static final int CONFIG_OVERLAYS = 4;

	// floor definitions by id, loaded on the client thread and kept for the session (the cache does not change)
	private static final ConcurrentHashMap<Integer, Underlay> UNDERLAYS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Integer, Integer> OVERLAYS = new ConcurrentHashMap<>();

	/**
	 * A floor underlay definition's colour as the client keeps it (FloorUnderlayDefinition.setHsl).
	 */
	static final class Underlay
	{
		final int hue, saturation, lightness, hueMultiplier;

		Underlay(int rgb)
		{
			double r = (rgb >> 16 & 255) / 256.0;
			double g = (rgb >> 8 & 255) / 256.0;
			double b = (rgb & 255) / 256.0;
			double min = Math.min(r, Math.min(g, b));
			double max = Math.max(r, Math.max(g, b));
			double h = 0, s = 0;
			double l = (min + max) / 2.0;
			if (min != max)
			{
				if (l < 0.5)
				{
					s = (max - min) / (min + max);
				}
				if (l >= 0.5)
				{
					s = (max - min) / (2.0 - max - min);
				}
				if (r == max)
				{
					h = (g - b) / (max - min);
				}
				else if (g == max)
				{
					h = 2.0 + (b - r) / (max - min);
				}
				else if (max == b)
				{
					h = 4.0 + (r - g) / (max - min);
				}
			}
			h /= 6.0;
			saturation = clamp((int) (s * 256.0), 0, 255);
			lightness = clamp((int) (256.0 * l), 0, 255);
			int mul = l > 0.5 ? (int) (512.0 * (1.0 - l) * s) : (int) (512.0 * l * s);
			hueMultiplier = Math.max(mul, 1);
			hue = (int) (hueMultiplier * h);
		}
	}

	/**
	 * A floor overlay definition's unlit colour: its primary colour packed, TEXTURE_HSL if textured, HIDDEN_HSL if
	 * the colour is 0xFF00FF (FloorOverlayDefinition.setHsl and method709's overlay branch).
	 */
	static int overlayColor(int primaryRgb, int texture)
	{
		if (texture >= 0)
		{
			return TEXTURE_HSL;
		}
		if (primaryRgb == 0xFF00FF)
		{
			return HIDDEN_HSL;
		}
		double r = (primaryRgb >> 16 & 255) / 256.0;
		double g = (primaryRgb >> 8 & 255) / 256.0;
		double b = (primaryRgb & 255) / 256.0;
		double min = Math.min(r, Math.min(g, b));
		double max = Math.max(r, Math.max(g, b));
		double h = 0, s = 0;
		double l = (min + max) / 2.0;
		if (min != max)
		{
			if (l < 0.5)
			{
				s = (max - min) / (max + min);
			}
			if (l >= 0.5)
			{
				s = (max - min) / (2.0 - max - min);
			}
			if (r == max)
			{
				h = (g - b) / (max - min);
			}
			else if (max == g)
			{
				h = (b - r) / (max - min) + 2.0;
			}
			else if (b == max)
			{
				h = 4.0 + (r - g) / (max - min);
			}
		}
		h /= 6.0;
		int hue = (int) (256.0 * h);
		int saturation = clamp((int) (256.0 * s), 0, 255);
		int lightness = clamp((int) (l * 256.0), 0, 255);
		return packHsl(hue, saturation, lightness) & 0xFFFF;
	}

	/**
	 * The client's packing of a floor colour (HealthBarUpdate.method2533): the saturation is halved for each of the
	 * lightness thresholds 179, 192, 217 and 243 it exceeds, then {@code sat / 32 << 7 | hue / 4 << 10 | light / 2}.
	 * The raw value is returned (a negative hue packs negative); the client masks it when lighting the corner.
	 */
	public static int packHsl(int hue, int saturation, int lightness)
	{
		if (lightness > 179)
		{
			saturation /= 2;
		}
		if (lightness > 192)
		{
			saturation /= 2;
		}
		if (lightness > 217)
		{
			saturation /= 2;
		}
		if (lightness > 243)
		{
			saturation /= 2;
		}
		return (saturation / 32 << 7) + (hue / 4 << 10) + lightness / 2;
	}

	/**
	 * The client's light at a terrain vertex from the heights alone (before the shadow bytes): the vertex normal from
	 * the neighbouring heights against the light direction (-50, -10, -50), 84 on flat ground.
	 */
	public static int heightLight(int[][] heights, int x, int y)
	{
		int dx = heights[x + 1][y] - heights[x - 1][y];
		int dz = heights[x][y + 1] - heights[x][y - 1];
		int len = (int) Math.sqrt(dz * dz + dx * dx + 65536);
		int nx = (dx << 8) / len;
		int ny = 65536 / len;
		int nz = (dz << 8) / len;
		// (int) sqrt(5100) * 768 >> 8
		return (nx * -50 + nz * -50 + ny * -10) / 213 + 96;
	}

	/**
	 * The client's lit corner colour of an unlit base at light {@code i} (IndexCheck.method4392 / FriendSystem.method1931).
	 */
	public static int lit(int unlitHsl, int i)
	{
		if (unlitHsl == TEXTURE_HSL)
		{
			return clamp(i, 2, 126);
		}
		return (unlitHsl & 0xFF80) + clamp((unlitHsl & 127) * i / 128, 2, 126);
	}

	private final int planes, sizeX, sizeY;
	// per plane, x, y: the unlit colour, HIDDEN_HSL, or NONE when the tile has no such layer
	private final int[] underlay, overlay;

	private UnlitTerrainColors(int planes, int sizeX, int sizeY)
	{
		this.planes = planes;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		underlay = new int[planes * sizeX * sizeY];
		overlay = new int[planes * sizeX * sizeY];
		Arrays.fill(underlay, NONE);
		Arrays.fill(overlay, NONE);
	}

	private int idx(int plane, int x, int y)
	{
		return (plane * sizeX + x) * sizeY + y;
	}

	private boolean inside(int plane, int x, int y)
	{
		return plane >= 0 && plane < planes && x >= 0 && x < sizeX && y >= 0 && y < sizeY;
	}

	/**
	 * The unlit underlay colour of a tile (extended scene coordinates), HIDDEN_HSL, or NONE.
	 */
	public int underlay(int plane, int x, int y)
	{
		return inside(plane, x, y) ? underlay[idx(plane, x, y)] : NONE;
	}

	/**
	 * The unlit overlay colour of a tile (extended scene coordinates), HIDDEN_HSL, TEXTURE_HSL, or NONE.
	 */
	public int overlay(int plane, int x, int y)
	{
		return inside(plane, x, y) ? overlay[idx(plane, x, y)] : NONE;
	}

	/**
	 * The unlit colour of a tile paint: the overlay's when the tile has an overlay (the client builds the paint from
	 * the overlay then), else the underlay's.
	 */
	public int paint(int plane, int x, int y)
	{
		int o = overlay(plane, x, y);
		return o != NONE ? o : underlay(plane, x, y);
	}

	/**
	 * The unlit colour of one layer of a tile.
	 */
	public int layer(int plane, int x, int y, boolean isOverlay)
	{
		return isOverlay ? overlay(plane, x, y) : underlay(plane, x, y);
	}

	/**
	 * Loads the floor definitions the scene's tiles use that are not cached yet. Client thread only (the cache reads
	 * are the client's own).
	 *
	 * @return whether the definitions are available (false when the colour mode does not need them or the config index
	 * is not loaded)
	 */
	public static boolean loadDefinitions(Client client, Scene scene)
	{
		if (!DiffuseColors.useUnlitTerrainColors())
		{
			return false;
		}
		IndexDataBase config = client.getIndexConfig();
		if (config == null)
		{
			log.warn("Config index not available; terrain keeps the game's lit colours");
			return false;
		}
		short[][][] underlayIds = scene.getUnderlayIds();
		short[][][] overlayIds = scene.getOverlayIds();
		for (int z = 0; z < underlayIds.length; z++)
		{
			for (short[] column : underlayIds[z])
			{
				for (short id : column)
				{
					int u = id & 0x7FFF;
					if (u > 0 && !UNDERLAYS.containsKey(u - 1))
					{
						UNDERLAYS.put(u - 1, new Underlay(decodeUnderlayRgb(config.loadData(CONFIG_UNDERLAYS, u - 1))));
					}
				}
			}
			for (short[] column : overlayIds[z])
			{
				for (short id : column)
				{
					int o = id & 0x7FFF;
					if (o > 0 && !OVERLAYS.containsKey(o - 1))
					{
						OVERLAYS.put(o - 1, decodeOverlay(config.loadData(CONFIG_OVERLAYS, o - 1)));
					}
				}
			}
		}
		return true;
	}

	// FloorUnderlayDefinition.decode: opcode 1 = rgb (3 bytes); others are ignored, 0 ends
	static int decodeUnderlayRgb(@Nullable byte[] data)
	{
		int rgb = 0;
		if (data == null)
		{
			return rgb;
		}
		try
		{
			int pos = 0;
			while (pos < data.length)
			{
				int opcode = data[pos++] & 0xFF;
				if (opcode == 0)
				{
					break;
				}
				if (opcode == 1)
				{
					rgb = readMedium(data, pos);
					pos += 3;
				}
			}
		}
		catch (ArrayIndexOutOfBoundsException ex)
		{
			log.warn("Malformed underlay definition", ex);
		}
		return rgb;
	}

	// FloorOverlayDefinition.decode: 1 = primaryRgb, 2 = texture (byte), 5 = hideUnderlay false, 7 = secondaryRgb,
	// 8 = nothing; 0 ends
	static int decodeOverlay(@Nullable byte[] data)
	{
		int primaryRgb = 0;
		int texture = -1;
		if (data != null)
		{
			try
			{
				int pos = 0;
				while (pos < data.length)
				{
					int opcode = data[pos++] & 0xFF;
					if (opcode == 0)
					{
						break;
					}
					switch (opcode)
					{
						case 1:
							primaryRgb = readMedium(data, pos);
							pos += 3;
							break;
						case 2:
							texture = data[pos++] & 0xFF;
							break;
						case 7:
							pos += 3;
							break;
						default:
							break;
					}
				}
			}
			catch (ArrayIndexOutOfBoundsException ex)
			{
				log.warn("Malformed overlay definition", ex);
			}
		}
		return overlayColor(primaryRgb, texture);
	}

	private static int readMedium(byte[] data, int pos)
	{
		return (data[pos] & 0xFF) << 16 | (data[pos + 1] & 0xFF) << 8 | (data[pos + 2] & 0xFF);
	}

	/**
	 * Computes the scene's unlit colours from the cached definitions (after {@link #loadDefinitions}).
	 */
	public static UnlitTerrainColors compute(Scene scene)
	{
		return compute(scene.getUnderlayIds(), scene.getOverlayIds(),
			id -> UNDERLAYS.get(id), id -> OVERLAYS.getOrDefault(id, overlayColor(0, -1)));
	}

	/**
	 * The recompute itself, over the client's id arrays (value = id + 1, 0 = none, bit 15 ignored).
	 *
	 * @param underlays the underlay definition of an id, or null for an unknown id (counted as absent, as the client
	 *                  does with a definition it cannot load: it still counts it but with zero values; unknown ids
	 *                  should not occur after {@link #loadDefinitions})
	 * @param overlays  the unlit overlay colour of an id
	 */
	static UnlitTerrainColors compute(short[][][] underlayIds, short[][][] overlayIds,
		IntFunction<Underlay> underlays, IntUnaryOperator overlays)
	{
		final int planes = Math.min(underlayIds.length, MAX_Z);
		final int sizeX = underlayIds[0].length;
		final int sizeY = underlayIds[0][0].length;
		final UnlitTerrainColors out = new UnlitTerrainColors(planes, sizeX, sizeY);

		// 2D prefix sums of the window's five quantities: the exact integer sums the client's sliding window keeps
		final int stride = sizeY + 1;
		final int[] sumHue = new int[(sizeX + 1) * stride];
		final int[] sumSat = new int[sumHue.length];
		final int[] sumLight = new int[sumHue.length];
		final int[] sumMul = new int[sumHue.length];
		final int[] count = new int[sumHue.length];

		for (int z = 0; z < planes; z++)
		{
			final short[][] uIds = underlayIds[z];
			final short[][] oIds = overlayIds[z];
			for (int x = 0; x < sizeX; x++)
			{
				for (int y = 0; y < sizeY; y++)
				{
					int h = 0, s = 0, l = 0, m = 0, n = 0;
					int id = uIds[x][y] & 0x7FFF;
					if (id > 0)
					{
						Underlay u = underlays.apply(id - 1);
						if (u != null)
						{
							h = u.hue;
							s = u.saturation;
							l = u.lightness;
							m = u.hueMultiplier;
						}
						n = 1;
					}
					int p = (x + 1) * stride + y + 1;
					int left = x * stride + y + 1, up = (x + 1) * stride + y, diag = x * stride + y;
					sumHue[p] = h + sumHue[left] + sumHue[up] - sumHue[diag];
					sumSat[p] = s + sumSat[left] + sumSat[up] - sumSat[diag];
					sumLight[p] = l + sumLight[left] + sumLight[up] - sumLight[diag];
					sumMul[p] = m + sumMul[left] + sumMul[up] - sumMul[diag];
					count[p] = n + count[left] + count[up] - count[diag];
				}
			}

			// the client builds tiles for x, y in 1..size-2 only
			for (int x = 1; x < sizeX - 1; x++)
			{
				int x0 = Math.max(0, x - WINDOW_BEHIND);
				int x1 = Math.min(sizeX - 1, x + WINDOW_AHEAD);
				for (int y = 1; y < sizeY - 1; y++)
				{
					int underlayId = uIds[x][y] & 0x7FFF;
					int overlayId = oIds[x][y] & 0x7FFF;
					if (underlayId == 0 && overlayId == 0)
					{
						continue;
					}
					int i = out.idx(z, x, y);
					if (underlayId > 0)
					{
						int y0 = Math.max(0, y - WINDOW_BEHIND);
						int y1 = Math.min(sizeY - 1, y + WINDOW_AHEAD);
						int a = (x1 + 1) * stride + y1 + 1, b = x0 * stride + y1 + 1, c = (x1 + 1) * stride + y0, d = x0 * stride + y0;
						int hue = sumHue[a] - sumHue[b] - sumHue[c] + sumHue[d];
						int sat = sumSat[a] - sumSat[b] - sumSat[c] + sumSat[d];
						int light = sumLight[a] - sumLight[b] - sumLight[c] + sumLight[d];
						int mul = sumMul[a] - sumMul[b] - sumMul[c] + sumMul[d];
						int n = count[a] - count[b] - count[c] + count[d];
						// n >= 1 (the tile itself) and mul >= n (each multiplier is at least 1)
						out.underlay[i] = packHsl(hue * 256 / mul, sat / n, light / n) & 0xFFFF;
					}
					else
					{
						out.underlay[i] = HIDDEN_HSL;
					}
					if (overlayId > 0)
					{
						out.overlay[i] = overlays.applyAsInt(overlayId - 1);
					}
				}
			}
		}
		return out;
	}

	/**
	 * Checks the recompute against the client's lit tile colours of the scene and logs the counts once.
	 *
	 * @param sceneOffset the extended scene offset of tile scene locations (SceneData.sceneOffset)
	 * @return the number of corners that failed a check
	 */
	public int selfCheck(Scene scene, int sceneOffset)
	{
		final Tile[][][] tiles = scene.getExtendedTiles();
		final int[][][] heights = scene.getTileHeights();
		final int vStride = sizeY + 1;
		// the light a textured corner gives at each vertex, -1 unknown
		final int[] light = new int[(sizeX + 1) * vStride];
		// coloured corners: vertex, lit colour, unlit colour, layer (0 underlay, 1 overlay)
		int[] cVertex = new int[1 << 16], cLit = new int[1 << 16], cUnlit = new int[1 << 16];
		byte[] cLayer = new byte[1 << 16];
		int corners = 0;
		int texturedTooBright = 0;

		for (int z = 0; z < planes; z++)
		{
			Arrays.fill(light, -1);
			for (int x = 1; x < sizeX - 1; x++)
			{
				for (int y = 1; y < sizeY - 1; y++)
				{
					Tile tile = tiles[z][x][y];
					if (tile == null)
					{
						continue;
					}
					for (Tile t = tile; t != null; t = t == tile ? tile.getBridge() : null)
					{
						final int plane = t.getRenderLevel();
						if (plane != z)
						{
							continue; // the bridge's level is visited with its own plane
						}
						SceneTilePaint paint = t.getSceneTilePaint();
						if (paint != null && paint.getNeColor() != HIDDEN_HSL)
						{
							final boolean textured = paint.getTexture() != -1;
							final int unlit = paint(z, x, y);
							int[] lits = {paint.getSwColor(), paint.getSeColor(), paint.getNeColor(), paint.getNwColor()};
							int[] vxs = {x, x + 1, x + 1, x};
							int[] vys = {y, y, y + 1, y + 1};
							for (int k = 0; k < 4; k++)
							{
								int v = vxs[k] * vStride + vys[k];
								if (textured)
								{
									int l = lits[k];
									if (l > 2 && l < 126)
									{
										light[v] = l;
									}
									if (l > clamp(heightLight(heights[z], vxs[k], vys[k]), 2, 126))
									{
										texturedTooBright++;
									}
								}
								else
								{
									if (corners == cVertex.length)
									{
										cVertex = grow(cVertex);
										cLit = grow(cLit);
										cUnlit = grow(cUnlit);
										cLayer = Arrays.copyOf(cLayer, cLayer.length * 2);
									}
									cVertex[corners] = v;
									cLit[corners] = lits[k];
									cUnlit[corners] = unlit;
									cLayer[corners] = (byte) (overlay(z, x, y) != NONE ? 1 : 0);
									corners++;
								}
							}
						}
						SceneTileModel model = t.getSceneTileModel();
						if (model != null)
						{
							final int[] faceX = model.getFaceX(), faceY = model.getFaceY(), faceZ = model.getFaceZ();
							final int[] vertexX = model.getVertexX(), vertexZ = model.getVertexZ();
							final int[] colorA = model.getTriangleColorA(), colorB = model.getTriangleColorB(), colorC = model.getTriangleColorC();
							final int[] textures = model.getTriangleTextureId();
							final int baseX = (x - sceneOffset) * 128, baseY = (y - sceneOffset) * 128;
							for (int f = 0; f < faceX.length; f++)
							{
								if (colorA[f] == HIDDEN_HSL)
								{
									continue;
								}
								final boolean isOverlay = TerrainVertices.isOverlayFace(t, f);
								final boolean textured = textures != null && textures[f] != -1;
								final int unlit = layer(z, x, y, isOverlay);
								int[] verts = {faceX[f], faceY[f], faceZ[f]};
								int[] lits = {colorA[f], colorB[f], colorC[f]};
								for (int k = 0; k < 3; k++)
								{
									int dx = vertexX[verts[k]] - baseX, dz = vertexZ[verts[k]] - baseY;
									if ((dx != 0 && dx != 128) || (dz != 0 && dz != 128))
									{
										continue; // edge and centre vertices average the corners
									}
									int vx = x + (dx == 128 ? 1 : 0), vy = y + (dz == 128 ? 1 : 0);
									int v = vx * vStride + vy;
									if (textured)
									{
										int l = lits[k];
										if (l > 2 && l < 126)
										{
											light[v] = l;
										}
										if (l > clamp(heightLight(heights[z], vx, vy), 2, 126))
										{
											texturedTooBright++;
										}
									}
									else
									{
										if (corners == cVertex.length)
										{
											cVertex = grow(cVertex);
											cLit = grow(cLit);
											cUnlit = grow(cUnlit);
											cLayer = Arrays.copyOf(cLayer, cLayer.length * 2);
										}
										cVertex[corners] = v;
										cLit[corners] = lits[k];
										cUnlit[corners] = unlit;
										cLayer[corners] = (byte) (isOverlay ? 1 : 0);
										corners++;
									}
								}
							}
						}
					}
				}
			}

			// evaluate this plane's coloured corners while its lights are known
			for (int c = 0; c < corners; c++)
			{
				int lit = cLit[c], unlit = cUnlit[c], v = cVertex[c];
				boolean overlay = cLayer[c] == 1;
				if (unlit == NONE || unlit == HIDDEN_HSL)
				{
					missing[overlay ? 1 : 0]++;
					continue;
				}
				if ((lit & 0xFF80) == (unlit & 0xFF80))
				{
					hueSatPass[overlay ? 1 : 0]++;
				}
				else
				{
					hueSatFail[overlay ? 1 : 0]++;
				}
				int i = light[v];
				if (i != -1)
				{
					if (lit == lit(unlit, i))
					{
						lightPass++;
					}
					else
					{
						lightFail++;
					}
				}
				int ih = heightLight(heights[z], v / vStride, v % vStride);
				if ((lit & 127) > (lit(unlit, ih) & 127))
				{
					tooBright++;
				}
			}
			corners = 0;
		}
		tooBright += texturedTooBright;

		int failed = hueSatFail[0] + hueSatFail[1] + lightFail + tooBright + missing[0] + missing[1];
		log.info("Unlit terrain self-check: hue/sat underlay {}/{} overlay {}/{} corners match; lightness {}/{} where a "
				+ "textured corner gives the light; {} corners brighter than the heights allow; {} coloured corners "
				+ "without a recomputed colour{}",
			hueSatPass[0], hueSatPass[0] + hueSatFail[0], hueSatPass[1], hueSatPass[1] + hueSatFail[1],
			lightPass, lightPass + lightFail, tooBright, missing[0] + missing[1],
			failed == 0 ? "" : " -- the client's tile colour computation may have changed");
		return failed;
	}

	// self-check counters (by layer: 0 underlay, 1 overlay)
	private final int[] hueSatPass = new int[2], hueSatFail = new int[2], missing = new int[2];
	private int lightPass, lightFail, tooBright;

	private static int[] grow(int[] a)
	{
		return Arrays.copyOf(a, a.length * 2);
	}

	private static int clamp(int v, int min, int max)
	{
		return Math.min(Math.max(v, min), max);
	}
}
