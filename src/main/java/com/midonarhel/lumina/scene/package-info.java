/**
 * Scene loading and zone upload. {@link com.midonarhel.lumina.scene.SceneLoader} keeps the loaded scenes
 * ({@link com.midonarhel.lumina.scene.SceneContext}: zones plus {@link com.midonarhel.lumina.scene.SceneData}),
 * reuses or re-uploads zones on a map load and rebuilds invalidated ones. {@link com.midonarhel.lumina.scene.SceneUploader}
 * writes a zone's terrain and static models, {@link com.midonarhel.lumina.scene.ModelUploader} the dynamic and
 * temporary models, {@link com.midonarhel.lumina.scene.Zone} holds a zone's buffers and draws them.
 * {@link com.midonarhel.lumina.scene.SceneDataLoader} loads the 117 HD data files and builds each scene's data;
 * {@link com.midonarhel.lumina.scene.AreaHiding} is 117 HD's area hiding at draw time.
 * <p>
 * The subpackages {@code areas}, {@code environment}, {@code materials}, {@code overrides}, {@code sky} and
 * {@code terrain} are the data and generators ported from 117 HD; each says where its files come from.
 * {@code SceneData} itself is 117 HD's rs117/hd/scene/SceneContext.java, trimmed. Colours and normals of what is
 * uploaded are decided in {@code shading}, never here.
 */
package com.midonarhel.lumina.scene;
