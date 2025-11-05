package com.mapper.merge

import com.mapper.model.*
import com.mapper.merge.data.*
import com.mapper.merge.operation.MergeOperation
import com.mapper.matcher.MatchResult

/**
 * Core merge engine that orchestrates the voting-based matching process
 * 
 * This engine manages:
 * - Pending and confirmed matches for classes, methods, and fields
 * - Vote accumulation from multiple heuristic operations
 * - Iterative execution of operations until convergence
 */
class MergeEngine(
    val envA: JarEnvironment,
    val envB: JarEnvironment
) {
    // === Confirmed Matches ===
    private val classMatches = mutableMapOf<String, MatchEntry>()
    private val classMatchesInverse = mutableMapOf<String, MatchEntry>()
    
    private val methodMatches = mutableMapOf<String, MethodMatchEntry>()
    private val methodMatchesInverse = mutableMapOf<String, MethodMatchEntry>()
    
    private val fieldMatches = mutableMapOf<String, FieldMatchEntry>()
    private val fieldMatchesInverse = mutableMapOf<String, FieldMatchEntry>()
    
    // === Pending Matches (still voting) ===
    private val pendingClassMatches = mutableMapOf<String, MatchEntry>()
    private val pendingMethodMatches = mutableMapOf<String, MethodMatchEntry>()
    private val pendingFieldMatches = mutableMapOf<String, FieldMatchEntry>()
    
    // === Method Groups (inheritance hierarchies) ===
    private val oldMethodGroups = mutableMapOf<String, MethodGroup>()
    private val newMethodGroups = mutableMapOf<String, MethodGroup>()
    
    // === Operations Pipeline ===
    private val operations = mutableListOf<MergeOperation>()
    
    // === Change tracking for iteration ===
    private var changesLastCycle = 0
    
    companion object {
        const val VOTE_WEAK = 1
        const val VOTE_MEDIUM = 2
        const val VOTE_STRONG = 3
        const val VOTE_VERY_STRONG = 5
    }
    
    // ===================================
    // Class Matching API
    // ===================================
    
    fun getClassMatch(className: String): MatchEntry? = classMatches[className]
    
    fun getClassMatchInverse(className: String): MatchEntry? = classMatchesInverse[className]
    
    fun getPendingClassMatch(oldClassName: String): MatchEntry {
        return pendingClassMatches.getOrPut(oldClassName) {
            val classA = envA.classes[oldClassName] 
                ?: throw IllegalArgumentException("Class not found: $oldClassName")
            MatchEntry(classA)
        }
    }
    
    fun isClassMatched(className: String): Boolean {
        return classMatches.containsKey(className) || classMatchesInverse.containsKey(className)
    }
    
    /**
     * Cast a vote for a class match
     * Returns true if vote was accepted, false if rejected (type mismatch, etc)
     */
    fun voteClass(oldClass: ClassInfo, newClass: ClassInfo, weight: Int = VOTE_WEAK): Boolean {
        val entry = getPendingClassMatch(oldClass.name)
        
        // If already confirmed as inverse match, check if it's the same
        if (classMatchesInverse.containsKey(newClass.name)) {
            return entry.newClass == newClass
        }
        
        return entry.vote(newClass, weight)
    }
    
    fun voteClass(oldClassName: String, newClassName: String, weight: Int = VOTE_WEAK): Boolean {
        val oldClass = envA.classes[oldClassName] ?: return false
        val newClass = envB.classes[newClassName] ?: return false
        return voteClass(oldClass, newClass, weight)
    }
    
    /**
     * Confirm a class match (lock it in)
     */
    fun confirmClassMatch(entry: MatchEntry) {
        if (entry.newClass == null) {
            throw IllegalStateException("Cannot confirm match without target")
        }
        
        pendingClassMatches.remove(entry.oldClass.name)
        classMatches[entry.oldClass.name] = entry
        classMatchesInverse[entry.newClass!!.name] = entry
        
        // Remove votes for the now-matched target from other pending matches
        for (pending in pendingClassMatches.values) {
            pending.removeVote(entry.newClass!!)
        }
    }
    
    fun getAllClassMatches(): Collection<MatchEntry> = classMatches.values
    fun getPendingClassMatches(): Collection<MatchEntry> = pendingClassMatches.values
    
    // ===================================
    // Method Matching API
    // ===================================
    
    fun getMethodMatch(methodSig: String): MethodMatchEntry? = methodMatches[methodSig]
    
    fun getMethodMatchInverse(methodSig: String): MethodMatchEntry? = methodMatchesInverse[methodSig]
    
    fun getPendingMethodMatch(methodSig: String): MethodMatchEntry {
        return pendingMethodMatches.getOrPut(methodSig) {
            val methodA = envA.methods[methodSig] 
                ?: throw IllegalArgumentException("Method not found: $methodSig")
            MethodMatchEntry(methodA)
        }
    }
    
    fun isMethodMatched(methodSig: String): Boolean {
        return methodMatches.containsKey(methodSig) || methodMatchesInverse.containsKey(methodSig)
    }
    
    fun voteMethod(oldMethod: MethodInfo, newMethod: MethodInfo, weight: Int = VOTE_WEAK): Boolean {
        val entry = getPendingMethodMatch(oldMethod.fullSignature)
        
        if (methodMatchesInverse.containsKey(newMethod.fullSignature)) {
            return entry.newMethod == newMethod
        }
        
        return entry.vote(newMethod, weight)
    }
    
    fun confirmMethodMatch(entry: MethodMatchEntry) {
        if (entry.newMethod == null) {
            throw IllegalStateException("Cannot confirm match without target")
        }
        
        pendingMethodMatches.remove(entry.oldMethod.fullSignature)
        methodMatches[entry.oldMethod.fullSignature] = entry
        methodMatchesInverse[entry.newMethod!!.fullSignature] = entry
        
        for (pending in pendingMethodMatches.values) {
            pending.removeVote(entry.newMethod!!)
        }
    }
    
    fun getAllMethodMatches(): Collection<MethodMatchEntry> = methodMatches.values
    fun getPendingMethodMatches(): Collection<MethodMatchEntry> = pendingMethodMatches.values
    
    // ===================================
    // Field Matching API
    // ===================================
    
    fun getFieldMatch(fieldSig: String): FieldMatchEntry? = fieldMatches[fieldSig]
    
    fun getFieldMatchInverse(fieldSig: String): FieldMatchEntry? = fieldMatchesInverse[fieldSig]
    
    fun getPendingFieldMatch(fieldSig: String): FieldMatchEntry {
        return pendingFieldMatches.getOrPut(fieldSig) {
            val fieldA = envA.fields[fieldSig] 
                ?: throw IllegalArgumentException("Field not found: $fieldSig")
            FieldMatchEntry(fieldA)
        }
    }
    
    fun isFieldMatched(fieldSig: String): Boolean {
        return fieldMatches.containsKey(fieldSig) || fieldMatchesInverse.containsKey(fieldSig)
    }
    
    fun voteField(oldField: FieldInfo, newField: FieldInfo, weight: Int = VOTE_WEAK): Boolean {
        val entry = getPendingFieldMatch(oldField.fullSignature)
        
        if (fieldMatchesInverse.containsKey(newField.fullSignature)) {
            return entry.newField == newField
        }
        
        return entry.vote(newField, weight)
    }
    
    fun confirmFieldMatch(entry: FieldMatchEntry) {
        if (entry.newField == null) {
            throw IllegalStateException("Cannot confirm match without target")
        }
        
        pendingFieldMatches.remove(entry.oldField.fullSignature)
        fieldMatches[entry.oldField.fullSignature] = entry
        fieldMatchesInverse[entry.newField!!.fullSignature] = entry
        
        for (pending in pendingFieldMatches.values) {
            pending.removeVote(entry.newField!!)
        }
    }
    
    fun getAllFieldMatches(): Collection<FieldMatchEntry> = fieldMatches.values
    fun getPendingFieldMatches(): Collection<FieldMatchEntry> = pendingFieldMatches.values
    
    // ===================================
    // Method Groups (Inheritance)
    // ===================================
    
    fun getOldMethodGroup(methodSig: String): MethodGroup {
        return oldMethodGroups.getOrPut(methodSig) {
            val method = envA.methods[methodSig]!!
            MethodGroup(method)
        }
    }
    
    fun getNewMethodGroup(methodSig: String): MethodGroup {
        return newMethodGroups.getOrPut(methodSig) {
            val method = envB.methods[methodSig]!!
            MethodGroup(method)
        }
    }
    
    // ===================================
    // Operations Pipeline
    // ===================================
    
    fun addOperation(operation: MergeOperation) {
        operations.add(operation)
    }
    
    fun addOperation(index: Int, operation: MergeOperation) {
        operations.add(index, operation)
    }
    
    fun getChangesLastCycle(): Int = changesLastCycle
    
    fun incrementChangeCount() {
        changesLastCycle++
    }
    
    fun resetChanges() {
        changesLastCycle = 0
    }
    
    /**
     * Execute the merge pipeline
     */
    fun merge(): MatchResult {
        println("Starting voting-based merge...")
        
        var operationIndex = 0
        var iteration = 0
        
        while (operationIndex < operations.size && iteration < 50) {
            val operation = operations[operationIndex]
            
            println("  [Iter $iteration] Running: ${operation.javaClass.simpleName}")
            
            val result = operation.operate(this)
            
            when (result) {
                is OperationResult.Continue -> {
                    operationIndex++
                }
                is OperationResult.JumpTo -> {
                    if (result.condition(this)) {
                        operationIndex = result.targetIndex
                        iteration++
                    } else {
                        operationIndex++
                    }
                }
                is OperationResult.Done -> {
                    break
                }
            }
        }
        
        println("\nMerge complete after $iteration iterations")
        println("Matched - Classes: ${classMatches.size}, Methods: ${methodMatches.size}, Fields: ${fieldMatches.size}")
        
        return MatchResult(
            classMatches = classMatches.mapValues { it.value.newClass!!.name },
            methodMatches = methodMatches.mapValues { it.value.newMethod!!.fullSignature },
            fieldMatches = fieldMatches.mapValues { it.value.newField!!.fullSignature }
        )
    }
}

/**
 * Result returned by each operation
 */
sealed class OperationResult {
    object Continue : OperationResult()
    object Done : OperationResult()
    data class JumpTo(val targetIndex: Int, val condition: (MergeEngine) -> Boolean) : OperationResult()
}

