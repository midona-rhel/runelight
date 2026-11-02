package com.midonarhel.lumina.light;

/**
 * A light that does not cast shadows. Position in world space (game units, y down), color in 0-1,
 * intensity scales the color, radius is where the light reaches zero.
 */
public class PointLight
{
	float x, y, z;
	float radius;
	float r, g, b;
	float intensity;
	// logical plane, -1 unknown: the storey whose walls and floors stop it
	int plane = -1;
	// where its lines to what it lights start (x, z: in its own tile, off the wall it hangs on), and its storey's floor
	// and ceiling (y, down); none known: its position, no floor or ceiling
	float ox, oz, floorY = Float.POSITIVE_INFINITY, ceilY = Float.NEGATIVE_INFINITY;
	// what decides which lights a full cluster keeps (LightClusters): steady, its definition's strength times reach
	float importance;
	// the scene object it is on (VoxelCapture.ownerOf, 0: none): written to scene dumps for the light map's bake
	int owner;

	public PointLight set(float x, float y, float z, float radius, float r, float g, float b, float intensity)
	{
		this.x = x;
		this.y = y;
		this.z = z;
		this.radius = radius;
		this.r = r;
		this.g = g;
		this.b = b;
		this.intensity = intensity;
		this.plane = -1;
		ox = x;
		oz = z;
		floorY = Float.POSITIVE_INFINITY;
		ceilY = Float.NEGATIVE_INFINITY;
		importance = intensity * radius;
		owner = 0;
		return this;
	}

	public PointLight onPlane(int plane)
	{
		this.plane = plane;
		return this;
	}

	/** Where its lines start, and its storey's floor and ceiling (lamp.glsl). */
	PointLight from(float ox, float oz, float floorY, float ceilY)
	{
		this.ox = ox;
		this.oz = oz;
		this.floorY = floorY;
		this.ceilY = ceilY;
		return this;
	}

	/** What a full cluster keeps it by: more is kept first. */
	PointLight importance(float importance)
	{
		this.importance = importance;
		return this;
	}
}
