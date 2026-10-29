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
package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.scene.materials.MaterialManager;
import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.util.GsonUtils;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL42C.glTexStorage3D;

/**
 * The scene's texture array, built as 117 HD builds its material texture array (MaterialManager / TextureManager)
 * at the configured texture resolution: one layer per vanilla texture (layer = vanilla texture id), then one
 * per HD image Lumina ships for a material's texture owner, then an empty layer. Images are scaled bicubically,
 * HD images flipped horizontally to match vanilla UV orientation, stored as sRGB (GL_SRGB8_ALPHA8) with mipmaps,
 * repeating, and filtered trilinearly with anisotropic filtering.
 */
@Singleton
@Slf4j
public class TextureManager
{
	public static final int TEXTURE_COUNT = 256;
	// 117 HD's textureResolution setting, read when the array is built
	private int textureSize;
	private static final String[] SUPPORTED_IMAGE_EXTENSIONS = {"png", "jpg"};

	private IntBuffer pixelBuffer;
	private BufferedImage scaledImage;
	private BufferedImage vanillaImage;

	public int initTextureArray(TextureProvider textureProvider)
	{
		if (!allTexturesLoaded(textureProvider))
		{
			return -1;
		}

		Texture[] textures = textureProvider.getTextures();
		MaterialManager.initVanillaTextures(textures);
		textureSize = HdSettings.textureResolution.getSize();

		// HD images of material texture owners that Lumina ships
		List<BufferedImage> images = new ArrayList<>();
		Map<String, Integer> imageLayers = new HashMap<>();
		for (String name : MaterialManager.textureImageNames())
		{
			BufferedImage image = loadImage(name);
			if (image != null)
			{
				imageLayers.put(name, textures.length + images.size());
				images.add(image);
			}
		}
		int emptyLayer = textures.length + images.size();
		int layerCount = emptyLayer + 1;

		int textureArrayId = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);
		int mipLevels = 1 + (int) Math.floor(Math.log(textureSize) / Math.log(2));
		int format = GL_SRGB8_ALPHA8;
		if (GL.getCapabilities().glTexStorage3D != 0)
		{
			glTexStorage3D(GL_TEXTURE_2D_ARRAY, mipLevels, format, textureSize, textureSize, layerCount);
		}
		else
		{
			for (int i = 0; i < mipLevels; i++)
			{
				int size = textureSize >> i;
				glTexImage3D(GL_TEXTURE_2D_ARRAY, i, format, size, size, layerCount, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
			}
		}
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);

		// Set brightness to 1.0d to upload unmodified textures to GPU
		double save = textureProvider.getBrightness();
		textureProvider.setBrightness(1.0d);
		int cnt = 0;
		for (int textureId = 0; textureId < textures.length; textureId++)
		{
			if (textures[textureId] == null)
			{
				continue;
			}
			BufferedImage image = loadVanillaImage(textureProvider, textureId);
			if (image != null)
			{
				uploadTexture(textureId, image, true);
				cnt++;
			}
		}
		textureProvider.setBrightness(save);
		for (int i = 0; i < images.size(); i++)
		{
			uploadTexture(textures.length + i, images.get(i), false);
		}
		log.debug("Uploaded {} vanilla textures and {} HD images", cnt, images.size());

		MaterialManager.setImageLayers(imageLayers, emptyLayer);

		glActiveTexture(GL_TEXTURE1);
		glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);
		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
		glActiveTexture(GL_TEXTURE0);

		pixelBuffer = null;
		scaledImage = null;
		vanillaImage = null;
		return textureArrayId;
	}

	private static BufferedImage loadImage(String name)
	{
		for (String ext : SUPPORTED_IMAGE_EXTENSIONS)
		{
			try (InputStream in = TextureManager.class.getResourceAsStream(GsonUtils.SCENE_DATA_PATH + "textures/" + name + "." + ext))
			{
				if (in != null)
				{
					return ImageIO.read(in);
				}
			}
			catch (Exception ex)
			{
				log.warn("Unable to load texture {}.{}", name, ext, ex);
			}
		}
		return null;
	}

	private BufferedImage loadVanillaImage(TextureProvider textureProvider, int textureId)
	{
		int[] pixels = textureProvider.load(textureId);
		if (pixels == null)
		{
			log.warn("No pixels for vanilla texture index {}", textureId);
			return null;
		}
		if (pixels.length != 128 * 128)
		{
			log.warn("Unknown dimensions for vanilla texture at index {} ({} pixels)", textureId, pixels.length);
			return null;
		}
		if (vanillaImage == null)
		{
			vanillaImage = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
		}
		for (int j = 0; j < pixels.length; j++)
		{
			int rgb = pixels[j];
			// Black is considered transparent in vanilla, with anything else being fully opaque
			int alpha = rgb == 0 ? 0 : 0xFF;
			vanillaImage.setRGB(j % 128, j / 128, alpha << 24 | rgb & 0xFFFFFF);
		}
		return vanillaImage;
	}

	// 117 HD's TextureManager.uploadTexture
	private void uploadTexture(int textureLayer, BufferedImage image, boolean isVanilla)
	{
		int numPixels = textureSize * textureSize;
		if (pixelBuffer == null)
		{
			pixelBuffer = BufferUtils.createIntBuffer(numPixels);
		}
		if (scaledImage == null)
		{
			scaledImage = new BufferedImage(textureSize, textureSize, BufferedImage.TYPE_INT_ARGB);
		}

		AffineTransform t = new AffineTransform();
		if (!isVanilla)
		{
			// Flip non-vanilla textures horizontally to match vanilla UV orientation
			t.translate(textureSize, 0);
			t.scale(-1, 1);
		}
		t.scale((double) textureSize / image.getWidth(), (double) textureSize / image.getHeight());
		AffineTransformOp scaleOp = new AffineTransformOp(t, AffineTransformOp.TYPE_BICUBIC);
		scaleOp.filter(image, scaledImage);

		int[] pixels = ((DataBufferInt) scaledImage.getRaster().getDataBuffer()).getData();
		pixelBuffer.clear();
		pixelBuffer.put(pixels).flip();

		glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, textureLayer, textureSize, textureSize, 1,
			GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
	}

	// 117 HD's TextureManager.setAnisotropicFilteringLevel
	public void setAnisotropicFilteringLevel(int textureArrayId, int level)
	{
		glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);
		if (level == 0)
		{
			//level = 0 means no mipmaps and no anisotropic filtering
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		}
		else
		{
			// Trilinear filtering is used for HD textures as linear filtering produces noisy textures
			// that are very noticeable on terrain
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		}

		if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic)
		{
			final float maxSamples = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
			glTexParameterf(GL_TEXTURE_2D_ARRAY, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, Math.max(1, Math.min(level, maxSamples)));
		}
	}

	public void freeTextureArray(int textureArrayId)
	{
		glDeleteTextures(textureArrayId);
	}

	/**
	 * Check if all textures have been loaded and cached yet.
	 */
	private boolean allTexturesLoaded(TextureProvider textureProvider)
	{
		Texture[] textures = textureProvider.getTextures();
		if (textures == null || textures.length == 0)
		{
			return false;
		}

		for (int textureId = 0; textureId < textures.length; textureId++)
		{
			Texture texture = textures[textureId];
			if (texture != null)
			{
				int[] pixels = textureProvider.load(textureId);
				if (pixels == null)
				{
					return false;
				}
			}
		}

		return true;
	}
}
