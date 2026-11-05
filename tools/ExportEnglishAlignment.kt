import com.beautifulquran.domain.EnglishTypography
import com.beautifulquran.domain.EnglishWordAlignment
import com.beautifulquran.domain.ReviewedEnglishAlignment
import java.io.File
import java.util.Base64

// Invoked by the offline exporter; uses the production typesetter and tokenizer.
fun main(args: Array<String>) {
    fun decode(value: String) = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    fun encode(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    val rows = File(args[0]).readLines().map { it.split('\t') }
    rows.forEachIndexed { index, row ->
        val (key, raw, surfaces, glosses, lemmas) = row
        val next = rows.getOrNull(index + 1)?.takeIf { it[0].substringBefore(':') == key.substringBefore(':') }
        val continues = EnglishTypography.quoteContinuesInto(next?.let { decode(it[1]) })
        val text = EnglishTypography.typeset(decode(raw), continues)
        val arabic = decode(surfaces).split('\u0000')
        val cards = decode(glosses).split('\u0000').map(EnglishTypography::typesetName)
        val alignment = cards.takeIf { it.any(String::isNotBlank) }?.let {
            EnglishWordAlignment.align(text, it, decode(lemmas).split('\u0000'), arabic)
        }
        val tokens = ReviewedEnglishAlignment.tokens(text).joinToString(";") { range ->
            val middle = (range.first + range.last + 1f) / 2 / text.length
            val head = alignment?.positionAt(middle)?.toString().orEmpty()
            val ink = head
            "${range.first},${range.last + 1},${encode(text.substring(range))},$head,$ink"
        }
        println(listOf(key, encode(text), ReviewedEnglishAlignment.fingerprint(text, arabic), tokens).joinToString("\t"))
    }
}
