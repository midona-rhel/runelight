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

	// the shader's rule: still below 128
	private static boolean still(int code)
	{
		return code < 128;
	}

	@Test
	public void baseStaysTopMoves()
	{
		int m = material(WindSway.OBJECT, 0, false);
		int anchor = WindSway.anchor(5 * 128 + 40, 3 * 128);
		assertEquals(0, WindSway.vertexCode(m, 10, 0, 10, 300, anchor));
		assertEquals(0, WindSway.vertexCode(m, 10, 20, 10, 300, anchor)); // below the origin
		int top = WindSway.vertexCode(m, 10, -300, 10, 300, anchor);
		assertTrue(top >= 128 && top < 32768);
		assertEquals(300, WindSway.bendUnits(top), 6);
		assertEquals(anchor, top & 63);
		// a bend curve, not a shear: half way up bends a third as far (0.5^1.5)
		assertEquals(300 * Math.pow(0.5, 1.5), WindSway.bendUnits(WindSway.vertexCode(m, 0, -150, 0, 300, anchor)), 4);
	}

	@Test
	public void sameHeightSameBend()
	{
		// every vertex at one height of an object moves exactly alike, wherever it is across the object
		int m = material(WindSway.TREE, 0, false);
		int anchor = WindSway.anchor(1000, 2000);
		int a = WindSway.vertexCode(m, 0, -700, 0, 1000, anchor);
		assertEquals(a, WindSway.vertexCode(m, 200, -700, 0, 1000, anchor));
		assertEquals(a, WindSway.vertexCode(m, -30, -700, 180, 1000, anchor));
	}

	@Test
	public void treeTrunkStaysCanopyMoves()
	{
		int m = material(WindSway.TREE, 0, false);
		float h = 1000;
		// low on the tree nothing moves, even out at the branches
		assertEquals(0, WindSway.vertexCode(m, 200, -200, 0, h, 0));
		assertTrue(WindSway.vertexCode(m, 200, -500, 0, h, 0) >= 128);
		// the top moves the full height, the bend grows smoothly up the tree
		assertEquals(1000, WindSway.bendUnits(WindSway.vertexCode(m, 0, -1000, 0, h, 0)), 10);
		float prev = 0;
		for (int y = 300; y <= 1000; y += 50)
		{
			float b = WindSway.bendUnits(WindSway.vertexCode(m, 0, -y, 0, h, 0));
			assertTrue(b >= prev);
			prev = b;
		}
	}

	@Test
	public void modifierAndInvert()
	{
		float strong = WindSway.bendUnits(WindSway.vertexCode(material(WindSway.OBJECT, 3, false), 0, -400, 0, 400, 0));
		float weak = WindSway.bendUnits(WindSway.vertexCode(material(WindSway.OBJECT, -3, false), 0, -400, 0, 400, 0));
		assertEquals(800, strong, 10);
		assertEquals(100, weak, 4);
		// banners hang from the top: that stays and the bottom moves
		int banner = material(WindSway.OBJECT_NO_GROUND, 0, true);
		assertEquals(0, WindSway.vertexCode(banner, 0, -400, 0, 400, 0));
		assertTrue(WindSway.vertexCode(banner, 0, 0, 0, 400, 0) >= 128);
	}

	@Test
	public void smallPlantsBendFurther()
	{
		// a 64 unit tuft bends like a 128 unit plant
		int m = material(WindSway.OBJECT, 0, false);
		assertEquals(128, WindSway.bendUnits(WindSway.vertexCode(m, 0, -64, 0, 64, 0)), 3);
	}

	@Test
	public void jiggleFlag()
	{
		int c = WindSway.vertexCode(material(WindSway.JIGGLE, 0, false), 0, -300, 0, 300, 0);
		assertTrue((c & WindSway.JIGGLE_BIT) != 0);
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
		int max = WindSway.vertexCode(material(WindSway.TREE, 3, false), 0, -9000, 0, 9000, 63);
		assertEquals(255, max >> WindSway.BEND_SHIFT);
		assertTrue(max <= Short.MAX_VALUE);
	}

	// sway.glsl's anchor tile: the tile congruent to the code's residue nearest the vertex
	private static int[] shaderAnchor(int code, float wx, float wz)
	{
		float rx = code & 7, rz = code >> 3 & 7;
		float kx = wx / 128f - 0.5f, kz = wz / 128f - 0.5f;
		float m = WindSway.ANCHOR_MOD;
		return new int[]{
			(int) (rx + m * Math.floor((kx - rx) / m + 0.5f)),
			(int) (rz + m * Math.floor((kz - rz) / m + 0.5f))};
	}

	@Test
	public void shaderFindsTheAnchor()
	{
		int m = material(WindSway.OBJECT, 0, false);
		// objects anywhere in the scene, negative coordinates included (the extended scene)
		for (int ox : new int[]{-1000, -129, -1, 0, 64, 127, 128, 5000, 23000})
		{
			for (int oz : new int[]{-700, 0, 300, 9999})
			{
				int code = WindSway.vertexCode(m, 0, -100, 0, 100, WindSway.anchor(ox, oz));
				int tx = Math.floorDiv(ox, 128), tz = Math.floorDiv(oz, 128);
				// every vertex up to 3.5 tiles out finds the same anchor tile
				for (int dx = -440; dx <= 440; dx += 40)
				{
					for (int dz = -440; dz <= 440; dz += 40)
					{
						assertArrayEquals(new int[]{tx, tz}, shaderAnchor(code, ox + dx, oz + dz));
					}
				}
			}
		}
	}

	@Test
	public void anchorsDiffer()
	{
		Set<Integer> seen = new HashSet<>();
		for (int x = 0; x < 8; ++x)
		{
			for (int z = 0; z < 8; ++z)
			{
				int a = WindSway.anchor(x * 128 + 64, z * 128 + 64);
				assertTrue(a >= 0 && a < 64);
				seen.add(a);
			}
		}
		assertEquals(64, seen.size());
		assertNotEquals(WindSway.anchor(64, 64), WindSway.anchor(192, 64));
	}

	@Test
	public void materialBitsDontCollide()
	{
		int m = material(WindSway.TREE, 2, true);
		assertTrue(WindSway.sways(m));
		assertTrue(!WindSway.sways(SurfaceMaterials.TRANSMISSIVE | SurfaceMaterials.EMISSIVE_FULL | SurfaceMaterials.FIRE));
		// SurfaceMaterials ignores the wind bits
		assertEquals(SurfaceMaterials.face(0, -1, 0, 255), SurfaceMaterials.face(m, -1, 0, 255));
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
