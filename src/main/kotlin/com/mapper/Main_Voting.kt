package com.mapper

import com.mapper.analyzer.JarAnalyzer
import com.mapper.merge.MergeEngine
import com.mapper.merge.operation.*
import com.mapper.matcher.MatchResult
import java.io.File
import kotlin.system.exitProcess

/**
 * Main entry point using the voting-based matching architecture
 * 
 * This replaces the similarity-scoring approach with a democratic
 * voting system inspired by ObfuscationMapper
 */
fun main(args: Array<String>) {
    println("=== Kotlin Bytecode Mapper (Voting System) ===")
    println("Hybrid architecture combining efficiency with confidence\n")

    if (args.size < 2) {
        printUsage()
        exitProcess(1)
    }

    val jarPathA = args[0]
    val jarPathB = args[1]
    val outputPath = args.getOrNull(2) ?: "mappings.txt"

    // Validate inputs
    if (!File(jarPathA).exists()) {
        println("Error: JAR file not found: $jarPathA")
        exitProcess(1)
    }

    if (!File(jarPathB).exists()) {
        println("Error: JAR file not found: $jarPathB")
        exitProcess(1)
    }

    try {
        // Analyze both JARs
        println("Analyzing JAR A: $jarPathA")
        val analyzer = JarAnalyzer()
        val envA = analyzer.analyzeJar(jarPathA)
        println("  Found ${envA.classes.size} classes, ${envA.methods.size} methods, ${envA.fields.size} fields\n")

        println("Analyzing JAR B: $jarPathB")
        val envB = analyzer.analyzeJar(jarPathB)
        println("  Found ${envB.classes.size} classes, ${envB.methods.size} methods, ${envB.fields.size} fields\n")

        // Build the merge engine and operations pipeline
        val engine = MergeEngine(envA, envB)
        
        println("Configuring voting pipeline...\n")
        
        // Phase 1: Anchor non-obfuscated (your efficient Phase 1!)
        engine.addOperation(AnchorNonObfuscated())
        
        // Phase 2-N: Iterative voting operations
        val iterationStart = 1
        engine.addOperation(MatchUniqueConstants())       // High-value votes
        engine.addOperation(MatchUniqueDescriptors())     // Your efficient descriptor matching
        engine.addOperation(MatchByStructure())           // Your structural similarity
        engine.addOperation(VoteCollector())              // Confirm high-confidence matches
        
        // Loop: If we made progress, run another iteration
        engine.addOperation(jumpTo(iterationStart) { 
            it.getChangesLastCycle() > 0 
        })

        // Execute the pipeline
        println("Executing merge pipeline...\n")
        val result = engine.merge()

        // Print and export results
        result.printSummary()

        println("\nExporting mappings to: $outputPath")
        result.exportToFile(outputPath)

        println("\n✓ Mapping complete!")

    } catch (e: Exception) {
        println("Error during mapping: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

private fun printUsage() {
    println("""
        Usage: bytecode-mapper <jar-a> <jar-b> [output-file]

        Arguments:
          jar-a       - Path to the first JAR file (source)
          jar-b       - Path to the second JAR file (target)
          output-file - Optional output file for mappings (default: mappings.txt)

        Example:
          bytecode-mapper old-version.jar new-version.jar mappings.txt

        Description:
          This tool maps obfuscated identifiers between two versions of a JAR file
          using a voting-based confidence system.
          
          The mapper:
          1. Anchors non-obfuscated identifiers (instant matches)
          2. Collects votes from multiple heuristics (constants, descriptors, structure)
          3. Confirms matches with high vote counts and confidence gaps
          4. Iterates until convergence
    """.trimIndent())
}

