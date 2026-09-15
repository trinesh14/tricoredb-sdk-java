package io.github.trinesh14.tricoredb;

import java.util.Map;

/** Catalog metadata for one vector collection. */
public record VectorCollectionInfo(
        String collection,
        int dimension,
        VectorMetric metric,
        long count,
        VectorQuantization quantization) {

    static VectorCollectionInfo from(Map<String, Object> m) {
        return new VectorCollectionInfo(
                Wire.str(m, "collection"),
                (int) Wire.num(m, "dimension"),
                VectorMetric.fromWire(Wire.str(m, "metric")),
                Wire.num(m, "count"),
                VectorQuantization.fromWire(Wire.str(m, "quantization")));
    }
}
