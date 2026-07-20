package com.safehaven.integration;

import com.safehaven.crypto.CryptoUtils;
import com.safehaven.utils.CompressionUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ParameterizedRoundTripTest {

    static Stream<Arguments> cryptoInputs() {
        SecureRandom rng = new SecureRandom();
        return Stream.of(
            Arguments.of("empty data", new byte[0]),
            Arguments.of("single byte", new byte[]{0x42}),
            Arguments.of("all zeros 1KB", new byte[1024]),
            Arguments.of("all 0xFF 1KB", fill(new byte[1024], (byte) 0xFF)),
            Arguments.of("random 1KB", randomBytes(1024, rng)),
            Arguments.of("random 10KB", randomBytes(10 * 1024, rng)),
            Arguments.of("random 1MB", randomBytes(1024 * 1024, rng)),
            Arguments.of("ascii text", "The quick brown fox jumps over the lazy dog".getBytes()),
            Arguments.of("unicode text", "Hello 世界 🌍! Привет!".getBytes())
        );
    }

    static Stream<Arguments> compressionInputs() {
        SecureRandom rng = new SecureRandom();
        return Stream.of(
            Arguments.of("empty data", new byte[0]),
            Arguments.of("single byte", new byte[]{0x42}),
            Arguments.of("all zeros 1KB", new byte[1024]),
            Arguments.of("all 0xFF 1KB", fill(new byte[1024], (byte) 0xFF)),
            Arguments.of("random 10KB", randomBytes(10 * 1024, rng)),
            Arguments.of("random 100KB", randomBytes(100 * 1024, rng)),
            Arguments.of("repeating pattern", "ABCDEFGHIJ".repeat(1000).getBytes()),
            Arguments.of("highly compressible", "AAAAAAAAAA".repeat(10000).getBytes())
        );
    }

    @ParameterizedTest(name = "crypto round-trip: {0} ({1} bytes)")
    @MethodSource("cryptoInputs")
    void cryptoRoundTrip_VariedInputs(String label, byte[] input) {
        SecretKey key = CryptoUtils.generateFileKey();
        byte[] encrypted = CryptoUtils.encrypt(input, key);
        byte[] decrypted = CryptoUtils.decrypt(encrypted, key);
        assertArrayEquals(input, decrypted, "Crypto round-trip failed for: " + label);
    }

    @ParameterizedTest(name = "compression round-trip: {0} ({1} bytes)")
    @MethodSource("compressionInputs")
    void compressionRoundTrip_VariedInputs(String label, byte[] input) throws Exception {
        byte[] compressed = CompressionUtils.compress(input);
        byte[] decompressed = CompressionUtils.decompress(compressed);
        assertArrayEquals(input, decompressed, "Compression round-trip failed for: " + label);
    }

    private static byte[] fill(byte[] arr, byte val) {
        for (int i = 0; i < arr.length; i++) arr[i] = val;
        return arr;
    }

    private static byte[] randomBytes(int len, SecureRandom rng) {
        byte[] arr = new byte[len];
        rng.nextBytes(arr);
        return arr;
    }
}
