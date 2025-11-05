package com.mapper.merge.operation

import com.mapper.merge.MergeEngine
import com.mapper.merge.OperationResult
import com.mapper.merge.data.MatchEntry
import com.mapper.merge.data.MethodMatchEntry
import com.mapper.merge.data.FieldMatchEntry

/**
 * Collects votes and confirms high-confidence matches
 * 
 * Confirms matches that have:
 * 1. High vote count (at least 3 votes)
 * 2. Significant confidence gap (1st place - 2nd place >= 2 votes)
 * 
 * Keeps your excellent confidence gap threshold logic!
 */
class VoteCollector(
    private val minVotes: Int = 3,
    private val minGap: Int = 2,
    private val batchPercent: Double = 0.10 // Confirm top 10% per cycle
) : MergeOperation {
    
    override fun operate(engine: MergeEngine): OperationResult {
        var confirmed = 0
        
        // Confirm classes
        confirmed += confirmClasses(engine)
        
        // Confirm methods
        confirmed += confirmMethods(engine)
        
        // Confirm fields
        confirmed += confirmFields(engine)
        
        if (confirmed > 0) {
            println("    Confirmed $confirmed high-confidence matches")
        }
        
        return OperationResult.Continue
    }
    
    private fun confirmClasses(engine: MergeEngine): Int {
        val pending = engine.getPendingClassMatches().toList()
        if (pending.isEmpty()) return 0
        
        // Sort by vote difference (confidence) descending
        val sortedByConfidence = pending.sortedByDescending { it.getVoteDifference() }
        
        // Calculate how many to confirm this cycle
        val batchSize = maxOf(5, (pending.size * batchPercent).toInt())
        val toConfirm = sortedByConfidence.take(batchSize)
        
        var confirmed = 0
        for (entry in toConfirm) {
            if (entry.getHighest() == null) continue
            if (entry.getHighestVotes() < minVotes) continue
            if (entry.getVoteDifference() < minGap) continue
            
            // Confirm it!
            entry.setNewClass(entry.getHighest()!!)
            engine.confirmClassMatch(entry)
            engine.incrementChangeCount()
            confirmed++
            
            // Propagate owner match to members
            propagateOwnerMatch(engine, entry)
        }
        
        return confirmed
    }
    
    private fun confirmMethods(engine: MergeEngine): Int {
        val pending = engine.getPendingMethodMatches().toList()
        if (pending.isEmpty()) return 0
        
        val sortedByConfidence = pending.sortedByDescending { it.getVoteDifference() }
        
        val batchSize = maxOf(10, (pending.size * batchPercent).toInt())
        val toConfirm = sortedByConfidence.take(batchSize)
        
        var confirmed = 0
        for (entry in toConfirm) {
            if (entry.getHighest() == null) continue
            if (entry.getHighestVotes() < minVotes) continue
            if (entry.getVoteDifference() < minGap) continue
            
            entry.setNewMethod(entry.getHighest()!!)
            engine.confirmMethodMatch(entry)
            engine.incrementChangeCount()
            confirmed++
        }
        
        return confirmed
    }
    
    private fun confirmFields(engine: MergeEngine): Int {
        val pending = engine.getPendingFieldMatches().toList()
        if (pending.isEmpty()) return 0
        
        val sortedByConfidence = pending.sortedByDescending { it.getVoteDifference() }
        
        val batchSize = maxOf(5, (pending.size * batchPercent).toInt())
        val toConfirm = sortedByConfidence.take(batchSize)
        
        var confirmed = 0
        for (entry in toConfirm) {
            if (entry.getHighest() == null) continue
            if (entry.getHighestVotes() < minVotes) continue
            if (entry.getVoteDifference() < minGap) continue
            
            entry.setNewField(entry.getHighest()!!)
            engine.confirmFieldMatch(entry)
            engine.incrementChangeCount()
            confirmed++
        }
        
        return confirmed
    }
    
    /**
     * When a class match is confirmed, update all pending method/field entries
     * to restrict their search space to only members of the matched class
     */
    private fun propagateOwnerMatch(engine: MergeEngine, matchEntry: MatchEntry) {
        val oldClass = matchEntry.oldClass
        val newClass = matchEntry.newClass!!
        
        // Update method match entries
        for (method in oldClass.methods) {
            val methodEntry = engine.getPendingMethodMatch(method.fullSignature)
            methodEntry.setOwnerMatch(newClass)
        }
        
        // Update field match entries
        for (field in oldClass.fields) {
            val fieldEntry = engine.getPendingFieldMatch(field.fullSignature)
            fieldEntry.setOwnerMatch(newClass)
        }
    }
}

