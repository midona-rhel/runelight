package com.midonarhel.lumina.lava;

/** Explicit terrain and object-face lava identities; ordinary yellow objects are unaffected. */
public final class LavaMaterials
{
	private LavaMaterials() { }
	private static final boolean ENABLED = Boolean.getBoolean("lumina.lavaMaterials");
	/** Candidate upload layout requires the staged lava shader set; disabled for ordinary client launches. */
	public static boolean enabled() { return ENABLED; }
	// Representative linear radiance hue shared by the staged surface and named Fossil Island lava lights.
	// This is a color choice, not a point-light intensity or radius change.
	public static final float EMISSION_RED = 0.65f, EMISSION_GREEN = 0.12f, EMISSION_BLUE = 0.006f;

	/** 0 none, 1 untextured pool, 2 textured/crusted lava, 3 textured/ropy lava. */
	public static int variant(int overlay, int texture, boolean overlayFace)
	{
		if (texture == 31) return 2;
		if (texture == 40) return 3;
		return overlayFace && overlay == 19 ? 1 : 0;
	}

	/** Fossil Island's captured untextured lava is overlay 152, distinct from generic lava overlay 19. */
	public static int variant(int overlay, int texture, boolean overlayFace, int worldX, int worldY)
	{
		if (overlayFace && overlay == 152 && worldX >= 3776 && worldX < 3840 && worldY >= 3712 && worldY < 3840)
			return 1;
		return variant(overlay, texture, overlayFace);
	}

	public static boolean fossilLavaObject(int objectId)
	{
		return objectId >= 14417 && objectId <= 14427 || objectId == 14429;
	}

	/**
	 * These captured volcanic models use material H=8,S=7 for molten faces, including every face of sheet14427.
	 * Their rock/bank faces use different exact material families. Lightness varies with baked lighting and contouring.
	 * Restrict the override to these object IDs and require all corners, so nearby yellow objects and rock stay intact.
	 */
	public static int objectFaceVariant(int objectId, int texture, int colorA, int colorB, int colorC)
	{
		return fossilLavaObject(objectId) && texture == -1
			&& ((colorA & 0xffff) >> 7) == 71 && ((colorB & 0xffff) >> 7) == 71 && ((colorC & 0xffff) >> 7) == 71 ? 1 : 0;
	}

	/** Reuse unused snow class 3 as lava; low two material bits carry the variant. */
	public static int tag(int textureAndMaterial, int variant)
	{
		if (variant == 0) return textureAndMaterial;
		return (textureAndMaterial & ~((3 << 14) | (7 << 9))) | (3 << 14) | ((variant - 1) << 9);
	}
}
