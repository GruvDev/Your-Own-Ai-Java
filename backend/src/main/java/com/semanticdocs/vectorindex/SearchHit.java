package com.semanticdocs.vectorindex;

/**
 * One result from the index.
 *
 * @param externalId the id we were given at insert time (a chunk id in this project)
 * @param distance   raw distance from the metric - smaller is closer
 * @param score      distance mapped to a friendly 0..1 similarity for the UI
 */
public record SearchHit(long externalId, float distance, float score)
        implements Comparable<SearchHit> {

    /** Sorts best-first. */
    @Override
    public int compareTo(SearchHit other) {
        return Float.compare(this.distance, other.distance);
    }

    public static SearchHit of(long externalId, float distance, DistanceMetric metric) {
        return new SearchHit(externalId, distance, toScore(distance, metric));
    }

    /** Maps a distance to a 0..1 similarity so the frontend can draw a bar. */
    private static float toScore(float distance, DistanceMetric metric) {
        float score = switch (metric) {
            case COSINE -> 1f - distance;          // distance in [0,2]
            case DOT -> -distance;                 // normalised vectors -> [-1,1]
            case EUCLIDEAN -> 1f / (1f + distance);
        };
        if (score < 0f) return 0f;
        if (score > 1f) return 1f;
        return score;
    }
}
