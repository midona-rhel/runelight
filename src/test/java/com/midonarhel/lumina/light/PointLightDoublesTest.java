package com.midonarhel.lumina.light;

import static org.junit.Assert.assertEquals;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** A lamp standing twice (the same light at the same place, from objects on two storeys) lights once. */
public class PointLightDoublesTest
{
	@Test
	public void theLowerStoreysStays()
	{
		List<PointLight> l = new ArrayList<>();
		l.add(new PointLight().set(100, -206, 200, 353, 0.97f, 0.3f, 0, 7.15f).onPlane(1));
		l.add(new PointLight().set(500, -206, 200, 353, 0.97f, 0.3f, 0, 7.15f).onPlane(0));
		l.add(new PointLight().set(100, -206, 200, 353, 0.97f, 0.3f, 0, 6.9f).onPlane(0));
		// a different light at the same place stays
		l.add(new PointLight().set(100, -206, 200, 450, 1f, 0.51f, 0.14f, 7.5f).onPlane(1));
		PointLight.dropDoubles(l);
		assertEquals(3, l.size());
		assertEquals(500, l.get(0).x, 0);
		assertEquals(0, l.get(1).plane);
		assertEquals(100, l.get(1).x, 0);
		assertEquals(450, l.get(2).radius, 0);
	}
}
