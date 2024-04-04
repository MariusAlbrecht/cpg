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
package de.fraunhofer.aisec.cpg.console

import de.fraunhofer.aisec.cpg.analysis.Analyzer
import org.jetbrains.kotlinx.ki.shell.BaseCommand
import org.jetbrains.kotlinx.ki.shell.Command
import org.jetbrains.kotlinx.ki.shell.Plugin
import org.jetbrains.kotlinx.ki.shell.Shell
import org.jetbrains.kotlinx.ki.shell.configuration.ReplConfiguration

private const val s = "Analyzer"

class RunPlugin : Plugin {
    inner class Load(conf: ReplConfiguration) : BaseCommand() {
        override val name: String by conf.get(default = "run")
        override val short: String by conf.get(default = "r")
        override val description: String = "runs an analyzer, or multiple separated by space."

        override val params = "<analyzer>"

        override fun execute(line: String): Command.Result {
            val p = line.indexOf(' ')
            if (p == -1) {
                return Command.Result.Failure("not enough arguments")
            }

            val analyzers = line.substring(p + 1).trim().split(" ")
            val snippet = mutableListOf<String>() // to be constructed

//            // check if `result` is defined because it is required for the analyzers
//            snippet.add(
//                """
//                if(
//                    try {
//                        Class.forName("result")
//                        false
//                    } catch (e: Exception) {
//                        true
//                    }
//                ) {
//                    println("result is not defined. Please run a translation first.")
//                }
//            """.trimIndent()
//            )

            for (analyzer in analyzers) {
//                val validAnalyzer =
//                    try {
//                        // exists and has run method with signature run(TranslationResult): Void
//                        Class.forName("de.fraunhofer.aisec.cpg.analysis.$analyzer")
//                            .getDeclaredMethod(
//                                "run",
//                                de.fraunhofer.aisec.cpg.TranslationResult::class.java
//                            )
//                            .returnType == Void.TYPE
//                    } catch (e: Exception) {
//                        false
//                    }
                if (Analyzer.allAnalyzers.contains(analyzer)) {
                    snippet.add("de.fraunhofer.aisec.cpg.analysis.$analyzer().run(result)")
                } else {
                    println(
                        // TODO: logging
                        "Analyzer \"$analyzer\" does not exist or isn't specified in de.fraunhofer.aisec.cpg.analysis.Analyzer.allAnalyzers"
                    )
                }
            }
            return Command.Result.RunSnippets(snippet)
        }
    }

    lateinit var repl: Shell

    override fun init(repl: Shell, config: ReplConfiguration) {
        this.repl = repl

        repl.registerCommand(Load(config))
    }

    override fun cleanUp() {
        // nothing to do
    }
}
