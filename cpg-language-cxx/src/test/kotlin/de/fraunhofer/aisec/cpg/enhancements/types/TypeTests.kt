/*
 * Copyright (c) 2019, Fraunhofer AISEC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *                    $$$$$$\  $$$$$$$\   $$$$$$\
 *                   $$  __$$\ $$  __$$\ $$  __$$\
 *                   $$ /  \__|$$ |  $$ |$$ /  \__|
 *                   $$ |      $$$$$$$  |$$ |$$$$\
 *                   $$ |      $$  ____/ $$ |\_$$ |
 *                   $$ |  $$\ $$ |      $$ |  $$ |
 *                   \$$$$$   |$$ |      \$$$$$   |
 *                    \______/ \__|       \______/
 *
 */
package de.fraunhofer.aisec.cpg.enhancements.types

import de.fraunhofer.aisec.cpg.*
import de.fraunhofer.aisec.cpg.TestUtils.analyze
import de.fraunhofer.aisec.cpg.TestUtils.analyzeAndGetFirstTU
import de.fraunhofer.aisec.cpg.TestUtils.findByUniqueName
import de.fraunhofer.aisec.cpg.frontends.cxx.CPPLanguage
import de.fraunhofer.aisec.cpg.frontends.cxx.CXXLanguageFrontend
import de.fraunhofer.aisec.cpg.graph.*
import de.fraunhofer.aisec.cpg.graph.types.*
import java.nio.file.Path
import java.util.*
import kotlin.test.*

internal class TypeTests : BaseTest() {
    @Test
    fun reference() {
        val objectType: Type = IntegerType("int", 32, CPPLanguage(), NumericType.Modifier.SIGNED)
        val pointerType: Type = PointerType(objectType, PointerType.PointerOrigin.POINTER)
        val unknownType: Type = UnknownType.getUnknownType(CPPLanguage())
        val incompleteType: Type = IncompleteType()
        val parameterList =
            listOf<Type>(IntegerType("int", 32, CPPLanguage(), NumericType.Modifier.SIGNED))
        val functionPointerType: Type =
            FunctionPointerType(parameterList, CPPLanguage(), IncompleteType())

        // Test 1: ObjectType becomes PointerType containing the original ObjectType as ElementType
        assertEquals(
            PointerType(objectType, PointerType.PointerOrigin.POINTER),
            objectType.reference(PointerType.PointerOrigin.POINTER)
        )

        // Test 2: Existing PointerType adds one level more of references as ElementType
        assertEquals(
            PointerType(pointerType, PointerType.PointerOrigin.POINTER),
            pointerType.reference(PointerType.PointerOrigin.POINTER)
        )

        // Test 3: UnknownType cannot be referenced
        assertEquals(unknownType, unknownType.reference(null))

        // Test 4: IncompleteType can be refereced e.g. void*
        assertEquals(
            PointerType(incompleteType, PointerType.PointerOrigin.POINTER),
            incompleteType.reference(PointerType.PointerOrigin.POINTER)
        )

        // Test 5: Create reference to function pointer = pointer to function pointer
        assertEquals(
            PointerType(functionPointerType, PointerType.PointerOrigin.POINTER),
            functionPointerType.reference(PointerType.PointerOrigin.POINTER)
        )
    }

    @Test
    fun dereference() {
        val objectType: Type = IntegerType("int", 32, CPPLanguage(), NumericType.Modifier.SIGNED)
        val pointerType: Type = PointerType(objectType, PointerType.PointerOrigin.POINTER)
        val unknownType: Type = UnknownType.getUnknownType(CPPLanguage())
        val incompleteType: Type = IncompleteType()
        val parameterList =
            listOf<Type>(IntegerType("int", 32, CPPLanguage(), NumericType.Modifier.SIGNED))
        val functionPointerType: Type =
            FunctionPointerType(parameterList, CPPLanguage(), IncompleteType())

        // Test 1: Dereferencing an ObjectType results in an UnknownType, since we cannot track the
        // type
        // of the corresponding memory
        assertEquals(UnknownType.getUnknownType(CPPLanguage()), objectType.dereference())

        // Test 2: Dereferencing a PointerType results in the corresponding elementType of the
        // PointerType (can also be another PointerType)
        assertEquals(objectType, pointerType.dereference())

        // Test 3: Dereferecing unknown or incomplete type results in the same type
        assertEquals(unknownType, unknownType.dereference())
        assertEquals(incompleteType, incompleteType.dereference())

        // Test 5: Due to the definition in the C-Standard dereferencing function pointer yields the
        // same function pointer
        assertEquals(functionPointerType, functionPointerType.dereference())
    }

