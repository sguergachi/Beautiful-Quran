package com.beautifulquran.data

import android.content.Context
import com.beautifulquran.domain.RelatedSearchTerm
import com.beautifulquran.domain.SearchConcept
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** Concepts and focused English thesaurus links loaded together on first search. */
data class SearchVocabulary(
    val concepts: List<SearchConcept>,
    val thesaurus: Map<String, List<RelatedSearchTerm>>,
)

/** Lazily decodes packaged QSAC concepts and focused WordNet links on first search. */
class SearchConceptRepository(context: Context) {
    private val assets = context.applicationContext.assets
    private val conceptLock = Any()

    @Volatile
    private var cache: SearchVocabulary? = null
    @Volatile
    private var conceptCache: List<SearchConcept>? = null

    @Synchronized
    fun vocabulary(): SearchVocabulary = cache ?: assets.open(ASSET_NAME).bufferedReader().use {
        decodeSearchVocabulary(it.readText())
    }.also {
        cache = it
        conceptCache = it.concepts
    }

    /** Loads the small concept-only candidate set without waiting for WordNet decoding. */
    fun concepts(): List<SearchConcept> = cache?.concepts ?: conceptCache ?: synchronized(conceptLock) {
        cache?.concepts ?: conceptCache ?: assets.open(CANDIDATE_ASSET_NAME).bufferedReader().use {
            decodeSearchConcepts(it.readText())
        }.also { if (cache == null) conceptCache = it }
    }

    private companion object {
        const val ASSET_NAME = "search_concepts.json"
        const val CANDIDATE_ASSET_NAME = "search_concept_candidates.json"
    }
}

internal fun decodeSearchVocabulary(text: String): SearchVocabulary {
    val asset = Json.decodeFromString<SearchConceptAsset>(text)
    validateSearchAsset(asset.version, asset.sourceCommit, asset.thesaurusSha256)
    val concepts = asset.concepts.toDomain()
    val thesaurus = asset.thesaurus.mapValues { (_, related) ->
        related.map { pair ->
            check(pair.size == 2) { "Invalid thesaurus pair $pair" }
            RelatedSearchTerm(pair[0].jsonPrimitive.content, pair[1].jsonPrimitive.int)
        }
    }
    return SearchVocabulary(concepts, thesaurus)
}

internal fun decodeSearchConcepts(text: String): List<SearchConcept> {
    val asset = Json.decodeFromString<SearchConceptCandidateAsset>(text)
    validateSearchAsset(asset.version, asset.sourceCommit, asset.thesaurusSha256)
    return asset.concepts.toDomain()
}

private fun List<SearchConceptJson>.toDomain(): List<SearchConcept> = map { concept ->
    SearchConcept(
        name = concept.name,
        primaryTerms = concept.primary,
        secondaryTerms = concept.secondary,
        category = concept.category,
        domain = concept.domain,
        ayahKeys = concept.ayahs.toIntArray(),
    )
}

private fun validateSearchAsset(version: Int, sourceCommit: String, thesaurusSha256: String) {
    check(version == 2) { "Unsupported search concept asset $version" }
    check(sourceCommit == QSAC_SOURCE_COMMIT) { "Unexpected QSAC source $sourceCommit" }
    check(thesaurusSha256 == WORDNET_SHA256) { "Unexpected thesaurus source $thesaurusSha256" }
}

private const val QSAC_SOURCE_COMMIT = "cb3852b127bfdda6668c5eec9e5c1d9cdcde3810"
private const val WORDNET_SHA256 = "38b16326159f51853626b7d24a44c453fa88ab33f06fce5ec8fc5996d1c2be93"

@Serializable
private data class SearchConceptAsset(
    val version: Int,
    val source: String,
    val sourceCommit: String,
    val thesaurusSource: String,
    val thesaurusSha256: String,
    val concepts: List<SearchConceptJson>,
    val thesaurus: Map<String, List<JsonArray>>,
)

@Serializable
private data class SearchConceptCandidateAsset(
    val version: Int,
    val source: String,
    val sourceCommit: String,
    val thesaurusSource: String,
    val thesaurusSha256: String,
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
