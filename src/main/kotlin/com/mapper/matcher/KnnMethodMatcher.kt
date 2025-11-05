package com.mapper.matcher

import com.mapper.model.MethodInfo
import kotlin.math.sqrt

/**
 * K-Nearest Neighbor matcher using numeric feature vectors
 *
 * Use this in Phase 4 for final disambiguation when you have
 * multiple good candidates and need to pick the best match.
 */
class KnnMethodMatcher {

    /**
     * Feature vector representing a method's numeric characteristics
     */
    data class MethodFeatureVector(
        // Structural metrics (normalized 0-1)
        val instructionCount: Double,
        val maxStack: Double,
        val maxLocals: Double,
        val basicBlockEstimate: Double,

        // Count-based features
        val parameterCount: Double,
        val exceptionCount: Double,
        val branchCount: Double,

        // Boolean features (0 or 1)
        val hasLoops: Double,
        val isVoidReturn: Double,
        val hasTryCatch: Double,

        // Hash-based features for variable-length data
        val stringConstantsHash: Double,
        val numericConstantsHash: Double,
        val methodCallPatternHash: Double,
        val fieldAccessPatternHash: Double,
        val typeUsageHash: Double,

        // Opcode distribution (top-15 most common as percentages)
        val opcodeALOAD: Double,
        val opcodeGETFIELD: Double,
        val opcodePUTFIELD: Double,
        val opcodeINVOKEVIRTUAL: Double,
        val opcodeINVOKESPECIAL: Double,
        val opcodeINVOKESTATIC: Double,
        val opcodeILOAD: Double,
        val opcodeISTORE: Double,
        val opcodeIFEQ: Double,
        val opcodeGOTO: Double,
        val opcodeRETURN: Double,
        val opcodeNEW: Double,
        val opcodeCHECKCAST: Double,
        val opcodeIADD: Double,
        val opcodeAASTORE: Double,

        // Type ratios
        val primitiveParamRatio: Double,
        val objectParamRatio: Double,
        val arrayParamRatio: Double
    ) {
        fun toArray(): DoubleArray = doubleArrayOf(
            instructionCount,
            maxStack,
            maxLocals,
            basicBlockEstimate,
            parameterCount,
            exceptionCount,
            branchCount,
            hasLoops,
            isVoidReturn,
            hasTryCatch,
            stringConstantsHash,
            numericConstantsHash,
            methodCallPatternHash,
            fieldAccessPatternHash,
            typeUsageHash,
            opcodeALOAD,
            opcodeGETFIELD,
            opcodePUTFIELD,
            opcodeINVOKEVIRTUAL,
            opcodeINVOKESPECIAL,
            opcodeINVOKESTATIC,
            opcodeILOAD,
            opcodeISTORE,
            opcodeIFEQ,
            opcodeGOTO,
            opcodeRETURN,
            opcodeNEW,
            opcodeCHECKCAST,
            opcodeIADD,
            opcodeAASTORE,
            primitiveParamRatio,
            objectParamRatio,
            arrayParamRatio
        )
    }

    // Feature importance weights (higher = more important for matching)
    private val featureWeights = doubleArrayOf(
        1.5,  // instructionCount - medium-high (can vary slightly)
        1.0,  // maxStack
        1.0,  // maxLocals
        2.0,  // basicBlockEstimate - high (structural)
        2.0,  // parameterCount - high (usually preserved)
        2.5,  // exceptionCount - very high (distinctive)
        2.0,  // branchCount - high (structural)
        1.5,  // hasLoops - medium-high
        0.5,  // isVoidReturn - low (common)
        2.0,  // hasTryCatch - high (distinctive)
        3.5,  // stringConstantsHash - VERY high (unique)
        3.0,  // numericConstantsHash - very high (unique)
        3.0,  // methodCallPatternHash - very high (structural fingerprint)
        2.5,  // fieldAccessPatternHash - high
        2.0,  // typeUsageHash - high
        1.0,  // opcodeALOAD
        1.2,  // opcodeGETFIELD - slightly higher (field access pattern)
        1.2,  // opcodePUTFIELD
        1.3,  // opcodeINVOKEVIRTUAL - higher (method call pattern)
        1.3,  // opcodeINVOKESPECIAL
        1.3,  // opcodeINVOKESTATIC
        0.8,  // opcodeILOAD - lower (very common)
        0.8,  // opcodeISTORE
        1.2,  // opcodeIFEQ - higher (branching)
        1.2,  // opcodeGOTO - higher (branching)
        0.7,  // opcodeRETURN - low (common)
        1.5,  // opcodeNEW - medium-high (object creation)
        1.3,  // opcodeCHECKCAST - medium-high (type usage)
        0.9,  // opcodeIADD - medium-low
        1.0,  // opcodeAASTORE
        1.5,  // primitiveParamRatio - medium-high
        1.5,  // objectParamRatio
        1.5   // arrayParamRatio
    )

