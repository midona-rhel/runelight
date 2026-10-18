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
		return this;
	}
}
