// Where the sky probes are (SkyProbes.java, which mirrors these; a test checks). Shared by the update pass
// (sky_probes_frag.glsl) and the lookup (skylight_ao.glsl).
//
// The probes form a window of SKY_PROBES x SKY_PROBES columns around the player, SKY_PROBE_SPACING world units apart,
// wrapped like a torus: probe (i, j) of the world, its center at ((i + 0.5), (j + 0.5)) * SKY_PROBE_SPACING, lives in
// texel (i mod SKY_PROBES, j mod SKY_PROBES), so walking only replaces the columns at the window's far edge. Each
// column holds SKY_LAYERS probes that follow the ground, the lowest SKY_LAYER_BASE above it and the others
// SKY_LAYER_STEP apart. The volume's slice SKY_LAYERS is not a layer: it holds the columns' ground and the top of the
// scenery over them (world y, down), as the probes were placed with them.

#define SKY_PROBES 160
#define SKY_PROBE_SPACING 64.0
#define SKY_LAYERS 10
#define SKY_LAYER_BASE 24.0
#define SKY_LAYER_STEP 80.0

// the probe window's index of a probe along one axis in its texel (the index may be negative)
int skyProbeSlot(int i) {
  return i - SKY_PROBES * int(floor(float(i) / float(SKY_PROBES)));
}
