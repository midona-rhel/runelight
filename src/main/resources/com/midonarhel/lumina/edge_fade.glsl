// The edge of the drawn world (EdgeFade.java): a circle around the camera's focal point, the same one the zone
// culling uses, so turning the camera never changes what is drawn. Everything fades into the sky behind it toward
// the circle and is fully sky at it; also toward the edge of the loaded scene. World coordinates (scene local).

uniform vec4 edgeFade;   // xy: focal point xz, z: radius where the world is all sky, w: width of the fade
uniform vec2 edgeScene;  // the loaded scene's min and max on both axes

// Distance inside the edge, world units; 0 or less at and past it
float edgeDistance(vec3 world) {
  vec2 s = min(world.xz - edgeScene.x, edgeScene.y - world.xz);
  return min(edgeFade.z - length(world.xz - edgeFade.xy), min(s.x, s.y));
}

// 0 well inside, 1 at the edge and past it: how much of the sky behind replaces this point
float edgeFadeAmount(vec3 world) {
  float t = clamp(edgeDistance(world) / max(edgeFade.w, 1.0), 0.0, 1.0);
  return 1.0 - t * t * (3.0 - 2.0 * t);
}
