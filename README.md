# Kotlin Bytecode Mapper

A dynamic JAR-to-JAR bytecode identifier mapper using the ASM library from OW2. This tool analyzes two versions of a JAR file and maps obfuscated identifiers (classes, methods, fields) between them using structural similarity analysis.

## Overview

This mapper is designed to match identifiers between two versions of a JAR where names have been obfuscated to patterns like:
- `class[0-9]*` - Obfuscated class names
- `method[0-9]*` - Obfuscated method names
- `field[0-9]*` - Obfuscated field names

## Algorithm Design

The mapper uses **Method 2: Iterative Similarity Scoring** with the following approach:

### Phase 1: Anchor Matching
- Match all non-obfuscated identifiers by name
- These provide anchors for propagating matches to obfuscated code

### Phase 2: Iterative Propagation
The algorithm iteratively matches identifiers based on structural similarity:

1. **Class Matching**
   - Super class matches (if already matched)
   - Interface matches (if already matched)
   - Method signature similarity (descriptor patterns)
   - Field type similarity
   - Member count ratios

2. **Method Matching** (requires matched owner class)
   - Descriptor exact match or partial match
   - Argument type matches (if types already matched)
   - Return type matches (if type already matched)
   - Constant pool similarity
   - Instruction sequence patterns
   - Static/instance modifier match

3. **Field Matching** (requires matched owner class)
   - Field type matches (if type already matched)
   - Initial value comparison
   - Static/instance modifier match

### Matching Rules

- **Obfuscated identifiers** can only match to other obfuscated identifiers
- **Non-obfuscated identifiers** can only match to other non-obfuscated identifiers
- All obfuscated classes must be in the default/base package
- Matches require:
  - Similarity score ≥ 0.85
  - Confidence gap (1st - 2nd place) ≥ 0.25

### Propagation Strategy

When a match is confirmed, weak scores propagate to related identifiers:
- Class match → super class, interfaces
- Method match → argument types, return type
- Field match → field type

This creates a cascading effect where initial anchors propagate through the codebase.

## Key Features

✅ **Structural Analysis**
- Analyzes class hierarchies, interfaces, members
- Compares method signatures and bytecode patterns
- Examines constant pools and instruction sequences

✅ **Conservative Matching**
- High confidence thresholds to avoid false positives
- Would rather skip a match than match incorrectly
- Bipartite matching ensures 1-to-1 relationships

✅ **Iterative Refinement**
- Multiple passes to propagate matches
- Each iteration confirms high-confidence matches
- Continues until no new matches found

## Building

```bash
./gradlew build
```

## Usage

```bash
./gradlew run --args="<jar-a> <jar-b> [output-file]"
```

Or after building:

```bash
java -jar build/libs/bytecode-mapper-1.0.0.jar <jar-a> <jar-b> [output-file]
```

### Arguments

- `jar-a` - Path to the first JAR file (source)
- `jar-b` - Path to the second JAR file (target)
- `output-file` - Optional output file for mappings (default: `mappings.txt`)

### Example

```bash
./gradlew run --args="old-version.jar new-version.jar mappings.txt"
```

## Output Format

The tool generates a text file with three sections:

```
## Class Mappings
class123 -> class456
class124 -> class457

## Method Mappings
class123.method1()V -> class456.method1()V
class123.method2(I)I -> class456.method5(I)I

## Field Mappings
class123.field1:I -> class456.field2:I
class123.field2:Ljava/lang/String; -> class456.field3:Ljava/lang/String;
```

## Architecture

### Core Components

1. **Model** (`com.mapper.model`)
   - `ClassInfo` - Represents a class with metadata
   - `MethodInfo` - Represents a method with signature
   - `FieldInfo` - Represents a field
   - `JarEnvironment` - Container for all analyzed identifiers

2. **Analyzer** (`com.mapper.analyzer`)
   - `JarAnalyzer` - Parses JARs using ASM
   - Extracts classes, methods, fields, constants
   - Builds instruction patterns

3. **Matcher** (`com.mapper.matcher`)
   - `IdentifierMatcher` - Core matching algorithm
   - Implements iterative similarity scoring
   - Manages confirmed matches and propagation

## Implementation Notes

### Why Method 2 (Iterative Scoring) Over Method 1 (Execution Mapping)?

**Method 1 Challenges:**
- Extremely complex to implement correctly
- Requires simulating execution which may differ between versions
- Entry-point dependent - misses unreachable code
- Hard to handle reflection, dynamic dispatch, native methods
- Doesn't scale well with large JARs

**Method 2 Advantages:**
- Proven approach (similar to existing deobfuscation tools)
- More robust to edge cases
- Easier to debug and tune
- Scales well with iterative refinement
- Handles partial information gracefully

### Scoring Strategy

The system uses multiple signals for scoring:

- **Strong signals** (high weight):
  - Exact descriptor matches
  - Matched owner classes
  - Identical constant pools

- **Medium signals**:
  - Partially matched type signatures
  - Similar instruction patterns
  - Matching access modifiers

- **Weak signals** (propagation):
  - Related types (superclass, interfaces)
  - Referenced types in signatures
  - Field types

### Confidence Thresholds

The mapper uses conservative thresholds to avoid false matches:

- **Minimum similarity**: 0.85 (85%)
- **Confidence gap**: 0.25 (25% difference between 1st and 2nd place)

This ensures that only high-confidence, unambiguous matches are confirmed.

## Future Enhancements

Potential improvements:

1. **CFG Structure Comparison**
   - Compare control flow graphs for method matching
   - Match based on branching patterns

2. **Call Graph Analysis**
   - Build method call graphs
   - Match based on caller/callee relationships

3. **String Constant Analysis**
   - Weight string constants higher (often unique)
   - Match methods using same error messages

4. **Machine Learning**
   - Train models on known mappings
   - Improve scoring weights automatically

5. **Incremental Mapping**
   - Support for mapping across multiple versions
   - Build confidence over time

## Dependencies

- Kotlin 1.9.22
- ASM 9.6 (org.ow2.asm)
- JVM 11+

## License

This project is provided as-is for educational purposes.
