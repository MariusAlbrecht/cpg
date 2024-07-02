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
package de.fraunhofer.aisec.cpg

import de.fraunhofer.aisec.cpg.frontends.*
import de.fraunhofer.aisec.cpg.graph.*
import de.fraunhofer.aisec.cpg.graph.declarations.*
import de.fraunhofer.aisec.cpg.graph.scopes.*
import de.fraunhofer.aisec.cpg.graph.statements.*
import de.fraunhofer.aisec.cpg.graph.statements.expressions.*
import de.fraunhofer.aisec.cpg.graph.types.FunctionPointerType
import de.fraunhofer.aisec.cpg.graph.types.IncompleteType
import de.fraunhofer.aisec.cpg.graph.types.Type
import de.fraunhofer.aisec.cpg.helpers.Util
import de.fraunhofer.aisec.cpg.sarif.PhysicalLocation
import java.util.*
import java.util.function.Predicate
import org.slf4j.LoggerFactory

/**
 * The scope manager builds a multi-tree structure of nodes associated to a scope. These scopes
 * capture the validity of certain (Variable-, Field-, Record-)declarations but are also used to
 * identify outer scopes that should be the target of a jump (continue, break, throw).
 *
 * Language frontends MUST call [enterScope] and [leaveScope] when they encounter nodes that modify
 * the scope and [resetToGlobal] when they first handle a new [TranslationUnitDeclaration].
 * Afterwards the currently valid "stack" of scopes within the tree can be accessed.
 *
 * If a language frontend encounters a [Declaration] node, it MUST call [addDeclaration], rather
 * than adding the declaration to the node itself. This ensures that all declarations are properly
 * registered in the scope map and can be resolved later.
 */
class ScopeManager : ScopeProvider {
    /**
     * A map associating each CPG node with its scope. The key type is intentionally a nullable
     * [Node] because the [GlobalScope] is not associated to a CPG node when it is first created. It
     * is later associated using the [resetToGlobal] function.
     */
    private val scopeMap: MutableMap<Node?, Scope> = IdentityHashMap()

    /** A lookup map for each scope and its associated FQN. */
    private val fqnScopeMap: MutableMap<String, NameScope> = mutableMapOf()

    /** The currently active scope. */
    var currentScope: Scope? = null
        private set

    /** Represents an alias with the name [to] for the particular name [from]. */
    data class Alias(var from: Name, var to: Name)

    /**
     * A cache map of reference tags (computed with [Reference.referenceTag]) and their respective
     * pair of original [Reference] and resolved [ValueDeclaration]. This is used by
     * [resolveReference] as a caching mechanism.
     */
    private val symbolTable = mutableMapOf<ReferenceTag, Pair<Reference, ValueDeclaration>>()

    /** True, if the scope manager is currently in a [BlockScope]. */
    val isInBlock: Boolean
        get() = this.firstScopeOrNull { it is BlockScope } != null
    /** True, if the scope manager is currently in a [FunctionScope]. */
    val isInFunction: Boolean
        get() = this.firstScopeOrNull { it is FunctionScope } != null
    /** True, if the scope manager is currently in a [RecordScope], e.g. a class. */
    val isInRecord: Boolean
        get() = this.firstScopeOrNull { it is RecordScope } != null

    val globalScope: GlobalScope?
        get() = scopeMap[null] as? GlobalScope

    /** The current block, according to the scope that is currently active. */
    val currentBlock: Block?
        get() = this.firstScopeIsInstanceOrNull<BlockScope>()?.astNode as? Block
    /** The current function, according to the scope that is currently active. */
    val currentFunction: FunctionDeclaration?
        get() = this.firstScopeIsInstanceOrNull<FunctionScope>()?.astNode as? FunctionDeclaration

    /**
     * The current method in the active scope tree, this ensures that 'this' keywords are mapped
     * correctly if a method contains a lambda or other types of function declarations
     */
    val currentMethod: MethodDeclaration?
        get() =
            this.firstScopeOrNull { scope: Scope? -> scope?.astNode is MethodDeclaration }?.astNode
                as? MethodDeclaration
    /** The current record, according to the scope that is currently active. */
    val currentRecord: RecordDeclaration?
        get() = this.firstScopeIsInstanceOrNull<RecordScope>()?.astNode as? RecordDeclaration

    val currentTypedefs: Collection<TypedefDeclaration>
        get() = this.getCurrentTypedefs(currentScope)

    val currentNamespace: Name?
        get() {
            val namedScope = this.firstScopeIsInstanceOrNull<NameScope>()
            return if (namedScope is NameScope) namedScope.name else null
        }

    init {
        pushScope(GlobalScope())
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(ScopeManager::class.java)
    }

