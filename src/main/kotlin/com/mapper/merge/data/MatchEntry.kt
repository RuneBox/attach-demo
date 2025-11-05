package com.mapper.merge.data

import com.mapper.model.*

/**
 * Tracks votes for matching a class between old and new versions
 */
class MatchEntry(
    val oldClass: ClassInfo
) {
    var newClass: ClassInfo? = null
        private set
    
    private val votes = mutableMapOf<ClassInfo, Int>()
    private var highestVotes = 0
    private var highestClass: ClassInfo? = null
    private var secondHighestVotes = 0
    
    /**
     * Cast a vote for a potential match
     * Returns true if vote was accepted
     */
    fun vote(target: ClassInfo, weight: Int = 1): Boolean {
        // Already confirmed
        if (newClass != null) {
            return newClass == target
        }
        
        // Type validation (can't match enum to class, etc)
        if (!areCompatibleTypes(oldClass, target)) {
            return false
        }
        
        val currentVotes = votes.getOrDefault(target, 0) + weight
        votes[target] = currentVotes
        
        // Update highest/second highest tracking
        when {
            currentVotes > highestVotes -> {
                if (target == highestClass) {
                    // Same leader, just more votes
                    highestVotes = currentVotes
                } else {
                    // New leader
                    secondHighestVotes = highestVotes
                    highestVotes = currentVotes
                    highestClass = target
                }
            }
            currentVotes > secondHighestVotes -> {
                secondHighestVotes = currentVotes
            }
        }
        
        return true
    }
    
    fun setNewClass(target: ClassInfo) {
        if (!areCompatibleTypes(oldClass, target)) {
            throw IllegalStateException("Cannot match incompatible types")
        }
        newClass = target
        votes.clear()
    }
    
    fun removeVote(target: ClassInfo) {
        votes.remove(target) ?: return
        
        // Recalculate highest/second highest
        highestVotes = 0
        secondHighestVotes = 0
        highestClass = null
        
        val sorted = votes.entries.sortedByDescending { it.value }
        if (sorted.isNotEmpty()) {
            highestClass = sorted[0].key
            highestVotes = sorted[0].value
        }
        if (sorted.size > 1) {
            secondHighestVotes = sorted[1].value
        }
    }
    
    fun getVotes(): Map<ClassInfo, Int> = votes
    fun getHighestVotes(): Int = highestVotes
    fun getHighest(): ClassInfo? = highestClass
    fun getVoteDifference(): Int = highestVotes - secondHighestVotes
    
    private fun areCompatibleTypes(a: ClassInfo, b: ClassInfo): Boolean {
        // Basic validation - can add more sophisticated checks
        return true
    }
}

/**
 * Tracks votes for matching a method between old and new versions
 */
class MethodMatchEntry(
    val oldMethod: MethodInfo
) {
    var newMethod: MethodInfo? = null
        private set
    
    private var ownerMatch: ClassInfo? = null // Track when owner class is matched
    
    private val votes = mutableMapOf<MethodInfo, Int>()
    private var highestVotes = 0
    private var highestMethod: MethodInfo? = null
    private var secondHighestVotes = 0
    
    fun vote(target: MethodInfo, weight: Int = 1): Boolean {
        if (newMethod != null) {
            return newMethod == target
        }
        
        // Static/instance must match
        if (oldMethod.isStatic != target.isStatic) {
            return false
        }
        
        // If owner is matched, target must belong to matched owner
        if (ownerMatch != null && target.owner != ownerMatch!!.name) {
            return false
        }
        
        // Can't match constructors/static initializers to regular methods
        if (oldMethod.name.startsWith("<") != target.name.startsWith("<")) {
            return false
        }
        
        val currentVotes = votes.getOrDefault(target, 0) + weight
        votes[target] = currentVotes
        
        when {
            currentVotes > highestVotes -> {
                if (target == highestMethod) {
                    highestVotes = currentVotes
                } else {
                    secondHighestVotes = highestVotes
                    highestVotes = currentVotes
                    highestMethod = target
                }
            }
            currentVotes > secondHighestVotes -> {
                secondHighestVotes = currentVotes
            }
        }
        
        return true
    }
    
    fun setNewMethod(target: MethodInfo) {
        newMethod = target
        votes.clear()
    }
    
    fun removeVote(target: MethodInfo) {
        votes.remove(target) ?: return
        
        highestVotes = 0
        secondHighestVotes = 0
        highestMethod = null
        
        val sorted = votes.entries.sortedByDescending { it.value }
        if (sorted.isNotEmpty()) {
            highestMethod = sorted[0].key
            highestVotes = sorted[0].value
        }
        if (sorted.size > 1) {
            secondHighestVotes = sorted[1].value
        }
    }
    
    fun setOwnerMatch(owner: ClassInfo) {
        ownerMatch = owner
        
        // Remove votes for methods not in the matched owner
        val toRemove = votes.keys.filter { it.owner != owner.name }
        toRemove.forEach { removeVote(it) }
    }
    
    fun getVotes(): Map<MethodInfo, Int> = votes
    fun getHighestVotes(): Int = highestVotes
    fun getHighest(): MethodInfo? = highestMethod
    fun getVoteDifference(): Int = highestVotes - secondHighestVotes
}

