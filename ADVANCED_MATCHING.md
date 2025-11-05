# Advanced Matching Techniques: TF-IDF vs KNN

This document explains two advanced matching approaches for bytecode mapping and provides a recommendation.

## Overview

Both TF-IDF and KNN are powerful techniques for finding similar methods, but they excel at different aspects:

- **TF-IDF**: Best for finding methods with similar **unique features** (strings, constants, patterns)
- **KNN**: Best for finding methods with similar **structural properties** (complexity, size, shape)

## Approach #1: TF-IDF Text Fingerprinting

### How It Works

1. Convert each method into a "document" of feature tokens
2. Build a TF-IDF model across all methods in the corpus
3. Find methods with highest cosine similarity on TF-IDF vectors

### Example

**Method A:**
```
Tokens: [
  USTR:5012931,              // "Invalid password"
  USTR:7234019,              // "Login failed"
  UNUM:86400,                // Session timeout constant
  DESC:(Ljava/lang/String;)Z,
  MCALL:java/security/MessageDigest.digest([B)[B,
  MCALL:java/lang/String.equals(Ljava/lang/Object;)Z,
  NG3:ALOAD_GETFIELD_ALOAD,
  NG3:GETFIELD_ALOAD_INVOKEVIRTUAL,
  OPC:ALOAD, OPC:ALOAD, OPC:GETFIELD, ...
]
```

**TF-IDF Scoring:**
- `USTR:5012931` → High IDF (rare string, appears in 1 method) → High score
- `UNUM:86400` → Medium IDF (appears in 3 methods) → Medium score
- `OPC:ALOAD` → Low IDF (appears in 90% of methods) → Low score

### Strengths

✅ **Automatically weights unique features**
- Rare string constants get very high scores
- Common opcodes get low scores
- No manual weight tuning needed

✅ **Fast for large datasets**
- Can process thousands of methods in seconds
- Sparse vector representation is efficient

✅ **Handles variable-length features naturally**
- Some methods have 1 string, others have 50
- TF-IDF handles this gracefully

✅ **Good for "fuzzy" matching**
- Methods don't need exact matches
- Similar patterns score well even with differences

### Weaknesses

❌ **Loses sequence information**
- `[ALOAD, GETFIELD, ILOAD]` vs `[ILOAD, ALOAD, GETFIELD]` look identical
- N-grams help but add token explosion

❌ **Treats features independently**
- Doesn't know that `exception_types` relates to `exception_handlers`

❌ **Tokenization can be tricky**
- Need to balance different feature types
- Too many opcode tokens can drown out important features

### Best Use Cases

- **Initial candidate filtering** - Reduce 10,000 methods to top 20 candidates
- **Unique feature matching** - Methods with distinctive strings/constants
- **Fast approximate search** - When speed matters more than precision

---

## Approach #2: K-Nearest Neighbor on Feature Vectors

### How It Works

1. Encode each method as a fixed-size numeric feature vector
2. Compute weighted distance (cosine/Euclidean) between vectors
3. Return k nearest neighbors

### Example

**Method A:**
```
Feature Vector: [
  cyclomatic_complexity: 0.35,      // Normalized to 0-1
  basic_blocks: 0.40,
  bytecode_size: 0.47,
  max_stack: 0.30,
  parameter_count: 0.20,
  exception_handler_count: 0.40,
  string_constants_hash: 0.8234,   // Consistent hash
  method_call_pattern_hash: 0.6127,
  opcode_ALOAD: 0.15,              // 15% of instructions
  opcode_GETFIELD: 0.08,
  ...
]
```

**Method B (good match):**
```
Feature Vector: [
  cyclomatic_complexity: 0.33,  // Close!
  basic_blocks: 0.40,           // Exact!
  bytecode_size: 0.49,          // Close!
  ...
]
```

**Distance = 0.92** (high cosine similarity → good match)

### Strengths

✅ **Captures numeric relationships**
- Complexity 7 is closer to 6 than to 15
- Allows fuzzy numeric matching

✅ **Returns top-k candidates**
- Get 5 best matches with confidence scores
- Perfect for disambiguation

✅ **Easy to weight features**
- Boost importance of critical features
- Fine-tune matching behavior

