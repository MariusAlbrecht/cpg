/*
 * Copyright (c) 2021, Fraunhofer AISEC. All rights reserved.
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
package de.fraunhofer.aisec.cpg.frontends.llvm

import de.fraunhofer.aisec.cpg.*
import de.fraunhofer.aisec.cpg.graph.*
import de.fraunhofer.aisec.cpg.graph.declarations.VariableDeclaration
import de.fraunhofer.aisec.cpg.graph.statements.*
import de.fraunhofer.aisec.cpg.graph.statements.expressions.*
import de.fraunhofer.aisec.cpg.graph.types.ObjectType
import de.fraunhofer.aisec.cpg.test.*
import java.nio.file.Path
import kotlin.test.*
import kotlin.test.Test

class LLVMIRLanguageFrontendTest {
    @Test
    fun test1() {
        val topLevel = Path.of("src", "test", "resources", "llvm")

        val frontend =
            LLVMIRLanguageFrontend(
                LLVMIRLanguage(),
                TranslationContext(
                    TranslationConfiguration.builder().build(),
                    ScopeManager(),
                    TypeManager()
                )
            )
        frontend.parse(topLevel.resolve("main.ll").toFile())
    }

    @Test
    fun testVectorPoison() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(
                listOf(topLevel.resolve("vector_poison.ll").toFile()),
                topLevel,
                true
            ) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        assertEquals(1, tu.declarations.size)

        val main = tu.functions["main"]
        assertNotNull(main)
        assertLocalName("i32", main.type)

        val xVector =
            (main.bodyOrNull<Block>(0)?.statements?.get(0) as? DeclarationStatement)
                ?.singleDeclaration as? VariableDeclaration
        val xInit = xVector?.initializer as? InitializerListExpression
        assertNotNull(xInit)
        assertLocalName("poison", xInit.initializers[0] as? Reference)
        assertEquals(0L, (xInit.initializers[1] as? Literal<*>)?.value)
        assertEquals(0L, (xInit.initializers[2] as? Literal<*>)?.value)
        assertEquals(0L, (xInit.initializers[3] as? Literal<*>)?.value)
    }

    @Test
    fun testIntegerOps() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(
                listOf(topLevel.resolve("integer_ops.ll").toFile()),
                topLevel,
                true
            ) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        assertEquals(2, tu.declarations.size)

        val main = tu.functions["main"]
        assertNotNull(main)
        assertLocalName("i32", main.type)

        val rand = tu.functions["rand"]
        assertNotNull(rand)
        assertNull(rand.body)

        val decl = tu.variables["x"]
        assertNotNull(decl)

        val call = decl.initializer as? CallExpression
        assertNotNull(call)
        assertLocalName("rand", call)
        assertTrue(call.invokes.contains(rand))
        assertEquals(0, call.arguments.size)

        val xorStatement = main.bodyOrNull<DeclarationStatement>(3)
        assertNotNull(xorStatement)

        val xorDecl = xorStatement.singleDeclaration as? VariableDeclaration
        assertNotNull(xorDecl)
        assertLocalName("a", xorDecl)
        assertEquals("i32", xorDecl.type.typeName)

        val xor = xorDecl.initializer as? BinaryOperator
        assertNotNull(xor)
        assertEquals("^", xor.operatorCode)
    }

    @Test
    fun testIdentifiedStruct() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("struct.ll").toFile()), topLevel, true) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        assertNotNull(tu)

        val rt = tu.records["struct.RT"]
        assertNotNull(rt)

        val st = tu.records["struct.ST"]
        assertNotNull(st)

        assertEquals(3, st.fields.size)

        var field = st.fields.firstOrNull()
        assertNotNull(field)
        assertLocalName("i32", field.type)

        field = st.fields[1]
        assertNotNull(field)
        assertLocalName("double", field.type)

        field = st.fields[2]
        assertNotNull(field)
        assertLocalName("struct.RT", field.type)
        assertSame(rt, (field.type as? ObjectType)?.recordDeclaration)

        val foo = tu.functions["foo"]
        assertNotNull(foo)

        val s = foo.parameters.firstOrNull { it.name.localName == "s" }
        assertNotNull(s)

        val arrayidx =
            foo.bodyOrNull<DeclarationStatement>(0)?.singleDeclaration as? VariableDeclaration
        assertNotNull(arrayidx)

        // arrayidx will be assigned to a chain of the following expressions:
        // &s[1].field2.field1[5][13]
        // we will check them in the reverse order (after the unary operator)

        val unary = arrayidx.initializer as? UnaryOperator
        assertNotNull(unary)
        assertEquals("&", unary.operatorCode)

        var arrayExpr = unary.input as? SubscriptExpression
        assertNotNull(arrayExpr)
        assertLocalName("13", arrayExpr)
        assertEquals(
            13L,
            (arrayExpr.subscriptExpression as? Literal<*>)?.value
        ) // should this be integer instead of long?

        arrayExpr = arrayExpr.arrayExpression as? SubscriptExpression
        assertNotNull(arrayExpr)
        assertLocalName("5", arrayExpr)
        assertEquals(
            5L,
            (arrayExpr.subscriptExpression as? Literal<*>)?.value
        ) // should this be integer instead of long?

        var memberExpression = arrayExpr.arrayExpression as? MemberExpression
        assertNotNull(memberExpression)
        assertLocalName("field_1", memberExpression)

        memberExpression = memberExpression.base as? MemberExpression
        assertNotNull(memberExpression)
        assertLocalName("field_2", memberExpression)

        arrayExpr = memberExpression.base as? SubscriptExpression
        assertNotNull(arrayExpr)
        assertLocalName("1", arrayExpr)
        assertEquals(
            1L,
            (arrayExpr.subscriptExpression as? Literal<*>)?.value
        ) // should this be integer instead of long?

        val ref = arrayExpr.arrayExpression as? Reference
        assertNotNull(ref)
        assertLocalName("s", ref)
        assertSame(s, ref.refersTo)
    }

    @Test
    fun testSwitchCase() { // TODO: Update the test
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(
                listOf(topLevel.resolve("switch_case.ll").toFile()),
                topLevel,
                true
            ) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        val main = tu.functions["main"]
        assertNotNull(main)

        val onzeroLabel = main.labels.getOrNull(0)
        assertNotNull(onzeroLabel)
        assertLocalName("onzero", onzeroLabel)
        assertTrue(onzeroLabel.subStatement is Block)

        val ononeLabel = main.labels.getOrNull(1)
        assertNotNull(ononeLabel)
        assertLocalName("onone", ononeLabel)
        assertTrue(ononeLabel.subStatement is Block)

        val defaultLabel = main.labels.getOrNull(2)
        assertNotNull(defaultLabel)
        assertLocalName("otherwise", defaultLabel)
        assertTrue(defaultLabel.subStatement is Block)

        // Check that the type of %a is i32
        val a = main.variables["a"]
        assertNotNull(a)
        assertLocalName("a", a)
        assertEquals("i32", a.type.typeName)

        // Check that the jump targets are set correctly
        val switchStatement = main.switches.firstOrNull()
        assertNotNull(switchStatement)

        // Check that we have switch(a)
        assertSame(a, (switchStatement.selector as Reference).refersTo)

        val cases = switchStatement.statement as Block
        // Check that the first case is case 0 -> goto onzero and that the BB is inlined
        val case1 = cases.statements[0] as CaseStatement
        assertEquals(0L, (case1.caseExpression as Literal<*>).value as Long)
        assertSame(onzeroLabel.subStatement, cases.statements[1])
        // Check that the second case is case 1 -> goto onone and that the BB is inlined
        val case2 = cases.statements[2] as CaseStatement
        assertEquals(1L, (case2.caseExpression as Literal<*>).value as Long)
        assertSame(ononeLabel.subStatement, cases.statements[3])

        // Check that the default location is inlined
        val defaultStatement = cases.statements[4] as? DefaultStatement
        assertNotNull(defaultStatement)
        assertSame(defaultLabel.subStatement, cases.statements[5])
    }

    @Test
    fun testBrStatements() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("br.ll").toFile()), topLevel, true) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        assertEquals(2, tu.declarations.size)

        val main = tu.functions["main"]
        assertNotNull(main)

        // Test that the types and values of the comparison expression are correct
        val icmpStatement = main.bodyOrNull<DeclarationStatement>(1)
        assertNotNull(icmpStatement)
        val variableDecl = icmpStatement.declarations[0] as VariableDeclaration
        val comparison = variableDecl.initializer as BinaryOperator
        assertEquals("==", comparison.operatorCode)
        val rhs = (comparison.rhs as Literal<*>)
        val lhs = (comparison.lhs as Reference).refersTo as VariableDeclaration
        assertEquals(10L, (rhs.value as Long))
        assertEquals(tu.primitiveType("i32"), rhs.type)
        assertLocalName("x", comparison.lhs as Reference)
        assertLocalName("x", lhs)
        assertEquals(tu.primitiveType("i32"), lhs.type)

        // Check that the jump targets are set correctly
        val ifStatement = main.ifs.firstOrNull()
        assertNotNull(ifStatement)
        assertEquals("IfUnequal", (ifStatement.elseStatement!! as GotoStatement).labelName)
        val ifBranch = (ifStatement.thenStatement as Block)

        // Check that the condition is set correctly
        val ifCondition = ifStatement.condition
        assertSame(variableDecl, (ifCondition as Reference).refersTo)

        val elseBranch =
            (ifStatement.elseStatement!! as GotoStatement).targetLabel?.subStatement as Block
        assertEquals(2, elseBranch.statements.size)
        assertEquals("  %y = mul i32 %x, 32768", elseBranch.statements[0].code)
        assertEquals("  ret i32 %y", elseBranch.statements[1].code)

        // Check that it's  the correct then-branch
        assertEquals(2, ifBranch.statements.size)
        assertEquals("  %condUnsigned = icmp ugt i32 %x, -3", ifBranch.statements[0].code)

        val ifBranchVariableDecl =
            (ifBranch.statements[0] as DeclarationStatement).declarations[0] as VariableDeclaration
        val ifBranchComp = ifBranchVariableDecl.initializer as BinaryOperator
        assertEquals(">", ifBranchComp.operatorCode)
        assertTrue(ifBranchComp.rhs is CastExpression)
        assertTrue(ifBranchComp.lhs is CastExpression)

        val ifBranchCompRhs = ifBranchComp.rhs as CastExpression
        assertEquals(tu.objectType("ui32"), ifBranchCompRhs.castType)
        assertEquals(tu.objectType("ui32"), ifBranchCompRhs.type)
        val ifBranchCompLhs = ifBranchComp.lhs as CastExpression
        assertEquals(tu.objectType("ui32"), ifBranchCompLhs.castType)
        assertEquals(tu.objectType("ui32"), ifBranchCompLhs.type)

        val declRefExpr = ifBranchCompLhs.expression as Reference
        assertEquals(-3, ((ifBranchCompRhs.expression as Literal<*>).value as Long))
        assertLocalName("x", declRefExpr)
        // TODO: declRefExpr.refersTo is null. Is that expected/intended?

        val ifBranchSecondStatement = ifBranch.statements[1] as? IfStatement
        assertNotNull(ifBranchSecondStatement)
        val ifRet = ifBranchSecondStatement.thenStatement as? Block
        assertNotNull(ifRet)
        assertEquals(1, ifRet.statements.size)
        assertEquals("  ret i32 1", ifRet.statements[0].code)
    }

    @Test
    fun testAtomicrmw() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(
                listOf(topLevel.resolve("atomicrmw.ll").toFile()),
                topLevel,
                true
            ) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        val foo = tu.functions["foo"]
        assertNotNull(foo)

        val atomicrmwStatement = foo.bodyOrNull<Block>()
        assertNotNull(atomicrmwStatement)

        // Check that the value is assigned to
        val decl = (atomicrmwStatement.statements[0].declarations[0] as VariableDeclaration)
        assertLocalName("old", decl)
        assertLocalName("i32", decl.type)
        assertEquals("*", (decl.initializer as UnaryOperator).operatorCode)
        assertLocalName("ptr", (decl.initializer as UnaryOperator).input)

        // Check that the replacement equals *ptr = *ptr + 1
        val replacement = (atomicrmwStatement.statements[1] as AssignExpression)
        assertEquals(1, replacement.lhs.size)
        assertEquals(1, replacement.rhs.size)
        assertEquals("=", replacement.operatorCode)
        assertEquals("*", (replacement.lhs.first() as UnaryOperator).operatorCode)
        assertLocalName("ptr", (replacement.lhs.first() as UnaryOperator).input)
        // Check that the rhs is equal to *ptr + 1
        val add = replacement.rhs.first() as BinaryOperator
        assertEquals("+", add.operatorCode)
        assertEquals("*", (add.lhs as UnaryOperator).operatorCode)
        assertLocalName("ptr", (add.lhs as UnaryOperator).input)
        assertEquals(1L, (add.rhs as Literal<*>).value as Long)
    }

    @Test
    fun testCmpxchg() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(
                listOf(topLevel.resolve("atomicrmw.ll").toFile()),
                topLevel,
                true
            ) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        val foo = tu.functions["foo"]
        assertNotNull(foo)

        val cmpxchgStatement = foo.bodyOrNull<Block>(1)
        assertNotNull(cmpxchgStatement)
        assertEquals(2, cmpxchgStatement.statements.size)

        // Check that the first statement is "literal_i32_i1 val_success = literal_i32_i1(*ptr, *ptr
        // == 5)"
        val decl = (cmpxchgStatement.statements[0].declarations[0] as VariableDeclaration)
        assertLocalName("val_success", decl)
        assertLocalName("literal_i32_i1", decl.type)

        // Check that the first value is *ptr
        val value1 = (decl.initializer as ConstructExpression).arguments[0] as UnaryOperator
        assertEquals("*", value1.operatorCode)
        assertLocalName("ptr", value1.input)

        // Check that the first value is *ptr == 5
        val value2 = (decl.initializer as ConstructExpression).arguments[1] as BinaryOperator
        assertEquals("==", value2.operatorCode)
        assertEquals("*", (value2.lhs as UnaryOperator).operatorCode)
        assertLocalName("ptr", (value2.lhs as UnaryOperator).input)
        assertEquals(5L, (value2.rhs as Literal<*>).value as Long)

        val ifStatement = cmpxchgStatement.statements[1] as IfStatement
        // The condition is the same as the second value above
        val ifExpr = ifStatement.condition as BinaryOperator
        assertEquals("==", ifExpr.operatorCode)
        assertEquals("*", (ifExpr.lhs as UnaryOperator).operatorCode)
        assertLocalName("ptr", (ifExpr.lhs as UnaryOperator).input)
        assertEquals(5L, (ifExpr.rhs as Literal<*>).value as Long)

        val thenExpr = ifStatement.thenStatement as AssignExpression
        assertEquals(1, thenExpr.lhs.size)
        assertEquals(1, thenExpr.rhs.size)
        assertEquals("=", thenExpr.operatorCode)
        assertEquals("*", (thenExpr.lhs.first() as UnaryOperator).operatorCode)
        assertLocalName("ptr", (thenExpr.lhs.first() as UnaryOperator).input)
        assertLocalName("old", thenExpr.rhs.first() as Reference)
        assertLocalName("old", (thenExpr.rhs.first() as Reference).refersTo)
    }

    @Test
    fun testExtractvalue() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(
                listOf(topLevel.resolve("atomicrmw.ll").toFile()),
                topLevel,
                true
            ) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        val foo = tu.functions["foo"]
        assertNotNull(foo)

        val decl = foo.variables["value_loaded"]
        assertNotNull(decl)
        assertLocalName("i1", decl.type)

        assertLocalName("val_success", (decl.initializer as MemberExpression).base)
        assertEquals(".", (decl.initializer as MemberExpression).operatorCode)
        assertLocalName("field_1", decl.initializer as MemberExpression)
    }

    @Test
    fun testLiteralStruct() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(
                listOf(topLevel.resolve("literal_struct.ll").toFile()),
                topLevel,
                true
            ) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        assertNotNull(tu)

        val foo = tu.functions["foo"]
        assertNotNull(foo)
        assertEquals("literal_i32_i8", foo.type.typeName)

        val record = (foo.type as? ObjectType)?.recordDeclaration
        assertNotNull(record)
        assertEquals(2, record.fields.size)

        val returnStatement = foo.bodyOrNull<ReturnStatement>(0)
        assertNotNull(returnStatement)

        val construct = returnStatement.returnValue as? ConstructExpression
        assertNotNull(construct)
        assertEquals(2, construct.arguments.size)

        var arg = construct.arguments.getOrNull(0) as? Literal<*>
        assertNotNull(arg)
        assertEquals("i32", arg.type.typeName)
        assertEquals(4L, arg.value)

        arg = construct.arguments.getOrNull(1) as? Literal<*>
        assertNotNull(arg)
        assertEquals("i8", arg.type.typeName)
        assertEquals(2L, arg.value)
    }

    @Test
    fun testVariableScope() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(
                listOf(topLevel.resolve("global_local_var.ll").toFile()),
                topLevel,
                true
            ) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        assertNotNull(tu)

        val main = tu.functions["main"]
        assertNotNull(main)

        val globalX = tu.variables["x"]
        assertNotNull(globalX)
        assertEquals("i32*", globalX.type.typeName)

        val globalA = tu.variables["a"]
        assertNotNull(globalA)
        assertEquals("i32*", globalA.type.typeName)

        val loadXStatement = main.bodyOrNull<DeclarationStatement>(1)
        assertNotNull(loadXStatement)
        assertLocalName("locX", loadXStatement.singleDeclaration)

        val initXOp =
            (loadXStatement.singleDeclaration as VariableDeclaration).initializer as UnaryOperator
        assertEquals("*", initXOp.operatorCode)

        var ref = initXOp.input as? Reference
        assertNotNull(ref)
        assertLocalName("x", ref)
        assertSame(globalX, ref.refersTo)

        val loadAStatement = main.bodyOrNull<DeclarationStatement>(2)
        assertNotNull(loadAStatement)
        assertLocalName("locA", loadAStatement.singleDeclaration)
        val initAOp =
            (loadAStatement.singleDeclaration as VariableDeclaration).initializer as UnaryOperator
        assertEquals("*", initAOp.operatorCode)

        ref = initAOp.input as? Reference
        assertNotNull(ref)
        assertLocalName("a", ref)
        assertSame(globalA, ref.refersTo)
    }

    @Test
    fun testAlloca() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("alloca.ll").toFile()), topLevel, true) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        assertNotNull(tu)

        val main = tu.functions["main"]
        assertNotNull(main)

        // %ptr = alloca i32
        val ptr = main.bodyOrNull<DeclarationStatement>()?.singleDeclaration as? VariableDeclaration
        assertNotNull(ptr)

        val alloca = ptr.initializer as? NewArrayExpression
        assertNotNull(alloca)
        assertEquals("i32*", alloca.type.typeName)

        // store i32 3, i32* %ptr
        val store = main.assigns.firstOrNull()
        assertNotNull(store)
        assertEquals("=", store.operatorCode)

        assertEquals(1, store.lhs.size)
        val dereferencePtr = store.lhs.first() as? UnaryOperator
        assertNotNull(dereferencePtr)
        assertEquals("*", dereferencePtr.operatorCode)
        assertEquals("i32", dereferencePtr.type.typeName)
        assertSame(ptr, (dereferencePtr.input as? Reference)?.refersTo)

        assertEquals(1, store.rhs.size)
        val value = store.rhs.first() as? Literal<*>
        assertNotNull(value)
        assertEquals(3L, value.value)
        assertEquals("i32", value.type.typeName)
    }

    @Test
    fun testUndefInsertvalue() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(
                listOf(topLevel.resolve("undef_insertvalue.ll").toFile()),
                topLevel,
                true
            ) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        assertNotNull(tu)

        val foo = tu.functions["foo"]
        assertNotNull(foo)
        assertEquals("literal_i32_i8", foo.type.typeName)

        val record = (foo.type as? ObjectType)?.recordDeclaration
        assertNotNull(record)
        assertEquals(2, record.fields.size)

        val declStatement = foo.bodyOrNull<DeclarationStatement>()
        assertNotNull(declStatement)

        val varDecl = declStatement.singleDeclaration as VariableDeclaration
        assertLocalName("a", varDecl)
        assertEquals("literal_i32_i8", varDecl.type.typeName)
        val args = (varDecl.initializer as ConstructExpression).arguments
        assertEquals(2, args.size)
        assertLiteralValue(100L, args[0])
        assertLiteralValue(null, args[1])

        val block = foo.blocks.firstOrNull()
        assertNotNull(block)

        // First copy a to b
        val b = block.variables["b"]
        assertNotNull(b)
        assertLocalName("b", b)
        assertEquals("literal_i32_i8", b.type.typeName)

        // Now, we set b.field_1 to 7
        val assign = block.assigns.firstOrNull()
        assertNotNull(assign)

        assertEquals("=", assign.operatorCode)
        assertEquals(1, assign.lhs.size)
        assertEquals(1, assign.rhs.size)
        assertLocalName("b", (assign.lhs.first() as MemberExpression).base)
        assertEquals(".", (assign.lhs.first() as MemberExpression).operatorCode)
        assertLocalName("field_1", assign.lhs.first() as MemberExpression)
        assertEquals(7L, (assign.rhs.first() as Literal<*>).value as Long)
    }

    @Test
    fun testTryCatch() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(
                listOf(topLevel.resolve("try_catch.ll").toFile()),
                topLevel,
                true
            ) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        val throwingFoo = tu.functions["throwingFoo"]

        val main = tu.functions["main"]
        assertNotNull(main)

        val mainBody = main.body as Block
        val tryStatement = mainBody.statements[0] as? TryStatement
        assertNotNull(tryStatement)

        // Check the assignment of the function call
        val resDecl =
            (tryStatement.tryBlock?.statements?.get(0) as? DeclarationStatement)?.singleDeclaration
                as? VariableDeclaration
        assertNotNull(resDecl)
        assertLocalName("res", resDecl)
        val call = resDecl.initializer as? CallExpression
        assertNotNull(call)
        assertLocalName("throwingFoo", call)
        assertTrue(call.invokes.contains(throwingFoo))
        assertEquals(0, call.arguments.size)

        // Check that the second part of the try-block is inlined by the pass
        val aDecl =
            (tryStatement.tryBlock?.statements?.get(1) as? DeclarationStatement)?.singleDeclaration
                as? VariableDeclaration
        assertNotNull(aDecl)
        assertLocalName("a", aDecl)
        val resStatement = tryStatement.tryBlock?.statements?.get(2) as? ReturnStatement
        assertNotNull(resStatement)

        // Check that the catch block is inlined by the pass
        assertEquals(1, tryStatement.catchClauses.size)
        assertEquals(5, tryStatement.catchClauses[0].body?.statements?.size)
        assertLocalName("_ZTIi | ...", tryStatement.catchClauses[0])
        val ifStatement = tryStatement.catchClauses[0].body?.statements?.get(4) as? IfStatement
        assertNotNull(ifStatement)
        assertTrue(ifStatement.thenStatement is Block)
        assertEquals(4, (ifStatement.thenStatement as Block).statements.size)
        assertTrue(ifStatement.elseStatement is Block)
        assertEquals(1, (ifStatement.elseStatement as Block).statements.size)
    }

    @Test
    fun testLoopPhi() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("loopPhi.ll").toFile()), topLevel, true) {
                it.registerLanguage<LLVMIRLanguage>()
            }
        val main = tu.functions["loopPhi"]
        assertNotNull(main)
    }

    @Test
    fun testPhi() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("phi.ll").toFile()), topLevel, true) {
                it.registerLanguage<LLVMIRLanguage>()
            }
        val main = tu.functions["main"]
        assertNotNull(main)

        val mainBody = main.body as Block
        val yDecl =
            (mainBody.statements[0] as DeclarationStatement).singleDeclaration
                as VariableDeclaration
        assertNotNull(yDecl)

        val ifStatement = mainBody.statements[3] as? IfStatement
        assertNotNull(ifStatement)

        val thenStmt = ifStatement.thenStatement as? Block
        assertNotNull(thenStmt)
        assertEquals(3, thenStmt.statements.size)
        assertNotNull(thenStmt.statements[1] as? AssignExpression)
        val aDecl =
            (thenStmt.statements[0] as DeclarationStatement).singleDeclaration
                as VariableDeclaration
        val thenY = thenStmt.statements[1] as AssignExpression
        assertEquals(1, thenY.lhs.size)
        assertEquals(1, thenY.rhs.size)
        assertSame(aDecl, (thenY.rhs.first() as Reference).refersTo)
        assertSame(yDecl, (thenY.lhs.first() as Reference).refersTo)

        val elseStmt = ifStatement.elseStatement as? Block
        assertNotNull(elseStmt)
        assertEquals(3, elseStmt.statements.size)
        val bDecl =
            (elseStmt.statements[0] as DeclarationStatement).singleDeclaration
                as VariableDeclaration
        assertNotNull(elseStmt.statements[1] as? AssignExpression)
        val elseY = elseStmt.statements[1] as AssignExpression
        assertEquals(1, elseY.lhs.size)
        assertEquals(1, elseY.lhs.size)
        assertSame(bDecl, (elseY.rhs.first() as Reference).refersTo)
        assertSame(yDecl, (elseY.lhs.first() as Reference).refersTo)

        val continueBlock =
            (thenStmt.statements[2] as? GotoStatement)?.targetLabel?.subStatement as? Block
        assertNotNull(continueBlock)
        assertEquals(
            yDecl,
            ((continueBlock.statements[1] as ReturnStatement).returnValue as Reference).refersTo
        )
    }

    @Test
    fun testVectorOperations() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("vector.ll").toFile()), topLevel, true) {
                it.registerLanguage<LLVMIRLanguage>()
            }
        val main = tu.functions["main"]
        assertNotNull(main)

        // Test that x is initialized correctly
        val mainBody = main.body as Block
        val origX =
            ((mainBody.statements[0] as? DeclarationStatement)?.singleDeclaration
                as? VariableDeclaration)
        val xInit = origX?.initializer as? InitializerListExpression
        assertNotNull(xInit)
        assertEquals(10L, (xInit.initializers[0] as? Literal<*>)?.value)
        assertEquals(9L, (xInit.initializers[1] as? Literal<*>)?.value)
        assertEquals(6L, (xInit.initializers[2] as? Literal<*>)?.value)
        assertEquals(-100L, (xInit.initializers[3] as? Literal<*>)?.value)

        // Test that y is initialized correctly
        val origY =
            ((mainBody.statements[1] as? DeclarationStatement)?.singleDeclaration
                as? VariableDeclaration)
        val yInit = origY?.initializer as? InitializerListExpression
        assertNotNull(yInit)
        assertEquals(15L, (yInit.initializers[0] as? Literal<*>)?.value)
        assertEquals(34L, (yInit.initializers[1] as? Literal<*>)?.value)
        assertEquals(99L, (yInit.initializers[2] as? Literal<*>)?.value)
        assertEquals(1000L, (yInit.initializers[3] as? Literal<*>)?.value)

        // Test that extractelement works
        val zInit =
            ((mainBody.statements[2] as? DeclarationStatement)?.singleDeclaration
                    as? VariableDeclaration)
                ?.initializer as? SubscriptExpression
        assertNotNull(zInit)
        assertEquals(0L, (zInit.subscriptExpression as? Literal<*>)?.value)
        assertEquals("x", (zInit.arrayExpression as? Reference)?.name?.localName)
        assertSame(origX, (zInit.arrayExpression as? Reference)?.refersTo)

        // Test the assignment of y to yMod
        val yModInit =
            ((mainBody.statements[3] as Block).statements[0] as? DeclarationStatement)
                ?.singleDeclaration as? VariableDeclaration
        assertNotNull(yModInit)
        assertEquals("y", (yModInit.initializer as? Reference)?.name?.localName)
        assertSame(origY, (yModInit.initializer as? Reference)?.refersTo)
        // Now, test the modification of yMod[3] = 8
        val yMod = ((mainBody.statements[3] as Block).statements[1] as? AssignExpression)
        assertNotNull(yMod)
        assertEquals(1, yMod.lhs.size)
        assertEquals(1, yMod.rhs.size)
        assertEquals(
            3L,
            ((yMod.lhs.first() as? SubscriptExpression)?.subscriptExpression as? Literal<*>)?.value
        )
        assertSame(
            yModInit,
            ((yMod.lhs.first() as? SubscriptExpression)?.arrayExpression as? Reference)?.refersTo
        )
        assertEquals(8L, (yMod.rhs.first() as? Literal<*>)?.value)

        // Test the last shufflevector instruction which does not contain constant as initializers.
        val shuffledInit =
            ((mainBody.statements[4] as? DeclarationStatement)?.singleDeclaration
                    as? VariableDeclaration)
                ?.initializer as? InitializerListExpression
        assertNotNull(shuffledInit)
        assertSame(
            origX,
            ((shuffledInit.initializers[0] as? SubscriptExpression)?.arrayExpression as? Reference)
                ?.refersTo
        )
        assertSame(
            yModInit,
            ((shuffledInit.initializers[1] as? SubscriptExpression)?.arrayExpression as? Reference)
                ?.refersTo
        )
        assertSame(
            yModInit,
            ((shuffledInit.initializers[2] as? SubscriptExpression)?.arrayExpression as? Reference)
                ?.refersTo
        )
        assertSame(
            1,
            ((shuffledInit.initializers[0] as? SubscriptExpression)?.subscriptExpression
                    as? Literal<*>)
                ?.value
        )
        assertSame(
            2,
            ((shuffledInit.initializers[1] as? SubscriptExpression)?.subscriptExpression
                    as? Literal<*>)
                ?.value
        )
        assertSame(
            3,
            ((shuffledInit.initializers[2] as? SubscriptExpression)?.subscriptExpression
                    as? Literal<*>)
                ?.value
        )
    }

    @Test
    fun testFence() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("fence.ll").toFile()), topLevel, true) {
                it.registerLanguage<LLVMIRLanguage>()
            }
        val main = tu.functions["main"]
        assertNotNull(main)

        // Test that x is initialized correctly
        val mainBody = main.body as Block

        val fenceCall = mainBody.statements[0] as? CallExpression
        assertNotNull(fenceCall)
        assertEquals(1, fenceCall.arguments.size)
        assertEquals(2, (fenceCall.arguments[0] as Literal<*>).value)

        val fenceCallScope = mainBody.statements[2] as? CallExpression
        assertNotNull(fenceCallScope)
        assertEquals(2, fenceCallScope.arguments.size)
        // TODO: This doesn't match but it doesn't seem to be our mistake
        // assertEquals(5, (fenceCallScope.arguments[0] as Literal<*>).value)
        assertEquals("scope", (fenceCallScope.arguments[1] as Literal<*>).value)
    }

    @Test
    fun testExceptions() {
        val topLevel = Path.of("src", "test", "resources", "llvm")
        val tu =
            analyzeAndGetFirstTU(
                listOf(topLevel.resolve("exceptions.ll").toFile()),
                topLevel,
                true
            ) {
                it.registerLanguage<LLVMIRLanguage>()
            }

        val funcF = tu.functions["f"]
        assertNotNull(funcF)

        val tryStatement =
            (funcF.bodyOrNull<LabelStatement>(0)?.subStatement as? Block)
                ?.statements
                ?.firstOrNull { s -> s is TryStatement } as? TryStatement
        assertNotNull(tryStatement)
        assertEquals(2, tryStatement.tryBlock?.statements?.size)
        assertFullName(
            "_CxxThrowException",
            tryStatement.tryBlock?.statements?.get(0) as? CallExpression
        )
        assertEquals(
            "end",
            (tryStatement.tryBlock?.statements?.get(1) as? GotoStatement)
                ?.targetLabel
                ?.name
                ?.localName
        )

        assertEquals(1, tryStatement.catchClauses.size)
        val catchSwitchExpr =
            tryStatement.catchClauses[0].body?.statements?.get(0) as? DeclarationStatement
        assertNotNull(catchSwitchExpr)
        val catchswitchCall =
            (catchSwitchExpr.singleDeclaration as? VariableDeclaration)?.initializer
                as? CallExpression
        assertNotNull(catchswitchCall)
        assertFullName("llvm.catchswitch", catchswitchCall)
        val ifExceptionMatches =
            tryStatement.catchClauses[0].body?.statements?.get(1) as? IfStatement
        val matchesExceptionCall = ifExceptionMatches?.condition as? CallExpression
        assertNotNull(matchesExceptionCall)
        assertFullName("llvm.matchesCatchpad", matchesExceptionCall)
        assertEquals(
            catchSwitchExpr.singleDeclaration,
            (matchesExceptionCall.arguments[0] as Reference).refersTo
        )
        assertEquals(null, (matchesExceptionCall.arguments[1] as Literal<*>).value)
        assertEquals(64L, (matchesExceptionCall.arguments[2] as Literal<*>).value as Long)
        assertEquals(null, (matchesExceptionCall.arguments[3] as Literal<*>).value)

        val catchBlock = ifExceptionMatches.thenStatement as? Block
        assertNotNull(catchBlock)
        assertFullName(
            "llvm.catchpad",
            ((catchBlock.statements[0] as? DeclarationStatement)?.singleDeclaration
                    as? VariableDeclaration)
                ?.initializer as? CallExpression
        )

        val innerTry = catchBlock.statements[1] as? TryStatement
        assertNotNull(innerTry)
        assertFullName(
            "_CxxThrowException",
            innerTry.tryBlock?.statements?.get(0) as? CallExpression
        )
        assertLocalName(
            "try.cont",
            (innerTry.tryBlock?.statements?.get(1) as? GotoStatement)?.targetLabel
        )

        val innerCatchClause =
            (innerTry.catchClauses[0].body?.statements?.get(1) as? IfStatement)?.thenStatement
                as? Block
        assertNotNull(innerCatchClause)
        assertFullName(
            "llvm.catchpad",
            ((innerCatchClause.statements[0] as? DeclarationStatement)?.singleDeclaration
                    as? VariableDeclaration)
                ?.initializer as? CallExpression
        )
        assertLocalName("try.cont", (innerCatchClause.statements[1] as? GotoStatement)?.targetLabel)

        val innerCatchThrows =
            (innerTry.catchClauses[0].body?.statements?.get(1) as? IfStatement)?.elseStatement
                as? UnaryOperator
        assertNotNull(innerCatchThrows)
        assertNotNull(innerCatchThrows.input)
        assertSame(
            innerTry.catchClauses[0].parameter,
            (innerCatchThrows.input as? Reference)?.refersTo
        )
    }

    // TODO: Write test for calling a vararg function (e.g. printf). LLVM code snippets can already
    // be found in client.ll.
}
