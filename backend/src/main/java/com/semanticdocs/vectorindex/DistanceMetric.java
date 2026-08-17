package com.semanticdocs.vectorindex;

/**
 * How "far apart" two vectors are. Smaller = more similar, always.
 *
 * <p>Interview note: on L2-normalised vectors, COSINE and DOT produce the identical
 * ranking, and EUCLIDEAN produces the identical ranking too (because
 * |a-b|^2 = 2 - 2*dot when |a| = |b| = 1). We normalise once at insert time and then
 * use the cheapest formula. That is a real optimisation, not trivia.
 */
public enum DistanceMetric {

    /** 1 - cosine similarity. Range [0, 2]. Safe even if vectors are not normalised. */
    COSINE {
        @Override
        public float distance(float[] a, float[] b) {
            float dot = 0f, na = 0f, nb = 0f;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                na += a[i] * a[i];
                nb += b[i] * b[i];
            }
            if (na == 0f || nb == 0f) return 1f;
            double sim = dot / (Math.sqrt(na) * Math.sqrt(nb));
            return (float) (1.0 - sim);
        }

        @Override
        public boolean requiresNormalisation() {
            return true;
        }
    },

    /** Negative inner product. Assumes vectors are already normalised. */
    DOT {
        @Override
        public float distance(float[] a, float[] b) {
            float dot = 0f;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
            }
            return -dot;
        }

        @Override
        public boolean requiresNormalisation() {
            return true;
        }
    },

    /** Squared euclidean distance. We skip the sqrt because it does not change ordering. */
    EUCLIDEAN {
        @Override
        public float distance(float[] a, float[] b) {
            float sum = 0f;
            for (int i = 0; i < a.length; i++) {
                float d = a[i] - b[i];
                sum += d * d;
            }
            return sum;
        }

        @Override
        public boolean requiresNormalisation() {
            return false;
        }
    };

    public abstract float distance(float[] a, float[] b);

    /** True when this metric only behaves correctly on unit-length vectors. */
    public abstract boolean requiresNormalisation();

    /** Scales a vector to unit length in place. Returns the same array for convenience. */
    public static float[] normalise(float[] v) {
        double sum = 0.0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum == 0.0) return v;
        float inv = (float) (1.0 / Math.sqrt(sum));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
        return v;
    }
}
