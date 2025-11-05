package com.mapper.model

/**
 * Base interface for all identifiers (classes, methods, fields)
 */
sealed interface Identifier {
    val name: String
    val obfuscated: Boolean
}

/**
 * Represents a class with all its metadata and members
 */
data class ClassInfo(
    override val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val access: Int,
    val outerClass: String? = null,
    val innerClasses: List<String> = emptyList(),
    val methods: MutableList<MethodInfo> = mutableListOf(),
    val fields: MutableList<FieldInfo> = mutableListOf(),
    override val obfuscated: Boolean = isObfuscated(name)
) : Identifier {

    val signature: String
        get() = name

    fun isInDefaultPackage(): Boolean = !name.contains('/')

    companion object {
        fun isObfuscated(name: String): Boolean {
            val simpleName = name.substringAfterLast('/')
            return !simpleName.startsWith("class") &&
                   !simpleName.startsWith("method") &&
                   !simpleName.startsWith("field") &&
                   !simpleName.startsWith("client")
        }
    }
}

/**
 * Represents a method with its signature
 */
data class MethodInfo(
    override val name: String,
    val owner: String,
    val descriptor: String,
    val access: Int,
    val signature: String? = null,
    val exceptions: List<String> = emptyList(),
    val instructions: List<String> = emptyList(), // Simplified instruction list
    val constants: Set<Any> = emptySet(), // String/numeric constants used
    override val obfuscated: Boolean = ClassInfo.isObfuscated(name)
) : Identifier {

    val fullSignature: String
        get() = "$owner.$name$descriptor"

    val isStatic: Boolean
        get() = (access and org.objectweb.asm.Opcodes.ACC_STATIC) != 0

    fun getArgumentTypes(): List<String> {
        return parseDescriptor(descriptor).first
    }

    fun getReturnType(): String {
        return parseDescriptor(descriptor).second
    }

    companion object {
        fun parseDescriptor(desc: String): Pair<List<String>, String> {
            val args = mutableListOf<String>()
            var i = 1 // Skip opening '('

            while (i < desc.length && desc[i] != ')') {
                val (type, next) = parseType(desc, i)
                args.add(type)
                i = next
            }

            val (returnType, _) = parseType(desc, i + 1)
            return Pair(args, returnType)
        }

        private fun parseType(desc: String, start: Int): Pair<String, Int> {
            var i = start
            var arrayDepth = 0

            while (i < desc.length && desc[i] == '[') {
                arrayDepth++
                i++
            }

            return when (desc[i]) {
                'L' -> {
                    val end = desc.indexOf(';', i)
                    Pair(desc.substring(start, end + 1), end + 1)
                }
                else -> {
                    Pair(desc.substring(start, i + 1), i + 1)
                }
            }
        }
    }
}

/**
 * Represents a field
 */
data class FieldInfo(
    override val name: String,
    val owner: String,
    val descriptor: String,
    val access: Int,
    val signature: String? = null,
    val value: Any? = null, // Initial value if any
    override val obfuscated: Boolean = ClassInfo.isObfuscated(name)
) : Identifier {

    val fullSignature: String
        get() = "$owner.$name:$descriptor"

    val isStatic: Boolean
        get() = (access and org.objectweb.asm.Opcodes.ACC_STATIC) != 0

    fun getFieldType(): String = descriptor
}

/**
 * Represents a potential match between two identifiers with a score
 */
data class MatchCandidate<T : Identifier>(
    val source: T,
    val target: T,
    var score: Double
) {
    override fun toString(): String {
        return "${source.name} -> ${target.name} (score: ${"%.4f".format(score)})"
    }
}

/**
 * Container for all analyzed classes from a JAR
 */
data class JarEnvironment(
    val classes: Map<String, ClassInfo>,
    val methods: Map<String, MethodInfo>,
    val fields: Map<String, FieldInfo>
) {
    fun getClass(name: String): ClassInfo? = classes[name]
    fun getMethod(fullSignature: String): MethodInfo? = methods[fullSignature]
    fun getField(fullSignature: String): FieldInfo? = fields[fullSignature]
}
