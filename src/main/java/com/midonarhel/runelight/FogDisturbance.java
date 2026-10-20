package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.util.Arrays;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Actors pushing the ground fog aside. A square map of 4 cells per tile around the player holds how far the fog has
 * been pushed out of each cell (0 untouched, 1 cleared). Every {@link #STEP_SECONDS} each actor carves a soft disc
 * where it stands and the rest relaxes back toward 0, so the fog creeps back in over a few seconds and a walker
 * leaves a trail. The map wraps (cell c lives in slot c mod {@link #SIZE}), so moving the window only clears the
 * rows and columns that come into it, and the GPU sees it as a GL_REPEAT texture sampled at world position / EXTENT
 * (fog_frag.glsl). Only the rows that changed are uploaded.
 */
class FogDisturbance
{
	static final int SIZE = 256;
	/** World units per cell: 4 cells per 128 unit tile. */
	static final int CELL = 32;
	/** World units the map spans; the shader fades the effect out toward the edge of it. */
	static final int EXTENT = SIZE * CELL;
	/** Seconds between updates: a walker moves under a quarter tile per step, so its trail has no gaps. */
	static final float STEP_SECONDS = 0.05f;
	/** Seconds for a disturbance to fade to 1/e of what it was. */
	static final float RECOVERY_SECONDS = 2.5f;
	/** Disturbance left below which a cell counts as undisturbed again, so quiet rows are not touched at all. */
	static final float REST = 0.01f;
	/** Radius of the cleared area around an actor, added to half its footprint, world units. */
	static final float MARGIN = 96;

	private final float[] amount = new float[SIZE * SIZE];
	// 8 bit copy for the texture, row after row by slot
	private final ByteBuffer bytes = BufferUtils.createByteBuffer(SIZE * SIZE);
	// the absolute column and row each slot currently holds, to tell when the window has moved past it
	private final int[] slotColumn = new int[SIZE];
	private final int[] slotRow = new int[SIZE];
	private final boolean[] rowActive = new boolean[SIZE];
	private int dirtyFirst = SIZE, dirtyLast = -1;
	private float sinceStep;
	private boolean any;

	private int texture;

	FogDisturbance()
	{
		for (int i = 0; i < SIZE; ++i)
		{
			slotColumn[i] = i;
			slotRow[i] = i;
		}
	}

	/**
	 * Move the window to the cell around world x, z, clearing the rows and columns that enter it.
	 */
	void recenter(float x, float z)
	{
		int first = (int) Math.floor(x / CELL) - SIZE / 2;
		for (int slot = 0; slot < SIZE; ++slot)
		{
			int wanted = first + Math.floorMod(slot - first, SIZE);
			if (slotColumn[slot] != wanted)
			{
				slotColumn[slot] = wanted;
				for (int row = 0; row < SIZE; ++row)
				{
					amount[row * SIZE + slot] = 0;
					bytes.put(row * SIZE + slot, (byte) 0);
				}
				markDirty(0, SIZE - 1);
			}
		}
		first = (int) Math.floor(z / CELL) - SIZE / 2;
		for (int slot = 0; slot < SIZE; ++slot)
		{
			int wanted = first + Math.floorMod(slot - first, SIZE);
			if (slotRow[slot] != wanted)
			{
				slotRow[slot] = wanted;
				for (int col = 0; col < SIZE; ++col)
				{
					amount[slot * SIZE + col] = 0;
					bytes.put(slot * SIZE + col, (byte) 0);
				}
				markDirty(slot, slot);
			}
		}
	}

	/**
	 * Advance by dt seconds and, once a step has passed, relax the map and carve each actor in. Positions are in
	 * world units.
	 */
	void update(float dt, float centerX, float centerZ, WorldView wv, float strength)
	{
		sinceStep += dt;
		if (sinceStep < STEP_SECONDS)
		{
			return;
		}
		float elapsed = Math.min(sinceStep, 0.5f);
		sinceStep = 0;

		recenter(centerX, centerZ);
		relax(elapsed);
		for (Actor actor : wv.players())
		{
			carveActor(actor, 1, wv, strength);
		}
		for (NPC npc : wv.npcs())
		{
			NPCComposition composition = npc.getTransformedComposition();
			if (composition == null)
			{
				composition = npc.getComposition();
			}
			if (composition != null)
			{
				carveActor(npc, composition.getSize(), wv, strength);
			}
		}
		upload();
	}

	private void carveActor(Actor actor, int size, WorldView wv, float strength)
	{
		if (actor == null || actor.isDead() || actor.getWorldLocation().getPlane() != wv.getPlane())
		{
			return;
		}
		LocalPoint lp = actor.getLocalLocation();
		if (lp != null)
		{
			carve(lp.getX(), lp.getY(), size * 64f + MARGIN, strength);
		}
	}

	/**
	 * Push the fog out of a soft disc, never putting back what is already cleared.
	 */
	void carve(float x, float z, float radius, float strength)
	{
		int x0 = (int) Math.floor((x - radius) / CELL), x1 = (int) Math.floor((x + radius) / CELL);
		int z0 = (int) Math.floor((z - radius) / CELL), z1 = (int) Math.floor((z + radius) / CELL);
		float inv = 1 / (radius * radius);
		for (int cz = z0; cz <= z1; ++cz)
		{
			int row = Math.floorMod(cz, SIZE);
			if (slotRow[row] != cz)
			{
				continue;
			}
			float dz = (cz + 0.5f) * CELL - z;
			for (int cx = x0; cx <= x1; ++cx)
			{
				int col = Math.floorMod(cx, SIZE);
				if (slotColumn[col] != cx)
				{
					continue;
				}
				float dx = (cx + 0.5f) * CELL - x;
				float q = (dx * dx + dz * dz) * inv;
				if (q >= 1)
				{
					continue;
				}
				float falloff = 1 - q;
				float v = strength * falloff * falloff;
				int i = row * SIZE + col;
				if (v > amount[i])
				{
					amount[i] = v;
					bytes.put(i, (byte) Math.round(v * 255));
					rowActive[row] = true;
					any = true;
					markDirty(row, row);
				}
			}
		}
	}

	/**
	 * Let every disturbed cell recover by dt seconds. Rows with nothing disturbed cost nothing.
	 */
	void relax(float dt)
	{
		if (!any)
		{
			return;
		}
		float decay = (float) Math.exp(-dt / RECOVERY_SECONDS);
		any = false;
		for (int row = 0; row < SIZE; ++row)
		{
			if (!rowActive[row])
			{
				continue;
			}
			boolean left = false;
			for (int i = row * SIZE, end = i + SIZE; i < end; ++i)
			{
				float v = amount[i];
				if (v == 0)
				{
					continue;
				}
				v *= decay;
				if (v < REST)
				{
					v = 0;
				}
				else
				{
					left = true;
				}
				amount[i] = v;
				bytes.put(i, (byte) Math.round(v * 255));
			}
			rowActive[row] = left;
			any |= left;
			markDirty(row, row);
		}
	}

	float amountAt(float x, float z)
	{
		int cx = (int) Math.floor(x / CELL), cz = (int) Math.floor(z / CELL);
		int col = Math.floorMod(cx, SIZE), row = Math.floorMod(cz, SIZE);
		return slotColumn[col] == cx && slotRow[row] == cz ? amount[row * SIZE + col] : 0;
	}

	boolean isDisturbed()
	{
		return any;
	}

	private void markDirty(int first, int last)
	{
		dirtyFirst = Math.min(dirtyFirst, first);
		dirtyLast = Math.max(dirtyLast, last);
	}

	private void upload()
	{
		if (texture == 0 || dirtyLast < dirtyFirst)
		{
			return;
		}
		glBindTexture(GL_TEXTURE_2D, texture);
		bytes.limit((dirtyLast + 1) * SIZE).position(dirtyFirst * SIZE);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, dirtyFirst, SIZE, dirtyLast - dirtyFirst + 1, GL_RED, GL_UNSIGNED_BYTE, bytes);
		bytes.clear();
		glBindTexture(GL_TEXTURE_2D, 0);
		dirtyFirst = SIZE;
		dirtyLast = -1;
	}

	/**
	 * Bind the map to {@code unit}, creating it on first use.
	 */
	void bind(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		if (texture == 0)
		{
			texture = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, texture);
			glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, SIZE, SIZE, 0, GL_RED, GL_UNSIGNED_BYTE, bytes);
			glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
			dirtyFirst = SIZE;
			dirtyLast = -1;
		}
		else
		{
			glBindTexture(GL_TEXTURE_2D, texture);
		}
		glActiveTexture(GL_TEXTURE0);
	}

	static void unbind(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	/**
	 * Forget every disturbance, for when the effect is switched off.
	 */
	void clear()
	{
		if (!any && dirtyLast < dirtyFirst)
		{
			return;
		}
		Arrays.fill(amount, 0);
		Arrays.fill(rowActive, false);
		for (int i = 0; i < SIZE * SIZE; ++i)
		{
			bytes.put(i, (byte) 0);
		}
		any = false;
		markDirty(0, SIZE - 1);
		upload();
	}

	void free()
	{
		glDeleteTextures(texture);
		texture = 0;
	}
}
