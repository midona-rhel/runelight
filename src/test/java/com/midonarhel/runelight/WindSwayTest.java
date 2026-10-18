package com.midonarhel.runelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class WindSwayTest
{
	private static int material(int mode, int modifier, boolean invert)
	{
		return (mode | (modifier + 3) << 3 | (invert ? 1 << 6 : 0)) << WindSway.MODEL_SHIFT;
	}

	// the shader's rule: still below 512
	private static boolean still(int code)
	{
		return code < 512;
	}

	@Test
	public void baseStaysTopMoves()
	{
		int m = material(WindSway.OBJECT, 0, false);
		assertEquals(0, WindSway.vertexCode(m, 10, 0, 10, 100, 5));
		assertEquals(0, WindSway.vertexCode(m, 10, 20, 10, 100, 5)); // below the origin
		int top = WindSway.vertexCode(m, 10, -100, 10, 100, 5);
		assertTrue(top >= 512 && top < 32768);
		assertEquals(100, WindSway.bendUnits(top), 4);
		assertEquals(5, top & 15);
		assertEquals(2, top >> WindSway.FLUTTER_SHIFT & 3);
		// higher vertices bend further
		assertTrue(WindSway.vertexCode(m, 0, -50, 0, 100, 0) >> WindSway.BEND_SHIFT < top >> WindSway.BEND_SHIFT);
	}

	@Test
	public void treeTrunkStaysCanopyMoves()
	{
		int m = material(WindSway.TREE, 0, false);
		float h = 1000;
		// low on the tree nothing moves, even out at the branches
		assertEquals(0, WindSway.vertexCode(m, 200, -200, 0, h, 0));
		// the trunk axis at mid height stays, the canopy around it moves
		assertEquals(0, WindSway.vertexCode(m, 10, -500, 10, h, 0));
		assertTrue(WindSway.vertexCode(m, 200, -500, 0, h, 0) >= 512);
		// the top of the trunk follows the canopy
		assertEquals(1000, WindSway.bendUnits(WindSway.vertexCode(m, 0, -1000, 0, h, 0)), 40);
	}

	@Test
	public void modifierAndInvert()
	{
		float strong = WindSway.bendUnits(WindSway.vertexCode(material(WindSway.OBJECT, 3, false), 0, -400, 0, 400, 0));
		float weak = WindSway.bendUnits(WindSway.vertexCode(material(WindSway.OBJECT, -3, false), 0, -400, 0, 400, 0));
		assertEquals(800, strong, 30);
		assertEquals(100, weak, 8);
		// banners hang from the top: that stays and the bottom moves
		int banner = material(WindSway.OBJECT_NO_GROUND, 0, true);
		assertEquals(0, WindSway.vertexCode(banner, 0, -400, 0, 400, 0));
		assertTrue(WindSway.vertexCode(banner, 0, 0, 0, 400, 0) >= 512);
	}

	@Test
	public void jiggleFlag()
	{
		int c = WindSway.vertexCode(material(WindSway.JIGGLE, 0, false), 0, -300, 0, 300, 0);
		assertTrue((c & WindSway.JIGGLE_BIT) != 0);
		assertEquals(3, c >> WindSway.FLUTTER_SHIFT & 3);
		assertEquals(0, WindSway.vertexCode(material(WindSway.VERTEX, 0, false), 0, -300, 0, 300, 0) & WindSway.JIGGLE_BIT);
	}

	@Test
	public void otherVerticesStayStill()
	{
		// not swaying, flat height, ground border (-1) and dynamic models' w of 1
		assertEquals(0, WindSway.vertexCode(0, 0, -300, 0, 300, 0));
		assertEquals(0, WindSway.vertexCode(material(WindSway.OBJECT, 0, false), 0, 0, 0, 0, 0));
		assertTrue(still(-1));
		assertTrue(still(1));
		assertTrue(still(0));
		// the largest code still fits a positive short
		int max = WindSway.vertexCode(material(WindSway.OBJECT, 3, false), 0, -9000, 0, 9000, 15);
		assertEquals(63, max >> WindSway.BEND_SHIFT);
		assertTrue(max <= Short.MAX_VALUE);
	}

	@Test
	public void materialBitsDontCollide()
	{
		int m = material(WindSway.TREE, 2, true);
		assertTrue(WindSway.sways(m));
		assertTrue(!WindSway.sways(SurfaceMaterials.TRANSMISSIVE | SurfaceMaterials.EMISSIVE_FULL | SurfaceMaterials.FIRE));
		// SurfaceMaterials ignores the wind bits
		assertEquals(SurfaceMaterials.face(0, -1, 0), SurfaceMaterials.face(m, -1, 0));
	}

	@Test
	public void phasesScatter()
	{
		Set<Integer> seen = new HashSet<>();
		for (int x = 0; x < 8; ++x)
		{
			for (int z = 0; z < 8; ++z)
			{
				int p = WindSway.phase(x * 128 + 64, z * 128 + 64);
				assertTrue(p >= 0 && p < 16);
				seen.add(p);
			}
		}
		assertTrue(seen.size() >= 12);
		assertNotEquals(WindSway.phase(64, 64), WindSway.phase(192, 64));
	}

	@Test
	public void importedTable()
	{
		WindSway.load(new Gson());
		// object ids from 117 HD's gamevals: MAGIC_TREE_1, YEW_TREE_1, OAK_TREE_1, TREE, WILLOWTREE, MAGICTREE
		for (int id : new int[]{8397, 8503, 8463, 1276, 10819, 10834})
		{
			assertEquals("tree " + id, WindSway.TREE << WindSway.MODEL_SHIFT, WindSway.forObject(id, null) & 7 << WindSway.MODEL_SHIFT);
		}
		// GRASS_1 and FERN sway as whole objects, HEDGE jiggles
		assertEquals(WindSway.OBJECT, WindSway.forObject(1253, null) >> WindSway.MODEL_SHIFT & 7);
		assertEquals(WindSway.OBJECT, WindSway.forObject(1298, null) >> WindSway.MODEL_SHIFT & 7);
		assertEquals(WindSway.JIGGLE, WindSway.forObject(1116, null) >> WindSway.MODEL_SHIFT & 7);
		// ROCKS1, ROCKS2, a sepulchre statue, a Varrock drain, an achey stump: still
		for (int id : new int[]{11390, 11391, 38590, 7110, 3371, -1, 1 << 20})
		{
			assertEquals("still " + id, 0, WindSway.forObject(id, null));
		}
	}

	@Test
	public void zoneSwayFilter()
	{
		Zone zone = new Zone();
		zone.addSwayRange(100, 200);
		zone.addSwayRange(200, 250); // merges
		zone.addSwayRange(400, 450);
		assertEquals(4, zone.swayRangeCount);

		int[][] only = filter(zone, true, 0, 300, 420, 1000);
		assertArrayEquals(new int[]{100, 250, 420, 450}, flatten(only));
		int[][] rest = filter(zone, false, 0, 300, 420, 1000);
		assertArrayEquals(new int[]{0, 100, 250, 300, 450, 1000}, flatten(rest));
		// a range with no swaying part
		assertArrayEquals(new int[]{}, flatten(filter(zone, true, 500, 600)));
		assertArrayEquals(new int[]{500, 600}, flatten(filter(zone, false, 500, 600)));
	}

	private static int[][] filter(Zone zone, boolean only, int... ranges)
	{
		Zone.drawOff.clear();
		Zone.drawEnd.clear();
		for (int i = 0; i < ranges.length; i += 2)
		{
			Zone.pushRange(ranges[i], ranges[i + 1]);
		}
		zone.filterSway(only);
		int n = Zone.drawOff.position();
		int[][] out = new int[n][];
		for (int i = 0; i < n; ++i)
		{
			out[i] = new int[]{Zone.drawOff.get(i), Zone.drawEnd.get(i)};
		}
		return out;
	}

	private static int[] flatten(int[][] r)
	{
		int[] f = new int[r.length * 2];
		for (int i = 0; i < r.length; ++i)
		{
			f[i * 2] = r[i][0];
			f[i * 2 + 1] = r[i][1];
		}
		return f;
	}
}
