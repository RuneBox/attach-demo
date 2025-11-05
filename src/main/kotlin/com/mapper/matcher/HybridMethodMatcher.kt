package com.mapper.matcher

import com.mapper.model.MethodInfo

/**
 * Hybrid matcher combining TF-IDF and KNN for optimal matching
 *
 * Strategy:
 * 1. Use TF-IDF for fast candidate generation (filter thousands -> tens)
 * 2. Use KNN for precise final ranking (pick best from top candidates)
 * 3. Combine scores with weighted average
 */
class HybridMethodMatcher {

    private val tfIdfMatcher = TfIdfMethodMatcher()
    private val knnMatcher = KnnMethodMatcher()

    /**
     * Build models from source and target method collections
     */
    fun buildModels(sourceMethods: Collection<MethodInfo>, targetMethods: Collection<MethodInfo>) {
        // Build TF-IDF model on target methods (the search space)
        tfIdfMatcher.buildModel(targetMethods)

        // Build KNN normalization from both collections
        knnMatcher.buildNormalization(sourceMethods + targetMethods)
    }

    /**
     * Find best matches for a method using hybrid approach
     *
     * @param queryMethod The method to match
     * @param candidateMethods Pool of candidate methods to match against
     * @param tfIdfK Number of candidates to retrieve with TF-IDF
     * @param knnK Number of candidates to retrieve with KNN
     * @return List of (method, combinedScore) pairs, sorted by score descending
     */
    fun findBestMatches(
        queryMethod: MethodInfo,
        candidateMethods: Collection<MethodInfo>,
        tfIdfK: Int = 20,
        knnK: Int = 10
    ): List<Pair<MethodInfo, Double>> {
        // Phase 1: TF-IDF candidate generation (fast filtering)
        val tfIdfCandidates = tfIdfMatcher.findTopCandidates(
            queryMethod,
            candidateMethods,
            k = tfIdfK
        )

        if (tfIdfCandidates.isEmpty()) {
            return emptyList()
        }

        // If TF-IDF gives us very few candidates, just use those
        if (tfIdfCandidates.size <= 3) {
            return tfIdfCandidates
        }

        // Phase 2: KNN refinement on TF-IDF candidates
        val tfIdfMethods = tfIdfCandidates.map { it.first }
        val knnResults = knnMatcher.findKNearest(
            queryMethod,
            tfIdfMethods,
            k = minOf(knnK, tfIdfMethods.size)
        )

        // Phase 3: Combine scores with weighted average
        val tfIdfScoreMap = tfIdfCandidates.toMap()
        val combinedScores = knnResults.map { (method, knnScore) ->
            val tfIdfScore = tfIdfScoreMap[method] ?: 0.0

            // Weighted combination:
            // - TF-IDF weight: 0.4 (good for unique features like strings)
            // - KNN weight: 0.6 (better for structural similarity)
            val combinedScore = (tfIdfScore * 0.4) + (knnScore * 0.6)

            method to combinedScore
        }

        return combinedScores.sortedByDescending { it.second }
    }

    /**
     * Find single best match with confidence score
     *
     * Returns null if:
     * - No candidates found
     * - Best score below threshold
     * - Confidence gap between 1st and 2nd too small
     *
     * @param minScore Minimum combined score (default 0.7)
     * @param minGap Minimum gap between 1st and 2nd place (default 0.15)
     */
    fun findBestMatchWithConfidence(
        queryMethod: MethodInfo,
        candidateMethods: Collection<MethodInfo>,
        minScore: Double = 0.7,
        minGap: Double = 0.15
    ): Pair<MethodInfo, Double>? {
        val matches = findBestMatches(queryMethod, candidateMethods, tfIdfK = 20, knnK = 10)

        if (matches.isEmpty()) return null

        val best = matches[0]
        val second = matches.getOrNull(1)

        // Check minimum score threshold
        if (best.second < minScore) return null

        // Check confidence gap (avoid ambiguous matches)
        if (second != null) {
            val gap = best.second - second.second
            if (gap < minGap) return null
        }

        return best
    }
}

/**
 * Extended result that includes both TF-IDF and KNN scores for analysis
 */
data class DetailedMatchResult(
    val method: MethodInfo,
    val tfIdfScore: Double,
    val knnScore: Double,
    val combinedScore: Double
) {
    override fun toString(): String {
        return "${method.fullSignature}\n" +
               "  TF-IDF: ${"%.4f".format(tfIdfScore)}\n" +
               "  KNN:    ${"%.4f".format(knnScore)}\n" +
               "  Combined: ${"%.4f".format(combinedScore)}"
    }
}
