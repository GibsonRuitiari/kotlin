/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.model

import org.jetbrains.kotlin.codegen.ClassFileFactory
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.backend.js.CompilerResult
import org.jetbrains.kotlin.js.backend.ast.JsProgram
import org.jetbrains.kotlin.library.KotlinLibrary
import java.io.File

object BinaryArtifacts {
    class Jvm(val classFileFactory: ClassFileFactory) : ResultingArtifact.Binary<Jvm>() {
        override val kind: BinaryKind<Jvm>
            get() = ArtifactKinds.Jvm
    }

    sealed class Js : ResultingArtifact.Binary<Js>() {
        abstract val outputFile: File
        override val kind: BinaryKind<Js>
            get() = ArtifactKinds.Js
    }

    class OldJsArtifact(override val outputFile: File, val jsProgram: JsProgram) : Js()

    class JsIrArtifact(override val outputFile: File, val compilerResult: CompilerResult, val pirCompilerResult: CompilerResult?) : Js()

    class JsKlibArtifact(override val outputFile: File, val descriptor: ModuleDescriptor, val library: KotlinLibrary) : Js()

    class Native : ResultingArtifact.Binary<Native>() {
        override val kind: BinaryKind<Native>
            get() = ArtifactKinds.Native
    }

    class KLib : ResultingArtifact.Binary<KLib>() {
        override val kind: BinaryKind<KLib>
            get() = ArtifactKinds.KLib
    }
}
