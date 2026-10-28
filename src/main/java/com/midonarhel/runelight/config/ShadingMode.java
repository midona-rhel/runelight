package com.midonarhel.runelight.config;

/**
 * 117 HD's shading modes (its ShadingMode), with its names: where a surface's shading comes from.
 */
public enum ShadingMode
{
	/** The shading baked into the game's colors removed; the sun does the directional shading. */
	DEFAULT("117 HD", false, true, true),
	/** The game's baked shading kept; the sun lights every face the same. */
	VANILLA("Vanilla", false, false, false),
	/** The game's unlit colors, every face lit the same. */
	NONE("None", true, false, false),
	/** The game's unlit colors, shaded by the sun. */
	UNLIT("Experimental", true, false, true);

	private final String name;
	/** Untextured faces take the model's unlit colors. */
	public final boolean unlitFaceColors;
	/** The light the game baked into its colors is taken back out. */
	public final boolean undoVanillaShading;
	/** The sun shades by the angle it meets a surface at (N.L); without it 117 HD's constant 0.7. */
	public final boolean directionalShading;

	ShadingMode(String name, boolean unlitFaceColors, boolean undoVanillaShading, boolean directionalShading)
	{
		this.name = name;
		this.unlitFaceColors = unlitFaceColors;
		this.undoVanillaShading = undoVanillaShading;
		this.directionalShading = directionalShading;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
