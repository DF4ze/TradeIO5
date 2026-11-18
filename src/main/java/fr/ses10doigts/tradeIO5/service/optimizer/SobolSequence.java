package fr.ses10doigts.tradeIO5.service.optimizer;

import java.util.SplittableRandom;

/**
 * Suite de Sobol déterministe (nombres directeurs de Joe & Kuo, jusqu'à {@value #MAX_DIMENSIONS} dimensions), décalée par une
 * translation aléatoire fixée par la graine (Cranley-Patterson) : même graine ⇒ même suite, graine différente ⇒ suite différente.
 * Le point d'origine (tout à zéro) est ignoré. Au-delà de {@value #MAX_DIMENSIONS} dimensions (espace L1 : jusqu'à ~20), les
 * dimensions supplémentaires sont tirées uniformément par un générateur à graine fixe (déterministe, non quasi-aléatoire).
 */
public final class SobolSequence {

    public static final int MAX_DIMENSIONS = 12;

    private static final int BITS = 30;
    private static final double SCALE = 1.0 / (1L << BITS);
    // {s, a, m1..ms} par dimension 2..12
    private static final int[][] DIRECTIONS = {
            {1, 0, 1}, {2, 1, 1, 3}, {3, 1, 1, 3, 1}, {3, 2, 1, 1, 1}, {4, 1, 1, 1, 3, 3}, {4, 4, 1, 3, 5, 13},
            {5, 2, 1, 1, 5, 5, 17}, {5, 4, 1, 1, 5, 5, 5}, {5, 7, 1, 1, 7, 11, 19}, {5, 11, 1, 1, 5, 1, 1}, {5, 13, 1, 1, 1, 3, 11}};

    private final int dimensions;
    private final int sobolDims;
    private final SplittableRandom extra;
    private final long[][] direction;
    private final long[] state;
    private final double[] shift;
    private long index;

    public SobolSequence(int dimensions, long seed) {
        if (dimensions < 1) {
            throw new IllegalArgumentException("Dimensions Sobol : au moins 1, reçu " + dimensions);
        }
        this.dimensions = dimensions;
        this.sobolDims = Math.min(dimensions, MAX_DIMENSIONS);
        this.extra = new SplittableRandom(seed ^ 0x9E3779B97F4A7C15L);
        this.direction = new long[sobolDims][BITS];
        this.state = new long[sobolDims];
        this.shift = new double[dimensions];
        for (int k = 0; k < BITS; k++) {
            direction[0][k] = 1L << (BITS - 1 - k);
        }
        for (int d = 1; d < sobolDims; d++) {
            int[] def = DIRECTIONS[d - 1];
            int s = def[0];
            int a = def[1];
            long[] v = direction[d];
            for (int k = 0; k < s; k++) {
                v[k] = (long) def[2 + k] << (BITS - 1 - k);
            }
            for (int k = s; k < BITS; k++) {
                v[k] = v[k - s] ^ (v[k - s] >> s);
                for (int i = 1; i < s; i++) {
                    if (((a >> (s - 1 - i)) & 1) == 1) {
                        v[k] ^= v[k - i];
                    }
                }
            }
        }
        SplittableRandom random = new SplittableRandom(seed);
        for (int d = 0; d < dimensions; d++) {
            shift[d] = random.nextDouble();
        }
    }

    /** Point suivant, chaque coordonnée dans [0, 1[. */
    public double[] next() {
        int c = Long.numberOfTrailingZeros(~index);
        index++;
        double[] out = new double[dimensions];
        for (int d = 0; d < sobolDims; d++) {
            state[d] ^= direction[d][c];
            double u = state[d] * SCALE + shift[d];
            out[d] = u >= 1.0 ? u - 1.0 : u;
        }
        for (int d = sobolDims; d < dimensions; d++) {
            out[d] = extra.nextDouble();
        }
        return out;
    }
}
