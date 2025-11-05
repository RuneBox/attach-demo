package com.mapper.matcher

import com.mapper.model.MethodInfo
import kotlin.math.log
import kotlin.math.sqrt

/**
 * TF-IDF based method fingerprinting for fast candidate generation
 *
 * Use this in Phase 3 to quickly filter thousands of methods down to
 * top 10-20 candidates per method.
 */
class TfIdfMethodMatcher {

    private val documentFrequency = mutableMapOf<String, Int>()
    private val methodDocuments = mutableMapOf<String, List<String>>()
    private var totalDocuments = 0

    /**
     * Build the TF-IDF model from a collection of methods
     */
    fun buildModel(methods: Collection<MethodInfo>) {
        totalDocuments = methods.size
        documentFrequency.clear()
        methodDocuments.clear()

        methods.forEach { method ->
            val tokens = generateTokens(method)
            methodDocuments[method.fullSignature] = tokens

            // Count document frequency for each unique token
            tokens.toSet().forEach { token ->
                documentFrequency[token] = documentFrequency.getOrDefault(token, 0) + 1
            }
        }
    }

    /**
     * Find top-k most similar methods using cosine similarity on TF-IDF vectors
     */
    fun findTopCandidates(
        queryMethod: MethodInfo,
        candidateMethods: Collection<MethodInfo>,
        k: Int = 10
    ): List<Pair<MethodInfo, Double>> {
        val queryTokens = generateTokens(queryMethod)
        val queryTfIdf = computeTfIdf(queryTokens)

        val scores = candidateMethods.map { candidate ->
            val candidateTokens = methodDocuments[candidate.fullSignature]
                ?: generateTokens(candidate)
            val candidateTfIdf = computeTfIdf(candidateTokens)

            val similarity = cosineSimilarity(queryTfIdf, candidateTfIdf)
            candidate to similarity
        }

        return scores.sortedByDescending { it.second }.take(k)
    }

    /**
     * Generate tokens from method features
     * Uses tiered prefixes to control TF-IDF weighting
     */
    private fun generateTokens(method: MethodInfo): List<String> {
        val tokens = mutableListOf<String>()

        // TIER 1: Unique high-value features (rare = high IDF)
        method.constants.filterIsInstance<String>().forEach { str ->
            // Use hash to handle special characters
            tokens.add("USTR:${str.hashCode()}")
        }

        method.constants.filterIsInstance<Number>().forEach { num ->
            val value = num.toLong()
            // Skip very common small numbers
            if (value !in -1L..10L) {
                tokens.add("UNUM:$value")
            }
        }

        // TIER 2: Structural patterns (medium weight)
        tokens.add("DESC:${method.descriptor}")

        // Normalize complexity into buckets to allow fuzzy matching
        val complexityBucket = when {
            method.instructions.size < 10 -> "TINY"
            method.instructions.size < 50 -> "SMALL"
            method.instructions.size < 200 -> "MEDIUM"
            method.instructions.size < 500 -> "LARGE"
            else -> "HUGE"
        }
        tokens.add("SIZE:$complexityBucket")

        // Method calls with obfuscated names normalized
        method.instructions
            .filter { it.startsWith("METHOD:") }
            .map { it.substring(7) } // Remove "METHOD:" prefix
            .forEach { call ->
                val normalized = normalizeObfuscatedCall(call)
                tokens.add("MCALL:$normalized")
            }

        // Field accesses with obfuscated names normalized
        method.instructions
            .filter { it.startsWith("FIELD:") }
            .map { it.substring(6) } // Remove "FIELD:" prefix
            .forEach { field ->
                val normalized = normalizeObfuscatedField(field)
                tokens.add("FACCS:$normalized")
            }

        // Type constants (NEW instructions)
        method.instructions
            .filter { it.startsWith("TYPE:") }
            .map { it.substring(5) }
            .forEach { type ->
                if (!isObfuscatedType(type)) {
                    tokens.add("NEWTYPE:$type")
                }
            }

        // TIER 3: Opcode N-grams (captures sequence patterns)
        val opcodes = method.instructions
            .filter { it.startsWith("OP:") }
            .map { it.substring(3) }

        // 3-grams for sequence matching
        opcodes.windowed(3).forEach { window ->
            tokens.add("NG3:${window.joinToString("_")}")
        }

        // 4-grams for more specific patterns
        opcodes.windowed(4).forEach { window ->
            tokens.add("NG4:${window.joinToString("_")}")
        }

        // TIER 4: Opcode histogram (low weight, very common)
        opcodes.groupBy { it }.forEach { (opcode, occurrences) ->
            // Add each opcode once per occurrence
            repeat(occurrences.size) {
                tokens.add("OPC:$opcode")
            }
        }

        return tokens
    }

    /**
     * Normalize obfuscated method calls to focus on non-obfuscated parts
     * Example: com/obf/a.b(I)V -> com/obf/OBF.OBF(I)V
     * Example: java/lang/String.substring(II)Ljava/lang/String; -> KEEP AS-IS
     */
    private fun normalizeObfuscatedCall(call: String): String {
        val parts = call.split(".")
        if (parts.size != 2) return call

        val owner = parts[0]
        val nameAndDesc = parts[1]

        val normalizedOwner = if (isObfuscatedClassName(owner)) "OBF" else owner

        val methodName = nameAndDesc.substringBefore("(")
        val descriptor = nameAndDesc.substringAfter("(", "")

        val normalizedMethod = if (isObfuscatedMethodName(methodName)) "OBF" else methodName

        return "$normalizedOwner.$normalizedMethod($descriptor"
    }

    private fun normalizeObfuscatedField(field: String): String {
        // field format: owner.name
        val parts = field.split(".")
        if (parts.size != 2) return field

        val owner = if (isObfuscatedClassName(parts[0])) "OBF" else parts[0]
        val name = if (isObfuscatedFieldName(parts[1])) "OBF" else parts[1]

        return "$owner.$name"
    }

    private fun isObfuscatedClassName(name: String): Boolean {
        val simpleName = name.substringAfterLast('/')
        return simpleName.length <= 2 ||
               simpleName.startsWith("class") ||
               simpleName.startsWith("a") && simpleName.length == 1
    }

    private fun isObfuscatedMethodName(name: String): Boolean {
        return name.length <= 2 || name.startsWith("method")
    }

    private fun isObfuscatedFieldName(name: String): Boolean {
        return name.length <= 2 || name.startsWith("field")
    }

    private fun isObfuscatedType(type: String): Boolean {
        return type.contains("/") && isObfuscatedClassName(type)
    }

    /**
     * Compute TF-IDF vector for a list of tokens
     */
    private fun computeTfIdf(tokens: List<String>): Map<String, Double> {
        val termFrequency = tokens.groupBy { it }
            .mapValues { it.value.size.toDouble() / tokens.size }

        return termFrequency.mapValues { (term, tf) ->
            val df = documentFrequency[term] ?: 1
            val idf = log(totalDocuments.toDouble() / df)
            tf * idf
        }
    }

    /**
     * Compute cosine similarity between two TF-IDF vectors
     */
    private fun cosineSimilarity(vec1: Map<String, Double>, vec2: Map<String, Double>): Double {
        val allTerms = vec1.keys + vec2.keys

        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        allTerms.forEach { term ->
            val v1 = vec1[term] ?: 0.0
            val v2 = vec2[term] ?: 0.0

            dotProduct += v1 * v2
            norm1 += v1 * v1
            norm2 += v2 * v2
        }

        return if (norm1 > 0 && norm2 > 0) {
            dotProduct / (sqrt(norm1) * sqrt(norm2))
        } else {
            0.0
        }
    }
}
