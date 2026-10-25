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
	 * Boxes, [minX, minY, minZ, maxX, maxY, maxZ] in world tiles and planes, that the light spawns in; null for
	 * anywhere. Not in excludeAreas, which is null for nowhere.
	 */
	public int[][] areas, excludeAreas;

	/**
	 * Animations (117 HD's gamevals) of the parent that show the light; none for any. Not in
	 * excludeAnimationIds. -1 is no animation. With waitForAnimation it also needs one to be playing.
	 */
	public int[] animationIds, excludeAnimationIds;
	public boolean waitForAnimation;

	/**
	 * Height above the ground, game units.
	 */
	public int height;
	/**
	 * x, y (up), z in game units; rotated with the parent's orientation.
	 */
	public float[] offset;
	/**
	 * Where on the parent the light goes instead of at offset.
	 */
	public Alignment alignment = Alignment.CUSTOM;
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
	/**
	 * Milliseconds from the light being made to it spawning, and from its parent going to it fading out.
	 */
	public int spawnDelay, despawnDelay;
	/**
	 * It lasts spawnDelay + despawnDelay, whatever its parent does.
	 */
	public boolean fixedDespawnTime;
	/**
	 * It goes the moment its parent does.
	 */
	public boolean despawnWithParent;
	/**
	 * The light of an NPC stays when another actor stands on its tile.
	 */
	public boolean ignoreActorHiding;
	/**
	 * Light from the sky outside: its color and strength follow the time of day (OutdoorLight).
	 */
	public boolean outdoorLighting;

	// derived after loading
	public transient int kind;
	public transient float red, green, blue;
	public transient boolean nightOnly;

	/**
	 * 117 HD's alignments. CUSTOM puts the light at offset. The others put it on the edge of the parent's footprint,
	 * facing orientation (JAU, 0 north then clockwise): turned with the parent when relative, at its corner rather than
	 * its side when not radial.
	 */
	enum Alignment
	{
		CUSTOM(0, false, true),
		NORTH(0, true, false), NORTHEAST(256, true, false), NORTHEAST_CORNER(256, false, false),
		EAST(512, true, false), SOUTHEAST(768, true, false), SOUTHEAST_CORNER(768, false, false),
		SOUTH(1024, true, false), SOUTHWEST(1280, true, false), SOUTHWEST_CORNER(1280, false, false),
		WEST(1536, true, false), NORTHWEST(1792, true, false), NORTHWEST_CORNER(1792, false, false),
		BACK(0, true, true), BACKLEFT(256, true, true), BACKLEFT_CORNER(256, false, true),
		LEFT(512, true, true), FRONTLEFT(768, true, true), FRONTLEFT_CORNER(768, false, true),
		FRONT(1024, true, true), FRONTRIGHT(1280, true, true), FRONTRIGHT_CORNER(1280, false, true),
		RIGHT(1536, true, true), BACKRIGHT(1792, true, true), BACKRIGHT_CORNER(1792, false, true);

		final int orientation;
		final boolean radial, relative;

		Alignment(int orientation, boolean radial, boolean relative)
		{
			this.orientation = orientation;
			this.radial = radial;
			this.relative = relative;
		}
	}

	private static final float JAU_TO_RAD = (float) (Math.PI * 2 / 2048);
	private static final float TILE = 128;

	public static final int STATIC = 0;
	public static final int FLICKER = 1;
	public static final int PULSE = 2;

	private static float srgbToLinear(float c)
	{
		return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055) / 1.055, 2.4);
	}

	void prepare()
	{
		if (color == null || color.length < 3)
		{
			color = new float[]{255, 255, 255};
		}
		// linear, like 117 HD: their strengths are tuned for lighting in linear space
		red = srgbToLinear(color[0] / 255f);
		green = srgbToLinear(color[1] / 255f);
		blue = srgbToLinear(color[2] / 255f);
		kind = "FLICKER".equals(type) ? FLICKER : "PULSE".equals(type) ? PULSE : STATIC;
		nightOnly = "NIGHT".equals(schedule);
		if (offset == null || offset.length != 3)
		{
			offset = new float[3];
		}
	}

	/**
	 * Whether a light at this world tile may spawn: inside one of the areas, outside all of excludeAreas.
	 */
	boolean allowedAt(int worldX, int worldY, int plane)
	{
		return (areas == null || inBoxes(areas, worldX, worldY, plane))
			&& (excludeAreas == null || !inBoxes(excludeAreas, worldX, worldY, plane));
	}

	private static boolean inBoxes(int[][] boxes, int x, int y, int plane)
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

	/**
	 * Whether the light shows or hides with an animation of its parent.
	 */
	boolean animationSpecific()
	{
		return waitForAnimation || animationIds != null || excludeAnimationIds != null;
	}

	/**
	 * Whether the parent playing this animation (-1: none) shows the light.
	 *
	 * @param playing whether it is actually playing, for waitForAnimation
	 */
	boolean matchesAnimation(int animationId, boolean playing)
	{
		if (waitForAnimation && !playing)
		{
			return false;
		}
		if (excludeAnimationIds != null && contains(excludeAnimationIds, animationId))
		{
			return false;
		}
		return animationIds == null || contains(animationIds, animationId);
	}

	private static boolean contains(int[] ids, int id)
	{
		for (int i : ids)
		{
			if (i == id)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Where the light goes for a parent standing at x, z on the ground at this height (y down), as 117 HD puts it.
	 * Orientation is in game units (2048 per turn, 0 = south); the offsets turn with it and their y points up. An
	 * alignment ignores the offsets, and puts the light on the edge of the footprint, sizeX by sizeY tiles.
	 *
	 * @param out x, y, z
	 */
	void locate(float[] out, float x, float ground, float z, int orientation, int sizeX, int sizeY)
	{
		if (alignment == Alignment.CUSTOM)
		{
			float s = (float) Math.sin(orientation * JAU_TO_RAD);
			float c = (float) Math.cos(orientation * JAU_TO_RAD);
			float ox = offset[0], oz = offset[2];
			out[0] = x - c * ox - s * oz;
			out[1] = ground - height - 1 - offset[1];
			out[2] = z - c * oz + s * ox;
			return;
		}
		// 117 HD turns only a relative alignment, so the others all face north
		float angle = (alignment.relative ? (orientation + alignment.orientation) & 2047 : 0) * JAU_TO_RAD;
		float width = sizeX * TILE;
		float reach = alignment.radial ? width / 2 : (float) Math.sqrt(2 * width * width) / 2;
		out[0] = x + reach * (float) Math.sin(angle);
		out[1] = ground - height - 1;
		out[2] = z + reach * (float) Math.cos(angle) * sizeY / sizeX;
	}
}
