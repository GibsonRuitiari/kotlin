/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.ir2wasm

import org.jetbrains.kotlin.backend.wasm.WasmSymbols
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.isTrueConst
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.utils.addToStdlib.flatGroupBy
import org.jetbrains.kotlin.wasm.ir.WasmImmediate
import org.jetbrains.kotlin.wasm.ir.WasmOp

class Label

// TODO: eliminate the temporary variable
class SwitchGenerator(private val expression: IrWhen, private val generator: BodyGenerator, private val symbols: WasmSymbols) {
    data class ExpressionToLabel(val expression: IrExpression, val label: Label)
    data class CallToLabel(val call: IrCall, val label: Label)
    data class ValueToLabel(val value: Any?, val label: Label)

    private val IrSimpleFunctionSymbol.isEqEq
        get() = this == symbols.irBuiltIns.eqeqSymbol || symbols.equalityFunctions.containsValue(this)

    // @return null if the IrWhen cannot be emitted as lookupswitch or tableswitch.
    fun generate(): Boolean {
        val expressionToLabels = ArrayList<ExpressionToLabel>()
        var elseExpression: IrExpression? = null
        val callToLabels = ArrayList<CallToLabel>()

        // Parse the when structure. Note that the condition can be nested. See matchConditions() for details.
        for (branch in expression.branches) {
            if (branch is IrElseBranch) {
                elseExpression = branch.result
            } else {
                val conditions = matchConditions(branch.condition) ?: return false
                val thenLabel = Label()
                expressionToLabels.add(ExpressionToLabel(branch.result, thenLabel))
                callToLabels += conditions.map { CallToLabel(it, thenLabel) }
            }
        }

        // switch isn't applicable if there's no case at all, e.g., when() { else -> ... }
        if (callToLabels.size == 0)
            return false

        val calls = callToLabels.map { it.call }

        // To generate a switch from a when it must be a comparison of a single
        // variable, the "subject", against a series of constants. We assume the
        // subject is the left hand side of the first condition, provided the
        // first condition is a comparison. If the first condition is of the form:
        //
        //     CALL EQEQ(<unsafe-coerce><UInt, Int>(var),_)
        //
        // we must be trying to generate an _unsigned_ int switch, and need to
        // account for unsafe-coerce in all comparisons that arise from the
        // wrapping and unwrapping of the UInt inline class wrapper. Otherwise,
        // this is a primitive Int or String switch, with a condition of the form
        //
        //     CALL EQEQ(var,_)

        val firstCondition = callToLabels[0].call
        if (!firstCondition.symbol.isEqEq) return false
        val subject = firstCondition.getValueArgument(0)
        return when {
            subject is IrCall && subject.isCoerceFromUIntToInt() ->
                generateUIntSwitch(subject.getValueArgument(0)!! as? IrGetValue, calls, callToLabels, expressionToLabels, elseExpression)

            subject is IrGetValue || subject is IrConst<*> && subject.type.isString() -> // also generate tableswitch for literal string subject
                generatePrimitiveSwitch(subject, calls, callToLabels, expressionToLabels, elseExpression)

            else -> null

        }?.genOptimizedIfEnoughCases() ?: false
    }

    fun IrCall.isCoerceFromUIntToInt(): Boolean =
        symbol == symbols.unsafeCoerceIntrinsic
                && getTypeArgument(0)?.isUInt() == true
                && getTypeArgument(1)?.isInt() == true

    private fun generateUIntSwitch(
        subject: IrGetValue?,
        conditions: List<IrCall>,
        callToLabels: ArrayList<CallToLabel>,
        expressionToLabels: ArrayList<ExpressionToLabel>,
        elseExpression: IrExpression?
    ): Switch? {
        if (subject == null) return null
        // We check that all conditions are of the form
        //    CALL EQEQ (<unsafe-coerce><UInt,Int>(subject),
        //               <unsafe-coerce><UInt,Int>( Constant ))
        if (!areConstUIntComparisons(conditions)) return null

        // Filter repeated cases. Allowed in Kotlin but unreachable.
        val cases = callToLabels.map {
            val constCoercion = it.call.getValueArgument(1)!! as IrCall
            val constValue = (constCoercion.getValueArgument(0) as IrConst<*>).value
            ValueToLabel(
                constValue,
                it.label
            )
        }.distinctBy { it.value }

        expressionToLabels.removeUnreachableLabels(cases)

        return IntSwitch(
            subject,
            elseExpression,
            expressionToLabels,
            callToLabels,
            cases
        )
    }

