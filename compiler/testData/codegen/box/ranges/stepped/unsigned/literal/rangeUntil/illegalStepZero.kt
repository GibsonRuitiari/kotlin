// Auto-generated by GenerateSteppedRangesCodegenTestData. Do not edit!
// WITH_STDLIB
// KT-34166: Translation of loop over literal completely removes the validation of step
// DONT_TARGET_EXACT_BACKEND: JS
// !LANGUAGE: +RangeUntilOperator
@file:OptIn(ExperimentalStdlibApi::class)
import kotlin.test.*

fun box(): String {
    assertFailsWith<IllegalArgumentException> {
        for (i in 1u..<8u step 0) {
        }
    }

    assertFailsWith<IllegalArgumentException> {
        for (i in 1uL..<8uL step 0L) {
        }
    }

    return "OK"
}