package com.mapper.merge.operation

import com.mapper.merge.MergeEngine
import com.mapper.merge.OperationResult
import com.mapper.model.MethodInfo

/**
 * Match methods that share unique constant values
 * 
 * If a method uses a distinctive string like "Database connection failed"
 * or a magic number like 86400, those are strong indicators of identity.
 * 
 * Leverages your excellent constant extraction from JarAnalyzer!
 */
class MatchUniqueConstants : MergeOperation {
    
    override fun operate(engine: MergeEngine): OperationResult {
        var votes = 0
        
        // Build maps of unique constants to methods
        val oldConstantToMethod = buildConstantMap(engine.envA.methods.values, engine)
        val newConstantToMethod = buildConstantMap(engine.envB.methods.values, engine)
        
        // Match methods that share unique constants
        for ((constant, methodA) in oldConstantToMethod) {
            if (methodA == null) continue // Was not unique
            
            val methodB = newConstantToMethod[constant]
            if (methodB != null) {
                // Both methods have this unique constant!
                val weight = when (constant) {
                    is String -> {
                        // Longer strings are more distinctive
                        if (constant.length > 20) MergeEngine.VOTE_VERY_STRONG
                        else if (constant.length > 10) MergeEngine.VOTE_STRONG
                        else MergeEngine.VOTE_MEDIUM
                    }
                    is Number -> {
                        // Non-trivial numbers are distinctive
                        val num = constant.toLong()
                        if (num > 1000 || num < -1000) MergeEngine.VOTE_STRONG
                        else MergeEngine.VOTE_MEDIUM
                    }
                    else -> MergeEngine.VOTE_MEDIUM
                }
                
                engine.voteMethod(methodA, methodB, weight)
                votes++
            }
        }
        
        if (votes > 0) {
            println("    Cast $votes votes from unique constants")
        }
        
        return OperationResult.Continue
    }
    
    /**
     * Build map of constant → method for constants that appear in only one method
     */
    private fun buildConstantMap(
        methods: Collection<MethodInfo>,
        engine: MergeEngine
    ): Map<Any, MethodInfo?> {
        val constantToMethod = mutableMapOf<Any, MethodInfo?>()
        
        for (method in methods) {
            if (engine.isMethodMatched(method.fullSignature)) continue
            
            for (constant in method.constants) {
                // Filter out trivial constants
                if (!isSignificantConstant(constant)) continue
                
                when (val existing = constantToMethod[constant]) {
                    null -> constantToMethod[constant] = method
                    else -> {
                        // Duplicate - mark as non-unique
                        if (existing != method) {
                            constantToMethod[constant] = null
                        }
                    }
                }
            }
        }
        
        return constantToMethod
    }
    
    private fun isSignificantConstant(constant: Any): Boolean {
        return when (constant) {
            is String -> {
                // Ignore very short or common strings
                constant.length >= 5 && 
                !constant.matches(Regex("^[a-z]+$")) && // Not just lowercase letters
                constant != "true" && 
                constant != "false"
            }
            is Int -> constant !in listOf(0, 1, -1, 2, -2)
            is Long -> constant !in listOf(0L, 1L, -1L, 2L, -2L)
            is Float -> constant != 0.0f && constant != 1.0f
            is Double -> constant != 0.0 && constant != 1.0
            else -> true
        }
    }
}

