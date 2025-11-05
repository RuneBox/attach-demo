package com.mapper.merge.operation

import com.mapper.merge.MergeEngine
import com.mapper.merge.OperationResult
import com.mapper.model.MethodInfo

/**
 * Match methods with unique descriptors within their owner class
 * 
 * If a class has only one method with signature (Ljava/lang/String;)V,
 * and the matched target class also has only one method with that signature,
 * they're very likely the same method.
 * 
 * Keeps your efficient descriptor-based discrete matching!
 */
class MatchUniqueDescriptors : MergeOperation {
    
    override fun operate(engine: MergeEngine): OperationResult {
        var votes = 0
        
        // Only process confirmed class matches
        for (matchEntry in engine.getAllClassMatches()) {
            val classA = matchEntry.oldClass
            val classB = matchEntry.newClass ?: continue
            
            // Build descriptor frequency maps for unmatched methods
            val descriptorToMethodA = mutableMapOf<String, MethodInfo?>()
            val descriptorToMethodB = mutableMapOf<String, MethodInfo?>()
            
            // Map old methods by descriptor (mark duplicates as null)
            for (methodA in classA.methods) {
                if (engine.isMethodMatched(methodA.fullSignature)) continue
                
                val key = makeDescriptorKey(methodA, engine)
                if (descriptorToMethodA.containsKey(key)) {
                    descriptorToMethodA[key] = null // Duplicate, not unique
                } else {
                    descriptorToMethodA[key] = methodA
                }
            }
            
            // Map new methods by descriptor (mark duplicates as null)
            for (methodB in classB.methods) {
                if (engine.isMethodMatched(methodB.fullSignature)) continue
                
                val key = makeDescriptorKey(methodB, engine)
                if (descriptorToMethodB.containsKey(key)) {
                    descriptorToMethodB[key] = null // Duplicate, not unique
                } else {
                    descriptorToMethodB[key] = methodB
                }
            }
            
            // Match unique descriptors
            for ((descriptor, methodA) in descriptorToMethodA) {
                if (methodA == null) continue // Was a duplicate
                
                val methodB = descriptorToMethodB[descriptor]
                if (methodB != null) {
                    // Unique match!
                    engine.voteMethod(methodA, methodB, MergeEngine.VOTE_STRONG)
                    votes++
                }
            }
        }
        
        if (votes > 0) {
            println("    Cast $votes votes from unique descriptors")
        }
        
        return OperationResult.Continue
    }
    
    /**
     * Create a descriptor key with mapped types
     * Similar to your areTypesMatched logic!
     */
    private fun makeDescriptorKey(method: MethodInfo, engine: MergeEngine): String {
        val desc = method.descriptor
        val mapped = StringBuilder()
        var i = 0
        
        while (i < desc.length) {
            when (desc[i]) {
                '(' -> {
                    mapped.append('(')
                    i++
                }
                ')' -> {
                    mapped.append(')')
                    i++
                }
                '[' -> {
                    mapped.append('[')
                    i++
                }
                'L' -> {
                    val end = desc.indexOf(';', i)
                    val className = desc.substring(i + 1, end)
                    
                    // Use matched class name if available, otherwise wildcard
                    val matchEntry = engine.getClassMatch(className)
                    if (matchEntry != null && matchEntry.newClass != null) {
                        mapped.append("L${matchEntry.newClass!!.name};")
                    } else {
                        mapped.append("L*;") // Unknown type = wildcard
                    }
                    i = end + 1
                }
                else -> {
                    // Primitive type
                    mapped.append(desc[i])
                    i++
                }
            }
        }
        
        // Include static/instance to prevent mismatches
        return if (method.isStatic) "STATIC:$mapped" else "INSTANCE:$mapped"
    }
}

