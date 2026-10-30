package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.scene.SceneContext;
import com.midonarhel.lumina.scene.Zone;
import java.util.Arrays;
import java.util.Set;

/**
 * The frame's scene draws, recorded while the client runs its draw callbacks and replayed later in the same order.
 * The callbacks ({@link ZoneDrawer}, {@link FrameRenderer}) issue no GL draws themselves: each records what the draw
 * needs (the zone, its coordinates and levels, the roof set, a copy of the entity projection, the tint) and the
 * replay hands the commands to an {@link Executor} in recording order. Recording is GL-free, so passes that need the
 * whole frame's geometry before any of it is drawn (the shadow maps) can run between recording and replay, and the
 * same commands can be replayed into more than one pass.
 * <p>
 * Zero allocations per frame once the arrays have grown to the frame's size: commands live in parallel primitive
 * arrays with a fixed slot per command, projection copies in a pool of {@code float[16]}.
 * <p>
 * A one-frame trace of the callback order (developer log) can be armed with {@link #traceNextFrame()}: the callbacks
 * report themselves through {@link #trace}, runs of the same callback are collapsed, and {@link #traceLine()} gives
 * the line at replay.
 */
public final class DrawList
{
	/** What the replay does with each command; GL in {@link FrameRenderer}, a stub in tests. */
	public interface Executor
	{
		/** Clears the scene colour and depth (the frame's first command). */
		void clear();

		/** 117 HD's sky, drawn first without depth. */
		void skyHd117();

		/** The vanilla skybox model uploaded this frame, drawn from its own VAO. */
		void skyboxVanilla();

		/** Lumina's sky pass, after the top-level opaque geometry. */
		void skyLumina();

		void entityProj(float[] projection);

		void entityTint(int h, int s, int l, int a);

		/** A zone's opaque geometry (blending off), and its gap fillers when the ground level is drawn. */
		void zoneOpaque(Zone zone, int zx, int zz, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds, boolean gapFillers);

		/** The frame's dynamic and temporary opaque models: every render thread's VAO ranges. */
		void dynamicOpaque();

		/**
		 * A zone's alpha geometry on one level: colour without depth writes, then (depthPass) depth without colour.
		 */
		void zoneAlpha(Zone zone, int zx, int zz, int cameraYaw, int cameraPitch, int minLevel, int level, int maxLevel,
			int alphaLevel, Set<Integer> hideRoofIds, boolean unsorted, boolean depthPass);

		/** A worldview's alpha pass is over: its zones drop their temporary alpha models. */
		void removeTemp(SceneContext ctx);
	}

	static final int CLEAR = 0;
	static final int SKY_HD117 = 1;
	static final int SKYBOX_VANILLA = 2;
	static final int SKY_LUMINA = 3;
	static final int ENTITY_PROJ = 4;
	static final int ENTITY_TINT = 5;
	static final int ZONE_OPAQUE = 6;
	static final int DYNAMIC_OPAQUE = 7;
	static final int ZONE_ALPHA = 8;
	static final int REMOVE_TEMP = 9;

	// ints and objects per command slot (ZONE_ALPHA uses the most: 10 ints, 2 objects)
	private static final int INTS = 10;
	private static final int OBJS = 2;

	private byte[] ops = new byte[1024];
	private int[] ints = new int[1024 * INTS];
	private Object[] objs = new Object[1024 * OBJS];
	private int count;

	private float[][] projections = new float[8][];
	private int projectionCount;

	// the callback trace: armed for one frame, collapsed runs of (kind, level, worldview)
	private boolean tracing;
	private final StringBuilder trace = new StringBuilder();
	private char traceKind;
	private int traceLevel, traceWv, traceRun;

	public int size()
	{
		return count;
	}

	public boolean isEmpty()
	{
		return count == 0;
	}

	private int add(int op)
	{
		if (count == ops.length)
		{
			int n = ops.length << 1;
			ops = Arrays.copyOf(ops, n);
			ints = Arrays.copyOf(ints, n * INTS);
			objs = Arrays.copyOf(objs, n * OBJS);
		}
		ops[count] = (byte) op;
		return count++;
	}

	private float[] copyProjection(float[] projection)
	{
		if (projectionCount == projections.length)
		{
			projections = Arrays.copyOf(projections, projections.length << 1);
		}
		float[] copy = projections[projectionCount];
		if (copy == null)
		{
			copy = projections[projectionCount] = new float[16];
		}
		System.arraycopy(projection, 0, copy, 0, 16);
		projectionCount++;
		return copy;
	}

	public void clear()
	{
		add(CLEAR);
	}

	public void skyHd117()
	{
		add(SKY_HD117);
	}

	public void skyboxVanilla()
	{
		add(SKYBOX_VANILLA);
	}

	public void skyLumina()
	{
		add(SKY_LUMINA);
	}

	/** Records the entity projection; the matrix is copied, so the caller may reuse its array. */
	public void entityProj(float[] projection)
	{
		int i = add(ENTITY_PROJ);
		objs[i * OBJS] = copyProjection(projection);
	}

	public void entityTint(int h, int s, int l, int a)
	{
		int i = add(ENTITY_TINT) * INTS;
		ints[i] = h;
		ints[i + 1] = s;
		ints[i + 2] = l;
		ints[i + 3] = a;
	}

