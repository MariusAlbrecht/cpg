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

class RunPlugin : Plugin {
    inner class Load(conf: ReplConfiguration) : BaseCommand() {
        override val name: String by conf.get(default = "run")
        override val short: String by conf.get(default = "r")
        override val description: String =
            "runs an analyzer, or multiple separated by space, or all if none " + "are specified"

        override val params = "<analyzer>"

        override fun execute(line: String): Command.Result {
            val snippet = mutableListOf<String>() // to be constructed
            val p = line.indexOf(' ')
            if (p == -1) {
                // no analyzer specified -> run all
                snippet.add("de.fraunhofer.aisec.cpg.analysis.Analyzer.allAnalyzers.forEach { it.run(result) }")
            } else {
                val analyzers = line.substring(p + 1).trim().split(" ")

                for (analyzer in analyzers) {
                    if (Analyzer.allAnalyzers.contains(analyzer)) {
                        snippet.add("de.fraunhofer.aisec.cpg.analysis.$analyzer().run(result)")
                    } else {
                        println(
                            // TODO: logging
                            "Analyzer \"$analyzer\" does not exist or isn't specified in de.fraunhofer.aisec.cpg.analysis.Analyzer.allAnalyzers"
                        )
                    }
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