    private fun generatePrimitiveSwitch(
        subject: IrExpression,
        conditions: List<IrCall>,
        callToLabels: ArrayList<CallToLabel>,
        expressionToLabels: ArrayList<ExpressionToLabel>,
        elseExpression: IrExpression?
    ): Switch? {
        // Checks if all conditions are CALL EQEQ(var,constant)
        if (!areConstantComparisons(conditions)) return null

        return when {
            subject is IrGetValue && areConstIntComparisons(conditions) -> {
                val cases = extractSwitchCasesAndFilterUnreachableLabels(callToLabels, expressionToLabels)
                IntSwitch(
                    subject,
                    elseExpression,
                    expressionToLabels,
                    callToLabels,
                    cases
                )
            }
            areConstStringComparisons(conditions) -> {
                val cases = extractSwitchCasesAndFilterUnreachableLabels(callToLabels, expressionToLabels)
                StringSwitch(
                    subject,
                    elseExpression,
                    expressionToLabels,
                    callToLabels,
                    cases
                )
            }
            else ->
                null
        }
    }

    // Check that all conditions are of the form
    //
    //  CALL EQEQ (<unsafe-coerce><UInt,Int>(subject), <unsafe-coerce><UInt,Int>( Constant ))
    //
    // where subject is taken to be the first variable compared on the left hand side, if any.
    private fun areConstUIntComparisons(conditions: List<IrCall>): Boolean {
        val lhs = conditions.map { it.takeIf { it.symbol.isEqEq }?.getValueArgument(0) as? IrCall }
        if (lhs.any { it == null || !it.isCoerceFromUIntToInt() }) return false
        val lhsVariableAccesses = lhs.map { it!!.getValueArgument(0) as? IrGetValue }
        if (lhsVariableAccesses.any { it == null || it.symbol != lhsVariableAccesses[0]!!.symbol }) return false

        val rhs = conditions.map { it.getValueArgument(1) as? IrCall }
        if (rhs.any { it == null || !it.isCoerceFromUIntToInt() || it.getValueArgument(0) !is IrConst<*> }) return false

        return true
    }

    private fun areConstantComparisons(conditions: List<IrCall>): Boolean {

        fun isValidIrGetValueTypeLHS(): Boolean {
            val lhs = conditions.map {
                it.takeIf { it.symbol.isEqEq }?.getValueArgument(0) as? IrGetValue
            }
            return lhs.all { it != null && it.symbol == lhs[0]!!.symbol }
        }

        fun isValidIrConstTypeLHS(): Boolean {
            val lhs = conditions.map {
                it.takeIf { it.symbol.isEqEq }?.getValueArgument(0) as? IrConst<*>
            }
            return lhs.all { it != null && it.value == lhs[0]!!.value }
        }

        // All conditions are equality checks && all LHS refer to the same tmp variable.
        if (!isValidIrGetValueTypeLHS() && !isValidIrConstTypeLHS())
            return false

        // All RHS are constants
        if (conditions.any { it.getValueArgument(1) !is IrConst<*> })
            return false

        return true
    }

    private fun areConstIntComparisons(conditions: List<IrCall>): Boolean {
        return checkTypeSpecifics(conditions, { it.isInt() }, { it.kind == IrConstKind.Int })
    }

    private fun areConstStringComparisons(conditions: List<IrCall>): Boolean {
        return checkTypeSpecifics(
            conditions,
            { it.isString() || it.isNullableString() },
            { it.kind == IrConstKind.String || it.kind == IrConstKind.Null })
    }

    private fun checkTypeSpecifics(
        conditions: List<IrCall>,
        subjectTypePredicate: (IrType) -> Boolean,
        irConstPredicate: (IrConst<*>) -> Boolean
    ): Boolean {
        val lhs = conditions.map { it.getValueArgument(0) as? IrGetValue ?: it.getValueArgument(0) as IrConst<*> }
        if (lhs.any { !subjectTypePredicate(it.type) })
            return false

        val rhs = conditions.map { it.getValueArgument(1) as IrConst<*> }
        if (rhs.any { !irConstPredicate(it) })
            return false

        return true
    }

