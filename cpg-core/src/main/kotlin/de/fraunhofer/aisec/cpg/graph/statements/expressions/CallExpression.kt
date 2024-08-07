/*
 * Copyright (c) 2020, Fraunhofer AISEC. All rights reserved.
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
package de.fraunhofer.aisec.cpg.graph.statements.expressions

import de.fraunhofer.aisec.cpg.PopulatedByPass
import de.fraunhofer.aisec.cpg.graph.*
import de.fraunhofer.aisec.cpg.graph.declarations.FunctionDeclaration
import de.fraunhofer.aisec.cpg.graph.declarations.TemplateDeclaration
import de.fraunhofer.aisec.cpg.graph.declarations.TemplateDeclaration.TemplateInitialization
import de.fraunhofer.aisec.cpg.graph.edge.*
import de.fraunhofer.aisec.cpg.graph.edge.Properties
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge.Companion.propertyEqualsList
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge.Companion.unwrap
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge.Companion.wrap
import de.fraunhofer.aisec.cpg.graph.types.*
import de.fraunhofer.aisec.cpg.passes.SymbolResolver
import java.util.*
import org.apache.commons.lang3.builder.ToStringBuilder
import org.neo4j.ogm.annotation.Relationship

/**
 * An expression, which calls another function. It has a list of arguments (list of [Expression]s)
 * and is connected via the INVOKES edge to its [FunctionDeclaration].
 */
open class CallExpression : Expression(), HasType.TypeObserver, ArgumentHolder {
    /**
     * Connection to its [FunctionDeclaration]. This will be populated by the [SymbolResolver]. This
     * will have an effect on the [type]
     */
    @PopulatedByPass(SymbolResolver::class)
    @Relationship(value = "INVOKES", direction = Relationship.Direction.OUTGOING)
    var invokeEdges = mutableListOf<PropertyEdge<FunctionDeclaration>>()
        protected set

    /**
     * A virtual property to quickly access the list of declarations that this call invokes without
     * property edges.
     */
    @PopulatedByPass(SymbolResolver::class)
    var invokes: List<FunctionDeclaration>
        get(): List<FunctionDeclaration> {
            val targets: MutableList<FunctionDeclaration> = ArrayList()
            for (propertyEdge in invokeEdges) {
                targets.add(propertyEdge.end)
            }
            return Collections.unmodifiableList(targets)
        }
        set(value) {
            unwrap(invokeEdges).forEach { it.unregisterTypeObserver(this) }
            invokeEdges = wrap(value, this)
            value.forEach { it.registerTypeObserver(this) }
        }

    /**
     * The list of arguments of this call expression, backed by a list of [PropertyEdge] objects.
     */
    @Relationship(value = "ARGUMENTS", direction = Relationship.Direction.OUTGOING)
    @AST
    var argumentEdges = mutableListOf<PropertyEdge<Expression>>()

    /**
     * The list of arguments as a simple list. This is a delegated property delegated to
     * [argumentEdges].
     */
    var arguments by PropertyEdgeDelegate(CallExpression::argumentEdges)

    /**
     * The expression that is being "called". This is currently not yet used in the [SymbolResolver]
     * but will be in the future. In most cases, this is a [Reference] and its [Reference.refersTo]
     * is intentionally left empty. It is not filled by the [SymbolResolver].
     */
    @AST var callee: Expression? = null

    /**
     * The [Name] of this call expression, based on its [callee].
     * * For simple calls, this is just the name of the [callee], e.g., a reference to a function
     * * For simple function pointers we want to prefix a *
     * * For class based function pointers we want to build a name like MyClass::*pointer
     */
    override var name: Name
        get() {
            val value = callee
            return if (value is UnaryOperator && value.input.type is FunctionPointerType) {
                value.input.name
            } else if (value is BinaryOperator && value.rhs.type is FunctionPointerType) {
                value.lhs.type.name.fqn("*" + value.rhs.name.localName)
            } else {
                value?.name ?: Name(EMPTY_NAME)
            }
        }
        set(_) {
            // read-only
        }

    fun setArgument(index: Int, argument: Expression) {
        argumentEdges[index].end = argument
    }

    override fun addArgument(expression: Expression) {
        return addArgument(expression, null)
    }

    /** Adds the specified [expression] with an optional [name] to this call. */
    fun addArgument(expression: Expression, name: String? = null) {
        val edge = PropertyEdge(this, expression)
        edge.addProperty(Properties.INDEX, argumentEdges.size)

        if (name != null) {
            edge.addProperty(Properties.NAME, name)
        }

        argumentEdges.add(edge)
    }

    override fun replaceArgument(old: Expression, new: Expression): Boolean {
        // First, we need to find the old index
        val idx = this.arguments.indexOf(old)
        if (idx == -1) {
            return false
        }

        setArgument(idx, new)
        return true
    }