    /**
     * Combines the state of several scope managers into this one. Primarily used in combination
     * with concurrent frontends.
     *
     * @param toMerge The scope managers to merge into this one
     */
    fun mergeFrom(toMerge: Collection<ScopeManager>) {
        val globalScopes = toMerge.mapNotNull { it.globalScope }
        val currGlobalScope = scopeMap[null]
        if (currGlobalScope !is GlobalScope) {
            LOGGER.error("Scope for null node is not a GlobalScope or is null")
        } else {
            currGlobalScope.mergeFrom(globalScopes)
            scopeMap[null] = currGlobalScope
        }
        for (manager in toMerge) {
            // loop through all scopes in the FQN map to check for potential duplicates we need to
            // merge
            for (entry in manager.fqnScopeMap.entries) {
                val existing = fqnScopeMap[entry.key]
                if (existing != null) {
                    // merge symbols
                    existing.symbols.mergeFrom(entry.value.symbols)

                    // copy over the typedefs as well just to be sure
                    existing.typedefs.putAll(entry.value.typedefs)

                    // also update the AST node of the existing scope to the "latest" we have seen
                    existing.astNode = entry.value.astNode

                    // now it gets more tricky. we also need to "redirect" the AST nodes in the sub
                    // scope manager to our
                    // existing NameScope (currently, they point to their own, invalid copy of the
                    // NameScope).
                    //
                    // The only way to do this, is to filter for the particular
                    // scope (the value of the map) and return the keys (the nodes)
                    val keys =
                        manager.scopeMap
                            .filter { it.value.astNode == entry.value.astNode }
                            .map { it.key }

                    // now, we redirect it to the existing scope
                    keys.forEach { manager.scopeMap[it] = existing }
                } else {
                    // this is the first we see for this particular FQN, so we add it to our map
                    fqnScopeMap[entry.key] = entry.value
                }
            }

            // We need to make sure that we do not put the "null" key (aka the global scope) of the
            // individual scope manager into our map, otherwise we would overwrite our merged global
            // scope.
            scopeMap.putAll(manager.scopeMap.filter { it.key != null })

            // free the maps, just to clear up some things. this scope manager will not be used
            // anymore
            manager.fqnScopeMap.clear()
            manager.scopeMap.clear()
        }
    }

    /**
     * Pushes the scope on the current scope stack. Used internally by [enterScope].
     *
     * @param scope the scope
     */
    private fun pushScope(scope: Scope) {
        if (scopeMap.containsKey(scope.astNode)) {
            LOGGER.error(
                "Node cannot be scoped twice. A node must be at most one associated scope apart from the parent scopes."
            )
            return
        }
        scopeMap[scope.astNode] = scope
        if (scope is NameScope) {
            // for this to work, it is essential that RecordDeclaration and NamespaceDeclaration
            // nodes have a FQN as their name.
            fqnScopeMap[scope.astNode?.name.toString()] = scope
        }
        currentScope?.let {
            it.children.add(scope)
            scope.parent = it
        }
        currentScope = scope
    }

    /**
     * This function, in combination with [leaveScope] is the main interaction point with the scope
     * manager for language frontends. Every time a language frontend handles a node that begins a
     * new scope, this function needs to be called. Appropriate scopes will then be created
     * on-the-fly, if they do not exist.
     *
     * The scope manager has an internal association between the type of scope, e.g. a [BlockScope]
     * and the CPG node it represents, e.g. a [Block].
     *
     * Afterwards, all calls to [addDeclaration] will be distributed to the
     * [de.fraunhofer.aisec.cpg.graph.DeclarationHolder] that is currently in-scope.
     */
    fun enterScope(nodeToScope: Node) {
        var newScope: Scope? = null

        // check, if the node does not have an entry in the scope map
        if (!scopeMap.containsKey(nodeToScope)) {
            newScope =
                when (nodeToScope) {
                    is Block -> BlockScope(nodeToScope)
                    is WhileStatement,
                    is DoStatement,
                    is AssertStatement -> LoopScope(nodeToScope as Statement)
                    is ForStatement,
                    is ForEachStatement -> LoopScope(nodeToScope as Statement)
                    is SwitchStatement -> SwitchScope(nodeToScope)
                    is FunctionDeclaration -> FunctionScope(nodeToScope)
                    is IfStatement -> ValueDeclarationScope(nodeToScope)
                    is CatchClause -> ValueDeclarationScope(nodeToScope)
                    is RecordDeclaration -> RecordScope(nodeToScope)
                    is TemplateDeclaration -> TemplateScope(nodeToScope)
                    is TryStatement -> TryScope(nodeToScope)
                    is TranslationUnitDeclaration -> FileScope(nodeToScope)
                    is NamespaceDeclaration -> newNameScopeIfNecessary(nodeToScope)
                    else -> {
                        LOGGER.error(
                            "No known scope for AST node of type {}",
                            nodeToScope.javaClass
                        )
                        return
                    }
                }
        }

        // push the new scope
        if (newScope != null) {
            pushScope(newScope)
            newScope.scopedName = currentNamespace?.toString()
        } else {
            currentScope = scopeMap[nodeToScope]
        }
    }

    /**
     * A small internal helper function used by [enterScope] to create a [NameScope].
     *
     * The issue with name scopes, such as a namespace, is that it can exist across several files,
     * i.e. translation units, represented by different [NamespaceDeclaration] nodes. But, in order
     * to make namespace resolution work across files, only one [NameScope] must exist that holds
     * all declarations, such as classes, independently of the translation units. Therefore, we need
     * to check, whether such as node already exists. If it does already exist:
     * - we update the scope map so that the current [NamespaceDeclaration] points to the existing
     *   [NameScope]
     * - we return null, indicating to [enterScope], that no new scope needs to be pushed by
     *   [enterScope].
     *
     * Otherwise, we return a new name scope.
     */
    private fun newNameScopeIfNecessary(nodeToScope: NamespaceDeclaration): NameScope? {
        val existingScope =
            filterScopes { it is NameScope && it.name == nodeToScope.name }.firstOrNull()

        return if (existingScope != null) {
            // update the AST node to this namespace declaration
            existingScope.astNode = nodeToScope

            // make it also available in the scope map. Otherwise, we cannot leave the
            // scope
            scopeMap[nodeToScope] = existingScope

            // do NOT return a new name scope, but rather return null, so enterScope knows that it
            // does not need to push a new scope
            null
        } else {
            NameScope(nodeToScope)
        }
    }

