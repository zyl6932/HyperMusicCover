package com.os4.musiccover;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Encodes an album art Bitmap into a minimal 1-frame MP4 video via Android's native
 * MediaCodec + MediaMuxer, so HyperOS's FastPlayer video wallpaper engine can play/freeze
 * it directly on the wallpaper window surface without any cross-window blur bleed-through.
 */
public class CoverVideoEncoder {

    private static final String TAG = "[MCCoverEnc] ";
    private static final String MIME_TYPE = "video/avc";
    private static final int TIMEOUT_US = 10000;

    private static volatile long sLastContentKey = 0;
    private static volatile String sCachedVideoPath = null;

    /**
     * Encodes a 1-frame (or brief 2-frame) MP4 video from the provided bitmap.
     * If the bitmap is identical to the last one and the file exists, returns the cached file.
     *
     * @param bitmap the source album art bitmap
     * @param destFile the output mp4 file
     * @return true if successful, false otherwise
     */
    public static synchronized boolean encodeBitmapToMp4(Bitmap bitmap, File destFile) {
        return encodeBitmapToMp4(bitmap, destFile, 0);
    }

    /**
     * Encodes a 1-frame MP4 video from the provided bitmap, using contentKey for cache verification.
     *
     * @param bitmap the source album art bitmap
     * @param destFile the output mp4 file
     * @param contentKey an explicit checksum (e.g. CRC32 of source JPEG), or 0 to compute from bitmap
     * @return true if successful, false otherwise
     */
    public static synchronized boolean encodeBitmapToMp4(Bitmap bitmap, File destFile, long contentKey) {
        if (bitmap == null || bitmap.isRecycled()) {
            Xp.log(TAG + "encodeBitmapToMp4: bitmap is null or recycled");
            return false;
        }

        if (contentKey == 0) {
            contentKey = computeBitmapChecksum(bitmap);
        }

        if (contentKey != 0 && contentKey == sLastContentKey && destFile.exists() && destFile.length() > 0) {
            Xp.log(TAG + "encodeBitmapToMp4: reusing cached video at " + destFile.getAbsolutePath());
            sCachedVideoPath = destFile.getAbsolutePath();
            return true;
        }

        long startTime = SystemClock.uptimeMillis();

        // Downscale to target video dimensions (max width 720, 16-aligned) to optimize encoding
        // latency and memory footprint. GPU bilinear texture filtering in FastPlayer scales it
        // smoothly to screen size with negligible visual difference for wallpaper backgrounds.
        int srcW = bitmap.getWidth();
        int srcH = bitmap.getHeight();
        int width = srcW;
        int height = srcH;
        if (width > 720) {
            width = 720;
            height = (int) Math.round((double) srcH * width / srcW);
        }
        width = (width / 16) * 16;
        height = (height / 16) * 16;

        Bitmap scaledBitmap = bitmap;
        if (scaledBitmap.getWidth() != width || scaledBitmap.getHeight() != height) {
            scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
        }

        MediaCodec encoder = null;
        MediaMuxer muxer = null;

        try {
            MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
            if (codecInfo == null) {
                Xp.log(TAG + "No encoder found for " + MIME_TYPE);
                return false;
            }

            int colorFormat = selectColorFormat(codecInfo, MIME_TYPE);
            if (colorFormat == 0) {
                Xp.log(TAG + "No supported color format found");
                return false;
            }

            // Adjust width & height if codec has specific alignment requirements
            MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(MIME_TYPE);
            if (caps != null && caps.getVideoCapabilities() != null) {
                MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                int widthAlignment = videoCaps.getWidthAlignment();
                int heightAlignment = videoCaps.getHeightAlignment();
                if (widthAlignment > 0 && width % widthAlignment != 0) {
                    width = (width / widthAlignment) * widthAlignment;
                }
                if (heightAlignment > 0 && height % heightAlignment != 0) {
                    height = (height / heightAlignment) * heightAlignment;
                }
            }

            if (scaledBitmap.getWidth() != width || scaledBitmap.getHeight() != height) {
                Bitmap prev = (scaledBitmap != bitmap) ? scaledBitmap : null;
                scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
                if (prev != null && prev != scaledBitmap) prev.recycle();
            }

            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createByCodecName(codecInfo.getName());
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            if (destFile.exists()) {
                destFile.delete();
            }
            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            muxer = new MediaMuxer(destFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int trackIndex = -1;
            boolean muxerStarted = false;

            // Prepare YUV buffer
            int[] argb = new int[width * height];
            scaledBitmap.getPixels(argb, 0, width, 0, 0, width, height);
            byte[] yuv = new byte[width * height * 3 / 2];
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                encodeYUV420P(yuv, argb, width, height);
            } else {
                encodeYUV420SP(yuv, argb, width, height);
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            // Feed 2 identical frames (0ms and 100ms) with EOS on frame 1
            for (int frame = 0; frame < 2; frame++) {
                int inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        inputBuffer.put(yuv);
                        long pts = frame * 100000L; // 0us, 100000us (100ms)
                        int flags = (frame == 1) ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
                        encoder.queueInputBuffer(inputIndex, 0, yuv.length, pts, flags);
                    }
                }
            }

            // Drain encoder output into muxer
            boolean eosReached = false;
            while (!eosReached) {
                int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if ((SystemClock.uptimeMillis() - startTime) > 2000L) {
                        Xp.log(TAG + "Encoding timed out after 2000ms");
                        break;
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new RuntimeException("format changed twice");
                    }
                    MediaFormat newFormat = encoder.getOutputFormat();
                    trackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                } else if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = encoder.getOutputBuffer(outputIndex);
                    if (outputBuffer != null) {
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0;
                        }
                        if (bufferInfo.size != 0) {
                            if (!muxerStarted) {
                                throw new RuntimeException("muxer hasn't started");
                            }
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                        }
                        encoder.releaseOutputBuffer(outputIndex, false);
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eosReached = true;
                        }
                    }
                }
            }

            if (!eosReached) {
                Xp.log(TAG + "encodeBitmapToMp4: timed out or ended before EOS");
                if (destFile.exists()) {
                    destFile.delete();
                }
                return false;
            }

            sLastContentKey = contentKey;
            sCachedVideoPath = destFile.getAbsolutePath();
            long cost = SystemClock.uptimeMillis() - startTime;
            Xp.log(TAG + "1-frame MP4 generated successfully at " + destFile.getAbsolutePath()
                    + " (" + width + "x" + height + ", " + destFile.length() + "B, " + cost + "ms)");
            return true;

        } catch (Throwable t) {
            Xp.log(TAG + "Failed to encode bitmap to mp4: " + t);
            return false;
        } finally {
            if (scaledBitmap != null && scaledBitmap != bitmap) {
                try {
                    scaledBitmap.recycle();
                } catch (Throwable ignored) {}
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Throwable ignored) {}
                try {
                    encoder.release();
                } catch (Throwable ignored) {}
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Throwable ignored) {}
                try {
                    muxer.release();
                } catch (Throwable ignored) {}
            }
        }
    }

    public static String getCachedVideoPath() {
        return sCachedVideoPath;
    }

    public static long computeBitmapChecksum(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return 0;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        long hash = ((long) w << 32) | (h & 0xFFFFFFFFL);
        int stepX = Math.max(1, w / 16);
        int stepY = Math.max(1, h / 16);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                hash = hash * 31 + bitmap.getPixel(x, y);
            }
        }
        return hash;
    }

    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) continue;
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    private static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int colorFormat : capabilities.colorFormats) {
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                return colorFormat;
            }
        }
        for (int colorFormat : capabilities.colorFormats) {
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                return colorFormat;
            }
        }
        return 0;
    }

    private static void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        final int frameSize = width * height;
        int yIndex = 0;
        int uvIndex = frameSize;
        for (int j = 0; j < height; j++) {
            int rowOffset = j * width;
            boolean isEvenRow = (j & 1) == 0;
            for (int i = 0; i < width; i++) {
                int p = argb[rowOffset + i];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuv420sp[yIndex++] = (byte) (y < 0 ? 0 : (y > 255 ? 255 : y));
                if (isEvenRow && (i & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    yuv420sp[uvIndex++] = (byte) (u < 0 ? 0 : (u > 255 ? 255 : u));
                    yuv420sp[uvIndex++] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                }
            }
        }
    }

    private static void encodeYUV420P(byte[] yuv420p, int[] argb, int width, int height) {
        final int frameSize = width * height;
        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + frameSize / 4;
        for (int j = 0; j < height; j++) {
            int rowOffset = j * width;
            boolean isEvenRow = (j & 1) == 0;
            for (int i = 0; i < width; i++) {
                int p = argb[rowOffset + i];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuv420p[yIndex++] = (byte) (y < 0 ? 0 : (y > 255 ? 255 : y));
                if (isEvenRow && (i & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    yuv420p[uIndex++] = (byte) (u < 0 ? 0 : (u > 255 ? 255 : u));
                    yuv420p[vIndex++] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                }
            }
        }
    }
}