    private fun extractSwitchCasesAndFilterUnreachableLabels(
        callToLabels: List<CallToLabel>,
        expressionToLabels: ArrayList<ExpressionToLabel>
    ): List<ValueToLabel> {
        // Don't generate repeated cases, which are unreachable but allowed in Kotlin.
        // Only keep the first encountered case:
        val cases =
            callToLabels.map { ValueToLabel((it.call.getValueArgument(1) as IrConst<*>).value, it.label) }.distinctBy { it.value }

        expressionToLabels.removeUnreachableLabels(cases)

        return cases
    }

    private fun ArrayList<ExpressionToLabel>.removeUnreachableLabels(cases: List<ValueToLabel>) {
        val reachableLabels = HashSet(cases.map { it.label })
        removeIf { it.label !in reachableLabels }
    }

    // psi2ir lowers multiple cases to nested conditions. For example,
    //
    // when (subject) {
    //   a, b, c -> action
    // }
    //
    // is lowered to
    //
    // if (if (subject == a)
    //       true
    //     else
    //       if (subject == b)
    //         true
    //       else
    //         subject == c) {
    //     action
    // }
    //
    // fir2ir lowers the same to an or sequence:
    //
    // if (((subject == a) || (subject == b)) || (subject = c)) action
    //
    // @return true if the conditions are equality checks of constants.
    private fun matchConditions(condition: IrExpression): ArrayList<IrCall>? {
        if (condition is IrWhen && condition.origin == IrStatementOrigin.WHEN_COMMA) {
            assert(condition.type.isBoolean()) { "WHEN_COMMA should always be a Boolean: ${condition.dump()}" }

            val candidates = ArrayList<IrCall>()

            // Match the following structure:
            //
            // when() {
            //   cond_1 -> true
            //   cond_2 -> true
            //   ...
            //   else -> cond_N
            // }
            //
            // Namely, the structure which returns true if any one of the condition is true.
            for (branch in condition.branches) {
                candidates += if (branch is IrElseBranch) {
                    assert(branch.condition.isTrueConst()) { "IrElseBranch.condition should be const true: ${branch.condition.dump()}" }
                    matchConditions(branch.result) ?: return null
                } else {
                    if (!branch.result.isTrueConst())
                        return null
                    matchConditions(branch.condition) ?: return null
                }
            }

            return if (candidates.isNotEmpty()) candidates else return null
        } else if (condition is IrCall && condition.symbol == symbols.irBuiltIns.ororSymbol) {
            val candidates = ArrayList<IrCall>()
            for (i in 0 until condition.valueArgumentsCount) {
                val argument = condition.getValueArgument(i)!!
                candidates += matchConditions(argument) ?: return null
            }
            return if (candidates.isNotEmpty()) candidates else return null
        } else if (condition is IrCall) {
            return arrayListOf(condition)
        }

        return null
    }

