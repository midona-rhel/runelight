package rs117.hd;

/**
 * What Lumina has brought back on top of 117 HD's renderer. With all of these off it draws the game with 117 HD's
 * ambient light alone. A feature that is false here does no work whatever its settings say, and its settings are
 * hidden; bringing one back is turning it on here, measured against the frame before.
 *
 * The values are constants so the settings' hidden flags can use them.
 */
public final class Features
{
	/** The sun and moon (directional light and its highlights) and the sky's light on what faces up. */
	public static final boolean SUN = false;
	/** Shadow maps. */
	public static final boolean SHADOWS = false;
	/** Point lights from objects, NPCs, projectiles and the rest. */
	public static final boolean LIGHTS = false;
	/** The daylight cycle and its sky; without it the background is the area's fog color. */
	public static final boolean SKY = false;
	/** Distance fog and ground fog. */
	public static final boolean FOG = false;

	private Features()
	{
	}
}
