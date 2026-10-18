package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The edge of the drawn world, and what is drawn inside it (edge_fade.glsl).
 *
 * The client draws the scenery in a square of the draw distance around the camera, which swings around the player
 * as the camera turns, so zones at the far corners came and went while spinning. Instead the world ends at a circle
 * of the draw distance around the camera's focal point (the player), which turning does not move: the client is
 * given a larger square that covers the circle wherever the camera is, zones and models outside the circle are
 * skipped, and everything fades into the sky behind it toward the circle, all sky at it, so where the culling stops
 * never shows. The fade is short in clear weather and reaches much further in fog.
 *
 * Zones are also tested against the view frustum with generous bounds (117 HD does the same), so tall trees and
 * towers whose ground is off screen still draw their tops at the screen's edges.
 */
final class EdgeFade
{
	static final int TILE = 128;
	// zones are tested this much larger than their 8x8 tiles: objects reach over zone borders
	static final int PAD_XZ = 4 * TILE;
	// and this much taller than the heights the client gives: tree canopies, towers, roofs
	static final int PAD_UP = 10 * TILE;
	static final int PAD_DOWN = 2 * TILE;
	// models are skipped once their center is this far past the edge
	static final int MODEL_PAD = 6 * TILE;
	// The client's square is extended past the circle by this many tiles: it is centered on the camera's tile, one
	// tile short to the north and east, and may leave out zones it only partly covers.
	static final int CLIENT_MARGIN_TILES = 10;
	// near plane for the frustum test, world units in front of the camera
	static final float NEAR = 50;

	// Fade width in fog: up to this share of the radius
	static final float FOGGY_SHARE = 0.6f;
	// weather fog (Weather.fogMultiplier) from where the fade starts to widen to thick fog
	static final float WEATHER_FOG_CLEAR = 0.3f;
	static final float WEATHER_FOG_THICK = 2.0f;
	// an area's 117 HD fog depth, the same way
	static final float AREA_FOG_CLEAR = 5;
	static final float AREA_FOG_THICK = 45;
	// the width eases toward its target over about this long, real seconds
	static final float WIDTH_SECONDS = 2;

	// this frame: focal point, radius and fade width (world units), the loaded scene's bounds
	float focalX, focalZ, radius, width = -1;
	float sceneMin, sceneMax;
	private boolean valid;
	private long widthMillis;

	// view frustum, camera-relative world space: 5 planes (left, right, bottom, top, near), a b c d each
	private final float[] planes = new float[20];
	private float cameraX, cameraY, cameraZ;

	/**
	 * The draw distance in tiles to give the client, so that its square around the camera covers the circle of
	 * {@code drawDistance} tiles around the focal point.
	 *
	 * @param offset horizontal distance from the camera to the focal point, world units
	 */
	static int clientDrawDistance(int drawDistance, float offset)
	{
		return drawDistance + (int) Math.ceil(offset / TILE) + CLIENT_MARGIN_TILES;
	}

	/**
	 * The radius of the drawn world around the focal point, world units: the draw distance, or less if the client
	 * would not take the larger square.
	 *
	 * @param clientDistance the draw distance the client actually uses, tiles
	 */
	static float radius(int drawDistance, int clientDistance, float offset)
	{
		return Math.max(0, Math.min(drawDistance * TILE, (clientDistance - CLIENT_MARGIN_TILES) * TILE - offset));
	}

	private static float smooth01(float x)
	{
		x = Math.max(0, Math.min(1, x));
		return x * x * (3 - 2 * x);
	}

	/**
	 * 0 in clear air, 1 in thick fog.
	 *
	 * @param weatherFog Weather.fogMultiplier
	 * @param areaFog    the area's 117 HD fog depth, scaled by the area fog setting and the weather
	 */
	static float fogginess(float weatherFog, float areaFog)
	{
		float w = smooth01((weatherFog - WEATHER_FOG_CLEAR) / (WEATHER_FOG_THICK - WEATHER_FOG_CLEAR));
		float a = smooth01((areaFog - AREA_FOG_CLEAR) / (AREA_FOG_THICK - AREA_FOG_CLEAR));
		return 1 - (1 - w) * (1 - a);
	}

	/** Fade width, world units: {@code clearTiles} in clear air, up to FOGGY_SHARE of the radius in fog. */
	static float targetWidth(int clearTiles, float radius, float fogginess)
	{
		float clear = Math.min(clearTiles * TILE, radius);
		return clear + (Math.max(clear, radius * FOGGY_SHARE) - clear) * fogginess;
	}

	/** The loaded scene's edges, as the vanilla fog has them: a tile in from the last loaded one. */
	static float sceneMin(int expandedChunks)
	{
		return (-expandedChunks * 8 + 1) * TILE;
	}

