package com.moulberry.flashback.exporting;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class SaveableFramebuffer implements AutoCloseable {
    private int pboId;
    public @Nullable FloatBuffer audioBuffer;

    private boolean isDownloading = false;
    private @Nullable NativeImage directDownload = null;

    public SaveableFramebuffer() {
        this.pboId = -1;
    }

    public void startDownload(GpuTexture gpuTexture, int width, int height) {
        if (this.isDownloading) {
            throw new IllegalStateException("Can't start downloading while already downloading");
        }
        this.isDownloading = true;

        if (com.moulberry.flashback.MobileCompat.isAndroid()) {
            this.directDownload = startDownloadDirect(gpuTexture, width, height);
            return;
        }

        if (this.pboId == -1) {
            this.pboId = GL30C.glGenBuffers();

            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
            GL30C.glBufferData(GL30C.GL_PIXEL_PACK_BUFFER, (long) width * height * 4, GL30C.GL_STREAM_READ);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);
        }

        int fbo = ((GlTexture)gpuTexture).getFbo(((GlDevice)RenderSystem.getDevice()).directStateAccess(), null);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
        GL30C.glReadPixels(0, 0, width, height, GL30C.GL_RGBA, GL30C.GL_UNSIGNED_BYTE, 0);
        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private static @Nullable NativeImage startDownloadDirect(GpuTexture gpuTexture, int width, int height) {
        try {
            NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            int fbo = ((GlTexture)gpuTexture).getFbo(((GlDevice)RenderSystem.getDevice()).directStateAccess(), null);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GL30C.glReadPixels(0, 0, width, height, GL30C.GL_RGBA, GL30C.GL_UNSIGNED_BYTE, nativeImage.pixels);

            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            return nativeImage;
        } catch (Throwable t) {
            com.moulberry.flashback.Flashback.LOGGER.error("Flashback mobile: direct framebuffer readback failed", t);
            return null;
        }
    }

    public @Nullable NativeImage finishDownload(int width, int height) {
        if (!this.isDownloading) {
            throw new IllegalStateException("Can't finish downloading before download has started");
        }
        this.isDownloading = false;

        if (this.directDownload != null) {
            NativeImage image = this.directDownload;
            this.directDownload = null;
            return image;
        }

        if (com.moulberry.flashback.MobileCompat.isAndroid()) {
            return null;
        }

        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);

        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
        ByteBuffer buffer = GL30C.glMapBuffer(GL30C.GL_PIXEL_PACK_BUFFER, GL30C.GL_READ_ONLY);

        if (buffer == null) {
            throw new IllegalStateException("OpenGL error occurred while mapping buffer");
        }

        // Copy bytes
        MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), nativeImage.pixels, nativeImage.size);

        GL30C.glUnmapBuffer(GL30C.GL_PIXEL_PACK_BUFFER);
        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);

        return nativeImage;
    }

    public void close() {
        if (this.pboId != -1) {
            GL30C.glDeleteBuffers(this.pboId);
            this.pboId = -1;
        }
        if (this.directDownload != null) {
            this.directDownload.close();
            this.directDownload = null;
        }
    }

}
