/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.frame.RenderThread;
import com.midonarhel.lumina.frame.VAO;
import com.midonarhel.lumina.shading.FaceShading;
import com.midonarhel.lumina.scene.overrides.ModelOverride;
import com.midonarhel.lumina.scene.overrides.UvType;
import com.midonarhel.lumina.scene.materials.MaterialManager;
import com.midonarhel.lumina.util.MathUtils;
import java.nio.IntBuffer;
import java.util.Arrays;
import javax.annotation.Nullable;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;

public class ModelUploader
{
	final int[] distances;
	final char[] zsortHead, zsortTail, zsortNext;

	private final float[] modelProjectedX;
	private final float[] modelProjectedY;

	private final float[] modelLocalX;
	private final float[] modelLocalY;
	private final float[] modelLocalZ;

	private final int[] numOfPriority;
	private final int[] eq10;
	private final int[] eq11;
	private final int[] lt10;
	private final int[][] orderedFaces;

	private final int[] vertexBuffer;

	private final float[] u, v;

	private static final int FACE_SIZE = (VAO.VERT_SIZE >> 2) * 3;
	private final FaceShading faceShading = new FaceShading();
	private final int[] faceData = new int[FACE_SIZE];
	private final float[] uvs = new float[12];
	private final float[] uvWork = new float[9];
	private static final int[] NO_NORMALS = new int[9];

	static final int MAX_VERTEX_COUNT = 6500;
	static final int MAX_FACE_COUNT = 8192; // was 6500
	static final int MAX_DIAMETER = 6000;
	private static final int MAX_FACES_PER_PRIORITY = 4000; // was 2500

	{
		distances = new int[MAX_VERTEX_COUNT];
		zsortHead = new char[MAX_DIAMETER];
		zsortTail = new char[MAX_DIAMETER];
		zsortNext = new char[MAX_FACE_COUNT];

		modelProjectedX = new float[MAX_VERTEX_COUNT];
		modelProjectedY = new float[MAX_VERTEX_COUNT];

		modelLocalX = new float[MAX_VERTEX_COUNT];
		modelLocalY = new float[MAX_VERTEX_COUNT];
		modelLocalZ = new float[MAX_VERTEX_COUNT];

		numOfPriority = new int[12];
		eq10 = new int[MAX_FACES_PER_PRIORITY];
		eq11 = new int[MAX_FACES_PER_PRIORITY];
		lt10 = new int[12];
		orderedFaces = new int[12][MAX_FACES_PER_PRIORITY];

		vertexBuffer = new int[MAX_FACE_COUNT * FACE_SIZE];

		u = new float[3];
		v = new float[3];
	}

	public int uploadSortedModel(RenderThread rt, Projection proj, Model model, ModelOverride override, int preOrientation, int orientation, int x, int y, int z,
		int cameraX, int cameraY, int cameraZ, IntBuffer opaqueBuffer, IntBuffer alphaBuffer, boolean prioritySort)
	{
		final int vertexCount = model.getVerticesCount();
		final float[] verticesX = model.getVerticesX();
		final float[] verticesY = model.getVerticesY();
		final float[] verticesZ = model.getVerticesZ();

		final int faceCount = Math.min(model.getFaceCount(), MAX_FACE_COUNT);
		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] faceColors3 = model.getFaceColors3();
		final byte[] faceRenderPriorities = model.getFaceRenderPriorities();

		final short[] faceTextures = model.getFaceTextures();

		// 117 HD's temp model path: override rotation and height offset
		if (override.rotate != 0)
		{
			orientation = Math.floorMod((int) (override.rotate * MathUtils.DEG_TO_JAU), 2048);
		}
		y += override.heightOffset;

