package com.mapper.merge.operation

import com.mapper.merge.MergeEngine
import com.mapper.merge.OperationResult
import com.mapper.model.ClassInfo

/**
 * Vote for classes based on structural similarity
 * 
 * Analyzes:
 * - Superclass/interface matches
 * - Member counts
 * - Method signature patterns
 * - Field type patterns
 * 
 * Converts your computeClassSimilarity into votes!
 */
class MatchByStructure : MergeOperation {
    
    override fun operate(engine: MergeEngine): OperationResult {
        var votes = 0
        
        // Only process unmatched obfuscated classes
        val obfClassesA = engine.envA.classes.values
            .filter { it.obfuscated && !engine.isClassMatched(it.name) }
        
        val obfClassesB = engine.envB.classes.values
            .filter { it.obfuscated && !engine.isClassMatched(it.name) }
        
        for (classA in obfClassesA) {
            for (classB in obfClassesB) {
                val structuralVotes = analyzeStructuralSimilarity(classA, classB, engine)
                
                if (structuralVotes > 0) {
                    engine.voteClass(classA, classB, structuralVotes)
                    votes += structuralVotes
                }
            }
        }
        
        if (votes > 0) {
            println("    Cast $votes votes from structural analysis")
        }
        
        return OperationResult.Continue
    }
    
    /**
     * Analyze structural similarity and return vote weight
     */
    private fun analyzeStructuralSimilarity(
        classA: ClassInfo,
        classB: ClassInfo,
        engine: MergeEngine
    ): Int {
        var voteWeight = 0
        
        // Superclass match (strong signal!)
        if (classA.superName != null && classB.superName != null) {
            val superMatchA = engine.getClassMatch(classA.superName)
            if (superMatchA != null && superMatchA.newClass?.name == classB.superName) {
                voteWeight += MergeEngine.VOTE_STRONG
            }
        }
        
        // Interface matches
        var matchedInterfaces = 0
        for (intfA in classA.interfaces) {
            val intfMatch = engine.getClassMatch(intfA)
            if (intfMatch != null && classB.interfaces.contains(intfMatch.newClass?.name)) {
                matchedInterfaces++
            }
        }
        if (matchedInterfaces > 0) {
            voteWeight += matchedInterfaces * MergeEngine.VOTE_MEDIUM
        }
        
        // Member count similarity
        if (isSimilarMemberCount(classA, classB)) {
            voteWeight += MergeEngine.VOTE_WEAK
        }
        
        // Method signature pattern similarity
        val methodSigSimilarity = computeMethodSignatureSimilarity(classA, classB)
        if (methodSigSimilarity > 0.5) {
            voteWeight += MergeEngine.VOTE_MEDIUM
        } else if (methodSigSimilarity > 0.3) {
            voteWeight += MergeEngine.VOTE_WEAK
        }
        
        // Field type pattern similarity
        val fieldTypeSimilarity = computeFieldTypeSimilarity(classA, classB)
        if (fieldTypeSimilarity > 0.5) {
            voteWeight += MergeEngine.VOTE_WEAK
        }
        
        return voteWeight
    }
    
    private fun isSimilarMemberCount(classA: ClassInfo, classB: ClassInfo): Boolean {
        val methodRatio = minOf(classA.methods.size, classB.methods.size).toDouble() /
                         maxOf(classA.methods.size, classB.methods.size, 1)
        val fieldRatio = minOf(classA.fields.size, classB.fields.size).toDouble() /
                        maxOf(classA.fields.size, classB.fields.size, 1)
        
        return methodRatio > 0.7 && fieldRatio > 0.7
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
}

