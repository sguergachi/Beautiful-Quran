package com.beautifulquran.ui.rootviewer

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class RootViewerReference(
    val title: String,
    val description: String,
    val url: String,
)

/** Stable, location-aware learning links for a word in the Root Viewer. */
internal fun rootViewerReferences(
    surahId: Int,
    ayah: Int,
    position: Int,
    root: String,
): List<RootViewerReference> = buildList {
    val location = encode("($surahId:$ayah:$position)")
    add(
        RootViewerReference(
            "Detailed word grammar",
            "Quranic Arabic Corpus analysis of this exact word and its segments.",
            "https://corpus.quran.com/wordmorphology.jsp?location=$location",
        ),
    )
    if (root.isNotBlank()) {
        add(
            RootViewerReference(
                "Quran root dictionary",
                "Quran-wide senses and derived forms grouped under this root.",
                "https://corpus.quran.com/qurandictionary.jsp?q=${encode(arabicToBuckwalter(root))}",
            ),
        )
    }
    add(
        RootViewerReference(
            "Read the full ayah",
            "Translations, recitation, and tafsir in the verse's wider context.",
            "https://quran.com/$surahId/$ayah",
        ),
    )
}

/** Hawramani's Lane entry for [root] — shown under the Classical lexicon section. */
internal fun laneLexiconUrl(root: String): String =
    "https://arabiclexicon.hawramani.com/search/${encode(root)}?cat=50"

private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

/** QAC's root-dictionary query uses its extended Buckwalter spelling. */
internal fun arabicToBuckwalter(value: String): String = value.map { char ->
    when (char) {
        'ء' -> "'"
        'آ' -> "|"
        'أ' -> ">"
        'ؤ' -> "&"
        'إ' -> "<"
        'ئ' -> "}"
        'ا' -> "A"
        'ب' -> "b"
        'ت' -> "t"
        'ث' -> "v"
        'ج' -> "j"
        'ح' -> "H"
        'خ' -> "x"
        'د' -> "d"
        'ذ' -> "*"
        'ر' -> "r"
        'ز' -> "z"
        'س' -> "s"
        'ش' -> "$"
        'ص' -> "S"
        'ض' -> "D"
        'ط' -> "T"
        'ظ' -> "Z"
        'ع' -> "E"
        'غ' -> "g"
        'ف' -> "f"
        'ق' -> "q"
        'ك' -> "k"
        'ل' -> "l"
        'م' -> "m"
        'ن' -> "n"
        'ه' -> "h"
        'و' -> "w"
        'ى' -> "Y"
        'ي' -> "y"
        'ة' -> "p"
        'ٱ' -> "{"
        else -> char.toString()
    }
}.joinToString("")