	public void zoneOpaque(Zone zone, int zx, int zz, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds, boolean gapFillers)
	{
		int c = add(ZONE_OPAQUE);
		int i = c * INTS;
		ints[i] = zx;
		ints[i + 1] = zz;
		ints[i + 2] = minLevel;
		ints[i + 3] = level;
		ints[i + 4] = maxLevel;
		ints[i + 5] = gapFillers ? 1 : 0;
		objs[c * OBJS] = zone;
		objs[c * OBJS + 1] = hideRoofIds;
	}

	public void dynamicOpaque()
	{
		add(DYNAMIC_OPAQUE);
	}

	public void zoneAlpha(Zone zone, int zx, int zz, int cameraYaw, int cameraPitch, int minLevel, int level, int maxLevel,
		int alphaLevel, Set<Integer> hideRoofIds, boolean unsorted, boolean depthPass)
	{
		int c = add(ZONE_ALPHA);
		int i = c * INTS;
		ints[i] = zx;
		ints[i + 1] = zz;
		ints[i + 2] = cameraYaw;
		ints[i + 3] = cameraPitch;
		ints[i + 4] = minLevel;
		ints[i + 5] = level;
		ints[i + 6] = maxLevel;
		ints[i + 7] = alphaLevel;
		ints[i + 8] = unsorted ? 1 : 0;
		ints[i + 9] = depthPass ? 1 : 0;
		objs[c * OBJS] = zone;
		objs[c * OBJS + 1] = hideRoofIds;
	}

	public void removeTemp(SceneContext ctx)
	{
		int c = add(REMOVE_TEMP);
		objs[c * OBJS] = ctx;
	}

	/** Hands every recorded command to {@code e}, in recording order. The list stays as it is: see {@link #reset}. */
	@SuppressWarnings("unchecked")
	public void replay(Executor e)
	{
		for (int c = 0; c < count; ++c)
		{
			int i = c * INTS;
			int o = c * OBJS;
			switch (ops[c])
			{
				case CLEAR:
					e.clear();
					break;
				case SKY_HD117:
					e.skyHd117();
					break;
				case SKYBOX_VANILLA:
					e.skyboxVanilla();
					break;
				case SKY_LUMINA:
					e.skyLumina();
					break;
				case ENTITY_PROJ:
					e.entityProj((float[]) objs[o]);
					break;
				case ENTITY_TINT:
					e.entityTint(ints[i], ints[i + 1], ints[i + 2], ints[i + 3]);
					break;
				case ZONE_OPAQUE:
					e.zoneOpaque((Zone) objs[o], ints[i], ints[i + 1], ints[i + 2], ints[i + 3], ints[i + 4],
						(Set<Integer>) objs[o + 1], ints[i + 5] != 0);
					break;
				case DYNAMIC_OPAQUE:
					e.dynamicOpaque();
					break;
				case ZONE_ALPHA:
					e.zoneAlpha((Zone) objs[o], ints[i], ints[i + 1], ints[i + 2], ints[i + 3], ints[i + 4], ints[i + 5],
						ints[i + 6], ints[i + 7], (Set<Integer>) objs[o + 1], ints[i + 8] != 0, ints[i + 9] != 0);
					break;
				case REMOVE_TEMP:
					e.removeTemp((SceneContext) objs[o]);
					break;
				default:
					throw new IllegalStateException("op " + ops[c]);
			}
		}
	}

	/** Forgets the frame's commands and drops the references they held. */
	public void reset()
	{
		Arrays.fill(objs, 0, count * OBJS, null);
		count = 0;
		projectionCount = 0;
	}

	// the callback trace

	/** Traces the callbacks of the next frame; the line is read at its replay. */
	public void traceNextFrame()
	{
		tracing = true;
		trace.setLength(0);
		traceRun = 0;
	}

	public boolean isTracing()
	{
		return tracing;
	}

	/**
	 * A callback reports itself: {@code kind} names it (P pre, o zone opaque, t temp model, D pass opaque, E pre pass
	 * alpha, a zone alpha, A pass alpha, Q post), {@code level} is its level or pass or -1, {@code worldViewId} the
	 * scene's. Client thread only.
	 */
	public void trace(char kind, int level, int worldViewId)
	{
		if (!tracing)
		{
			return;
		}
		if (traceRun > 0 && kind == traceKind && level == traceLevel && worldViewId == traceWv)
		{
			traceRun++;
			return;
		}
		flushTrace();
		traceKind = kind;
		traceLevel = level;
		traceWv = worldViewId;
		traceRun = 1;
	}

	private void flushTrace()
	{
		if (traceRun == 0)
		{
			return;
		}
		if (trace.length() > 0)
		{
			trace.append(' ');
		}
		trace.append(traceKind);
		if (traceLevel >= 0)
		{
			trace.append(traceLevel);
		}
		trace.append('@').append(traceWv);
		if (traceRun > 1)
		{
			trace.append('x').append(traceRun);
		}
		traceRun = 0;
	}

	/** The traced frame's callback order, and ends the trace; null when no trace was armed. */
	public String traceLine()
	{
		if (!tracing)
		{
			return null;
		}
		flushTrace();
		tracing = false;
		return trace.toString();
	}
}
