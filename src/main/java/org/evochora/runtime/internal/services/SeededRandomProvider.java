package org.evochora.runtime.internal.services;

import org.evochora.runtime.spi.IRandomProvider;
import org.apache.commons.math3.random.Well19937c;
import org.apache.commons.math3.random.RandomAdaptor;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Default implementation of {@link IRandomProvider} backed by Apache Commons Math {@link Well19937c}.
 * <p>
 * Uses reflection to access Well19937c's internal state (v array and index) for perfect checkpoint
 * serialization. This ensures 100% reproducibility across checkpoints, which is critical for
 * long-running evolutionary simulations. Well19937c is an industry-standard, scientifically-validated
 * RNG with excellent statistical properties and a period of 2^19937-1.
 * </p>
 * <p>
 * Supports deterministic derivation of child providers using a stable hashing scheme.
 * </p>
 */
public final class SeededRandomProvider implements IRandomProvider {

    private final long seed;
    private final Well19937c rng;

    // Cached reflection fields for fast serialization (initialized once, used every tick)
    private final Field vField;
    private final Field indexField;

    /**
     * Creates a new seeded random provider.
     * @param seed The initial seed for the random number generator.
     */
    public SeededRandomProvider(long seed) {
        this.seed = seed;
        this.rng = new Well19937c(seed);

        // Initialize reflection fields once for fast repeated access
        try {
            this.vField = rng.getClass().getSuperclass().getDeclaredField("v");
            this.indexField = rng.getClass().getSuperclass().getDeclaredField("index");
            this.vField.setAccessible(true);
            this.indexField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Failed to initialize RNG reflection fields", e);
        }
    }

    /**
     * Private constructor for derived providers.
     * @param seed The seed for the new provider.
     * @param alreadyHashed A flag to indicate if the seed is already hashed.
     */
    private SeededRandomProvider(long seed, boolean alreadyHashed) {
        this.seed = seed;
        this.rng = new Well19937c(seed);

        // Initialize reflection fields once for fast repeated access
        try {
            this.vField = rng.getClass().getSuperclass().getDeclaredField("v");
            this.indexField = rng.getClass().getSuperclass().getDeclaredField("index");
            this.vField.setAccessible(true);
            this.indexField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Failed to initialize RNG reflection fields", e);
        }
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
        // Wrap the Well19937c in a Random-compatible adapter
        return new RandomAdaptor(rng);
    }

    @Override
    public IRandomProvider deriveFor(String scope, long key) {
        long h = mix64(seed);
        h = mix64(h ^ mix64(hashString(scope)));
        h = mix64(h ^ mix64(key));
        return new SeededRandomProvider(h, true);
    }

    /**
     * Hashes a string using the FNV-1a 64-bit algorithm.
     * @param s The string to hash.
     * @return The hashed value.
     */
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

    /**
     * A SplitMix64 mix function for good bit diffusion.
     * @param z The value to mix.
     * @return The mixed value.
     */
    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    @Override
    public byte[] saveState() {
        try {
            // Access protected fields using cached Field objects (fast)
            int[] v = (int[]) vField.get(rng);
            int index = indexField.getInt(rng);

            // Serialize: 4 bytes for index + (v.length * 4) bytes for state array
            ByteBuffer buffer = ByteBuffer.allocate(4 + (v.length * 4));
            buffer.putInt(index);
            for (int value : v) {
                buffer.putInt(value);
            }
            return buffer.array();
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to serialize RNG state", e);
        }
    }

    @Override
    public void loadState(byte[] state) {
        if (state == null) {
            throw new IllegalArgumentException("RNG state cannot be null");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(state);
            int index = buffer.getInt();

            // Read the state array
            int[] v = (int[]) vField.get(rng);
            for (int i = 0; i < v.length; i++) {
                v[i] = buffer.getInt();
            }

            // Set both index and state array using cached Field objects (fast)
            indexField.setInt(rng, index);
            vField.set(rng, v);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to deserialize RNG state", e);
        }
    }
}


