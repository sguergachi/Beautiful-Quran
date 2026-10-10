package com.beautifulquran.ui.theme

import kotlinx.serialization.Serializable

/** The Latin voices that can be auditioned independently in Ink Lab. */
internal enum class TypeRole(val label: String) {
    Book("Book"), Leaf("Leaf"), BookItalic("Italic"), Title("Titles"), Ui("UI"), Note("Notes")
}

/** Variable axes, spacing and OpenType overrides for one Timeless face. */
@Serializable
data class TypefaceTuning(
    val weight: Int = 400,
    val style: Float = 100f,
    val italic: Float = 0f,
    val size: Float = 1f,
    val leading: Float = 1f,
    val tracking: Float = 0f,
    val features: Map<String, Int> = mapOf("kern" to 1, "liga" to 1),
) {
    val featureSettings: String
        get() = features.toSortedMap().entries.joinToString(", ") { "'${it.key}' ${it.value}" }
}

/** Saved alongside the other Ink Lab knobs; classic typography ignores these values. */
@Serializable
data class TypographyTuning(
    val book: TypefaceTuning = TypefaceTuning(),
    val bookItalic: TypefaceTuning = TypefaceTuning(style = 0f, italic = 100f),
    val title: TypefaceTuning = TypefaceTuning(style = 0f),
    val ui: TypefaceTuning = TypefaceTuning(
        weight = 420, features = mapOf("kern" to 1, "calt" to 1),
    ),
    val note: TypefaceTuning = TypefaceTuning(weight = 500, italic = 100f),
    /**
     * The English Mushaf leaf's hand, tuned apart from [book] because the leaf is
     * read for long stretches at one size while the rest of the app sets short
     * lines at many. Worked out in ten measured rounds against Garamond
     * (docs/ENGLISH_LEAF_TYPE.md): weight 365 matches Garamond's page colour and
     * stem thickness; contrast 25 gives hairlines of 1.6 px, as Garamond's;
     * [tracking] is added to the leaf's −0.025 em squeeze, easing it to −0.0125.
     */
    val leaf: TypefaceTuning = LEAF_DEFAULT,
) {
    /**
     * The same tuning for light ink on dark paper. Light type spreads into the
     * dark around it and reads heavier than the same weight in dark ink on
     * cream, so every role eases by [DARK_PAPER_WEIGHT_EASE] — wght 370 where
     * paper has 400. The variable axis makes that a continuous step; a static
     * cut could only jump a whole weight. Applied on top of whatever Ink Lab
     * has tuned, so the lab's numbers stay the paper-side values.
     */
    internal fun forDarkPaper(): TypographyTuning = copy(
        book = book.eased(), bookItalic = bookItalic.eased(), title = title.eased(),
        ui = ui.eased(), note = note.eased(), leaf = leaf.eased(),
    )

    private fun TypefaceTuning.eased() = copy(weight = weight - DARK_PAPER_WEIGHT_EASE)

    internal fun face(role: TypeRole): TypefaceTuning = when (role) {
        TypeRole.Book -> book
        TypeRole.Leaf -> leaf
        TypeRole.BookItalic -> bookItalic
        TypeRole.Title -> title
        TypeRole.Ui -> ui
        TypeRole.Note -> note
    }

    internal fun withFace(role: TypeRole, face: TypefaceTuning): TypographyTuning = when (role) {
        TypeRole.Book -> copy(book = face)
        TypeRole.Leaf -> copy(leaf = face)
        TypeRole.BookItalic -> copy(bookItalic = face)
        TypeRole.Title -> copy(title = face)
        TypeRole.Ui -> copy(ui = face)
        TypeRole.Note -> copy(note = face)
    }

    /** Includes every book and leaf metric and feature; excludes unrelated title/UI/note edits. */
    internal val bookCacheKey: String
        get() = java.security.MessageDigest.getInstance("SHA-256")
            .digest("vf3-$book-$bookItalic-$leaf".toByteArray())
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

/** Reviewed against the GSUB/GPOS tables of the pinned Timeless 1.094 archive. */
internal data class TypeFeature(val tag: String, val label: String, val specimen: String)

internal fun timelessFeatures(sans: Boolean, italic: Boolean): List<TypeFeature> {
    val sets = when {
        sans -> listOf(
            "a with tail" to "a", "Single storey a" to "a", "Alternate y" to "y",
            "Alternate Q" to "Q", "Alternate G" to "G", "Round punctuation" to "!?.:;…ij",
            "Round quotes" to "‘’“”,;", "Square quotes" to "‘’“”,;", "Alternate R" to "R",
            "Alternate figures" to "0123456789", "Alternate ampersand" to "&",
            "Circled figures" to "0123456789", "Filled circled figures" to "0123456789", "a variant III" to "a",
        ).mapIndexed { i, (label, specimen) -> TypeFeature("ss%02d".format(i + 1), label, specimen) }
        italic -> listOf(
            "ss01" to "C", "ss02" to "G", "ss03" to "K", "ss04" to "M", "ss05" to "Q",
            "ss06" to "R", "ss07" to "W", "ss09" to "&", "ss12" to "0123456789",
            "ss13" to "0123456789",
        ).map { (tag, specimen) -> TypeFeature(tag, when (tag) {
            "ss12" -> "Circled figures"
            "ss13" -> "Filled circled figures"
            else -> "Alternate $specimen"
        }, specimen) }
        else -> listOf("a", "a", "g", "c", "c", "j", "f", "y", "r", "y", "&",
            "C", "G", "K", "M", "Q", "R", "W", "0123456789", "0123456789",
        ).mapIndexed { i, specimen -> TypeFeature("ss%02d".format(i + 1), when (i) {
            18 -> "Circled figures"
            19 -> "Filled circled figures"
            else -> "Alternate $specimen"
        }, specimen) }
    }
    val common = listOf(
        TypeFeature("kern", "Kerning", "AV To Wa"),
        TypeFeature("dlig", "Discretionary ligatures", if (sans) "ff fi fl ffi ffl" else "fj ffj ft"),
        TypeFeature("case", "Case punctuation", "H (HELLO) [123456789]"),
        TypeFeature("tnum", "Tabular figures", "0123456789"),
        TypeFeature("pnum", "Proportional figures", "0123456789"),
        TypeFeature("frac", "Fractions", "1/2 3/4 7/8"),
        TypeFeature("numr", "Numerators", "0123456789"),
        TypeFeature("dnom", "Denominators", "0123456789"),
        TypeFeature("ordn", "Ordinals", "1st 2nd 3rd 4th a o"),
    )
    return sets + common + if (sans) {
        listOf(TypeFeature("calt", "Contextual alternates", "f fi ff ft (j)"))
    } else {
        listOf(
            TypeFeature("liga", "Standard ligatures", "ff fi fl ffi ffl"),
            TypeFeature("onum", "Old-style figures", "0123456789"),
            TypeFeature("lnum", "Lining figures", "0123456789"),
        )
    }
}

/** The leaf's shipped hand; see [TypographyTuning.leaf]. */
internal val LEAF_DEFAULT = TypefaceTuning(weight = 365, style = 25f, tracking = 0.0125f)

/** How far light-on-dark ink's weight eases against dark-on-paper ink; see [TypographyTuning.forDarkPaper]. */
internal const val DARK_PAPER_WEIGHT_EASE = 30
