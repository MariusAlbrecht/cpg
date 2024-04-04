package de.fraunhofer.aisec.cpg.analysis

import de.fraunhofer.aisec.cpg.TranslationResult

interface Analyzer {

    fun run(result: TranslationResult)

    companion object {
        /**
         * List of all available analyzers. Add new analyzers here.
         */
        val allAnalyzers: Array<String> = arrayOf(
            "NullPointerCheck",
            "OutOfBoundsCheck"
        )
    }
}