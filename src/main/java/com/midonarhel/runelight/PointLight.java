package com.midonarhel.runelight;

/**
 * A light that does not cast shadows. Position in world space (game units, y down), color in 0-1,
 * intensity scales the color, radius is where the light reaches zero.
 */
class PointLight
{
	float x, y, z;
	float radius;
	float r, g, b;
	float intensity;
	// logical plane, -1 unknown: the storey it lights
	int plane = -1;

	PointLight set(float x, float y, float z, float radius, float r, float g, float b, float intensity)
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
		return this;
	}

	PointLight onPlane(int plane)
	{
		this.plane = plane;
		return this;
	}
}