	static float sceneMax(int expandedChunks)
	{
		return (104 + expandedChunks * 8 - 1) * TILE;
	}

	/**
	 * This frame's edge.
	 *
	 * @param clearTiles fade width in clear air (config)
	 */
	void update(float focalX, float focalZ, float radius, int expandedChunks, int clearTiles, float fogginess)
	{
		this.focalX = focalX;
		this.focalZ = focalZ;
		this.radius = radius;
		sceneMin = sceneMin(expandedChunks);
		sceneMax = sceneMax(expandedChunks);
		float target = targetWidth(clearTiles, radius, fogginess);
		long now = System.currentTimeMillis();
		float dt = Math.max(0, Math.min(0.1f, (now - widthMillis) / 1000f));
		widthMillis = now;
		// weather and areas change gradually already; this only keeps a sudden change from snapping
		width = width < 0 ? target : width + (target - width) * Math.min(1, dt / WIDTH_SECONDS * 3);
		width = Math.min(width, radius);
		valid = true;
	}

	void invalidate()
	{
		valid = false;
	}

	/**
	 * The view frustum, from the column-major camera-relative world to clip matrix (vanilla projection: clip w is
	 * the distance in front of the camera).
	 */
	void setFrustum(float[] viewProj, float cameraX, float cameraY, float cameraZ)
	{
		this.cameraX = cameraX;
		this.cameraY = cameraY;
		this.cameraZ = cameraZ;
		// rows of the matrix: row r is m[r], m[4 + r], m[8 + r], m[12 + r]
		for (int i = 0; i < 4; ++i)
		{
			float w = viewProj[i * 4 + 3];
			planes[i] = w + viewProj[i * 4];            // left: w + x >= 0
			planes[4 + i] = w - viewProj[i * 4];        // right
			planes[8 + i] = w + viewProj[i * 4 + 1];    // bottom
			planes[12 + i] = w - viewProj[i * 4 + 1];   // top
			planes[16 + i] = w;                         // in front: w >= NEAR
		}
		planes[19] -= NEAR;
	}

	/** Whether any of the world box (world units, min and max corners) can be in front of all five planes. */
	boolean inFrustum(float minX, float minY, float minZ, float maxX, float maxY, float maxZ)
	{
		minX -= cameraX;
		maxX -= cameraX;
		minY -= cameraY;
		maxY -= cameraY;
		minZ -= cameraZ;
		maxZ -= cameraZ;
		for (int p = 0; p < 20; p += 4)
		{
			float a = planes[p], b = planes[p + 1], c = planes[p + 2], d = planes[p + 3];
			// the corner furthest along the plane's normal
			float x = a >= 0 ? maxX : minX;
			float y = b >= 0 ? maxY : minY;
			float z = c >= 0 ? maxZ : minZ;
			if (a * x + b * y + c * z + d < 0)
			{
				return false;
			}
		}
		return true;
	}

	/** Whether a horizontal box reaches inside the circle (world units). */
	boolean inCircle(float minX, float minZ, float maxX, float maxZ)
	{
		float dx = Math.max(Math.max(minX - focalX, focalX - maxX), 0);
		float dz = Math.max(Math.max(minZ - focalZ, focalZ - maxZ), 0);
		return dx * dx + dz * dz <= radius * radius;
	}

	/**
	 * Whether to draw a zone, from its corner and the heights the client gives (y points down, so minY is its top).
	 */
	boolean zoneVisible(int x, int z, int minY, int maxY)
	{
		if (!valid)
		{
			return true;
		}
		float x0 = x - PAD_XZ, z0 = z - PAD_XZ, x1 = x + 8 * TILE + PAD_XZ, z1 = z + 8 * TILE + PAD_XZ;
		return inCircle(x0, z0, x1, z1)
			&& inFrustum(x0, Math.min(minY, maxY) - PAD_UP, z0, x1, Math.max(minY, maxY) + PAD_DOWN, z1);
	}

	/** Whether a model at this world position can show inside the edge. */
	boolean modelVisible(int x, int z)
	{
		if (!valid)
		{
			return true;
		}
		float dx = x - focalX, dz = z - focalZ;
		float r = radius + MODEL_PAD;
		return dx * dx + dz * dz <= r * r;
	}

	/** The uniforms of edge_fade.glsl, for a program in use. */
	static final class Uniforms
	{
		private int edge = -1, scene = -1;

		void locate(int program)
		{
			edge = glGetUniformLocation(program, "edgeFade");
			scene = glGetUniformLocation(program, "edgeScene");
		}

		void set(EdgeFade e)
		{
			glUniform4f(edge, e.focalX, e.focalZ, e.radius, Math.max(e.width, 1));
			glUniform2f(scene, e.sceneMin, e.sceneMax);
		}
	}
}