    /**
     * Similar to [enterScope], but does so in a "read-only" mode, e.g. it does not modify the scope
     * tree and does not create new scopes on the fly, as [enterScope] does.
     */
    fun enterScopeIfExists(nodeToScope: Node?) {
        if (scopeMap.containsKey(nodeToScope)) {
            val scope = scopeMap[nodeToScope]

            // we need a special handling of name spaces, because
            // they are associated to more than one AST node
            if (scope is NameScope) {
                // update AST (see enterScope for an explanation)
                scope.astNode = nodeToScope
            }
            currentScope = scope
        }
    }

    /**
     * The counter-part of [enterScope]. Language frontends need to call this function, when the
     * scope of the currently processed AST node ends. There MUST have been a corresponding
     * [enterScope] call with the same [nodeToLeave], otherwise the scope-tree might be corrupted.
     *
     * @param nodeToLeave the AST node
     * @return the scope that was just left
     */
    fun leaveScope(nodeToLeave: Node): Scope? {
        // Check to return as soon as we know that there is no associated scope. This check could be
        // omitted but will increase runtime if leaving a node without scope will happen often.
        if (!scopeMap.containsKey(nodeToLeave)) {
            return null
        }

        val leaveScope = firstScopeOrNull { it.astNode == nodeToLeave }
        if (leaveScope == null) {
            if (scopeMap.containsKey(nodeToLeave)) {
                Util.errorWithFileLocation(
                    nodeToLeave,
                    LOGGER,
                    "Node of type {} has a scope but is not active in the moment.",
                    nodeToLeave.javaClass
                )
            } else {
                Util.errorWithFileLocation(
                    nodeToLeave,
                    LOGGER,
                    "Node of type {} is not associated with a scope.",
                    nodeToLeave.javaClass
                )
            }

            return null
        }

        // go back to the parent of the scope we just left
        currentScope = leaveScope.parent
        return leaveScope
    }

    /**
     * This function MUST be called when a language frontend first handles a [Declaration]. It adds
     * a declaration to the scope manager, taking into account the currently active scope.
     * Furthermore, it adds the declaration to the [de.fraunhofer.aisec.cpg.graph.DeclarationHolder]
     * that is associated with the current scope through [ValueDeclarationScope.addValueDeclaration]
     * and [StructureDeclarationScope.addStructureDeclaration].
     *
     * Setting [Scope.astNode] to false is useful, if you want to make sure a certain declaration is
     * visible within a scope, but is not directly part of the scope's AST. An example is the way
     * C/C++ handles unscoped enum constants. They are visible in the enclosing scope, e.g., a
     * translation unit, but they are added to the AST of their enum declaration, not the
     * translation unit. The enum declaration is then added to the translation unit.
     *
     * @param declaration the declaration to add
     * @param addToAST specifies, whether the declaration also gets added to the [Scope.astNode] of
     *   the current scope (if it implements [DeclarationHolder]). Defaults to true.
     */
    @JvmOverloads
    fun addDeclaration(declaration: Declaration?, addToAST: Boolean = true) {
        if (declaration != null) {
            // New stuff here
            currentScope?.addSymbol(declaration.symbol, declaration)
        }

        // Legacy stuff here
        when (declaration) {
            is ProblemDeclaration,
            is IncludeDeclaration -> {
                // directly add problems and includes to the global scope
                this.globalScope?.addDeclaration(declaration, addToAST)
            }
            is ValueDeclaration -> {
                val scope = this.firstScopeIsInstanceOrNull<ValueDeclarationScope>()
                scope?.addDeclaration(declaration, addToAST)
            }
            is ImportDeclaration,
            is EnumDeclaration,
            is RecordDeclaration,
            is NamespaceDeclaration,
            is TemplateDeclaration -> {
                val scope = this.firstScopeIsInstanceOrNull<StructureDeclarationScope>()
                scope?.addDeclaration(declaration, addToAST)
            }
        }
    }

    /**
     * This function tries to find the first scope that satisfies the condition specified in
     * [predicate]. It starts searching in the [searchScope], moving up-wards using the
     * [Scope.parent] attribute.
     *
     * @param searchScope the scope to start the search in
     * @param predicate the search predicate
     */
    @JvmOverloads
    fun firstScopeOrNull(searchScope: Scope? = currentScope, predicate: Predicate<Scope>): Scope? {
        // start at searchScope
        var scope = searchScope

        while (scope != null) {
            if (predicate.test(scope)) {
                return scope
            }

            // go up-wards in the scope tree
            scope = scope.parent
        }

        return null
    }

    /**
     * Tries to find the first scope that is an instance of the scope type [T]. Calls
     * [firstScopeOrNull] internally.
     *
     * @param searchScope the scope to start the search in
     */
    inline fun <reified T : Scope> firstScopeIsInstanceOrNull(
        searchScope: Scope? = currentScope
    ): T? {
        return this.firstScopeOrNull(searchScope) { it is T } as? T
    }

    /**
     * Retrieves all unique scopes that satisfy the condition specified in [predicate],
     * independently of their hierarchy.
     *
     * @param predicate the search predicate
     */
    fun filterScopes(predicate: (Scope) -> Boolean): List<Scope> {
        return scopeMap.values.filter(predicate).distinct()
    }

