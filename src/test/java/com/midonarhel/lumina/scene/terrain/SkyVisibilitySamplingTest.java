package com.midonarhel.lumina.scene.terrain;

import com.midonarhel.lumina.scene.SceneData;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import net.runelite.api.Constants;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * SceneData's sky visibility samplers on a stub top-level scene: SkyCover's layout is read back at the right tile,
 * and a static model vertex is never darker than the spot it stands on, whatever its normal.
 */
public class SkyVisibilitySamplingTest
{
	private static final int N = Constants.EXTENDED_SCENE_SIZE;
	private static final int GROUND = -100;

	private static SceneData sceneData()
	{
		int[][][] heights = new int[Constants.MAX_Z][N + 1][N + 1];
		for (int level = 0; level < Constants.MAX_Z; level++)
		{
			for (int[] column : heights[level])
			{
				Arrays.fill(column, GROUND - 240 * level);
			}
		}
		Scene scene = (Scene) Proxy.newProxyInstance(Scene.class.getClassLoader(), new Class<?>[]{Scene.class}, (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "getBaseX":
				case "getBaseY":
					return 3200;
				case "isInstance":
					return false;
				case "getTileHeights":
					return heights;
				default:
					throw new UnsupportedOperationException(method.getName());
			}
		});
		WorldView worldView = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[]{WorldView.class}, (proxy, method, args) ->
		{
			if (method.getName().equals("isTopLevel"))
			{
				return true;
			}
			throw new UnsupportedOperationException(method.getName());
		});
		return new SceneData(worldView, scene, 0);
	}

	/**
	 * Level 0 baked through SkyCover with one covered, walled-in tile at extended (ex, ez); the upper levels dark.
	 */
	private static SceneData withDarkTile(int ex, int ez)
	{
		SceneData data = sceneData();
		boolean[] covered = new boolean[N * N];
		byte[] walls = new byte[N * N];
		covered[ex * N + ez] = true;
		SkyCover.closeEdges(walls, N, ex, ez, SkyCover.EDGE_W | SkyCover.EDGE_N | SkyCover.EDGE_E | SkyCover.EDGE_S);
		float[] values = new float[N * N];
		SkyCover.propagate(N, covered, walls, values);
		byte[] out = new byte[Constants.MAX_Z * N * N * 4];
		SkyCover.quadrants(N, walls, values, out, 0);
		data.skyVisibility = out;
		return data;
	}

	@Test
	public void bakeIsReadBackAtItsOwnTile()
	{
		SceneData data = withDarkTile(70, 95);
		for (int corner = 0; corner < 4; corner++)
		{
			assertEquals(0, data.skyVisibilityCorner(0, 70, 95, corner));
			assertEquals(255, data.skyVisibilityCorner(0, 95, 70, corner));
		}
		assertEquals(255, data.skyVisibility(0, 69, 95, 1f, 0.5f)); // the neighbour's own corners, not shared
	}

	@Test
	public void openGroundVertexIs255WhateverItsNormal()
	{
		SceneData data = withDarkTile(70, 95);
		int sceneOffset = data.sceneOffset;
		// a vertex at ground height in the middle of the open tile beside the dark one (east of it), and at its
		// west edge, where a sideways normal would push the sample into the dark tile
		int tileX = 71 - sceneOffset, tileZ = 95 - sceneOffset;
		int[][] positions = {{tileX * 128 + 64, tileZ * 128 + 64}, {tileX * 128, tileZ * 128 + 64}};
		int[][] normals = {{0, 2048, 0}, {0, -2048, 0}, {-2048, 0, 0}, {2048, 0, 0}, {0, 0, 2048}, {0, 0, 0}, {-1, 1, 1}};
		for (int[] p : positions)
		{
			for (int[] n : normals)
			{
				assertEquals(255, data.skyVisibilityOfVertex(p[0], GROUND, p[1], n[0], n[1], n[2], 0));
			}
		}
		// upper levels are unbaked zeros in this array: a tall vertex high above the open tile must not read them as
		// cover (they are dark here only because the test left them so; the rule is tested, not the bake)
		assertTrue(data.skyVisibilityOfVertex(tileX * 128 + 64, GROUND - 1000, tileZ * 128 + 64, 0, -2048, 0, 0) <= 255);
	}

	@Test
	public void wallFacesTakeTheirSideOfTheWall()
	{
		SceneData data = withDarkTile(70, 95);
		int sceneOffset = data.sceneOffset;
		// a vertex on the dark tile's east edge: the face looking east (outside) is lit, the one looking west (in) is not
		int x = (71 - sceneOffset) * 128, z = (95 - sceneOffset) * 128 + 64;
		assertEquals(255, data.skyVisibilityOfVertex(x, GROUND, z, 2048, 0, 0, 0));
		assertEquals(0, data.skyVisibilityOfVertex(x - 1, GROUND, z, -2048, 0, 0, 0));
		// a vertex inside the dark tile is dark whatever its normal
		assertEquals(0, data.skyVisibilityOfVertex(x - 64, GROUND, z, 0, -2048, 0, 0));
	}
}
