package com.safehaven.utils;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CompressionUtilsTest {

    @Test
    void compressDecompress_RoundTrip() throws Exception {
        byte[] original = "Hello, SafeHaven! This is a test of compression.".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = CompressionUtils.compress(original);
        byte[] decompressed = CompressionUtils.decompress(compressed);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void compressDecompress_EmptyData() throws Exception {
        byte[] original = new byte[0];
        byte[] compressed = CompressionUtils.compress(original);
        byte[] decompressed = CompressionUtils.decompress(compressed);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void compressDecompress_LargeData() throws Exception {
        byte[] original = new byte[100_000];
        new java.security.SecureRandom().nextBytes(original);
        byte[] compressed = CompressionUtils.compress(original);
        byte[] decompressed = CompressionUtils.decompress(compressed);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void compressDecompress_BinaryData() throws Exception {
        byte[] original = new byte[]{0x00, 0x01, (byte) 0xFF, (byte) 0xFE, 0x42, 0x00};
        byte[] compressed = CompressionUtils.compress(original);
        byte[] decompressed = CompressionUtils.decompress(compressed);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void compress_ProducesSmallerOutput_ForRepeatingData() throws Exception {
        byte[] original = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = CompressionUtils.compress(original);
        assertTrue(compressed.length < original.length);
    }

    @Test
    void decompress_InvalidData_ThrowsException() {
        byte[] garbage = new byte[]{0x00, 0x01, 0x02, 0x03};
        assertThrows(java.io.IOException.class, () -> CompressionUtils.decompress(garbage));
    }
}
