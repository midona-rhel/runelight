package com.midonarhel.lumina.frame;

import static org.lwjgl.opengl.GL33C.glGetUniformLocation;

/**
 * The uniform locations a zone or model draw sets per draw: the zone base offset, the entity projection and the
 * entity tint. One instance per program that draws scene geometry (the scene program now; a shadow program draws
 * the same geometry with its own locations), passed explicitly to {@code Zone} and {@code VAO} draws.
 */
public final class DrawUniforms
{
	public final int base;
	public final int entityProj;
	public final int entityTint;

	DrawUniforms(int program)
	{
		base = glGetUniformLocation(program, "base");
		entityProj = glGetUniformLocation(program, "entityProj");
		entityTint = glGetUniformLocation(program, "entityTint");
	}
}
