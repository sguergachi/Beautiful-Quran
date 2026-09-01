package com.beautifulquran.data

import android.content.Context
import com.beautifulquran.domain.SearchConcept
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lazily decodes the small, packaged QSAC concept index on the first search. */
class SearchConceptRepository(context: Context) {
    private val assets = context.applicationContext.assets

    @Volatile
    private var cache: List<SearchConcept>? = null

    fun concepts(): List<SearchConcept> = cache ?: assets.open(ASSET_NAME).bufferedReader().use {
        decodeSearchConcepts(it.readText())
    }.also { cache = it }

    private companion object {
        const val ASSET_NAME = "search_concepts.json"
    }
}

internal fun decodeSearchConcepts(text: String): List<SearchConcept> {
    val asset = Json.decodeFromString<SearchConceptAsset>(text)
    check(asset.version == 1) { "Unsupported search concept asset ${asset.version}" }
    check(asset.sourceCommit == QSAC_SOURCE_COMMIT) { "Unexpected QSAC source ${asset.sourceCommit}" }
    return asset.concepts.map { concept ->
        SearchConcept(
            name = concept.name,
            primaryTerms = concept.primary,
            secondaryTerms = concept.secondary,
            category = concept.category,
            domain = concept.domain,
            ayahKeys = concept.ayahs.toIntArray(),
        )
    }
}

private const val QSAC_SOURCE_COMMIT = "cb3852b127bfdda6668c5eec9e5c1d9cdcde3810"

@Serializable
private data class SearchConceptAsset(
    val version: Int,
    val source: String,
    val sourceCommit: String,
    val concepts: List<SearchConceptJson>,
)

@Serializable
private data class SearchConceptJson(
    @SerialName("n") val name: String,
    @SerialName("p") val primary: List<String>,
    @SerialName("s") val secondary: List<String>,
    @SerialName("c") val category: String,
    @SerialName("d") val domain: String,
    @SerialName("a") val ayahs: List<Int>,
)
