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
package com.midonarhel.lumina.shadow;

/**
 * The light-space square a shadow map covers, anchored to the world so the camera never moves it.
 * <p>
 * Light space: {@code x} along {@link #right} (horizontal, perpendicular to the light), {@code y} along {@link #up}
 * (perpendicular to both), {@code z} along the light's travel direction (minus the caster direction). The centre is
 * the camera focal point taken at a fixed height (the scene's height midpoint, so walking uphill changes nothing),
 * snapped to {@link #CHUNK} steps in light-space x and y; a fit is stale once the focal point has crossed half a chunk
 * from the snapped centre, or the caster has turned so far that a caster of {@link #REFERENCE_HEIGHT} units would
 * move its shadow more than a texel (shadow length h/tan(e) changes by h/sin²(e) per radian). The radius is the draw
 * distance plus a margin, capped; the depth range brackets every point of the square's column between the scene's
 * height bounds (plus room for objects), at the caster's elevation.
 * <p>
 * Two matrices: {@link #proj} maps local world coordinates to clip space for the depth pass (NDC z in [0,1] with
 * glClipControl ZERO_TO_ONE, else [-1,1]); {@link #ref} maps them to the receiver's (u, v, depth) in [0,1]³.
 * Both are column-major, {@code m[col * 4 + row]}. Pure math, no GL.
 */
public final class ShadowFit
{
	/** The snapping step of the centre, world units (a zone). */
	public static final int CHUNK = 1024;
	/** Tiles of margin around the draw distance: casters just outside the view still shadow what is inside. */
	public static final int MARGIN_TILES = 8;
	/** The radius cap, tiles: beyond this the texels get too coarse for 2048². */
	public static final int MAX_RADIUS_TILES = 90;
	/** World units above the highest terrain that casters (trees, towers) may reach. */
	public static final float OBJECT_HEIGHT = 2048;
	/** The caster height the texel criterion is for, world units (a tall tree). */
	public static final float REFERENCE_HEIGHT = 512;
	/** The texel criterion: the caster may turn until a REFERENCE_HEIGHT caster's shadow moved this many texels. */
	public static final float STALE_TEXELS = 1;
	private static final float DEPTH_MARGIN = 256;

	/** Unit vector toward the light (y down, so up is -y). */
	public final float[] dir = new float[3];
	/** Light-space axes in world coordinates. */
	public final float[] right = new float[3];
	public final float[] up = new float[3];
	/** The light's travel direction, -dir. */
	public final float[] forward = new float[3];

	/** The snapped centre, light-space x and y, and the depth origin z (the focal point's). */
	public float centreX, centreY, centreZ;
	/** The fixed height the focal point is taken at. */
	public float fixedY;
	public float radius;
	public float near, far;
	public int size;
	public boolean zeroToOne;
	public boolean valid;

	public final float[] proj = new float[16];
	public final float[] ref = new float[16];

	/** The radius for a draw distance: the distance plus the margin, capped, in world units. */
	public static float radiusFor(int drawDistanceTiles)
	{
		return Math.min(drawDistanceTiles + MARGIN_TILES, MAX_RADIUS_TILES) * 128f;
	}

	/**
	 * Fits the square for this caster around the focal point (local world x and z) at the fixed height.
	 *
	 * @param minY   the scene's highest point (y down: the smallest height), world units
	 * @param maxY   the scene's lowest point
	 * @param radius half the square's side, world units
	 * @param size   texels along a side
	 */
	public void fit(float[] casterDir, float focalX, float focalZ, float fixedY, float minY, float maxY, float radius, int size,
		boolean zeroToOne)
	{
		System.arraycopy(casterDir, 0, dir, 0, 3);
		axes();
		this.fixedY = fixedY;
		this.radius = radius;
		this.size = size;
		this.zeroToOne = zeroToOne;

		float px = dot(right, focalX, fixedY, focalZ);
		float py = dot(up, focalX, fixedY, focalZ);
		centreX = Math.round(px / CHUNK) * (float) CHUNK;
		centreY = Math.round(py / CHUNK) * (float) CHUNK;
		centreZ = dot(forward, focalX, fixedY, focalZ);

		// |z| <= (H + R) / sin(elevation) for any point of the square's column within the height bounds (see the class
		// comment's derivation: p.y - c.y = b up.y + z forward.y, |b| <= R, |up.y| <= 1)
		float h = Math.max(Math.abs(minY - OBJECT_HEIGHT - fixedY), Math.abs(maxY - fixedY));
		float sinElevation = Math.max(forward[1], 0.1f);
		float depth = (h + radius) / sinElevation + DEPTH_MARGIN;
		near = -depth;
		far = depth;
		matrices();
		valid = true;
	}

	/**
	 * A second map at the same centre, direction and depth range with its own size at the same texel density (the
	 * dynamic map beside a larger static one).
	 */
	public void copyScaled(ShadowFit from, int size)
	{
		System.arraycopy(from.dir, 0, dir, 0, 3);
		System.arraycopy(from.right, 0, right, 0, 3);
		System.arraycopy(from.up, 0, up, 0, 3);
		System.arraycopy(from.forward, 0, forward, 0, 3);
		centreX = from.centreX;
		centreY = from.centreY;
		centreZ = from.centreZ;
		fixedY = from.fixedY;
		near = from.near;
		far = from.far;
		zeroToOne = from.zeroToOne;
		this.size = size;
		radius = from.radius * size / from.size;
		matrices();
		valid = true;
	}