    abstract inner class Switch(
        val subject: IrExpression,
        val elseExpression: IrExpression?,
        val expressionToLabels: ArrayList<ExpressionToLabel>,
        val callToLabels: ArrayList<CallToLabel>
    ) {
        protected val defaultLabel = Label()

        open fun shouldOptimize() = false

        open fun genOptimizedIfEnoughCases(): Boolean {
            if (!shouldOptimize())
                return false

            genSwitch()
            return genBranchTargets()
        }

        protected abstract fun genSwitch()

        @Suppress("UNUSED_PARAMETER")
        protected fun genIntSwitch(unsortedIntCases: List<ValueToLabel>) {
            val intCases = unsortedIntCases.map { it.value as Int to it.label }
            val caseMin = intCases.minBy { it.first }
            val caseMax = intCases.maxBy { it.first }

            if (caseMin.first == Int.MIN_VALUE && caseMax.first == Int.MAX_VALUE) error("!!!")
            val resultType = generator.context.transformBlockResultType(expression.type)

            val notEmptyBackets = intCases
                .groupBy { (it.first - caseMin.first) % intCases.size }
                .values
                .filter { it.isNotEmpty() }

            val baseBlockIndex = generator.body.numberOfNestedBlocks

            repeat(notEmptyBackets.size + 1) { //backets + else branch
                generator.body.buildBlock(resultType)
            }
            generator.body.buildBlock(resultType) //br_table

            //(subject - min) % size
            resultType?.let { generateDefaultInitializerForType(it, generator.body) } //stub value
            subject.accept(generator, null)
            generator.body.buildConstI32(caseMin.first)
            generator.body.buildInstr(WasmOp.I32_SUB)
            generator.body.buildConstI32(intCases.size)
            generator.body.buildInstr(WasmOp.I32_REM_U)

            generator.body.buildInstr(
                WasmOp.BR_TABLE,
                WasmImmediate.LabelIdxVector(notEmptyBackets.indices.toList()),
                WasmImmediate.LabelIdx(notEmptyBackets.size)
            )
            generator.body.buildEnd()

            for (backet in notEmptyBackets) {
                if (resultType != null) {
                    generator.body.buildDrop()
                }

                for (branch in backet) {
                    val condition = callToLabels.first { it.label == branch.second }.call
                    val expression = expressionToLabels.first { it.label == branch.second }.expression
                    condition.acceptVoid(generator)
                    generator.body.buildIf(null, resultType)
                    expression.acceptVoid(generator)
                    generator.body.buildElse()
                }

                resultType?.let { generateDefaultInitializerForType(it, generator.body) } //stub value
                generator.body.buildBr(baseBlockIndex + 2)

                repeat(backet.size) { generator.body.buildEnd() }

                generator.body.buildBr(baseBlockIndex + 1)
                generator.body.buildEnd()
            }

            //else block
            if (resultType != null) {
                generator.body.buildDrop()
            }
            elseExpression?.acceptVoid(generator)
            generator.body.buildEnd()
            check(baseBlockIndex == generator.body.numberOfNestedBlocks)
        }

        private fun genBranchTargets(): Boolean {
//            with(codegen) {
//                val endLabel = Label()
//
//                for ((thenExpression, label) in expressionToLabels) {
//                    mv.visitLabel(label)
//                    thenExpression.accept(codegen, data).also {
//                        if (elseExpression != null) {
//                            it.materializedAt(expression.type)
//                        } else {
//                            it.discard()
//                        }
//                    }
//                    mv.goTo(endLabel)
//                }
//
//                mv.visitLabel(defaultLabel)
//                val result = elseExpression?.accept(codegen, data)?.materializedAt(expression.type) ?: unitValue
//                mv.mark(endLabel)
//                return result
//            }
            return true
        }
    }

    inner class IntSwitch(
        subject: IrGetValue,
        elseExpression: IrExpression?,
        expressionToLabels: ArrayList<ExpressionToLabel>,
        callToLabels: ArrayList<CallToLabel>,
        private val cases: List<ValueToLabel>
    ) : Switch(subject, elseExpression, expressionToLabels, callToLabels) {

        // IF is more compact when there are only 1 or fewer branches, in addition to else.
        override fun shouldOptimize() = cases.size > 1

        override fun genSwitch() {
            // Do not generate line numbers for the table switching. In particular,
            // the subject is extracted from the condition of the first branch which
            // will give the wrong stepping behavior for code such as:
            //
            // when {
            //   x == 42 -> 1
            //   x == 32 -> 2
            //   x == 24 -> 3
            //   ...
            // }
            //
            // If the subject line number is generated, we will not stop on the line
            // of the `when` but instead stop on the `x == 42` line. When x is 24,
            // we would stop on the line `x == 42` and then step to the line `x == 24`.
            // That is confusing and we prefer to stop on the `when` line and then step
            // to the `x == 24` line. This is accomplished by ignoring the line number
            // information for the subject as the `when` line number has already been
            // emitted.
//            codegen.noLineNumberScope {
//                val subjectValue = subject.accept(codegen, data)
//                subjectValue.materializeAt(Type.INT_TYPE, subjectValue.irType)
//            }
            genIntSwitch(cases)
        }
    }