    private var maxInstructionCount = 1.0
    private var maxStack = 1.0
    private var maxLocals = 1.0

    /**
     * Build normalization parameters from a collection of methods
     */
    fun buildNormalization(methods: Collection<MethodInfo>) {
        maxInstructionCount = methods.maxOfOrNull { it.instructions.size.toDouble() } ?: 1.0
        maxStack = 10.0 // Typical max stack size
        maxLocals = 20.0 // Typical max locals
    }

    /**
     * Find k nearest neighbors using weighted cosine similarity
     */
    fun findKNearest(
        queryMethod: MethodInfo,
        candidateMethods: Collection<MethodInfo>,
        k: Int = 5
    ): List<Pair<MethodInfo, Double>> {
        val queryVec = extractFeatures(queryMethod)

        val distances = candidateMethods.map { candidate ->
            val candidateVec = extractFeatures(candidate)
            val similarity = weightedCosineSimilarity(queryVec, candidateVec)
            candidate to similarity
        }

        return distances.sortedByDescending { it.second }.take(k)
    }

    /**
     * Extract feature vector from a method
     */
    fun extractFeatures(method: MethodInfo): MethodFeatureVector {
        val opcodes = method.instructions.filter { it.startsWith("OP:") }
            .map { it.substring(3) }

        val opcodeHistogram = opcodes.groupBy { it }
            .mapValues { it.value.size.toDouble() / opcodes.size }

        // Count branches (IF*, GOTO, SWITCH, etc.)
        val branches = opcodes.count {
            it.startsWith("IF") || it == "GOTO" || it.contains("SWITCH")
        }

        // Estimate basic blocks (rough approximation)
        val basicBlocks = branches + 1

        // Detect loops (GOTO with negative offset - simplified check)
        val hasLoops = opcodes.windowed(5).any { window ->
            window.count { it == "GOTO" } > 0 &&
            window.count { it.startsWith("IF") } > 0
        }

        // Check for try-catch
        val hasTryCatch = method.exceptions.isNotEmpty()

        // Descriptor analysis
        val descriptor = method.descriptor
        val isVoid = descriptor.endsWith(")V")
        val paramTypes = extractParamTypes(descriptor)

        val primitiveCount = paramTypes.count { isPrimitive(it) }
        val objectCount = paramTypes.count { it.startsWith("L") }
        val arrayCount = paramTypes.count { it.startsWith("[") }
        val totalParams = paramTypes.size.coerceAtLeast(1)

        return MethodFeatureVector(
            instructionCount = opcodes.size.toDouble() / maxInstructionCount,
            maxStack = 3.0 / maxStack, // Would need actual max_stack from ASM
            maxLocals = method.getArgumentTypes().size.toDouble() / maxLocals,
            basicBlockEstimate = basicBlocks.toDouble() / 20.0, // Normalize to ~20 max

            parameterCount = paramTypes.size.toDouble() / 10.0, // Normalize to ~10 params max
            exceptionCount = method.exceptions.size.toDouble() / 5.0, // ~5 max
            branchCount = branches.toDouble() / 20.0, // ~20 max

            hasLoops = if (hasLoops) 1.0 else 0.0,
            isVoidReturn = if (isVoid) 1.0 else 0.0,
            hasTryCatch = if (hasTryCatch) 1.0 else 0.0,

            // Hash-based features (consistent hashing)
            stringConstantsHash = hashStringConstants(method),
            numericConstantsHash = hashNumericConstants(method),
            methodCallPatternHash = hashMethodCalls(method),
            fieldAccessPatternHash = hashFieldAccesses(method),
            typeUsageHash = hashTypeUsage(method),

            // Opcode distribution
            opcodeALOAD = opcodeHistogram["ALOAD"] ?: 0.0,
            opcodeGETFIELD = opcodeHistogram["GETFIELD"] ?: 0.0,
            opcodePUTFIELD = opcodeHistogram["PUTFIELD"] ?: 0.0,
            opcodeINVOKEVIRTUAL = opcodeHistogram["INVOKEVIRTUAL"] ?: 0.0,
            opcodeINVOKESPECIAL = opcodeHistogram["INVOKESPECIAL"] ?: 0.0,
            opcodeINVOKESTATIC = opcodeHistogram["INVOKESTATIC"] ?: 0.0,
            opcodeILOAD = opcodeHistogram["ILOAD"] ?: 0.0,
            opcodeISTORE = opcodeHistogram["ISTORE"] ?: 0.0,
            opcodeIFEQ = opcodeHistogram["IFEQ"] ?: 0.0,
            opcodeGOTO = opcodeHistogram["GOTO"] ?: 0.0,
            opcodeRETURN = (opcodeHistogram["RETURN"] ?: 0.0) +
                           (opcodeHistogram["ARETURN"] ?: 0.0) +
                           (opcodeHistogram["IRETURN"] ?: 0.0),
            opcodeNEW = opcodeHistogram["NEW"] ?: 0.0,
            opcodeCHECKCAST = opcodeHistogram["CHECKCAST"] ?: 0.0,
            opcodeIADD = opcodeHistogram["IADD"] ?: 0.0,
            opcodeAASTORE = opcodeHistogram["AASTORE"] ?: 0.0,

            primitiveParamRatio = primitiveCount.toDouble() / totalParams,
            objectParamRatio = objectCount.toDouble() / totalParams,
            arrayParamRatio = arrayCount.toDouble() / totalParams
        )
    }

