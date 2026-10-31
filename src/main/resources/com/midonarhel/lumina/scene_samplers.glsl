// The sampler type of the scene targets (SceneTargets). With MSAA they are multisample textures, left unresolved: the
// composite fetches every sample of the pixels where the surface changes, and every other pass reads sample 0, which
// is what texelFetch(scene, px, 0) gives on both kinds. A plain texture without MSAA.
#include msaa_config
#if MSAA_SAMPLES > 0
#define SCENE_SAMPLER sampler2DMS
#define SCENE_SAMPLES MSAA_SAMPLES
#define sceneTextureSize() textureSize(sceneDepth)
#else
#define SCENE_SAMPLER sampler2D
#define SCENE_SAMPLES 1
#define sceneTextureSize() textureSize(sceneDepth, 0)
#endif
