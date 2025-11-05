package com.mapper.merge.operation

import com.mapper.merge.MergeEngine
import com.mapper.merge.OperationResult

/**
 * Base interface for all merge operations
 * 
 * Each operation casts votes for potential matches and returns
 * a result indicating whether to continue, jump, or stop
 */
interface MergeOperation {
    /**
     * Execute this operation on the merge engine
     * 
     * Operations should:
     * 1. Find potential matches using their heuristic
     * 2. Cast votes via engine.voteClass/Method/Field()
     * 3. Return Continue to proceed to next operation
     */
    fun operate(engine: MergeEngine): OperationResult
}

/**
 * Helper function to create a conditional jump operation
 */
fun jumpTo(targetIndex: Int, condition: (MergeEngine) -> Boolean): MergeOperation {
    return object : MergeOperation {
        override fun operate(engine: MergeEngine): OperationResult {
            return OperationResult.JumpTo(targetIndex, condition)
        }
    }
}

