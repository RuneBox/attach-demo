package com.mapper.merge.operation

import com.mapper.merge.MergeEngine
import com.mapper.merge.OperationResult

/**
 * Phase 1: Anchor all non-obfuscated identifiers
 * 
 * This is the most reliable operation - if two identifiers have the same
 * human-readable name, they're almost certainly the same thing.
 * 
 * Keeps your efficient Phase 1 logic!
 */
class AnchorNonObfuscated : MergeOperation {
    
    override fun operate(engine: MergeEngine): OperationResult {
        var matched = 0
        
        // Match non-obfuscated classes by name
        for (classA in engine.envA.classes.values) {
            if (classA.obfuscated) continue
            if (engine.isClassMatched(classA.name)) continue
            
            val classB = engine.envB.classes[classA.name]
            if (classB != null && !classB.obfuscated) {
                val entry = engine.getPendingClassMatch(classA.name)
                entry.setNewClass(classB)
                engine.confirmClassMatch(entry)
                engine.incrementChangeCount()
                matched++
                
                // Immediately propagate to owned members
                propagateToMembers(engine, classA, classB)
            }
        }
        
        println("    Anchored $matched non-obfuscated classes")
        return OperationResult.Continue
    }
    
    /**
     * When a class is matched, immediately match non-obfuscated members
     */
    private fun propagateToMembers(engine: MergeEngine, classA: com.mapper.model.ClassInfo, classB: com.mapper.model.ClassInfo) {
        // Match non-obfuscated methods
        for (methodA in classA.methods) {
            if (methodA.obfuscated) continue
            
            val methodB = classB.methods.find { 
                it.name == methodA.name && 
                it.descriptor == methodA.descriptor &&
                !it.obfuscated
            }
            
            if (methodB != null) {
                val entry = engine.getPendingMethodMatch(methodA.fullSignature)
                entry.setNewMethod(methodB)
                engine.confirmMethodMatch(entry)
                engine.incrementChangeCount()
            }
        }
        
        // Match non-obfuscated fields
        for (fieldA in classA.fields) {
            if (fieldA.obfuscated) continue
            
            val fieldB = classB.fields.find {
                it.name == fieldA.name &&
                it.descriptor == fieldA.descriptor &&
                !it.obfuscated
            }
            
            if (fieldB != null) {
                val entry = engine.getPendingFieldMatch(fieldA.fullSignature)
                entry.setNewField(fieldB)
                engine.confirmFieldMatch(entry)
                engine.incrementChangeCount()
            }
        }
    }
}

