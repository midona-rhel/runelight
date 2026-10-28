package com.midonarhel.runelight;

/**
 * What the forward renderer (RuneLightPlugin.FORWARD) has brought back so far. A feature that is false here does no
 * work at all, whatever its settings say: nothing per frame, at scene load or on a background thread, and no pass;
 * its settings are hidden, and a settings section with nothing left to show loses its header. Bringing a feature
 * back is turning it on here, measured against the frame before; the deferred renderer has them all.
 *
 * The values are constants so the settings' hidden flags can use them.
 */
final class Features
{
	private static final boolean DEFERRED = !RuneLightPlugin.FORWARD;

	/** The sun and moon (or the cave light underground) and the sky's light, with the game's baked shading removed. */
	static final boolean SUN = true;
	/** Shadows, AO, point lights and the light map, interiors, glow and light through leaves. */
	static final boolean LIGHTING = DEFERRED;
	/** The procedural sky, clouds and the night sky. */
	static final boolean SKY = DEFERRED;
	/** Rain, snow, lightning, weather sounds, wet ground and snow cover. */
	static final boolean WEATHER = DEFERRED;
	/** RuneLight's water: the surface pass, sunk beds, foam, reflections and waterfalls. */
	static final boolean WATER = DEFERRED;
	/** Volumetric fog. */
	static final boolean FOG = DEFERRED;
	/** Area environments and wind sway. */
	static final boolean ENVIRONMENT = DEFERRED;
	/** Ground blending, a change to the terrain's colors at upload. */
	static final boolean SURFACES = DEFERRED;

	private Features()
	{
	}
}