    /** This function returns the [Scope] associated with a node. */
    fun lookupScope(node: Node): Scope? {
        return if (node is TranslationUnitDeclaration) {
            globalScope
        } else scopeMap[node]
    }

    /** This function looks up scope by its FQN. This only works for [NameScope]s */
    fun lookupScope(fqn: String): NameScope? {
        return this.fqnScopeMap[fqn]
    }

    /**
     * This function SHOULD only be used by the
     * [de.fraunhofer.aisec.cpg.passes.EvaluationOrderGraphPass] while building up the EOG. It adds
     * a [BreakStatement] to the list of break statements of the current "breakable" scope.
     */
    fun addBreakStatement(breakStatement: BreakStatement) {
        if (breakStatement.label == null) {
            val scope = firstScopeOrNull { scope: Scope? -> scope?.isBreakable() == true }
            if (scope == null) {
                Util.errorWithFileLocation(
                    breakStatement,
                    LOGGER,
                    "Break inside of unbreakable scope. The break will be ignored, but may lead " +
                        "to an incorrect graph. The source code is not valid or incomplete."
                )
                return
            }
            (scope as Breakable).addBreakStatement(breakStatement)
        } else {
            val labelStatement = getLabelStatement(breakStatement.label)
            labelStatement?.subStatement?.let {
                val scope = lookupScope(it)
                (scope as Breakable?)?.addBreakStatement(breakStatement)
            }
        }
    }

    /**
     * This function SHOULD only be used by the
     * [de.fraunhofer.aisec.cpg.passes.EvaluationOrderGraphPass] while building up the EOG. It adds
     * a [ContinueStatement] to the list of continue statements of the current "continuable" scope.
     */
    fun addContinueStatement(continueStatement: ContinueStatement) {
        if (continueStatement.label == null) {
            val scope = firstScopeOrNull { scope: Scope? -> scope?.isContinuable() == true }
            if (scope == null) {
                LOGGER.error(
                    "Continue inside of not continuable scope. The continue will be ignored, but may lead " +
                        "to an incorrect graph. The source code is not valid or incomplete."
                )
                return
            }
            (scope as Continuable).addContinueStatement(continueStatement)
        } else {
            val labelStatement = getLabelStatement(continueStatement.label)
            labelStatement?.subStatement?.let {
                val scope = lookupScope(it)
                (scope as Continuable?)?.addContinueStatement(continueStatement)
            }
        }
    }

    /**
     * This function SHOULD only be used by the
     * [de.fraunhofer.aisec.cpg.passes.EvaluationOrderGraphPass] while building up the EOG. It adds
     * a [LabelStatement] to the list of label statements of the current scope.
     */
    fun addLabelStatement(labelStatement: LabelStatement) {
        currentScope?.addLabelStatement(labelStatement)
    }

    /**
     * This function is internal to the scope manager and primarily used by [addBreakStatement] and
     * [addContinueStatement]. It retrieves the [LabelStatement] associated with the [labelString].
     */
    private fun getLabelStatement(labelString: String?): LabelStatement? {
        if (labelString == null) return null
        var labelStatement: LabelStatement?
        var searchScope = currentScope
        while (searchScope != null) {
            labelStatement = searchScope.labelStatements[labelString]
            if (labelStatement != null) {
                return labelStatement
            }
            searchScope = searchScope.parent
        }
        return null
    }

    /**
     * This function MUST be called when a language frontend first enters a translation unit. It
     * sets the [GlobalScope] to the current translation unit specified in [declaration].
     */
    fun resetToGlobal(declaration: TranslationUnitDeclaration?) {
        val global = this.globalScope
        if (global != null) {
            // update the AST node to this translation unit declaration
            global.astNode = declaration
            currentScope = global
        }
    }

    /**
     * Adds typedefs to a [ValueDeclarationScope]. The language frontend needs to decide on the
     * scope of the typedef. Most likely, typedefs are global. Therefore, the [GlobalScope] is set
     * as default.
     */
    fun addTypedef(typedef: TypedefDeclaration, scope: ValueDeclarationScope? = globalScope) {
        scope?.addTypedef(typedef)
    }

    private fun getCurrentTypedefs(searchScope: Scope?): Collection<TypedefDeclaration> {
        val typedefs = mutableMapOf<Name, TypedefDeclaration>()

        val path = mutableListOf<ValueDeclarationScope>()
        var current = searchScope

        // We need to build a path from the current scope to the top most one
        while (current != null) {
            if (current is ValueDeclarationScope) {
                path += current
            }
            current = current.parent
        }

        // And then follow the path in reverse. This ensures us that a local definition
        // overwrites / shadows one that was there on a higher scope.
        for (scope in path.reversed()) {
            typedefs.putAll(scope.typedefs)
        }

        return typedefs.values
    }