✅ **Multiple distance metrics**
- Cosine similarity for directional similarity
- Euclidean for absolute distance

### Weaknesses

❌ **Fixed-size vectors required**
- Hard to encode `string_constants = ["a", "b", "c", ...]`
- Solution: Use hash-based features

❌ **Requires normalization**
- `bytecode_size=234` vs `max_stack=3` need scaling
- Different ranges require careful handling

❌ **Computationally expensive**
- O(n²) comparisons for naive KNN
- Can use approximate KNN (LSH, k-d trees) for speed

❌ **Feature engineering needed**
- Must manually design the feature vector
- Requires domain knowledge

### Best Use Cases

- **Final disambiguation** - Choose best from 3-5 similar candidates
- **Structural similarity** - When method "shape" matters
- **Numeric feature matching** - Complexity, size, counts important

---

## The Winning Strategy: Hybrid Approach 🏆

**Use both techniques in sequence:**

```
┌─────────────────────────────────────────────────────────┐
│ Phase 1-2: Exact Matching (Current Implementation)     │
│ ├─ Non-obfuscated anchors                              │
│ ├─ Exact descriptor + matched owner                    │
│ └─ High confidence (>0.9) structural matches           │
│                                                         │
│ Remaining: ~2000 unmatched methods                     │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ Phase 3: TF-IDF Candidate Generation ⭐               │
│ ├─ Generate fingerprint tokens for each method         │
│ ├─ Find top 20 candidates per method (fast!)          │
│ └─ Filter based on unique features (strings, calls)    │
│                                                         │
│ Output: ~40 candidates per method on average           │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ Phase 4: KNN Fine-Grained Scoring ⭐                  │
│ ├─ Extract feature vectors for candidates only         │
│ ├─ Rank candidates by structural similarity            │
│ └─ Pick best match with confidence gap check           │
│                                                         │
│ Output: Best match per method (if confident)           │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ Phase 5: Bipartite Matching & Confirmation             │
│ ├─ Combined score: TF-IDF (40%) + KNN (60%)           │
│ ├─ Threshold: Combined score > 0.7                     │
│ ├─ Confidence gap: 1st - 2nd > 0.15                   │
│ └─ Confirm matches meeting criteria                    │
└─────────────────────────────────────────────────────────┘
```

### Why This Works

1. **TF-IDF eliminates obvious non-matches quickly**
   - A method with strings `["Login", "Password"]` won't match one with `["Database", "Query"]`
   - Reduces search space from thousands to ~20 candidates

2. **KNN provides precise ranking among similar candidates**
   - Among 20 candidates with similar strings, find the one with matching structure
   - Numeric features (complexity, size) disambiguate

3. **Combined score leverages both strengths**
   - TF-IDF weight: 0.4 (unique features)
   - KNN weight: 0.6 (structural similarity)
   - Balances feature-based and structure-based matching

### Performance

| Phase | Input | Output | Time Complexity |
|-------|-------|--------|-----------------|
| Exact Matching | 10,000 methods | 8,000 remain | O(n) |
| TF-IDF | 8,000 methods | 20 candidates each | O(n log n) |
| KNN | 20 candidates | 5 best matches | O(k²) ≈ O(1) |
| Total | 10,000 methods | Matched | **Fast!** |

---

## Implementation Example

```kotlin
// Build the hybrid matcher
val hybridMatcher = HybridMethodMatcher()
hybridMatcher.buildModels(envA.methods.values, envB.methods.values)

// Find best match with confidence
val unmatchedMethods = envA.methods.values.filter { !isMatched(it) }

for (methodA in unmatchedMethods) {
    val candidatesB = envB.methods.values.filter { !isMatched(it) }

    val match = hybridMatcher.findBestMatchWithConfidence(
        queryMethod = methodA,
        candidateMethods = candidatesB,
        minScore = 0.7,      // Combined score threshold
        minGap = 0.15        // Confidence gap
    )

    if (match != null) {
        val (methodB, score) = match
        println("Matched: ${methodA.name} -> ${methodB.name} (score: $score)")
        confirmMatch(methodA, methodB, score)
    }
}
```

---

## Feature Importance Ranking

Based on empirical testing, here's how I'd rank features for matching:

