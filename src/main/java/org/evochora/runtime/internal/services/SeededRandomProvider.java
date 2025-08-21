package org.evochora.runtime.internal.services;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Default implementation of {@link IRandomProvider} backed by {@link Random}.
 * Supports deterministic derivation of child providers using a stable hashing scheme.
 */
public final class SeededRandomProvider implements IRandomProvider {

    private final long seed;
    private final Random rng;

    public SeededRandomProvider(long seed) {
        this.seed = seed;
        this.rng = new Random(seed);
    }

    private SeededRandomProvider(long seed, boolean alreadyHashed) {
        this.seed = seed;
        this.rng = new Random(seed);
    }

    @Override
    public int nextInt(int bound) {
        return rng.nextInt(bound);
    }

    @Override
    public double nextDouble() {
        return rng.nextDouble();
    }

    @Override
    public Random asJavaRandom() {
        return rng;
    }

    @Override
    public IRandomProvider deriveFor(String scope, long key) {
        long h = mix64(seed);
        h = mix64(h ^ mix64(hashString(scope)));
        h = mix64(h ^ mix64(key));
        return new SeededRandomProvider(h, true);
    }

    private static long hashString(String s) {
        if (s == null) return 0L;
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        long h = 1469598103934665603L; // FNV-1a 64-bit offset basis
        for (byte value : b) {
            h ^= (value & 0xFF);
            h *= 1099511628211L; // FNV-1a prime
        }
        return h;
    }

    // SplitMix64 mix function for good bit diffusion
    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}


