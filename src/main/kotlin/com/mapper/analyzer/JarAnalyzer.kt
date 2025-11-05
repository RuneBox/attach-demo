package com.mapper.analyzer

import com.mapper.model.*
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import java.io.File
import java.util.jar.JarFile

/**
 * Analyzes JAR files using ASM to extract all identifiers and their metadata
 */
class JarAnalyzer {

    fun analyzeJar(jarPath: String): JarEnvironment {
        val classes = mutableMapOf<String, ClassInfo>()
        val methods = mutableMapOf<String, MethodInfo>()
        val fields = mutableMapOf<String, FieldInfo>()

        JarFile(File(jarPath)).use { jar ->
            jar.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .forEach { entry ->
                    jar.getInputStream(entry).use { input ->
                        val classNode = ClassNode()
                        val reader = ClassReader(input)
                        reader.accept(classNode, ClassReader.SKIP_DEBUG)

                        val classInfo = analyzeClass(classNode)
                        classes[classInfo.name] = classInfo

                        // Add methods and fields to the maps
                        classInfo.methods.forEach { method ->
                            methods[method.fullSignature] = method
                        }
                        classInfo.fields.forEach { field ->
                            fields[field.fullSignature] = field
                        }
                    }
                }
        }

        // Second pass: resolve inner/outer class relationships
        resolveClassRelationships(classes)

        return JarEnvironment(classes, methods, fields)
    }

    private fun analyzeClass(classNode: ClassNode): ClassInfo {
        val classInfo = ClassInfo(
            name = classNode.name,
            superName = classNode.superName,
            interfaces = classNode.interfaces ?: emptyList(),
            access = classNode.access
        )

        // Analyze methods
        classNode.methods?.forEach { methodNode ->
            val methodInfo = analyzeMethod(methodNode, classInfo.name)
            classInfo.methods.add(methodInfo)
        }

        // Analyze fields
        classNode.fields?.forEach { fieldNode ->
            val fieldInfo = analyzeField(fieldNode, classInfo.name)
            classInfo.fields.add(fieldInfo)
        }

        return classInfo
    }

    private fun analyzeMethod(methodNode: MethodNode, owner: String): MethodInfo {
        val constants = mutableSetOf<Any>()
        val instructions = mutableListOf<String>()

        // Extract constants and instruction patterns
        methodNode.instructions?.forEach { insn ->
            when (insn) {
                is LdcInsnNode -> {
                    constants.add(insn.cst)
                }
                is IntInsnNode -> {
                    if (insn.operand != 0) {
                        constants.add(insn.operand)
                    }
                }
                is FieldInsnNode -> {
                    instructions.add("FIELD:${insn.owner}.${insn.name}")
                }
                is MethodInsnNode -> {
                    instructions.add("METHOD:${insn.owner}.${insn.name}${insn.desc}")
                }
                is TypeInsnNode -> {
                    instructions.add("TYPE:${insn.desc}")
                }
            }

            // Add opcode pattern
            instructions.add("OP:${insn.opcode}")
        }

        return MethodInfo(
            name = methodNode.name,
            owner = owner,
            descriptor = methodNode.desc,
            access = methodNode.access,
            signature = methodNode.signature,
            exceptions = methodNode.exceptions ?: emptyList(),
            instructions = instructions,
            constants = constants
        )
    }

    private fun analyzeField(fieldNode: FieldNode, owner: String): FieldInfo {
        return FieldInfo(
            name = fieldNode.name,
            owner = owner,
            descriptor = fieldNode.desc,
            access = fieldNode.access,
            signature = fieldNode.signature,
            value = fieldNode.value
        )
    }

    private fun resolveClassRelationships(classes: Map<String, ClassInfo>) {
        classes.values.forEach { classInfo ->
            // Find inner classes
            val innerClasses = classes.values
                .filter { it.name.startsWith("${classInfo.name}$") }
                .map { it.name }

            // Find outer class
            val outerClass = if (classInfo.name.contains('$')) {
                val outerName = classInfo.name.substringBeforeLast('$')
                if (classes.containsKey(outerName)) outerName else null
            } else null

            // Update the class info (Note: we need to create new instances due to immutability)
            // For now, we'll handle this in the data structure itself
        }
    }
}