		float orientSine = 0;
		float orientCosine = 0;
		int modelCameraX = cameraX - x;
		int modelCameraY = cameraY - y;
		int modelCameraZ = cameraZ - z;
		if (orientation != 0)
		{
			orientSine = Perspective.SINEF[orientation];
			orientCosine = Perspective.COSINEF[orientation];

			int inverseOrientation = (2048 - orientation) & 2047;
			int inverseSine = Perspective.SINE[inverseOrientation];
			int inverseCosine = Perspective.COSINE[inverseOrientation];
			int cameraX0 = modelCameraX;
			modelCameraX = modelCameraZ * inverseSine + cameraX0 * inverseCosine >> 16;
			modelCameraZ = modelCameraZ * inverseCosine - cameraX0 * inverseSine >> 16;
		}

		float[] p = proj.project(x, y, z, rt.tmp);
		int zero = (int) p[2];

		for (int v = 0; v < vertexCount; ++v)
		{
			float vertexX = verticesX[v];
			float vertexY = verticesY[v];
			float vertexZ = verticesZ[v];

			if (orientation != 0)
			{
				float x0 = vertexX;
				vertexX = vertexZ * orientSine + x0 * orientCosine;
				vertexZ = vertexZ * orientCosine - x0 * orientSine;
			}

			// move to local position
			vertexX += x;
			vertexY += y;
			vertexZ += z;

			modelLocalX[v] = vertexX;
			modelLocalY[v] = vertexY;
			modelLocalZ[v] = vertexZ;

			p = proj.project(vertexX, vertexY, vertexZ, rt.tmp);
			distances[v] = (int) p[2] - zero;
			if (p[2] >= 50)
			{
				modelProjectedX[v] = p[0] / p[2];
				modelProjectedY[v] = p[1] / p[2];
			}
			else
			{
				modelProjectedX[v] = -5000;
			}
		}

		faceShading.setModel(model, override, orientation, null, true, modelLocalX, modelLocalY, modelLocalZ);

		final int diameter = model.getDiameter();
		final int radius = model.getRadius();
		if (diameter >= MAX_DIAMETER)
		{
			return 0;
		}

		Arrays.fill(zsortHead, 0, diameter, (char) -1);
		Arrays.fill(zsortTail, 0, diameter, (char) -1);

		int minFz = diameter, maxFz = 0;
		for (char faceIdx = 0; faceIdx < faceCount; ++faceIdx)
		{
			if (faceColors3[faceIdx] != -2)
			{
				final int v1 = indices1[faceIdx];
				final int v2 = indices2[faceIdx];
				final int v3 = indices3[faceIdx];

				final float
					aX = modelProjectedX[v1],
					aY = modelProjectedY[v1],
					bX = modelProjectedX[v2],
					bY = modelProjectedY[v2],
					cX = modelProjectedX[v3],
					cY = modelProjectedY[v3];

				if ((aX == -5000 || bX == -5000 || cX == -5000 || (aX - bX) * (cY - bY) - (cX - bX) * (aY - bY) > 0)
					&& faceShading.shade(faceIdx))
				{
					int distance = radius + (distances[v1] + distances[v2] + distances[v3]) / 3;
					assert distance >= 0 && distance < diameter;

					if (zsortTail[distance] == (char) -1)
					{
						zsortHead[distance] = zsortTail[distance] = faceIdx;
						zsortNext[faceIdx] = (char) -1;
					}
					else
					{
						char lastFace = zsortTail[distance];
						zsortNext[lastFace] = faceIdx;
						zsortNext[faceIdx] = (char) -1;
						zsortTail[distance] = faceIdx;
					}

					minFz = Math.min(minFz, distance);
					maxFz = Math.max(maxFz, distance);
					faceUvs(faceShading, model, faceIdx, preOrientation, true, modelCameraX, modelCameraY, modelCameraZ,
						modelLocalX, modelLocalY, modelLocalZ, 0, 0, uvs, uvWork);

					// colours, alpha, bias, normals and material: 117 HD's temp model path (FaceShading)
					int alphaBias = faceShading.transparency << 24 | faceShading.depthBias << 16;
					int texture = faceTextures != null ? faceTextures[faceIdx] + 1 : 0;
					putFace(vertexBuffer, faceIdx * FACE_SIZE, v1, v2, v3,
						alphaBias | faceShading.color1, alphaBias | faceShading.color2, alphaBias | faceShading.color3,
						texture, uvs, faceFlags(faceShading), faceShading.normals, MaterialManager.slot(faceShading.material));
				}
			}
		}

