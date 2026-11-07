// Shared ambient: environment RGB tint/intensity scales sky illumination through openings.
// Direct casters and point lights are separate; SSAO is applied by opaque callers only.
uniform vec3 interiorTint;
vec3 environmentSkylight(vec3 sky, float exposure) {
  return sky * clamp(exposure, 0.0, 1.0) * envAmbient;
}
vec3 roomWarmFill(int room, vec3 world, vec3 normal) {
  return interiorTint * roomFillLevel(room) * roomWallFill(world, normal);
}