    /**
     * Resolves only references to Values in the current scope, static references to other visible
     * records are not resolved over the ScopeManager.
     *
     * @param ref
     * @return
     *
     * TODO: We should merge this function with [.resolveFunction]
     */
    fun resolveReference(ref: Reference): ValueDeclaration? {
        val startScope = ref.scope

        // Retrieve a unique tag for the particular reference based on the current scope
        val tag = ref.referenceTag

        // If we find a match in our symbol table, we can immediately return the declaration. We
        // need to be careful about potential collisions in our tags, since they are based on the
        // hash-code of the scope. We therefore take the extra precaution to compare the scope in
        // case we get a hit. This should not take too much performance overhead.
        val pair = symbolTable[tag]
        if (pair != null && ref.scope == pair.first.scope) {
            return pair.second
        }

        val (scope, name) = extractScope(ref, startScope)

        // Try to resolve value declarations according to our criteria
        val decl =
            resolve<ValueDeclaration>(scope) {
                    if (it.name.lastPartsMatch(name)) {
                        val helper = ref.resolutionHelper
                        return@resolve when {
                            // If the reference seems to point to a function (using a function
                            // pointer) the entire signature is checked for equality
                            helper?.type is FunctionPointerType && it is FunctionDeclaration -> {
                                val fptrType = helper.type as FunctionPointerType
                                // TODO(oxisto): Support multiple return values
                                val returnType = it.returnTypes.firstOrNull() ?: IncompleteType()
                                returnType == fptrType.returnType &&
                                    it.matchesSignature(fptrType.parameters) !=
                                        IncompatibleSignature
                            }
                            // If our language has first-class functions, we can safely return them
                            // as a reference
                            ref.language is HasFirstClassFunctions -> {
                                true
                            }
                            // Otherwise, we are not looking for functions here
                            else -> {
                                it !is FunctionDeclaration
                            }
                        }
                    }

                    return@resolve false
                }
                .firstOrNull()

        // Update the symbol cache, if we found a declaration for the tag
        if (decl != null) {
            symbolTable[tag] = Pair(ref, decl)
        }

        return decl
    }

    /**
     * Tries to resolve a function in a call expression.
     *
     * @param call the call expression
     * @return a list of possible functions
     */
    @JvmOverloads
    fun resolveFunctionLegacy(
        call: CallExpression,
        startScope: Scope? = currentScope
    ): List<FunctionDeclaration> {
        val (scope, name) = extractScope(call, startScope)

        val func =
            resolve<FunctionDeclaration>(scope) {
                it.name.lastPartsMatch(name) &&
                    it.matchesSignature(call.signature) != IncompatibleSignature
            }

        return func
    }

    /**
     * This function tries to resolve a [CallExpression] into its matching [FunctionDeclaration] (or
     * multiple functions, if applicable). The result is returned in the form of a
     * [CallResolutionResult] which holds detail information about intermediate results as well as
     * the kind of success the resolution had.
     *
     * Note: The [CallExpression.callee] needs to be resolved first, otherwise the call resolution
     * fails.
     */
    fun resolveCall(call: CallExpression, startScope: Scope? = currentScope): CallResolutionResult {
        val result =
            CallResolutionResult(
                call,
                setOf(),
                setOf(),
                mapOf(),
                setOf(),
                CallResolutionResult.SuccessKind.UNRESOLVED,
                startScope,
            )
        val language = call.language

        if (language == null) {
            result.success = CallResolutionResult.SuccessKind.PROBLEMATIC
            return result
        }

        // We can only resolve non-dynamic function calls here that have a reference node to our
        // function
        val callee = call.callee as? Reference ?: return result

        val (scope, _) = extractScope(callee, startScope)
        result.actualStartScope = scope

        // Retrieve a list of possible functions with a matching name
        result.candidateFunctions =
            callee.candidates.filterIsInstance<FunctionDeclaration>().toSet()

        if (call.language !is HasFunctionOverloading) {
            // If the function does not allow function overloading, and we have multiple candidate
            // symbols, the
            // result is "problematic"
            if (result.candidateFunctions.size > 1) {
                result.success = CallResolutionResult.SuccessKind.PROBLEMATIC
            }
        }

        // Filter functions that match the signature of our call, either directly or with casts;
        // those functions are "viable". Take default arguments into account if the language has
        // them.
        result.signatureResults =
            result.candidateFunctions
                .map {
                    Pair(
                        it,
                        it.matchesSignature(
                            call.signature,
                            call.language is HasDefaultArguments,
                            call
                        )
                    )
                }
                .filter { it.second is SignatureMatches }
                .associate { it }
        result.viableFunctions = result.signatureResults.keys

        // If we have a "problematic" result, we can stop here. In this case we cannot really
        // determine anything more.
        if (result.success == CallResolutionResult.SuccessKind.PROBLEMATIC) {
            result.bestViable = result.viableFunctions
            return result
        }

        // Otherwise, give the language a chance to narrow down the result (ideally to one) and set
        // the success kind.
        val pair = language.bestViableResolution(result)
        result.bestViable = pair.first
        result.success = pair.second

        return result
    }

    /**
     * This function extracts a scope for the [Name] in node, e.g. if the name is fully qualified.
     *
     * The pair returns the extracted scope and a name that is adjusted by possible import aliases.
     * The extracted scope is "responsible" for the name (e.g. declares the parent namespace) and
     * the returned name only differs from the provided name if aliasing was involved at the node
     * location (e.g. because of imports).
     *
     * Note: Currently only *fully* qualified names are properly resolved. This function will
     * probably return imprecise results for partially qualified names, e.g. if a name `A` inside
     * `B` points to `A::B`, rather than to `A`.
     *
     * @param node the nodes name references a namespace constituted by a scope
     * @param scope the current scope relevant for the name resolution, e.g. parent of node
     * @return a pair with the scope of node.name and the alias-adjusted name
     */
    fun extractScope(node: Node, scope: Scope? = currentScope): Pair<Scope?, Name> {
        return extractScope(node.name, node.location, scope)
    }