		int len = 0;
		if (faceRenderPriorities == null || !prioritySort)
		{
			for (int i = maxFz; i >= minFz; --i)
			{
				for (char face = zsortHead[i]; face != (char) -1; face = zsortNext[face])
				{
					int offset = face * FACE_SIZE;
					var b = (vertexBuffer[offset + 6] >>> 16 & ALPHA_FLAG) != 0 ? alphaBuffer : opaqueBuffer;
					b.put(vertexBuffer, offset, FACE_SIZE);
				}
			}
		}
		else
		{
			Arrays.fill(numOfPriority, 0);
			Arrays.fill(lt10, 0);

			for (int i = maxFz; i >= minFz; --i)
			{
				for (char face = zsortHead[i]; face != (char) -1; face = zsortNext[face])
				{
					final byte pri = faceRenderPriorities[face];
					final int distIdx = numOfPriority[pri]++;

					orderedFaces[pri][distIdx] = face;
					if (pri < 10)
					{
						lt10[pri] += i;
					}
					else if (pri == 10)
					{
						eq10[distIdx] = i;
					}
					else
					{
						eq11[distIdx] = i;
					}
				}
			}

			int avg12 = 0;
			if (numOfPriority[1] > 0 || numOfPriority[2] > 0)
			{
				avg12 = (lt10[1] + lt10[2]) / (numOfPriority[1] + numOfPriority[2]);
			}

			int avg34 = 0;
			if (numOfPriority[3] > 0 || numOfPriority[4] > 0)
			{
				avg34 = (lt10[3] + lt10[4]) / (numOfPriority[3] + numOfPriority[4]);
			}

			int avg68 = 0;
			if (numOfPriority[6] > 0 || numOfPriority[8] > 0)
			{
				avg68 = (lt10[8] + lt10[6]) / (numOfPriority[8] + numOfPriority[6]);
			}

			int drawnFaces = 0;
			int numDynFaces = numOfPriority[10];
			int[] dynFaces = orderedFaces[10];
			int[] dynFaceDistances = eq10;
			if (drawnFaces == numDynFaces)
			{
				drawnFaces = 0;
				numDynFaces = numOfPriority[11];
				dynFaces = orderedFaces[11];
				dynFaceDistances = eq11;
			}

			int currFaceDistance;
			if (drawnFaces < numDynFaces)
			{
				currFaceDistance = dynFaceDistances[drawnFaces];
			}
			else
			{
				currFaceDistance = -1000;
			}

			for (int pri = 0; pri < 10; ++pri)
			{
				while (pri == 0 && currFaceDistance > avg12)
				{
					final int face = dynFaces[drawnFaces++];
					int offset = face * FACE_SIZE;
					var b = (vertexBuffer[offset + 6] >>> 16 & ALPHA_FLAG) != 0 ? alphaBuffer : opaqueBuffer;
					b.put(vertexBuffer, offset, FACE_SIZE);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11])
					{
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					if (drawnFaces < numDynFaces)
					{
						currFaceDistance = dynFaceDistances[drawnFaces];
					}
					else
					{
						currFaceDistance = -1000;
					}
				}

				while (pri == 3 && currFaceDistance > avg34)
				{
					final int face = dynFaces[drawnFaces++];
					int offset = face * FACE_SIZE;
					var b = (vertexBuffer[offset + 6] >>> 16 & ALPHA_FLAG) != 0 ? alphaBuffer : opaqueBuffer;
					b.put(vertexBuffer, offset, FACE_SIZE);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11])
					{
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					if (drawnFaces < numDynFaces)
					{
						currFaceDistance = dynFaceDistances[drawnFaces];
					}
					else
					{
						currFaceDistance = -1000;
					}
				}