### Tier 1: Extremely High Value (Weight: 3.0-3.5)
1. **String constants** - Almost always unique
2. **Numeric constants (non-trivial)** - Magic numbers rarely change
3. **Method call patterns** - Structural fingerprint

### Tier 2: High Value (Weight: 2.0-2.5)
4. **Exception types** - Distinctive and preserved
5. **Parameter count** - Usually preserved
6. **Basic block count** - Structural metric
7. **Field access patterns** - What fields used together

### Tier 3: Medium Value (Weight: 1.0-1.5)
8. **Descriptor** - Often preserved but can change
9. **Opcode N-grams** - Sequence patterns
10. **Cyclomatic complexity** - Can vary slightly

### Tier 4: Low Value (Weight: 0.5-1.0)
11. **Bytecode size** - Can change with optimization
12. **Common opcodes (ALOAD, ILOAD)** - Too generic
13. **Return type** - Often changes

---

## TF-IDF Token Design Best Practices

### Use Tiered Prefixes

```
// High-value unique tokens (rare = high IDF)
USTR:hash    // Unique string constants
UNUM:value   // Unique numeric constants

// Medium-value structural tokens
DESC:signature
MCALL:method
EXCPT:type

// Low-value common tokens (but still useful)
NG3:opcode_opcode_opcode  // 3-grams
OPC:opcode                // Individual opcodes
```

### Normalize Obfuscated Names

```
// BAD: Obfuscated names create unique tokens per version
MCALL:com/obf/a.b()V
MCALL:com/obf/c.d()V  // Different in v2!

// GOOD: Normalize obfuscated parts
MCALL:com/obf/OBF.OBF()V
MCALL:com/obf/OBF.OBF()V  // Same in v2!
```

### Use N-grams for Sequences

```
// Instead of individual opcodes
OPC:ALOAD, OPC:GETFIELD, OPC:ILOAD

// Use N-grams to capture patterns
NG3:ALOAD_GETFIELD_ILOAD
NG3:GETFIELD_ILOAD_IADD

// This preserves sequence information!
```

---

## KNN Feature Vector Best Practices

### Always Normalize Features

```kotlin
// BAD: Different ranges
bytecode_size: 234    // Range: 0-5000
max_stack: 3          // Range: 0-10

// GOOD: Normalized to 0-1
bytecode_size: 0.047  // 234 / 5000
max_stack: 0.3        // 3 / 10
```

### Use Hashing for Variable-Length Data

```kotlin
// Can't put variable number of strings in fixed vector
// Solution: Consistent hashing
fun hashStringConstants(method: MethodInfo): Double {
    val sorted = method.constants
        .filterIsInstance<String>()
        .sorted()  // Ensure consistency
    return (sorted.joinToString("|").hashCode() and 0x7FFFFFFF).toDouble()
           / Int.MAX_VALUE
}
```

### Weight Features by Importance

```kotlin
val featureWeights = doubleArrayOf(
    3.5,  // stringConstantsHash - VERY important
    3.0,  // methodCallPatternHash - very important
    2.0,  // basicBlocks - important
    1.0,  // opcodeALOAD - medium
    0.5   // isVoidReturn - low importance
)
```

---

## Recommendation Summary

### ✅ Use TF-IDF When:
- Initial candidate filtering (thousands → tens)
- Methods have unique string constants
- Speed is critical
- Fuzzy matching is acceptable

### ✅ Use KNN When:
- Final disambiguation (5-10 candidates)
- Structural similarity is important
- Numeric features matter (complexity, size)
- You need top-k ranked results

### 🏆 Use Hybrid When:
- **Best overall performance** (recommended!)
- Large method pools (>1000 methods)
- Need both unique features AND structural similarity
- Want high precision with good recall

---

## Conclusion

**For instruction pattern fingerprinting, use TF-IDF with N-grams** because:
1. Captures sequence patterns efficiently
2. Automatically weights rare vs common opcodes
3. Fast even with complex instruction sequences
4. Natural representation for bytecode "shapes"

**For final matching, use KNN on feature vectors** because:
1. Precise numeric similarity
2. Returns ranked candidates
3. Easy to tune with feature weights
4. Perfect for "pick best from top 5" scenarios

**Best strategy: Use both in a hybrid pipeline** for optimal results! 🎯