    /**
     * This function extracts a scope for the [Name], e.g. if the name is fully qualified. `null` is
     * returned, if no scope can be extracted.
     *
     * The pair returns the extracted scope and a name that is adjusted by possible import aliases.
     * The extracted scope is "responsible" for the name (e.g. declares the parent namespace) and
     * the returned name only differs from the provided name if aliasing was involved at the node
     * location (e.g. because of imports).
     *
     * Note: Currently only *fully* qualified names are properly resolved. This function will
     * probably return imprecise results for partially qualified names, e.g. if a name `A` inside
     * `B` points to `A::B`, rather than to `A`.
     *
     * @param name the name
     * @param scope the current scope relevant for the name resolution, e.g. parent of node
     * @return a pair with the scope of node.name and the alias-adjusted name
     */
    fun extractScope(
        name: Name,
        location: PhysicalLocation? = null,
        scope: Scope? = currentScope,
    ): Pair<Scope?, Name> {
        var n = name
        var s = scope

        // First, we need to check, whether we have some kind of scoping.
        if (n.isQualified()) {
            // We need to check, whether we have an alias for the name's parent in this file
            n = resolveParentAlias(n, scope)

            // extract the scope name, it is usually a name space, but could probably be something
            // else as well in other languages
            val scopeName = n.parent

            // this is a scoped call. we need to explicitly jump to that particular scope
            val scopes = filterScopes { (it is NameScope && it.name == scopeName) }
            s =
                if (scopes.isEmpty()) {
                    Util.warnWithFileLocation(
                        location,
                        LOGGER,
                        "Could not find the scope $scopeName needed to resolve $n"
                    )
                    null
                } else {
                    scopes[0]
                }
        }

        return Pair(s, n)
    }

    /**
     * This function resolves a name alias (contained in an import alias) for the [Name.parent] of
     * the given [Name]. It also does this recursively.
     */
    fun resolveParentAlias(name: Name, scope: Scope?): Name {
        var parentName = name.parent ?: return name
        parentName = resolveParentAlias(parentName, scope)

        // Build a new name based on the eventual resolved parent alias
        var newName =
            if (parentName != name.parent) {
                Name(name.localName, parentName, delimiter = name.delimiter)
            } else {
                name
            }
        var decl =
            scope?.lookupSymbol(parentName.localName)?.singleOrNull {
                it is NamespaceDeclaration || it is RecordDeclaration
            }
        if (decl != null && parentName != decl.name) {
            // This is probably an already resolved alias so, we take this one
            return Name(newName.localName, decl.name, delimiter = newName.delimiter)
        }

        // Some special handling of typedefs; this should somehow be merged with the above but not
        // exactly sure how. The issue is that we cannot take the "name" of the typedef declaration,
        // but we rather want its original type name.
        // TODO: This really needs to be handled better somehow, maybe a common interface for
        //  typedefs, namespaces and records that return the correct name?
        decl = scope?.lookupSymbol(parentName.localName)?.singleOrNull { it is TypedefDeclaration }
        if ((decl as? TypedefDeclaration) != null) {
            return Name(newName.localName, decl.type.name, delimiter = newName.delimiter)
        }

        // If we do not have a match yet, it could be that we are trying to resolve an FQN type
        // during frontend translation. This is deprecated and will be replaced in the future
        // by a system that also resolves type during symbol resolving. However, to support aliases
        // from imports in this intermediate stage, we have to look for unresolved import
        // declarations and also take their aliases into account
        decl =
            scope
                ?.lookupSymbol(parentName.localName)
                ?.filterIsInstance<ImportDeclaration>()
                ?.singleOrNull()
        if (decl != null && decl.importedSymbols.isEmpty() && parentName != decl.import) {
            newName = Name(newName.localName, decl.import, delimiter = newName.delimiter)
        }

        return newName
    }

    /**
     * Directly jumps to a given scope. Returns the previous scope. Do not forget to set the scope
     * back to the old scope after performing the actions inside this scope.
     *
     * Handle with care, here be dragons. Should not be exposed outside the cpg-core module.
     */
    @PleaseBeCareful
    internal fun jumpTo(scope: Scope?): Scope? {
        val oldScope = currentScope
        currentScope = scope
        return oldScope
    }

    /**
     * This function can be used to execute multiple statements contained in [init] in the scope of
     * [scope]. The specified scope will be selected using [jumpTo]. The last expression in [init]
     * will also be used as a return value of this function. This can be useful, if you create
     * objects, such as a [Node] inside this scope and want to return it to the calling function.
     */
    fun <T : Any> withScope(scope: Scope?, init: (scope: Scope?) -> T): T {
        val oldScope = jumpTo(scope)
        val ret = init(scope)
        jumpTo(oldScope)

        return ret
    }

    fun resolveFunctionStopScopeTraversalOnDefinition(
        call: CallExpression
    ): List<FunctionDeclaration> {
        return resolve(currentScope, true) { f -> f.name.lastPartsMatch(call.name) }
    }

    /**
     * Traverses the scope upwards and looks for declarations of type [T] which matches the
     * condition [predicate].
     *
     * It returns a list of all declarations that match the predicate, ordered by reachability in
     * the scope stack. This means that "local" declarations will be in the list first, global items
     * will be last.
     *
     * @param searchScope the scope to start the search in
     * @param predicate predicate the element must match to
     * @param <T>
     */
    inline fun <reified T : Declaration> resolve(
        searchScope: Scope?,
        stopIfFound: Boolean = false,
        noinline predicate: (T) -> Boolean
    ): List<T> {
        return resolve(T::class.java, searchScope, stopIfFound, predicate)
    }