				while (pri == 5 && currFaceDistance > avg68)
				{
					final int face = dynFaces[drawnFaces++];
					int offset = face * FACE_SIZE;
					var b = (vertexBuffer[offset + 6] >>> 16 & ALPHA_FLAG) != 0 ? alphaBuffer : opaqueBuffer;
					b.put(vertexBuffer, offset, FACE_SIZE);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11])
					{
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					if (drawnFaces < numDynFaces)
					{
						currFaceDistance = dynFaceDistances[drawnFaces];
					}
					else
					{
						currFaceDistance = -1000;
					}
				}

				final int priNum = numOfPriority[pri];
				final int[] priFaces = orderedFaces[pri];

				for (int faceIdx = 0; faceIdx < priNum; ++faceIdx)
				{
					final int face = priFaces[faceIdx];
					int offset = face * FACE_SIZE;
					var b = (vertexBuffer[offset + 6] >>> 16 & ALPHA_FLAG) != 0 ? alphaBuffer : opaqueBuffer;
					b.put(vertexBuffer, offset, FACE_SIZE);
				}
			}

			while (currFaceDistance != -1000)
			{
				final int face = dynFaces[drawnFaces++];
				int offset = face * FACE_SIZE;
				var b = (vertexBuffer[offset + 6] >>> 16 & ALPHA_FLAG) != 0 ? alphaBuffer : opaqueBuffer;
				b.put(vertexBuffer, offset, FACE_SIZE);

				if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11])
				{
					drawnFaces = 0;
					dynFaces = orderedFaces[11];
					numDynFaces = numOfPriority[11];
					dynFaceDistances = eq11;
				}

				if (drawnFaces < numDynFaces)
				{
					currFaceDistance = dynFaceDistances[drawnFaces];
				}
				else
				{
					currFaceDistance = -1000;
				}
			}
		}

		return len;
	}

	/**
	 * Uploads a model unsorted.
	 *
	 * @param override the model override; null uploads the model as vanilla lit it, without normals (drawn unlit by
	 *                 the shader), which the skybox uses
	 */
	public int uploadTempModel(Model model, @Nullable ModelOverride override, int preOrientation, int orientation, int x, int y, int z,
		int cameraX, int cameraY, int cameraZ, IntBuffer buffer)
	{
		final int triangleCount = model.getFaceCount();
		final int vertexCount = model.getVerticesCount();

		final float[] verticesX = model.getVerticesX();
		final float[] verticesY = model.getVerticesY();
		final float[] verticesZ = model.getVerticesZ();

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] color1s = model.getFaceColors1();
		final int[] color2s = model.getFaceColors2();
		final int[] color3s = model.getFaceColors3();

		final byte[] transparencies = model.getFaceTransparencies();

		final short[] faceTextures = model.getFaceTextures();

		final byte[] bias = model.getFaceBias();

		final byte overrideAmount = model.getOverrideAmount();
		final byte overrideHue = model.getOverrideHue();
		final byte overrideSat = model.getOverrideSaturation();
		final byte overrideLum = model.getOverrideLuminance();

		// 117 HD's temp model path: override rotation and height offset
		if (override != null)
		{
			if (override.rotate != 0)
			{
				orientation = Math.floorMod((int) (override.rotate * MathUtils.DEG_TO_JAU), 2048);
			}
			y += override.heightOffset;
		}

		float orientSine = 0;
		float orientCosine = 0;
		int modelCameraX = cameraX - x;
		int modelCameraY = cameraY - y;
		int modelCameraZ = cameraZ - z;
		if (orientation != 0)
		{
			orientSine = Perspective.SINEF[orientation];
			orientCosine = Perspective.COSINEF[orientation];

			int inverseOrientation = (2048 - orientation) & 2047;
			int inverseSine = Perspective.SINE[inverseOrientation];
			int inverseCosine = Perspective.COSINE[inverseOrientation];
			int cameraX0 = modelCameraX;
			modelCameraX = modelCameraZ * inverseSine + cameraX0 * inverseCosine >> 16;
			modelCameraZ = modelCameraZ * inverseCosine - cameraX0 * inverseSine >> 16;
		}

		for (int v = 0; v < vertexCount; ++v)
		{
			float vertexX = verticesX[v];
			float vertexY = verticesY[v];
			float vertexZ = verticesZ[v];

			if (orientation != 0)
			{
				float x0 = vertexX;
				vertexX = vertexZ * orientSine + x0 * orientCosine;
				vertexZ = vertexZ * orientCosine - x0 * orientSine;
			}

			vertexX += x;
			vertexY += y;
			vertexZ += z;

			modelLocalX[v] = vertexX;
			modelLocalY[v] = vertexY;
			modelLocalZ[v] = vertexZ;
		}

		if (override != null)
		{
			faceShading.setModel(model, override, orientation, null, true, modelLocalX, modelLocalY, modelLocalZ);
		}

		int len = 0;
		for (int face = 0; face < triangleCount; ++face)
		{
			int color1, color2, color3;
			int alphaBias;
			int[] normals;
			int flags = 0;
			int slot = 0;
			if (override != null)
			{
				// colours, alpha, bias and normals as for static models (FaceShading), plus the model HSL override
				if (!faceShading.shade(face))
				{
					continue;
				}
				color1 = faceShading.color1;
				color2 = faceShading.color2;
				color3 = faceShading.color3;
				alphaBias = faceShading.transparency << 24 | faceShading.depthBias << 16;
				normals = faceShading.normals;
				flags = faceFlags(faceShading);
				slot = MaterialManager.slot(faceShading.material);
			}
			else
			{
				color1 = color1s[face];
				color2 = color2s[face];
				color3 = color3s[face];

				if (color3 == -1)
				{
					color2 = color3 = color1;
				}
				else if (color3 == -2)
				{
					continue;
				}

				// HSL override is not applied to textured faces
				if (faceTextures == null || faceTextures[face] == -1)
				{
					if (overrideAmount > 0)
					{
						color1 = FaceShading.interpolateHSL(color1, overrideHue, overrideSat, overrideLum, overrideAmount);
						color2 = FaceShading.interpolateHSL(color2, overrideHue, overrideSat, overrideLum, overrideAmount);
						color3 = FaceShading.interpolateHSL(color3, overrideHue, overrideSat, overrideLum, overrideAmount);
					}
				}

				alphaBias = 0;
				alphaBias |= transparencies != null ? (transparencies[face] & 0xff) << 24 : 0;
				alphaBias |= bias != null ? (bias[face] & 0xff) << 16 : 0;
				normals = NO_NORMALS;
			}

			int triangleA = indices1[face];
			int triangleB = indices2[face];
			int triangleC = indices3[face];


			if (override != null)
			{
				faceUvs(faceShading, model, face, preOrientation, true, modelCameraX, modelCameraY, modelCameraZ,
					modelLocalX, modelLocalY, modelLocalZ, 0, 0, uvs, uvWork);
			}
			else
			{
				computeFaceUvs(model, face, modelCameraX, modelCameraY, modelCameraZ, true, u, v);
				uvs[0] = u[0];
				uvs[1] = v[0];
				uvs[4] = u[1];
				uvs[5] = v[1];
				uvs[8] = u[2];
				uvs[9] = v[2];
			}

			int texture = faceTextures != null ? faceTextures[face] + 1 : 0;

			putFace(faceData, 0, triangleA, triangleB, triangleC,
				alphaBias | color1, alphaBias | color2, alphaBias | color3,
				texture, uvs, flags, normals, slot);
			buffer.put(faceData, 0, FACE_SIZE);

			len += 3;
		}

		return len;
	}

	/**
	 * Writes one face in the temporary vertex format (see {@link VAO#VERT_SIZE}): each vertex carries the colours of
	 * all three vertices of the face, in face order, for the shader's per-fragment blend.
	 */
	private void putFace(int[] out, int off, int a, int b, int c, int c1, int c2, int c3,
		int texture, float[] uvs, int flags, int[] n, int material)
	{
		off = putVertex(out, off, a, c1, c2, c3, texture, uvs[0], uvs[1], flags, n[0], n[1], n[2], material);
		off = putVertex(out, off, b, c1, c2, c3, texture, uvs[4], uvs[5], flags, n[3], n[4], n[5], material);
		putVertex(out, off, c, c1, c2, c3, texture, uvs[8], uvs[9], flags, n[6], n[7], n[8], material);
	}

	private int putVertex(int[] out, int off, int vertex, int c1, int c2, int c3, int texture, float u, float v, int flags,
		int nx, int ny, int nz, int material)
	{
		out[off++] = Float.floatToIntBits(modelLocalX[vertex]);
		out[off++] = Float.floatToIntBits(modelLocalY[vertex]);
		out[off++] = Float.floatToIntBits(modelLocalZ[vertex]);
		out[off++] = c1;
		out[off++] = c2;
		out[off++] = c3;
		out[off++] = (flags & 0xffff) << 16 | (texture & 0xffff);
		out[off++] = MathUtils.float16(v) << 16 | MathUtils.float16(u);
		out[off++] = (ny & 0xffff) << 16 | (nx & 0xffff);
		out[off++] = (material & 0xffff) << 16 | (nz & 0xffff);
		return off;
	}

	// flags bit 5 (not read by the shader): the face goes to the alpha buffer
	private static final int ALPHA_FLAG = 1 << 5;

	private static int faceFlags(FaceShading fs)
	{
		return (fs.upwardsNormals ? 1 << 3 : 0) | (fs.uvType == UvType.VANILLA ? 1 << 4 : 0) | (fs.alpha ? ALPHA_FLAG : 0);
	}

	private static final float[] GEOMETRY_UVS = {
		0, 0, 0, 0,
		1, 0, 0, 0,
		0, 1, 0, 0
	};

	/**
	 * 117 HD's face UVs for the face FaceShading last shaded, at out[0..1], out[4..5], out[8..9]: vanilla UVs from the
	 * texture triangle (projected along camera rays for temporary models), UVs computed by the override's UV type
	 * (world UVs evaluated per vertex as 117 HD's vertex shader does), or geometry UVs.
	 *
	 * @param px     the face vertices' positions as uploaded (scene-local after adding basex/basez)
	 */
	static void faceUvs(FaceShading fs, Model model, int face, int preOrientation, boolean project,
		float cameraX, float cameraY, float cameraZ, float[] px, float[] py, float[] pz, int basex, int basez,
		float[] out, float[] work)
	{
		UvType uvType = fs.uvType;
		if (uvType == UvType.VANILLA)
		{
			float[] u = new float[3], v = new float[3];
			computeFaceUvs(model, face, cameraX, cameraY, cameraZ, project, u, v);
			out[0] = u[0];
			out[1] = v[0];
			out[4] = u[1];
			out[5] = v[1];
			out[8] = u[2];
			out[9] = v[2];
		}
		else if (uvType != UvType.GEOMETRY)
		{
			fs.faceOverride.fillUvsForFace(out, model, preOrientation, uvType, face, work);
			if (uvType.worldUvs)
			{
				int a = model.getFaceIndices1()[face];
				int b = model.getFaceIndices2()[face];
				int c = model.getFaceIndices3()[face];
				worldUv(out, 0, px[a] + basex, py[a], pz[a] + basez);
				worldUv(out, 4, px[b] + basex, py[b], pz[b] + basez);
				worldUv(out, 8, px[c] + basex, py[c], pz[c] + basez);
			}
		}
		else
		{
			System.arraycopy(GEOMETRY_UVS, 0, out, 0, 12);
		}
	}

	/**
	 * 117 HD's computeVertexUvs for world UVs: uvw is a plane normal scaled by the UV scale.
	 */
	private static void worldUv(float[] uvw, int i, float x, float y, float z)
	{
		float nx = uvw[i], ny = uvw[i + 1], nz = uvw[i + 2];
		float scale = 1f / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		nx *= scale;
		ny *= scale;
		nz *= scale;
		// C1 = cross((0, 0, 1), N), C2 = cross((0, 1, 0), N)
		float c1x = -ny, c1y = nx, c1z = 0;
		float c2x = nz, c2y = 0, c2z = -nx;
		float c1 = (float) Math.sqrt(c1x * c1x + c1y * c1y + c1z * c1z);
		float c2 = (float) Math.sqrt(c2x * c2x + c2y * c2y + c2z * c2z);
		float tx, ty, tz;
		if (c1 > c2)
		{
			tx = c1x / c1;
			ty = c1y / c1;
			tz = c1z / c1;
		}
		else
		{
			tx = c2x / c2;
			ty = c2y / c2;
			tz = c2z / c2;
		}
		// B = cross(N, T)
		float bx = ny * tz - nz * ty;
		float by = nz * tx - nx * tz;
		// (mat3(T, B, N) * pos).xy / 128 * scale
		uvw[i] = (tx * x + bx * y + nx * z) / 128f * scale;
		uvw[i + 1] = (ty * x + by * y + ny * z) / 128f * scale;
	}

	static void computeFaceUvs(Model model, int face,
		float cameraX, float cameraY, float cameraZ, boolean project, float[] u, float[] v)
	{
		final float[] vertexX = model.getVerticesX();
		final float[] vertexY = model.getVerticesY();
		final float[] vertexZ = model.getVerticesZ();

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final byte[] textureFaces = model.getTextureFaces();
		final int[] texIndices1 = model.getTexIndices1();
		final int[] texIndices2 = model.getTexIndices2();
		final int[] texIndices3 = model.getTexIndices3();

		if (textureFaces != null && textureFaces[face] != -1)
		{
			final int triangleA = indices1[face];
			final int triangleB = indices2[face];
			final int triangleC = indices3[face];

			int tfaceIdx = textureFaces[face] & 0xff;
			int texA = texIndices1[tfaceIdx];
			int texB = texIndices2[tfaceIdx];
			int texC = texIndices3[tfaceIdx];

			// t1 = vertex[texA]
			float t1x = vertexX[texA];
			float t1y = vertexY[texA];
			float t1z = vertexZ[texA];
			// tangent = vertex[texB] - t1
			float tangentX = vertexX[texB] - t1x;
			float tangentY = vertexY[texB] - t1y;
			float tangentZ = vertexZ[texB] - t1z;
			// bitangent = vertex[texC] - t1
			float bitangentX = vertexX[texC] - t1x;
			float bitangentY = vertexY[texC] - t1y;
			float bitangentZ = vertexZ[texC] - t1z;

			// normal = tangent x bitangent
			float normalX = tangentY * bitangentZ - tangentZ * bitangentY;
			float normalY = tangentZ * bitangentX - tangentX * bitangentZ;
			float normalZ = tangentX * bitangentY - tangentY * bitangentX;

			float faceAx = vertexX[triangleA];
			float faceAy = vertexY[triangleA];
			float faceAz = vertexZ[triangleA];
			float faceBx = vertexX[triangleB];
			float faceBy = vertexY[triangleB];
			float faceBz = vertexZ[triangleB];
			float faceCx = vertexX[triangleC];
			float faceCy = vertexY[triangleC];
			float faceCz = vertexZ[triangleC];

			if (project)
			{
				// ray = camera - faceA
				float rayX = cameraX - faceAx;
				float rayY = cameraY - faceAy;
				float rayZ = cameraZ - faceAz;
				// faceA += ray * ((t1 - faceA) ⋅ normal) / (ray ⋅ normal)
				float scale = ((t1x - faceAx) * normalX + (t1y - faceAy) * normalY + (t1z - faceAz) * normalZ) /
					(rayX * normalX + rayY * normalY + rayZ * normalZ);
				faceAx += rayX * scale;
				faceAy += rayY * scale;
				faceAz += rayZ * scale;

				// ray = camera - faceB
				rayX = cameraX - faceBx;
				rayY = cameraY - faceBy;
				rayZ = cameraZ - faceBz;
				// faceB += ray * ((t2 - faceB) ⋅ normal) / (ray ⋅ normal)
				scale = ((vertexX[texB] - faceBx) * normalX + (vertexY[texB] - faceBy) * normalY +
					(vertexZ[texB] - faceBz) * normalZ) /
					(rayX * normalX + rayY * normalY + rayZ * normalZ);
				faceBx += rayX * scale;
				faceBy += rayY * scale;
				faceBz += rayZ * scale;

				// ray = camera - faceC
				rayX = cameraX - faceCx;
				rayY = cameraY - faceCy;
				rayZ = cameraZ - faceCz;
				// faceC += ray * ((t3 - faceC) ⋅ normal) / (ray ⋅ normal)
				scale = ((vertexX[texC] - faceCx) * normalX + (vertexY[texC] - faceCy) * normalY +
					(vertexZ[texC] - faceCz) * normalZ) /
					(rayX * normalX + rayY * normalY + rayZ * normalZ);
				faceCx += rayX * scale;
				faceCy += rayY * scale;
				faceCz += rayZ * scale;
			}

			float relativeAx = faceAx - t1x;
			float relativeAy = faceAy - t1y;
			float relativeAz = faceAz - t1z;
			float relativeBx = faceBx - t1x;
			float relativeBy = faceBy - t1y;
			float relativeBz = faceBz - t1z;
			float relativeCx = faceCx - t1x;
			float relativeCy = faceCy - t1y;
			float relativeCz = faceCz - t1z;

			// uAxis = bitangent x normal
			float uAxisX = bitangentY * normalZ - bitangentZ * normalY;
			float uAxisY = bitangentZ * normalX - bitangentX * normalZ;
			float uAxisZ = bitangentX * normalY - bitangentY * normalX;

			float inverseUDenominator = 1.0F / (uAxisX * tangentX + uAxisY * tangentY + uAxisZ * tangentZ);

			u[0] = (uAxisX * relativeAx + uAxisY * relativeAy + uAxisZ * relativeAz) * inverseUDenominator;
			u[1] = (uAxisX * relativeBx + uAxisY * relativeBy + uAxisZ * relativeBz) * inverseUDenominator;
			u[2] = (uAxisX * relativeCx + uAxisY * relativeCy + uAxisZ * relativeCz) * inverseUDenominator;

			// vAxis = tangent x normal
			float vAxisX = tangentY * normalZ - tangentZ * normalY;
			float vAxisY = tangentZ * normalX - tangentX * normalZ;
			float vAxisZ = tangentX * normalY - tangentY * normalX;

			float inverseVDenominator = 1.0F / (vAxisX * bitangentX + vAxisY * bitangentY + vAxisZ * bitangentZ);

			v[0] = (vAxisX * relativeAx + vAxisY * relativeAy + vAxisZ * relativeAz) * inverseVDenominator;
			v[1] = (vAxisX * relativeBx + vAxisY * relativeBy + vAxisZ * relativeBz) * inverseVDenominator;
			v[2] = (vAxisX * relativeCx + vAxisY * relativeCy + vAxisZ * relativeCz) * inverseVDenominator;
		}
		else
		{
			// Without a texture face, the client assigns tex = triangle, but the resulting
			// calculations can be reduced:
			//
			// t1 = vertex[texA]
			// tangent = vertex[texB] - t1
			// bitangent = vertex[texC] - t1
			//
			// relativeA = 0
			// relativeB = tangent
			// relativeC = bitangent
			//
			// normal = tangent x bitangent
			//
			// uAxis = bitangent x normal
			// u0 = (uAxis . relativeA) / (uAxis . tangent) // 0 because relativeA is 0
			// u1 = (uAxis . relativeB) / (uAxis . tangent) // 1 because relativeB=tangent
			// u2 = (uAxis . relativeC) / (uAxis . tangent) // 0 because uAxis is perpendicular to bitangent/relativeC
			//
			// vAxis = tangent x normal
			// v0 = (vAxis . relativeA) / (vAxis ⋅ bitangent) // 0 because relativeA is 0
			// v1 = (vAxis . relativeB) / (vAxis ⋅ bitangent) // 0 because vAxis is perpendicular to tangent/relativeB
			// v2 = (vAxis . relativeC) / (vAxis ⋅ bitangent) // 1 because relativeC=bitangent

			u[0] = 0f;
			v[0] = 0f;

			u[1] = 1f;
			v[1] = 0f;

			u[2] = 0f;
			v[2] = 1f;
		}
	}
}
