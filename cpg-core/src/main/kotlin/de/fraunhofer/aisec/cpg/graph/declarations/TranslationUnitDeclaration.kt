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
package de.fraunhofer.aisec.cpg.graph.declarations

import de.fraunhofer.aisec.cpg.graph.*
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge.Companion.propertyEqualsList
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge.Companion.unwrap
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdgeDelegate
import de.fraunhofer.aisec.cpg.graph.statements.Statement
import java.util.*
import org.apache.commons.lang3.builder.ToStringBuilder
import org.neo4j.ogm.annotation.Relationship

/** The top most declaration, representing a translation unit, for example a file. */
class TranslationUnitDeclaration :
    Declaration(), DeclarationHolder, StatementHolder, EOGStarterHolder {
    /** A list of declarations within this unit. */
    @Relationship(value = "DECLARATIONS", direction = Relationship.Direction.OUTGOING)
    @AST
    val declarationEdges: MutableList<PropertyEdge<Declaration>> = ArrayList()

    /** A list of includes within this unit. */
    @Relationship(value = "INCLUDES", direction = Relationship.Direction.OUTGOING)
    @AST
    val includeEdges: MutableList<PropertyEdge<IncludeDeclaration>> = ArrayList()

    /** A list of namespaces within this unit. */
    @Relationship(value = "NAMESPACES", direction = Relationship.Direction.OUTGOING)
    @AST
    val namespaceEdges: MutableList<PropertyEdge<NamespaceDeclaration>> = ArrayList()

    /** The list of statements. */
    @Relationship(value = "STATEMENTS", direction = Relationship.Direction.OUTGOING)
    @AST
    override var statementEdges: MutableList<PropertyEdge<Statement>> = ArrayList()

    override val declarations: List<Declaration>
        get() = unwrap(declarationEdges)

    override var statements: List<Statement> by
        PropertyEdgeDelegate(TranslationUnitDeclaration::statementEdges)

    val includes: List<IncludeDeclaration>
        get() = unwrap(includeEdges)

    val namespaces: List<NamespaceDeclaration>
        get() = unwrap(namespaceEdges)

    override fun addDeclaration(declaration: Declaration) {
        if (declaration is IncludeDeclaration) {
            addIfNotContains(includeEdges, declaration)
        } else if (declaration is NamespaceDeclaration) {
            addIfNotContains(namespaceEdges, declaration)
        }
        addIfNotContains(declarationEdges, declaration)
    }

    override fun toString(): String {
        return ToStringBuilder(this, TO_STRING_STYLE)
            .append("declarations", declarationEdges)
            .append("includes", includeEdges)
            .append("namespaces", namespaceEdges)
            .toString()
    }

    override val eogStarters: List<Node>
        get() {
            val list = mutableListOf<Node>()
            // Add all top-level declarations
            list += declarations
            // Add the TU itself, so that we can catch any static statements in the TU
            list += this

            return list
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TranslationUnitDeclaration) return false
        // TODO: This statement doesn't make sense to me. The declarationsPropertyEdge comparison is
        // more strict than the propertyEqualsList() isn't it?
        return super.equals(other) &&
            declarations == other.declarations &&
            propertyEqualsList(declarationEdges, other.declarationEdges) &&
            includes == other.includes &&
            propertyEqualsList(includeEdges, other.includeEdges) &&
            namespaces == other.namespaces &&
            propertyEqualsList(namespaceEdges, other.namespaceEdges)
    }

    override fun hashCode() = Objects.hash(super.hashCode(), includes, namespaces, declarations)
}