    fun <T : Declaration> resolve(
        klass: Class<T>,
        searchScope: Scope?,
        stopIfFound: Boolean = false,
        predicate: (T) -> Boolean
    ): List<T> {
        var scope = searchScope
        val declarations = mutableListOf<T>()

        while (scope != null) {
            if (scope is ValueDeclarationScope) {
                declarations.addAll(
                    scope.valueDeclarations.filterIsInstance(klass).filter(predicate)
                )
            }

            if (scope is StructureDeclarationScope) {
                var list = scope.structureDeclarations.filterIsInstance(klass).filter(predicate)

                // this was taken over from the old resolveStructureDeclaration.
                // TODO(oxisto): why is this only when the list is empty?
                if (list.isEmpty()) {
                    for (declaration in scope.structureDeclarations) {
                        if (declaration is RecordDeclaration) {
                            list = declaration.templates.filterIsInstance(klass).filter(predicate)
                        }
                    }
                }

                declarations.addAll(list)
            }

            // some (all?) languages require us to stop immediately if we found something on this
            // scope. This is the case where function overloading is allowed, but only within the
            // same scope
            if (stopIfFound && declarations.isNotEmpty()) {
                return declarations
            }

            // go upwards in the scope tree
            scope = scope.parent
        }

        return declarations
    }

    /**
     * Resolves function templates of the given [CallExpression].
     *
     * @param scope where we are searching for the FunctionTemplateDeclarations
     * @param call CallExpression we want to resolve an invocation target for
     * @return List of FunctionTemplateDeclaration that match the name provided in the
     *   CallExpression and therefore are invocation candidates
     */
    @JvmOverloads
    fun resolveFunctionTemplateDeclaration(
        call: CallExpression,
        scope: Scope? = currentScope
    ): List<FunctionTemplateDeclaration> {
        return resolve(scope, true) { c -> c.name.lastPartsMatch(call.name) }
    }

    /**
     * Retrieves the [RecordDeclaration] for the given name in the given scope.
     *
     * @param name the name
     * * @param scope the scope. Default is [currentScope]
     *
     * @return the declaration, or null if it does not exist
     */
    fun getRecordForName(name: Name): RecordDeclaration? {
        return findSymbols(name).filterIsInstance<RecordDeclaration>().singleOrNull()
    }

    fun typedefFor(alias: Name, scope: Scope? = currentScope): Type? {
        var current = scope

        // We need to build a path from the current scope to the top most one. This ensures us that
        // a local definition overwrites / shadows one that was there on a higher scope.
        while (current != null) {
            if (current is ValueDeclarationScope) {
                // This is a little bit of a hack to support partial FQN resolution at least with
                // typedefs, but it's not really ideal.
                // And this also should be merged with the scope manager logic when resolving names.
                //
                // The better approach would be to harmonize the FQN of all types in one pass before
                // all this happens.
                //
                // This process has several steps:
                // First, do a quick local lookup, to see if we have a typedef our current scope
                // (only do this if the name is not qualified)
                if (!alias.isQualified() && current == currentScope) {
                    val decl = current.typedefs[alias]
                    if (decl != null) {
                        return decl.type
                    }
                }

                // Next, try to look up the name either by its FQN (if it is qualified) or make it
                // qualified based on the current namespace
                val key =
                    current.typedefs.keys.firstOrNull {
                        var lookupName = alias

                        // If the lookup name is already a FQN, we can use the name directly
                        lookupName =
                            if (lookupName.isQualified()) {
                                lookupName
                            } else {
                                // Otherwise, we want to make an FQN out of it using the current
                                // namespace
                                currentNamespace?.fqn(lookupName.localName) ?: lookupName
                            }

                        it.lastPartsMatch(lookupName)
                    }
                if (key != null) {
                    return current.typedefs[key]?.type
                }
            }

            current = current.parent
        }

        return null
    }

    /** Returns the current scope for the [ScopeProvider] interface. */
    override val scope: Scope?
        get() = currentScope

    /**
     * This function tries to resolve a [Node.name] to a list of symbols (a symbol represented by a
     * [Declaration]) starting with [startScope]. This function can return a list of multiple
     * symbols in order to check for things like function overloading. but it will only return list
     * of symbols within the same scope; the list cannot be spread across different scopes.
     *
     * This means that as soon one or more symbols are found in a "local" scope, these shadow all
     * other occurrences of the same / symbol in a "higher" scope and only the ones from the lower
     * ones will be returned.
     */
    fun findSymbols(
        name: Name,
        location: PhysicalLocation? = null,
        startScope: Scope? = currentScope,
        predicate: ((Declaration) -> Boolean)? = null,
    ): List<Declaration> {
        val (scope, n) = extractScope(name, location, startScope)
        val list =
            scope?.lookupSymbol(n.localName, predicate = predicate)?.toMutableList()
                ?: mutableListOf()

        // If we have both the definition and the declaration of a function declaration in our list,
        // we chose only the definition
        val it = list.iterator()
        while (it.hasNext()) {
            val decl = it.next()
            if (decl is FunctionDeclaration) {
                val definition = decl.definition
                if (!decl.isDefinition && definition != null && definition in list) {
                    it.remove()
                }
            }
        }

        return list
    }
}

/**
 * [SignatureResult] will be the result of the function [FunctionDeclaration.matchesSignature] which
 * calculates whether the provided [CallExpression] will match the signature of the current
 * [FunctionDeclaration].
 */
