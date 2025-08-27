package org.evochora.compiler.backend.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Small n-dimensional vector utilities used by the layout phase.
 */
public final class Nd {

	private Nd() {}

	/**
	 * Creates a copy of the given vector.
	 * @param v The vector to copy.
	 * @return A new vector with the same components.
	 */
	public static int[] copy(int[] v) {
		int[] c = new int[v.length];
		System.arraycopy(v, 0, c, 0, v.length);
		return c;
	}

	/**
	 * Adds two vectors.
	 * @param a The first vector.
	 * @param b The second vector.
	 * @return A new vector containing the sum of the two vectors.
	 */
	public static int[] add(int[] a, int[] b) {
		int[] r = new int[Math.max(a.length, b.length)];
		for (int i = 0; i < r.length; i++) {
			int ai = i < a.length ? a[i] : 0;
			int bi = i < b.length ? b[i] : 0;
			r[i] = ai + bi;
		}
		return r;
	}

	/**
	 * Scales a vector by a scalar value.
	 * @param v The vector to scale.
	 * @param k The scalar value.
	 * @return A new vector containing the scaled components.
	 */
	public static int[] scale(int[] v, int k) {
		int[] r = new int[v.length];
		for (int i = 0; i < v.length; i++) r[i] = v[i] * k;
		return r;
	}

	/**
	 * @param v The vector.
	 * @return The number of dimensions of the vector.
	 */
	public static int dims(int[] v) {
		return v.length;
	}

	/**
	 * Converts a vector to a list of integers.
	 * @param v The vector to convert.
	 * @return A list containing the components of the vector.
	 */
	public static List<Integer> toList(int[] v) {
		List<Integer> list = new ArrayList<>(v.length);
		for (int x : v) list.add(x);
		return list;
	}
}