    /**
     * Compute weighted cosine similarity between two feature vectors
     */
    private fun weightedCosineSimilarity(vec1: MethodFeatureVector, vec2: MethodFeatureVector): Double {
        val a = vec1.toArray()
        val b = vec2.toArray()

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in a.indices) {
            val weight = featureWeights[i]
            val weightedA = a[i] * weight
            val weightedB = b[i] * weight

            dotProduct += weightedA * weightedB
            normA += weightedA * weightedA
            normB += weightedB * weightedB
        }

        return if (normA > 0 && normB > 0) {
            dotProduct / (sqrt(normA) * sqrt(normB))
        } else {
            0.0
        }
    }

    // Hash functions for variable-length features
    // These create normalized hash values (0-1) that are consistent for identical content

    private fun hashStringConstants(method: MethodInfo): Double {
        val strings = method.constants.filterIsInstance<String>()
            .sorted() // Sort for consistency
        return (strings.joinToString("|").hashCode() and 0x7FFFFFFF).toDouble() / Int.MAX_VALUE
    }

    private fun hashNumericConstants(method: MethodInfo): Double {
        val numbers = method.constants.filterIsInstance<Number>()
            .map { it.toLong() }
            .sorted()
        return (numbers.joinToString("|").hashCode() and 0x7FFFFFFF).toDouble() / Int.MAX_VALUE
    }

    private fun hashMethodCalls(method: MethodInfo): Double {
        val calls = method.instructions
            .filter { it.startsWith("METHOD:") }
            .map { it.substring(7) }
            .map { normalizeDescriptor(it) } // Normalize obfuscated parts
            .sorted()
        return (calls.joinToString("|").hashCode() and 0x7FFFFFFF).toDouble() / Int.MAX_VALUE
    }

    private fun hashFieldAccesses(method: MethodInfo): Double {
        val fields = method.instructions
            .filter { it.startsWith("FIELD:") }
            .map { it.substring(6) }
            .map { normalizeDescriptor(it) }
            .sorted()
        return (fields.joinToString("|").hashCode() and 0x7FFFFFFF).toDouble() / Int.MAX_VALUE
    }

    private fun hashTypeUsage(method: MethodInfo): Double {
        val types = method.instructions
            .filter { it.startsWith("TYPE:") }
            .map { it.substring(5) }
            .filter { !isObfuscatedType(it) } // Only non-obfuscated types
            .sorted()
        return (types.joinToString("|").hashCode() and 0x7FFFFFFF).toDouble() / Int.MAX_VALUE
    }

    private fun normalizeDescriptor(desc: String): String {
        // Replace obfuscated class names with placeholders
        return desc.replace(Regex("[a-z](?=/|\\.)"), "X")
    }

    private fun isObfuscatedType(type: String): Boolean {
        val simpleName = type.substringAfterLast('/')
        return simpleName.length <= 2
    }

    private fun extractParamTypes(descriptor: String): List<String> {
        val params = mutableListOf<String>()
        var i = 1 // Skip opening '('

        while (i < descriptor.length && descriptor[i] != ')') {
            when (descriptor[i]) {
                'L' -> {
                    val end = descriptor.indexOf(';', i)
                    params.add(descriptor.substring(i, end + 1))
                    i = end + 1
                }
                '[' -> {
                    var j = i
                    while (j < descriptor.length && descriptor[j] == '[') j++
                    if (descriptor[j] == 'L') {
                        val end = descriptor.indexOf(';', j)
                        params.add(descriptor.substring(i, end + 1))
                        i = end + 1
                    } else {
                        params.add(descriptor.substring(i, j + 1))
                        i = j + 1
                    }
                }
                else -> {
                    params.add(descriptor[i].toString())
                    i++
                }
            }
        }

        return params
    }

    private fun isPrimitive(type: String): Boolean {
        return type.length == 1 && type[0] in "ZBCSIJFD"
    }
}
