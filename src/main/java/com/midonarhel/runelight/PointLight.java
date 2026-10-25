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
	// A light that stays put and shines the same every time (scenery and world lights, not those tied to an animation)
	// can be baked into the light volume (LightVolume). bakeKey stands for it there (null: not baked); the rest is the
	// light as baked, without the swing of a flicker or pulse: its radius, its strength, and how much of that its
	// strength is now (bakeMod), with bakeModulated saying that it swings at all.
	Object bakeKey;
	float bakeRadius, bakeStrength, bakeMod = 1;
	boolean bakeModulated;
	// the light is there for the volume only: not drawn on its own, as it is hidden from this plane
	boolean bakeOnly;
	// the epoch the volume took it in at, 0 when it is not in it yet (LightVolume.epochOf)
	int bakeEpoch;

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
		this.bakeKey = null;
		this.bakeMod = 1;
		this.bakeModulated = false;
		this.bakeOnly = false;
		this.bakeEpoch = 0;
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

	/**
	 * Make the light one the volume can bake.
	 *
	 * @param key what stands for it from frame to frame
	 * @param strength its strength without the swing, which is {@code mod} of the strength now
	 */
	PointLight bakeable(Object key, float radius, float strength, float mod, boolean modulated)
	{
		bakeKey = key;
		bakeRadius = radius;
		bakeStrength = strength;
		bakeMod = mod;
		bakeModulated = modulated;
		return this;
	}
}