/**
 * Tracks votes for matching a field between old and new versions
 */
class FieldMatchEntry(
    val oldField: FieldInfo
) {
    var newField: FieldInfo? = null
        private set
    
    private var ownerMatch: ClassInfo? = null
    
    private val votes = mutableMapOf<FieldInfo, Int>()
    private var highestVotes = 0
    private var highestField: FieldInfo? = null
    private var secondHighestVotes = 0
    
    fun vote(target: FieldInfo, weight: Int = 1): Boolean {
        if (newField != null) {
            return newField == target
        }
        
        // Static/instance must match
        if (oldField.isStatic != target.isStatic) {
            return false
        }
        
        // If owner is matched, target must belong to matched owner
        if (ownerMatch != null && target.owner != ownerMatch!!.name) {
            return false
        }
        
        val currentVotes = votes.getOrDefault(target, 0) + weight
        votes[target] = currentVotes
        
        when {
            currentVotes > highestVotes -> {
                if (target == highestField) {
                    highestVotes = currentVotes
                } else {
                    secondHighestVotes = highestVotes
                    highestVotes = currentVotes
                    highestField = target
                }
            }
            currentVotes > secondHighestVotes -> {
                secondHighestVotes = currentVotes
            }
        }
        
        return true
    }
    
    fun setNewField(target: FieldInfo) {
        newField = target
        votes.clear()
    }
    
    fun removeVote(target: FieldInfo) {
        votes.remove(target) ?: return
        
        highestVotes = 0
        secondHighestVotes = 0
        highestField = null
        
        val sorted = votes.entries.sortedByDescending { it.value }
        if (sorted.isNotEmpty()) {
            highestField = sorted[0].key
            highestVotes = sorted[0].value
        }
        if (sorted.size > 1) {
            secondHighestVotes = sorted[1].value
        }
    }
    
    fun setOwnerMatch(owner: ClassInfo) {
        ownerMatch = owner
        
        val toRemove = votes.keys.filter { it.owner != owner.name }
        toRemove.forEach { removeVote(it) }
    }
    
    fun getVotes(): Map<FieldInfo, Int> = votes
    fun getHighestVotes(): Int = highestVotes
    fun getHighest(): FieldInfo? = highestField
    fun getVoteDifference(): Int = highestVotes - secondHighestVotes
}

/**
 * Method group - tracks override relationships in inheritance hierarchy
 */
class MethodGroup(
    val root: MethodInfo
) {
    private val methods = mutableSetOf(root)
    
    fun addMethod(method: MethodInfo) {
        methods.add(method)
    }
    
    fun getMethods(): Set<MethodInfo> = methods
    
    fun getOverrideInClass(owner: String): MethodInfo? {
        return methods.find { it.owner == owner }
    }
}
