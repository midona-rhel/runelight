/**
 * The one place a vertex colour or normal is decided. {@link com.midonarhel.lumina.shading.DiffuseColors} is the
 * switch between 117 HD's four shading modes and the two colour modes; {@link com.midonarhel.lumina.shading.FaceShading}
 * computes a model face's colours, alpha, depth bias, material and normals as 117 HD's uploaders do, for the static
 * and the temporary model path; {@link com.midonarhel.lumina.shading.ShadingVariables} are the constants the 117 HD
 * data expressions test (117 HD's src/main/java/rs117/hd/utils/HDVariables.java).
 * {@link com.midonarhel.lumina.shading.ShadingMode} is 117 HD's setting (rs117/hd/config/ShadingMode.java),
 * {@link com.midonarhel.lumina.shading.ColorMode} Lumina's.
 * <p>
 * Called by {@code scene.SceneUploader} and {@code scene.ModelUploader}; the shaders apply the light to the result.
 */
package com.midonarhel.lumina.shading;
