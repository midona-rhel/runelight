package com.midonarhel.runelight;

/**
 * One entry of lights.json, which is converted from 117 HD's light definitions by
 * tools/import_117hd_lights.py. Field names match the JSON.
 */
public class LightDefinition
{
	public String description;

	// fixed world position lights
	public Integer worldX, worldY;
	public int plane;

	// what the light attaches to
	public int[] objectIds;
	public int[] npcIds;
	public int[] projectileIds;
	public int[] graphicsObjectIds;

	/**
	 * Height above the ground, game units.
	 */
	public int height;
	/**
	 * x, y (up), z in game units; rotated with the parent's orientation.
	 */
	public float[] offset;
	public int radius = 300;
	public float strength = 5;
	/**
	 * sRGB 0-255.
	 */
	public float[] color;
	public String type = "STATIC";
	/**
	 * Pulse period, milliseconds.
	 */
	public float duration;
	/**
	 * Flicker or pulse amplitude, percent.
	 */
	public float range;
	public boolean visibleFromOtherPlanes;
	public float nightMultiplier = 1;
	/**
	 * "NIGHT" or null.
	 */
	public String schedule;
	public int fadeInDuration = 50;
	public int fadeOutDuration = 50;

	// derived after loading
	public transient int kind;
	public transient float red, green, blue;
	public transient boolean nightOnly;

	public static final int STATIC = 0;
	public static final int FLICKER = 1;
	public static final int PULSE = 2;

	void prepare()
	{
		if (color == null || color.length < 3)
		{
			color = new float[]{255, 255, 255};
		}
		red = color[0] / 255f;
		green = color[1] / 255f;
		blue = color[2] / 255f;
		kind = "FLICKER".equals(type) ? FLICKER : "PULSE".equals(type) ? PULSE : STATIC;
		nightOnly = "NIGHT".equals(schedule);
		if (offset == null || offset.length != 3)
		{
			offset = new float[3];
		}
	}
}