    // The following when structure:
    //
    //   when (s) {
    //     s1, s2 -> e1,
    //     s3 -> e2,
    //     s4 -> e3,
    //     ...
    //     else -> e
    //   }
    //
    // is implemented as:
    //
    //   // if s is String?, generate the following null check:
    //   if (s == null)
    //     // jump to the case where null is handled, if defined.
    //     // otherwise, jump out of the when().
    //     ...
    //   ...
    //   when (s.hashCode()) {
    //     h1 -> {
    //       if (s == s1)
    //         e1
    //       else if (s == s2)
    //         e1
    //       else if (s == s3)
    //         e2
    //       else
    //         e
    //     }
    //     h2 -> if (s == s3) e2 else e,
    //     ...
    //     else -> e
    //   }
    //
    // where s1.hashCode() == s2.hashCode() == s3.hashCode() == h1,
    //       s4.hashCode() == h2.
    //
    // A tableswitch or lookupswitch is then used for the hash code lookup.

    inner class StringSwitch(
        subject: IrExpression,
        elseExpression: IrExpression?,
        expressionToLabels: ArrayList<ExpressionToLabel>,
        callToLabels: ArrayList<CallToLabel>,
        private val cases: List<ValueToLabel>
    ) : Switch(subject, elseExpression, expressionToLabels, callToLabels) {

        private val hashToStringAndExprLabels = HashMap<Int, ArrayList<ValueToLabel>>()
        private val hashAndSwitchLabels = ArrayList<ValueToLabel>()

        init {
            for (case in cases)
                if (case.value != null) // null is handled specially and will never be dispatched from the switch.
                    hashToStringAndExprLabels.getOrPut(case.value.hashCode()) { ArrayList() }.add(
                        ValueToLabel(case.value, case.label)
                    )

            for (key in hashToStringAndExprLabels.keys)
                hashAndSwitchLabels.add(ValueToLabel(key, Label()))
        }

        // Using a switch, the subject string has to be traversed at least twice
        // (hash + comparison * N, where N is #strings hashed into the same bucket).
        // The optimization isn't better than an IF cascade when #switch-targets <= 2.
        //
        // Generate "optimized" version for @EnhancedNullability subject type
        // to model 1.0 behavior causing NPE in case of null value.
        // TODO make 'when' with String subject behavior consistent.
        // see:
        //  box/when/stringOptimization/enhancedNullability.kt
        //  box/when/stringOptimization/flexibleNullability.kt
        //
        // Generate "optimized" version for literal string subject to avoid performance regression
        // see:
        //  box/unit/nullableUnitInWhen3.kt
        override fun shouldOptimize() =
            hashAndSwitchLabels.size > (if (subject is IrConst<*>) 0 else 2)

        override fun genSwitch() {
//            with(codegen) {
//                // Do not generate line numbers for the table switching. In particular,
//                // the subject is extracted from the condition of the first branch which
//                // will give the wrong stepping behavior for code such as:
//                //
//                // when {
//                //   x == "x" -> 1
//                //   x == "y" -> 2
//                //   x == "z" -> 3
//                //   ...
//                // }
//                //
//                // If the subject line number is generated, we will not stop on the line
//                // of the `when` but instead stop on the `x == "x"` line. When x is "z",
//                // we would stop on the line `x == "x"` and then step to the line `x == "z"`.
//                // That is confusing and we prefer to stop on the `when` line and then step
//                // to the `x == "z"` line. This is accomplished by ignoring the line number
//                // information for the subject as the `when` line number has already been
//                // emitted.
//                noLineNumberScope {
//                    if (subject.type.isNullableString()) {
//                        subject.accept(codegen, data).materialize()
//                        mv.ifnull(cases.find { it.value == null }?.label ?: defaultLabel)
//                    }
//                    // Reevaluating the subject is fine here because it is a read of a temporary.
//                    subject.accept(codegen, data).materialize()
//                    mv.invokevirtual("java/lang/String", "hashCode", "()I", false)
//                }
//                genIntSwitch(hashAndSwitchLabels)
//
//                // Multiple strings can be hashed into the same bucket.
//                // Generate an if cascade to resolve that for each bucket.
//                for ((hash, switchLabel) in hashAndSwitchLabels) {
//                    mv.visitLabel(switchLabel)
//                    for ((string, label) in hashToStringAndExprLabels[hash]!!) {
//                        noLineNumberScope {
//                            subject.accept(codegen, data).materialize()
//                        }
//                        mv.aconst(string)
//                        mv.invokevirtual("java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
//                        mv.ifne(label)
//                    }
//                    mv.goTo(defaultLabel)
//                }
//            }
        }
    }
}