    override fun hasArgument(expression: Expression): Boolean {
        return expression in this.arguments
    }

    override fun removeArgument(expression: Expression): Boolean {
        arguments -= expression
        return true
    }

    /** Returns the function signature as list of types of the call arguments. */
    val signature: List<Type>
        get() = argumentEdges.map { it.end.type }

    /** Specifies, whether this call has any template arguments. */
    var template = false

    /** If the CallExpression instantiates a template, the call can provide template parameters. */
    @Relationship(value = "TEMPLATE_PARAMETERS", direction = Relationship.Direction.OUTGOING)
    @AST
    var templateParameterEdges: MutableList<PropertyEdge<Node>>? = null
        set(value) {
            field = value
            template = value != null
        }

    val templateParameters: List<Node>
        get(): List<Node> {
            return unwrap(templateParameterEdges ?: listOf())
        }

    /**
     * If the CallExpression instantiates a Template the CallExpression is connected to the template
     * which is instantiated. This is required by the expansion pass to access the Template
     * directly. The invokes edge will still point to the realization of the template.
     */
    @Relationship(value = "TEMPLATE_INSTANTIATION", direction = Relationship.Direction.OUTGOING)
    var templateInstantiation: TemplateDeclaration? = null
        set(value) {
            field = value
            template = value != null
        }

    /**
     * Adds a template parameter to this call expression. A parameter can either be an [Expression]
     * (usually a [Literal]) or a [Type].
     */
    @JvmOverloads
    fun addTemplateParameter(
        templateParam: Node,
        templateInitialization: TemplateInitialization? = TemplateInitialization.EXPLICIT
    ) {
        if (templateParam is Expression || templateParam is Type) {
            if (templateParameterEdges == null) {
                templateParameterEdges = mutableListOf()
            }

            val propertyEdge = PropertyEdge(this, templateParam)
            propertyEdge.addProperty(Properties.INDEX, templateParameters.size)
            propertyEdge.addProperty(Properties.INSTANTIATION, templateInitialization)
            templateParameterEdges?.add(propertyEdge)
            template = true
        }
    }

    fun updateTemplateParameters(
        initializationType: Map<Node?, TemplateInitialization?>,
        orderedInitializationSignature: List<Node>
    ) {
        if (templateParameterEdges == null) {
            templateParameterEdges = mutableListOf()
        }

        for (edge in templateParameterEdges ?: listOf()) {
            if (
                edge.getProperty(Properties.INSTANTIATION) != null &&
                    (edge.getProperty(Properties.INSTANTIATION) ==
                        TemplateInitialization.UNKNOWN) &&
                    initializationType.containsKey(edge.end)
            ) {
                edge.addProperty(Properties.INSTANTIATION, initializationType[edge.end])
            }
        }

        for (i in (templateParameterEdges?.size ?: 0) until orderedInitializationSignature.size) {
            val propertyEdge = PropertyEdge(this, orderedInitializationSignature[i])
            propertyEdge.addProperty(Properties.INDEX, templateParameterEdges?.size)
            propertyEdge.addProperty(
                Properties.INSTANTIATION,
                initializationType.getOrDefault(
                    orderedInitializationSignature[i],
                    TemplateInitialization.UNKNOWN
                )
            )
            templateParameterEdges?.add(propertyEdge)
        }
    }

    fun instantiatesTemplate(): Boolean {
        return templateInstantiation != null || templateParameterEdges != null || template
    }

    override fun typeChanged(newType: Type, src: HasType) {
        // If this is a template, we need to ignore incoming type changes, because our template
        // system will explicitly set the type
        if (this.template) {
            return
        }

        if (newType !is FunctionType) {
            return
        }

        if (newType.returnTypes.size == 1) {
            this.type = newType.returnTypes.single()
        } else if (newType.returnTypes.size > 1) {
            this.type = TupleType(newType.returnTypes)
        }
    }

    override fun assignedTypeChanged(assignedTypes: Set<Type>, src: HasType) {
        // Nothing to do
    }

    override fun toString(): String {
        return ToStringBuilder(this, TO_STRING_STYLE).appendSuper(super.toString()).toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CallExpression) return false
        return super.equals(other) &&
            arguments == other.arguments &&
            propertyEqualsList(argumentEdges, other.argumentEdges) &&
            templateParameters == other.templateParameters &&
            propertyEqualsList(templateParameterEdges, other.templateParameterEdges) &&
            templateInstantiation == other.templateInstantiation &&
            template == other.template
    }

    // TODO: Not sure if we can add the template, templateParameters, templateInstantiation fields
    //  here
    override fun hashCode() = Objects.hash(super.hashCode(), arguments)
}
