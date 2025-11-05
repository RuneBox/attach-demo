package com.mapper.matcher

import com.mapper.model.*
import java.io.File
import kotlin.math.max

/**
 * Core matching engine that computes similarity scores between identifiers
 * from two different JAR environments using iterative propagation
 */
class IdentifierMatcher(
    private val envA: JarEnvironment,
    private val envB: JarEnvironment
) {

    private val classMatches = mutableMapOf<String, MutableList<MatchCandidate<ClassInfo>>>()
    private val methodMatches = mutableMapOf<String, MutableList<MatchCandidate<MethodInfo>>>()
    private val fieldMatches = mutableMapOf<String, MutableList<MatchCandidate<FieldInfo>>>()

    // Final confirmed matches
    private val confirmedClassMatches = mutableMapOf<String, String>()
    private val confirmedMethodMatches = mutableMapOf<String, String>()
    private val confirmedFieldMatches = mutableMapOf<String, String>()

    companion object {
        const val ANCHOR_MATCH_THRESHOLD = 0.85
        const val CONFIDENCE_GAP_THRESHOLD = 0.25
        const val WEAK_SCORE = 0.1
        const val MEDIUM_SCORE = 0.5
        const val STRONG_SCORE = 1.0
    }

    /**
     * Main matching algorithm - iterative similarity-based approach
     */
    fun computeMatches(): MatchResult {
        println("Starting identifier matching...")

        // Phase 1: Anchor non-obfuscated identifiers
        matchNonObfuscatedClasses()
        println("Phase 1 complete: Matched ${confirmedClassMatches.size} non-obfuscated classes")

        // Phase 2: Iterative propagation
        var iteration = 0
        var newMatches: Int

        do {
            iteration++
            newMatches = 0

            // Match classes based on structural similarity
            newMatches += matchClassesByStructure()

            // Match methods based on owner and signature similarity
            newMatches += matchMethodsBySignature()

            // Match fields based on owner and type similarity
            newMatches += matchFieldsByType()

            println("Iteration $iteration: Confirmed $newMatches new matches")
        } while (newMatches > 0 && iteration < 20)

        println("Matching complete after $iteration iterations")
        println("Total matches - Classes: ${confirmedClassMatches.size}, " +
                "Methods: ${confirmedMethodMatches.size}, Fields: ${confirmedFieldMatches.size}")

        return MatchResult(
            classMatches = confirmedClassMatches,
            methodMatches = confirmedMethodMatches,
            fieldMatches = confirmedFieldMatches
        )
    }

    /**
     * Phase 1: Match all non-obfuscated classes by name
     */
    private fun matchNonObfuscatedClasses() {
        envA.classes.values
            .filter { !it.obfuscated }
            .forEach { classA ->
                val classB = envB.classes[classA.name]
                if (classB != null && !classB.obfuscated) {
                    confirmedClassMatches[classA.name] = classB.name
                    propagateFromClassMatch(classA, classB, WEAK_SCORE)
                }
            }
    }

    /**
     * Match classes based on structural similarity
     */
    private fun matchClassesByStructure(): Int {
        val candidates = mutableListOf<MatchCandidate<ClassInfo>>()

        // Find all obfuscated classes in default package
        val obfClassesA = envA.classes.values.filter { it.obfuscated && it.isInDefaultPackage() }
        val obfClassesB = envB.classes.values.filter { it.obfuscated && it.isInDefaultPackage() }

        // Compute similarity scores
        for (classA in obfClassesA) {
            if (confirmedClassMatches.containsKey(classA.name)) continue

            for (classB in obfClassesB) {
                if (confirmedClassMatches.containsValue(classB.name)) continue

                val score = computeClassSimilarity(classA, classB)
                if (score > 0.0) {
                    candidates.add(MatchCandidate(classA, classB, score))
                }
            }
        }

        return confirmBestMatches(
            candidates,
            { it.source.name },
            { it.target.name },
            confirmedClassMatches
        ) { classA, classB, score ->
            propagateFromClassMatch(classA, classB, score * WEAK_SCORE)
        }
    }

    /**
     * Compute structural similarity between two classes
     */
    private fun computeClassSimilarity(classA: ClassInfo, classB: ClassInfo): Double {
        var score = 0.0
        var maxScore = 0.0

        // Super class match
        maxScore += 1.0
        if (classA.superName != null && classB.superName != null) {
            if (isClassMatched(classA.superName) &&
                confirmedClassMatches[classA.superName] == classB.superName) {
                score += 1.0
            } else if (classA.superName == classB.superName && !ClassInfo.isObfuscated(classA.superName)) {
                score += 0.5
            }
        }

        // Interface matches
        maxScore += classA.interfaces.size.toDouble()
        for (intfA in classA.interfaces) {
            if (isClassMatched(intfA) && classB.interfaces.contains(confirmedClassMatches[intfA])) {
                score += 1.0
            } else if (classB.interfaces.contains(intfA) && !ClassInfo.isObfuscated(intfA)) {
                score += 0.5
            }
        }

        // Method count similarity
        maxScore += 1.0
        val methodCountRatio = minOf(classA.methods.size, classB.methods.size).toDouble() /
                               maxOf(classA.methods.size, classB.methods.size, 1)
        score += methodCountRatio

        // Field count similarity
        maxScore += 1.0
        val fieldCountRatio = minOf(classA.fields.size, classB.fields.size).toDouble() /
                              maxOf(classA.fields.size, classB.fields.size, 1)
        score += fieldCountRatio

        // Method signature matches (descriptor patterns)
        maxScore += 2.0
        score += computeMethodSignatureSimilarity(classA, classB) * 2.0

        // Field type matches
        maxScore += 1.0
        score += computeFieldTypeSimilarity(classA, classB)

        return if (maxScore > 0) score / maxScore else 0.0
    }

    private fun computeMethodSignatureSimilarity(classA: ClassInfo, classB: ClassInfo): Double {
        if (classA.methods.isEmpty() || classB.methods.isEmpty()) return 0.0

        val descriptorsA = classA.methods.map { it.descriptor }.toSet()
        val descriptorsB = classB.methods.map { it.descriptor }.toSet()

        val intersection = descriptorsA.intersect(descriptorsB).size
        val union = descriptorsA.union(descriptorsB).size

        return if (union > 0) intersection.toDouble() / union else 0.0
    }

    private fun computeFieldTypeSimilarity(classA: ClassInfo, classB: ClassInfo): Double {
        if (classA.fields.isEmpty() || classB.fields.isEmpty()) return 0.0

        val typesA = classA.fields.map { it.descriptor }.toSet()
        val typesB = classB.fields.map { it.descriptor }.toSet()

        val intersection = typesA.intersect(typesB).size
        val union = typesA.union(typesB).size

        return if (union > 0) intersection.toDouble() / union else 0.0
    }

    /**
     * Match methods based on signature similarity
     */
    private fun matchMethodsBySignature(): Int {
        val candidates = mutableListOf<MatchCandidate<MethodInfo>>()

        for (methodA in envA.methods.values) {
            if (confirmedMethodMatches.containsKey(methodA.fullSignature)) continue
            if (!methodA.obfuscated) continue // Only match obfuscated methods here

            for (methodB in envB.methods.values) {
                if (confirmedMethodMatches.containsValue(methodB.fullSignature)) continue
                if (!methodB.obfuscated) continue

                val score = computeMethodSimilarity(methodA, methodB)
                if (score > 0.0) {
                    candidates.add(MatchCandidate(methodA, methodB, score))
                }
            }
        }

        return confirmBestMatches(
            candidates,
            { it.source.fullSignature },
            { it.target.fullSignature },
            confirmedMethodMatches
        ) { methodA, methodB, score ->
            propagateFromMethodMatch(methodA, methodB, score * WEAK_SCORE)
        }
    }

    private fun computeMethodSimilarity(methodA: MethodInfo, methodB: MethodInfo): Double {
        var score = 0.0
        var maxScore = 0.0

        // Owner class must be matched
        if (!isClassMatched(methodA.owner)) return 0.0
        if (confirmedClassMatches[methodA.owner] != methodB.owner) return 0.0

        maxScore += 2.0
        score += 2.0 // Bonus for matched owner

        // Static/instance match
        maxScore += 1.0
        if (methodA.isStatic == methodB.isStatic) {
            score += 1.0
        }

        // Descriptor exact match
        maxScore += 3.0
        if (methodA.descriptor == methodB.descriptor) {
            score += 3.0
        } else {
            // Partial descriptor match (argument count)
            val argsA = methodA.getArgumentTypes()
            val argsB = methodB.getArgumentTypes()

            if (argsA.size == argsB.size) {
                score += 0.5

                // Match based on confirmed types
                var matchedArgs = 0
                for (i in argsA.indices) {
                    if (areTypesMatched(argsA[i], argsB[i])) {
                        matchedArgs++
                    }
                }
                score += (matchedArgs.toDouble() / argsA.size) * 1.5
            }

            // Return type match
            if (areTypesMatched(methodA.getReturnType(), methodB.getReturnType())) {
                score += 1.0
            }
        }

        // Constant similarity
        maxScore += 1.0
        if (methodA.constants.isNotEmpty() && methodB.constants.isNotEmpty()) {
            val intersection = methodA.constants.intersect(methodB.constants).size
            val union = methodA.constants.union(methodB.constants).size
            score += intersection.toDouble() / union
        }

        // Instruction pattern similarity
        maxScore += 2.0
        score += computeInstructionSimilarity(methodA, methodB) * 2.0

        return if (maxScore > 0) score / maxScore else 0.0
    }

    private fun computeInstructionSimilarity(methodA: MethodInfo, methodB: MethodInfo): Double {
        if (methodA.instructions.isEmpty() || methodB.instructions.isEmpty()) return 0.0

        // Compare instruction sequence length
        val lengthRatio = minOf(methodA.instructions.size, methodB.instructions.size).toDouble() /
                          maxOf(methodA.instructions.size, methodB.instructions.size)

        // Compare opcode patterns
        val opcodesA = methodA.instructions.filter { it.startsWith("OP:") }
        val opcodesB = methodB.instructions.filter { it.startsWith("OP:") }

        val opcodeRatio = if (opcodesA.size == opcodesB.size && opcodesA.size > 0) {
            opcodesA.zip(opcodesB).count { it.first == it.second }.toDouble() / opcodesA.size
        } else 0.0

        return (lengthRatio + opcodeRatio) / 2.0
    }

    /**
     * Match fields based on type similarity
     */
    private fun matchFieldsByType(): Int {
        val candidates = mutableListOf<MatchCandidate<FieldInfo>>()

        for (fieldA in envA.fields.values) {
            if (confirmedFieldMatches.containsKey(fieldA.fullSignature)) continue
            if (!fieldA.obfuscated) continue

            for (fieldB in envB.fields.values) {
                if (confirmedFieldMatches.containsValue(fieldB.fullSignature)) continue
                if (!fieldB.obfuscated) continue

                val score = computeFieldSimilarity(fieldA, fieldB)
                if (score > 0.0) {
                    candidates.add(MatchCandidate(fieldA, fieldB, score))
                }
            }
        }

        return confirmBestMatches(
            candidates,
            { it.source.fullSignature },
            { it.target.fullSignature },
            confirmedFieldMatches
        ) { fieldA, fieldB, score ->
            propagateFromFieldMatch(fieldA, fieldB, score * WEAK_SCORE)
        }
    }

    private fun computeFieldSimilarity(fieldA: FieldInfo, fieldB: FieldInfo): Double {
        var score = 0.0
        var maxScore = 0.0

        // Owner class must be matched
        if (!isClassMatched(fieldA.owner)) return 0.0
        if (confirmedClassMatches[fieldA.owner] != fieldB.owner) return 0.0

        maxScore += 2.0
        score += 2.0 // Bonus for matched owner

        // Static/instance match
        maxScore += 1.0
        if (fieldA.isStatic == fieldB.isStatic) {
            score += 1.0
        }

        // Type match
        maxScore += 2.0
        if (areTypesMatched(fieldA.descriptor, fieldB.descriptor)) {
            score += 2.0
        } else if (fieldA.descriptor == fieldB.descriptor) {
            score += 1.0
        }

        // Initial value match
        maxScore += 1.0
        if (fieldA.value == fieldB.value) {
            score += 1.0
        }

        return if (maxScore > 0) score / maxScore else 0.0
    }

    /**
     * Propagate weak matches from a confirmed class match
     */
    private fun propagateFromClassMatch(classA: ClassInfo, classB: ClassInfo, score: Double) {
        // Propagate to superclass
        if (classA.superName != null && classB.superName != null &&
            ClassInfo.isObfuscated(classA.superName) && ClassInfo.isObfuscated(classB.superName)) {
            addClassMatch(classA.superName, classB.superName, score)
        }

        // Propagate to interfaces
        for (i in classA.interfaces.indices) {
            if (i < classB.interfaces.size) {
                val intfA = classA.interfaces[i]
                val intfB = classB.interfaces[i]
                if (ClassInfo.isObfuscated(intfA) && ClassInfo.isObfuscated(intfB)) {
                    addClassMatch(intfA, intfB, score)
                }
            }
        }
    }

    private fun propagateFromMethodMatch(methodA: MethodInfo, methodB: MethodInfo, score: Double) {
        // Propagate to argument types
        val argsA = methodA.getArgumentTypes()
        val argsB = methodB.getArgumentTypes()

        for (i in argsA.indices) {
            if (i < argsB.size) {
                val typeA = extractClassName(argsA[i])
                val typeB = extractClassName(argsB[i])
                if (typeA != null && typeB != null &&
                    ClassInfo.isObfuscated(typeA) && ClassInfo.isObfuscated(typeB)) {
                    addClassMatch(typeA, typeB, score)
                }
            }
        }

        // Propagate to return type
        val retA = extractClassName(methodA.getReturnType())
        val retB = extractClassName(methodB.getReturnType())
        if (retA != null && retB != null &&
            ClassInfo.isObfuscated(retA) && ClassInfo.isObfuscated(retB)) {
            addClassMatch(retA, retB, score)
        }
    }

    private fun propagateFromFieldMatch(fieldA: FieldInfo, fieldB: FieldInfo, score: Double) {
        // Propagate to field type
        val typeA = extractClassName(fieldA.descriptor)
        val typeB = extractClassName(fieldB.descriptor)
        if (typeA != null && typeB != null &&
            ClassInfo.isObfuscated(typeA) && ClassInfo.isObfuscated(typeB)) {
            addClassMatch(typeA, typeB, score)
        }
    }

    private fun addClassMatch(classA: String, classB: String, score: Double) {
        classMatches.getOrPut(classA) { mutableListOf() }
            .find { it.target.name == classB }
            ?.let { it.score += score }
    }

    /**
     * Confirm best matches based on threshold and confidence gap
     */
    private fun <T : Identifier> confirmBestMatches(
        candidates: List<MatchCandidate<T>>,
        getSourceKey: (MatchCandidate<T>) -> String,
        getTargetKey: (MatchCandidate<T>) -> String,
        confirmedMatches: MutableMap<String, String>,
        onConfirm: (T, T, Double) -> Unit
    ): Int {
        // Group by source
        val bySource = candidates.groupBy { getSourceKey(it) }
        var newMatches = 0

        for ((sourceKey, matches) in bySource) {
            if (confirmedMatches.containsKey(sourceKey)) continue

            // Sort by score descending
            val sorted = matches.sortedByDescending { it.score }

            if (sorted.isEmpty()) continue

            val best = sorted[0]
            val secondBest = sorted.getOrNull(1)

            // Check thresholds
            if (best.score >= ANCHOR_MATCH_THRESHOLD) {
                val gap = if (secondBest != null) best.score - secondBest.score else 1.0

                if (gap >= CONFIDENCE_GAP_THRESHOLD) {
                    // Confirm the match
                    confirmedMatches[sourceKey] = getTargetKey(best)
                    onConfirm(best.source, best.target, best.score)
                    newMatches++
                }
            }
        }

        return newMatches
    }

    /**
     * Helper functions
     */
    private fun isClassMatched(className: String): Boolean {
        return confirmedClassMatches.containsKey(className)
    }

    private fun areTypesMatched(typeA: String, typeB: String): Boolean {
        val classA = extractClassName(typeA)
        val classB = extractClassName(typeB)

        if (classA == null || classB == null) {
            return typeA == typeB // Primitive types
        }

        return isClassMatched(classA) && confirmedClassMatches[classA] == classB
    }

    private fun extractClassName(descriptor: String): String? {
        return when {
            descriptor.startsWith("L") && descriptor.endsWith(";") ->
                descriptor.substring(1, descriptor.length - 1)
            descriptor.startsWith("[") ->
                extractClassName(descriptor.substring(1))
            else -> null
        }
    }
}

/**
 * Result of the matching process
 */
data class MatchResult(
    val classMatches: Map<String, String>,
    val methodMatches: Map<String, String>,
    val fieldMatches: Map<String, String>
) {
    fun printSummary() {
        println("\n=== Matching Summary ===")
        println("Classes matched: ${classMatches.size}")
        println("Methods matched: ${methodMatches.size}")
        println("Fields matched: ${fieldMatches.size}")

        if (classMatches.isNotEmpty()) {
            println("\nClass Matches:")
            classMatches.forEach { (source, target) ->
                println("  $source -> $target")
            }
        }
    }

    fun exportToFile(outputPath: String) {
        File(outputPath).bufferedWriter().use { writer ->
            writer.write("# Bytecode Mapping Results\n\n")

            writer.write("## Class Mappings\n")
            classMatches.forEach { (source, target) ->
                writer.write("$source -> $target\n")
            }

            writer.write("\n## Method Mappings\n")
            methodMatches.forEach { (source, target) ->
                writer.write("$source -> $target\n")
            }

            writer.write("\n## Field Mappings\n")
            fieldMatches.forEach { (source, target) ->
                writer.write("$source -> $target\n")
            }
        }
    }
}
