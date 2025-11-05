package com.mapper

import com.mapper.analyzer.JarAnalyzer
import com.mapper.matcher.IdentifierMatcher
import java.io.File
import kotlin.system.exitProcess

/**
 * Main entry point for the bytecode mapper
 *
 * Usage: java -jar bytecode-mapper.jar <jar-a> <jar-b> [output-file]
 */
fun main(args: Array<String>) {
    println("=== Kotlin Bytecode Mapper ===")
    println("Dynamic JAR to JAR identifier mapper using ASM\n")

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

        // Compute matches
        val matcher = IdentifierMatcher(envA, envB)
        val result = matcher.computeMatches()

        // Print and export results
        result.printSummary()

        println("\nExporting mappings to: $outputPath")
        result.exportToFile(outputPath)

        println("\nMapping complete!")

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
          This tool maps obfuscated identifiers between two versions of a JAR file.
          Identifiers are considered obfuscated if they match patterns like:
          - class[0-9]*
          - method[0-9]*
          - field[0-9]*

          The mapper uses structural similarity analysis and iterative propagation
          to find the best matches between identifiers.
    """.trimIndent())
}