	/**
	 * Whether a map drawn with this fit no longer serves the focal point and caster: the focal point crossed half a
	 * chunk in light-space x or y, or the caster turned beyond the texel criterion (or around).
	 */
	public boolean stale(float[] casterDir, float focalX, float focalZ)
	{
		if (!valid)
		{
			return true;
		}
		float px = dot(right, focalX, fixedY, focalZ);
		float py = dot(up, focalX, fixedY, focalZ);
		if (Math.abs(px - centreX) > CHUNK / 2f || Math.abs(py - centreY) > CHUNK / 2f)
		{
			return true;
		}
		return directionStale(casterDir);
	}

	/** The texel criterion alone. */
	public boolean directionStale(float[] casterDir)
	{
		float d = casterDir[0] * dir[0] + casterDir[1] * dir[1] + casterDir[2] * dir[2];
		if (d < 0)
		{
			return true;
		}
		float cx = casterDir[1] * dir[2] - casterDir[2] * dir[1];
		float cy = casterDir[2] * dir[0] - casterDir[0] * dir[2];
		float cz = casterDir[0] * dir[1] - casterDir[1] * dir[0];
		float angle = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
		float sinElevation = Math.max(-casterDir[1], 0.1f);
		return angle > STALE_TEXELS * texelWorld() * sinElevation * sinElevation / REFERENCE_HEIGHT;
	}

	/**
	 * The scene moved under the map (a scene swap): every world point's local coordinates changed by (dx, 0, dz), so
	 * the centre follows and the map's content stays valid.
	 */
	public void shift(float dx, float dz)
	{
		centreX += right[0] * dx + right[2] * dz;
		centreY += up[0] * dx + up[2] * dz;
		centreZ += forward[0] * dx + forward[2] * dz;
		matrices();
	}

	/** World units one texel covers. */
	public float texelWorld()
	{
		return 2 * radius / size;
	}

	/** The receiver's depth bias for this many texels of world distance along the light, in [0,1] depth units. */
	public float depthBias(float texels)
	{
		return texels * texelWorld() / (far - near);
	}

	/**
	 * The receiver-space (u, v) of a local world point into {@code out[0..1]}, and its depth into {@code out[2]}.
	 */
	public void project(float x, float y, float z, float[] out)
	{
		out[0] = ref[0] * x + ref[4] * y + ref[8] * z + ref[12];
		out[1] = ref[1] * x + ref[5] * y + ref[9] * z + ref[13];
		out[2] = ref[2] * x + ref[6] * y + ref[10] * z + ref[14];
	}

	private void axes()
	{
		forward[0] = -dir[0];
		forward[1] = -dir[1];
		forward[2] = -dir[2];
		// right = normalize(cross(worldUp, forward)) with worldUp = (0, -1, 0): horizontal
		float rx = -forward[2];
		float rz = forward[0];
		float rl = (float) Math.sqrt(rx * rx + rz * rz);
		if (rl < 1e-4f)
		{
			// the light straight down: any horizontal axis
			rx = 1;
			rz = 0;
			rl = 1;
		}
		right[0] = rx / rl;
		right[1] = 0;
		right[2] = rz / rl;
		// up = cross(forward, right)
		up[0] = forward[1] * right[2] - forward[2] * right[1];
		up[1] = forward[2] * right[0] - forward[0] * right[2];
		up[2] = forward[0] * right[1] - forward[1] * right[0];
	}

	private void matrices()
	{
		float range = far - near;
		// the depth pass: x, y in [-1,1], z per the clip control
		float zs = (zeroToOne ? 1 : 2) / range;
		float zo = (-centreZ - near) * zs - (zeroToOne ? 0 : 1);
		fill(proj, 1 / radius, -centreX / radius, -centreY / radius, zs, zo);
		// the receiver: u, v and depth in [0,1]
		float s = 1 / (2 * radius);
		fill(ref, s, -centreX * s + 0.5f, -centreY * s + 0.5f, 1 / range, (-centreZ - near) / range);
	}

	private void fill(float[] m, float xyScale, float xOff, float yOff, float zScale, float zOff)
	{
		m[0] = right[0] * xyScale;
		m[4] = right[1] * xyScale;
		m[8] = right[2] * xyScale;
		m[12] = xOff;
		m[1] = up[0] * xyScale;
		m[5] = up[1] * xyScale;
		m[9] = up[2] * xyScale;
		m[13] = yOff;
		m[2] = forward[0] * zScale;
		m[6] = forward[1] * zScale;
		m[10] = forward[2] * zScale;
		m[14] = zOff;
		m[3] = 0;
		m[7] = 0;
		m[11] = 0;
		m[15] = 1;
	}

	private static float dot(float[] a, float x, float y, float z)
	{
		return a[0] * x + a[1] * y + a[2] * z;
	}

	@Override
	public String toString()
	{
		return String.format("dir=(%.3f,%.3f,%.3f) centre=(%.0f,%.0f) fixedY=%.0f radius=%.0f depth=[%.0f,%.0f] size=%d texel=%.2f",
			dir[0], dir[1], dir[2], centreX, centreY, fixedY, radius, near, far, size, texelWorld());
	}
}