    @Test
    fun createFromCPP() {
        var result: Type

        with(
            CXXLanguageFrontend(
                CPPLanguage(),
                TranslationContext(
                    TranslationConfiguration.builder().build(),
                    ScopeManager(),
                    TypeManager()
                )
            )
        ) {
            // Test 1: Function pointer
            var typeString = "void (*single_param)(int)"
            result = parseType(typeString)
            val parameterList =
                listOf<Type>(IntegerType("int", 32, CPPLanguage(), NumericType.Modifier.SIGNED))
            var expected: Type = FunctionPointerType(parameterList, CPPLanguage(), IncompleteType())
            assertEquals(expected, result)

            // Test 1.1: interleaved brackets in function pointer
            typeString = "void ((*single_param)(int))"
            result = parseType(typeString)
            assertEquals(result, expected)

            // Test 2: Stronger binding of brackets and pointer
            typeString = "char (* const a)[]"
            result = parseType(typeString)
            expected =
                PointerType(
                    PointerType(
                        IntegerType("char", 8, CPPLanguage(), NumericType.Modifier.NOT_APPLICABLE),
                        PointerType.PointerOrigin.ARRAY
                    ),
                    PointerType.PointerOrigin.POINTER
                )
            assertEquals(expected, result)

            // Test 3: Mutable pointer to a mutable char
            typeString = "char *p"
            result = parseType(typeString)
            expected =
                PointerType(
                    IntegerType("char", 8, CPPLanguage(), NumericType.Modifier.NOT_APPLICABLE),
                    PointerType.PointerOrigin.POINTER
                )
            assertEquals(expected, result)

            // Test 3.1: Different Whitespaces
            typeString = "char* p"
            result = parseType(typeString)
            assertEquals(expected, result)

            // Test 3.2: Different Whitespaces
            typeString = "char * p"
            result = parseType(typeString)
            assertEquals(expected, result)

            // Test 4: Mutable pointer to a constant char
            typeString = "const char *p;"
            result = parseType(typeString)
            expected =
                PointerType(
                    IntegerType("char", 8, CPPLanguage(), NumericType.Modifier.NOT_APPLICABLE),
                    PointerType.PointerOrigin.POINTER
                )
            assertEquals(expected, result)

            // Test 5: Constant pointer to a mutable char
            typeString = "char * const p;"
            result = parseType(typeString)
            expected =
                PointerType(
                    IntegerType("char", 8, CPPLanguage(), NumericType.Modifier.NOT_APPLICABLE),
                    PointerType.PointerOrigin.POINTER
                )
            assertEquals(expected, result)

            // Test 6: Constant pointer to a constant char
            typeString = "const char * const p;"
            result = parseType(typeString)
            expected =
                PointerType(
                    IntegerType("char", 8, CPPLanguage(), NumericType.Modifier.NOT_APPLICABLE),
                    PointerType.PointerOrigin.POINTER
                )
            assertEquals(expected, result)

            // Test 7: Array of const pointer to static const char
            typeString = "static const char * const somearray []"
            result = parseType(typeString)
            expected =
                PointerType(
                    PointerType(
                        IntegerType("char", 8, CPPLanguage(), NumericType.Modifier.NOT_APPLICABLE),
                        PointerType.PointerOrigin.POINTER
                    ),
                    PointerType.PointerOrigin.ARRAY
                )
            assertEquals(expected, result)

            // Test 7.1: Array of array of pointer to static const char
            typeString = "static const char * somearray[][]"
            result = parseType(typeString)
            expected =
                PointerType(
                    PointerType(
                        PointerType(
                            IntegerType(
                                "char",
                                8,
                                CPPLanguage(),
                                NumericType.Modifier.NOT_APPLICABLE
                            ),
                            PointerType.PointerOrigin.POINTER
                        ),
                        PointerType.PointerOrigin.ARRAY
                    ),
                    PointerType.PointerOrigin.ARRAY
                )
            assertEquals(expected, result)

            // Test 8: Generics
            typeString = "Array<int> array"
            result = parseType(typeString)
            var generics = mutableListOf<Type>()
            generics.add(IntegerType("int", 32, CPPLanguage(), NumericType.Modifier.SIGNED))
            expected = ObjectType("Array", generics, false, CPPLanguage())
            assertEquals(expected, result)

            // Test 9: Compound Primitive Types
            typeString = "long long int"
            result = parseType(typeString)
            expected = IntegerType("long long int", 64, CPPLanguage(), NumericType.Modifier.SIGNED)
            assertEquals(expected, result)

            // Test 10: Unsigned/Signed Types
            typeString = "unsigned int"
            result = parseType(typeString)
            expected = IntegerType("unsigned int", 32, CPPLanguage(), NumericType.Modifier.UNSIGNED)
            assertEquals(expected, result)
            typeString = "signed int"
            result = parseType(typeString)
            expected = IntegerType("int", 32, CPPLanguage(), NumericType.Modifier.SIGNED)
            assertEquals(expected, result)
            typeString = "A a"
            result = parseType(typeString)
            expected = ObjectType("A", ArrayList(), false, CPPLanguage())
            assertEquals(expected, result)

            // Test 11: Unsigned + const + compound primitive Types
            expected =
                IntegerType(
                    "unsigned long long int",
                    64,
                    CPPLanguage(),
                    NumericType.Modifier.UNSIGNED
                )
            typeString = "const unsigned long long int a = 1"
            result = parseType(typeString)
            assertEquals(expected, result)

            typeString = "unsigned const long long int b = 1"
            result = parseType(typeString)
            assertEquals(expected, result)

            typeString = "unsigned long const long int c = 1"
            result = parseType(typeString)
            assertEquals(expected, result)

            typeString = "unsigned long long const int d = 1"
            result = parseType(typeString)
            assertEquals(expected, result)

            typeString = "unsigned long long int const e = 1"
            result = parseType(typeString)
            assertEquals(expected, result)

            // Test 12: C++ Reference Types
            typeString = "const int& ref = a"
            result = parseType(typeString)
            expected =
                ReferenceType(IntegerType("int", 32, CPPLanguage(), NumericType.Modifier.SIGNED))
            assertEquals(expected, result)

            typeString = "int const &ref2 = a"
            result = parseType(typeString)
            assertEquals(expected, result)

            // Test 13: Elaborated Type in Generics
            result = parseType("Array<struct Node>")
            generics = ArrayList()
            var generic = ObjectType("Node", ArrayList(), false, CPPLanguage())
            generics.add(generic)
            expected = ObjectType("Array", generics, false, CPPLanguage())
            assertEquals(expected, result)

            result = parseType("Array<myclass >")
            generics = ArrayList()
            generic = ObjectType("myclass", ArrayList(), false, CPPLanguage())
            generics.add(generic)
            expected = ObjectType("Array", generics, false, CPPLanguage())
            assertEquals(expected, result)
        }
    }

