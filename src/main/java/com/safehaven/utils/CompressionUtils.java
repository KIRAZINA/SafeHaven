package com.safehaven.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class CompressionUtils {

    /** Fixed-size buffer used by all streaming compression operations. */
    public static final int BUFFER_SIZE = 8192;

    public static byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        compressStream(new ByteArrayInputStream(data), baos);
        return baos.toByteArray();
    }

    public static byte[] decompress(byte[] compressedData) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        decompressStream(new ByteArrayInputStream(compressedData), baos);
        return baos.toByteArray();
    }

    /**
     * Compresses {@code in} as GZIP into {@code out} using a fixed-size buffer.
     * The GZIP stream is finished/flushed but the caller's {@code out} is left
     * open so it can be part of a larger pipeline (for example encryption).
     *
     * @return the number of uncompressed bytes read from {@code in}
     */
    public static long compressStream(InputStream in, OutputStream out) throws IOException {
        GZIPOutputStream gzip = new GZIPOutputStream(
                new NonClosingOutputStream(out), BUFFER_SIZE);
        try {
            return copyStream(in, gzip);
        } finally {
            // Finishes the GZIP member and releases the Deflater, without
            // closing the caller's output stream.
            gzip.close();
        }
    }

    /**
     * Decompresses GZIP data from {@code in} into {@code out} using a fixed-size
     * buffer. Neither the caller's input nor output stream is closed.
     *
     * @return the number of uncompressed bytes written to {@code out}
     */
    public static long decompressStream(InputStream in, OutputStream out) throws IOException {
        GZIPInputStream gzip = new GZIPInputStream(
                new NonClosingInputStream(in), BUFFER_SIZE);
        try {
            long decompressedSize = copyStream(gzip, out);

            // Drain any bytes left after the GZIP trailer. This is important
            // when the underlying stream is an AEAD CipherInputStream: reading
            // to EOF forces GCM tag verification before the stream is closed.
            byte[] drain = new byte[BUFFER_SIZE];
            while (in.read(drain) != -1) {
                // discard
            }
            return decompressedSize;
        } finally {
            // Releases the Inflater without closing the caller's input stream.
            gzip.close();
        }
    }

    private static long copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    /** Prevents GZIP from closing a stream that belongs to the caller. */
    private static final class NonClosingInputStream extends FilterInputStream {
        private NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // Caller owns the underlying stream.
        }
    }

    /** Prevents GZIP from closing a stream that belongs to the caller. */
    private static final class NonClosingOutputStream extends FilterOutputStream {
        private NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
