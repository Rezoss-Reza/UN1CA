import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.nio.ByteBuffer;

/** Isolated decoder metadata only; never writes decoded media or private app data. */
public final class FrameLayoutProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected public clip path");
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(args[0]);
            MediaFormat input = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                    extractor.selectTrack(i);
                    input = format;
                    break;
                }
            }
            if (input == null) throw new IllegalArgumentException("No video track");
            System.out.println("SOURCE " + input);
            input.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            System.out.println("DECODER " + codec.getName());
            codec.configure(input, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            int frames = 0;
            long deadline = System.nanoTime() + 120_000_000_000L;
            while (System.nanoTime() < deadline) {
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    System.out.println("OUTPUT " + codec.getOutputFormat());
                } else if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            if (frames % 30 == 0) {
                                Image image = codec.getOutputImage(index);
                                if (image == null) throw new IllegalStateException("No YUV Image");
                                try {
                                    System.out.printf("FRAME %d pts=%d image=%dx%d crop=%s format=%d%n",
                                            frames, info.presentationTimeUs, image.getWidth(), image.getHeight(),
                                            image.getCropRect(), image.getFormat());
                                    Image.Plane[] planes = image.getPlanes();
                                    for (int p = 0; p < planes.length; p++) {
                                        System.out.printf("PLANE %d rowStride=%d pixelStride=%d bytes=%d%n", p,
                                                planes[p].getRowStride(), planes[p].getPixelStride(),
                                                planes[p].getBuffer().remaining());
                                    }
                                } finally { image.close(); }
                            }
                            frames++;
                        }
                    } finally { codec.releaseOutputBuffer(index, false); }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        System.out.println("COMPLETE frames=" + frames);
                        return;
                    }
                }
            }
            throw new IllegalStateException("Decode timeout");
        } finally {
            if (codec != null) codec.release();
            extractor.release();
        }
    }
}
