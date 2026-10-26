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
	// logical plane, -1 unknown; and the rooms it reaches (RoomMap.lightCode), set when the frame's lights are culled
	int plane = -1;
	float roomCode = RoomMap.anyCode();
	// stays where it is (scenery and world lights): its view past the walls is cast once (LightOcclusion)
	boolean fixed;
	LightOcclusion.Entry occlusion;

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
		this.roomCode = RoomMap.anyCode();
		this.fixed = false;
		this.occlusion = null;
		return this;
	}

	PointLight onPlane(int plane)
	{
		this.plane = plane;
		return this;
	}

	PointLight fixed()
	{
		fixed = true;
		return this;
	}
}