sealed class SignatureResult(open val casts: List<CastResult>? = null) {
    val ranking: Int
        get() {
            var i = 0
            for (cast in this.casts ?: listOf()) {
                i += cast.depthDistance
            }
            return i
        }

    val isDirectMatch: Boolean
        get() {
            return this.casts?.all { it is DirectMatch } == true
        }
}

data object IncompatibleSignature : SignatureResult()

data class SignatureMatches(override val casts: List<CastResult>) : SignatureResult(casts)

fun FunctionDeclaration.matchesSignature(
    signature: List<Type>,
    useDefaultArguments: Boolean = false,
    call: CallExpression? = null,
): SignatureResult {
    val casts = mutableListOf<CastResult>()

    var remainingArguments = signature.size

    // Loop through all parameters of this function
    for ((i, param) in this.parameters.withIndex()) {
        // Once we are in variadic mode, all arguments match
        if (param.isVariadic) {
            remainingArguments = 0
            break
        }

        // Try to find a matching call argument by index
        val type = signature.getOrNull(i)

        // Yay, we still have arguments/types left
        if (type != null) {
            // Check, if we can cast the arg into our target type; and if, yes, what is
            // the "distance" to the base type. We need this to narrow down the type during
            // resolving
            val match = type.tryCast(param.type, call?.arguments?.getOrNull(i), param)
            if (match == CastNotPossible) {
                return IncompatibleSignature
            }

            casts += match
            remainingArguments--
        } else {
            // If the type (argument) is null, this might signal that we have less arguments than
            // our function signature, so we are likely not a match. But, the function could still
            // have a default argument (if the language supports it).
            if (useDefaultArguments) {
                val defaultParam = this.defaultParameters[i]
                if (defaultParam != null) {
                    casts += DirectMatch

                    // We have a matching default parameter, let's decrement the remaining arguments
                    // and continue matching
                    remainingArguments--
                    continue
                }
            }

            // We did not have a matching default parameter, or we don't have/support default
            // parameters, so our matching is done here
            return IncompatibleSignature
        }
    }

    // TODO(oxisto): In some languages, we can also have named parameters, but this is not yet
    //  supported

    // If we still have remaining arguments at the end of the matching check, the signature is
    // incompatible
    return if (remainingArguments > 0) {
        IncompatibleSignature
    } else {
        // Otherwise, return the matching cast results
        SignatureMatches(casts)
    }
}

/**
 * This is the result of [ScopeManager.resolveCall]. It holds all necessary intermediate results
 * (such as [candidateFunctions], [viableFunctions]) as well as the final result (see [bestViable])
 * of the call resolution.
 */
data class CallResolutionResult(
    /** The original call expression. */
    val call: CallExpression,

    /**
     * A set of candidate symbols we discovered based on the [CallExpression.callee] (using
     * [ScopeManager.findSymbols]), more specifically a list of [FunctionDeclaration] nodes.
     */
    var candidateFunctions: Set<FunctionDeclaration>,

    /**
     * A set of functions, that restrict the [candidateFunctions] to those whose signature match.
     */
    var viableFunctions: Set<FunctionDeclaration>,

    /**
     * A helper map to store the [SignatureResult] of each call to
     * [FunctionDeclaration.matchesSignature] for each function in [viableFunctions].
     */
    var signatureResults: Map<FunctionDeclaration, SignatureResult>,

    /**
     * This set contains the best viable function(s) of the [viableFunctions]. Ideally this is only
     * one, but because of ambiguities or other factors, this can contain multiple functions.
     */
    var bestViable: Set<FunctionDeclaration>,

    /** The kind of success this resolution had. */
    var success: SuccessKind,

    /**
     * The actual start scope of the resolution, after [ScopeManager.extractScope] is called on the
     * callee. This can differ from the original start scope parameter handed to
     * [ScopeManager.resolveCall] if the callee contains an FQN.
     */
    var actualStartScope: Scope?
) {
    /**
     * This enum holds information about the kind of success this call resolution had. For example,
     * whether it was successful without any errors or if an ambiguous result was returned.
     */
    enum class SuccessKind {
        /**
         * The call resolution was successful, and we have identified the best viable function(s).
         *
         * Ideally, we have only one function in [bestViable], but it could be that we still have
         * multiple functions in this list. The most common scenario for this is if we have a member
         * call to an interface, and we know at least partially which implemented classes could be
         * in the [MemberExpression.base]. In this case, all best viable functions of each of the
         * implemented classes are contained in [bestViable].
         */
        SUCCESSFUL,

        /**
         * The call resolution was problematic, i.e., some error occurred, or we were running into
         * an unexpected state. An example would be that we arrive at multiple [candidateFunctions]
         * for a language that does not have [HasFunctionOverloading].
         *
         * We try to store the most accurate result(s) possible in [bestViable].
         */
        PROBLEMATIC,

        /**
         * The call resolution was ambiguous in a way that we cannot decide between one or more
         * [viableFunctions]. This can happen if we have multiple functions that have the same
         * [SignatureResult.ranking]. A real compiler could not differentiate between those two
         * functions and would throw a compile error.
         *
         * We store all ambiguous functions in [bestViable].
         */
        AMBIGUOUS,

        /**
         * The call resolution was unsuccessful, we could not find a [bestViable] or even a list of
         * [viableFunctions] out of the [candidateFunctions].
         *
         * [bestViable] is empty in this case.
         */
        UNRESOLVED
    }
}