    /**
     * Test for usage of getTypeStringFromDeclarator to determine function pointer raw type string
     *
     * @throws Exception Any exception thrown during the analysis process
     */
    @Test
    @Throws(Exception::class)
    fun testFunctionPointerTypes() {
        val topLevel = Path.of("src", "test", "resources", "types")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("fptr_type.cpp").toFile()), topLevel, true)
        val noParamType = FunctionPointerType(emptyList(), CPPLanguage(), IncompleteType())
        val oneParamType =
            FunctionPointerType(
                listOf<Type>(IntegerType("int", 32, CPPLanguage(), NumericType.Modifier.SIGNED)),
                CPPLanguage(),
                IncompleteType()
            )
        val twoParamType =
            FunctionPointerType(
                listOf<Type>(
                    IntegerType("int", 32, CPPLanguage(), NumericType.Modifier.SIGNED),
                    IntegerType("unsigned long", 64, CPPLanguage(), NumericType.Modifier.UNSIGNED)
                ),
                CPPLanguage(),
                IntegerType("int", 32, CPPLanguage(), NumericType.Modifier.SIGNED)
            )
        val variables = tu.variables
        val localTwoParam = findByUniqueName(variables, "local_two_param")
        assertNotNull(localTwoParam)
        assertEquals(twoParamType, localTwoParam.type)

        val localOneParam = findByUniqueName(variables, "local_one_param")
        assertNotNull(localOneParam)
        assertEquals(oneParamType, localOneParam.type)

        val globalNoParam = findByUniqueName(variables, "global_no_param")
        assertNotNull(globalNoParam)
        assertEquals(noParamType, globalNoParam.type)

        val globalNoParamVoid = findByUniqueName(variables, "global_no_param_void")
        assertNotNull(globalNoParamVoid)
        assertEquals(noParamType, globalNoParamVoid.type)

        val globalTwoParam = findByUniqueName(variables, "global_two_param")
        assertNotNull(globalTwoParam)
        assertEquals(twoParamType, globalTwoParam.type)

        val globalOneParam = findByUniqueName(variables, "global_one_param")
        assertNotNull(globalOneParam)
        assertEquals(oneParamType, globalOneParam.type)
    }

    @Throws(Exception::class)
    @Test
    fun testCommonTypeTestCpp() {
        with(
            CXXLanguageFrontend(
                CPPLanguage(),
                TranslationContext(
                    TranslationConfiguration.builder().build(),
                    ScopeManager(),
                    TypeManager()
                )
            )
        ) {
            val topLevel =
                Path.of("src", "test", "resources", "compiling", "hierarchy", "multistep")
            val result = analyze("simple_inheritance.cpp", topLevel, true)
            val root = parseType("Root")
            val level0 = parseType("Level0")
            val level1 = parseType("Level1")
            val level1b = parseType("Level1B")
            val level2 = parseType("Level2")
            val unrelated = parseType("Unrelated")
            getCommonTypeTestGeneral(root, level0, level1, level1b, level2, unrelated, result)
        }
    }

    // level2 and level2b have two intersections, both root and level0 -> level0 is lower
    @Throws(Exception::class)
    @Test
    fun testCommonTypeTestCppMultiInheritance() {
        with(
            CXXLanguageFrontend(
                CPPLanguage(),
                TranslationContext(
                    TranslationConfiguration.builder().build(),
                    ScopeManager(),
                    TypeManager()
                )
            )
        ) {
            val topLevel =
                Path.of("src", "test", "resources", "compiling", "hierarchy", "multistep")
            val result = analyze("multi_inheritance.cpp", topLevel, true)

            val root = parseType("Root")
            val level0 = parseType("Level0")
            val level0b = parseType("Level0B")
            val level1 = parseType("Level1")
            val level1b = parseType("Level1B")
            val level1c = parseType("Level1C")
            val level2 = parseType("Level2")
            val level2b = parseType("Level2B")

            val typeManager = result.finalCtx.typeManager
            /*
            Type hierarchy:
                      Root------------
                       |             |
                     Level0  Level0B |
                      / \     /  \   |
                 Level1 Level1B  Level1C
                   |       \       /
                 Level2     Level2B
             */
            // Root is the top, but unrelated to Level0B
            for (t in listOf(root, level0, level1, level1b, level1c, level2, level2b)) {
                assertEquals(Optional.of(t), typeManager.getCommonType(listOf(t), result.finalCtx))
            }
            assertEquals(
                Optional.empty(),
                typeManager.getCommonType(listOf(root, level0b), result.finalCtx)
            )
            for (t in listOf(level0, level1, level2)) {
                assertEquals(
                    Optional.empty(),
                    typeManager.getCommonType(listOf(t, level0b), result.finalCtx)
                )
            }
            assertEquals(
                Optional.of(level0b),
                typeManager.getCommonType(listOf(level1b, level1c), result.finalCtx)
            )
            assertEquals(
                Optional.of(level0),
                typeManager.getCommonType(listOf(level1, level1b, level2, level2b), result.finalCtx)
            )
            assertEquals(
                Optional.of(root),
                typeManager.getCommonType(listOf(level1, level1c), result.finalCtx)
            )

            // level2 and level2b have two intersections, both root and level0 -> level0 is lower
            assertEquals(
                Optional.of(level0),
                typeManager.getCommonType(listOf(level2, level2b), result.finalCtx)
            )
        }
    }

    @Test
    @Throws(Exception::class)
    fun graphTest() {
        val topLevel = Path.of("src", "test", "resources", "types")
        val result = analyze("cpp", topLevel, true)
        val variableDeclarations = result.variables

        // Test PointerType chain with pointer
        val regularInt = findByUniqueName(variableDeclarations, "regularInt")
        val ptr = findByUniqueName(variableDeclarations, "ptr")
        assertTrue(ptr.type is PointerType)
        assertEquals((ptr.type as PointerType).elementType, regularInt.type)

        // Test type Propagation (auto) UnknownType
        val unknown = findByUniqueName(variableDeclarations, "unknown")
        assertEquals(UnknownType.getUnknownType(CPPLanguage()), unknown.type)

        // Test type Propagation auto
        val propagated = findByUniqueName(variableDeclarations, "propagated")
        assertEquals(regularInt.type, propagated.type)
    }

    private fun getCommonTypeTestGeneral(
        root: Type,
        level0: Type,
        level1: Type,
        level1b: Type,
        level2: Type,
        unrelated: Type,
        result: TranslationResult
    ) {
        /*
        Type hierarchy:
                  Root
                   |
                 Level0
                  / \
             Level1 Level1B
               |
             Level2
         */
        val provider = result.finalCtx.scopeManager
        val typeManager = result.finalCtx.typeManager

        // A single type is its own least common ancestor
        for (t in listOf(root, level0, level1, level1b, level2)) {
            assertEquals(Optional.of(t), typeManager.getCommonType(listOf(t), result.finalCtx))
        }

        // Root is the root of all types
        for (t in listOf(level0, level1, level1b, level2)) {
            assertEquals(
                Optional.of(root),
                typeManager.getCommonType(listOf(t, root), result.finalCtx)
            )
        }

        // Level0 is above all types but Root
        for (t in listOf(level1, level1b, level2)) {
            assertEquals(
                Optional.of(level0),
                typeManager.getCommonType(listOf(t, level0), result.finalCtx)
            )
        }

        // Level1 and Level1B have Level0 as common ancestor
        assertEquals(
            Optional.of(level0),
            typeManager.getCommonType(listOf(level1, level1b), result.finalCtx)
        )

        // Level2 and Level1B have Level0 as common ancestor
        assertEquals(
            Optional.of(level0),
            typeManager.getCommonType(listOf(level2, level1b), result.finalCtx)
        )

        // Level1 and Level2 have Level1 as common ancestor
        assertEquals(
            Optional.of(level1),
            typeManager.getCommonType(listOf(level1, level2), result.finalCtx)
        )

        // Check unrelated type behavior: No common root class
        for (t in listOf(root, level0, level1, level1b, level2)) {
            assertEquals(
                Optional.empty(),
                typeManager.getCommonType(listOf(unrelated, t), result.finalCtx)
            )
        }
    }
}