#!/usr/bin/env python3
"""Audit English ownership and continuous prefixes; literal scores are not semantics.

Input TSV: verse_key, published_translation, Arabic-ordered glosses joined by |.
Uses an external QF cache export; never downloads or changes packaged data.
"""
import argparse
import collections
import decimal
import hashlib
import json
import re
import sqlite3
import subprocess
import tempfile
import unicodedata
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("corpus", type=Path)
parser.add_argument("--output-dir", type=Path, required=True)
parser.add_argument("--baseline-ends", type=Path, help="Optional TSV of verse_key and comma-separated character ends")
parser.add_argument("--semantic-cases", type=Path, action="append", help="Annotated TSV: verse_key, translation, glosses, word=owner; repeatable")
parser.add_argument("--spoken-cases", type=Path, action="append", help="Same TSV, checking the full printed meaning has washed by its Arabic owner")
parser.add_argument("--meaning-cases", type=Path, help="TSV of printed regex, Arabic root, lemma, and gloss cue; * means unrestricted")
parser.add_argument("--arabic-cases", type=Path, help="TSV of printed regex, Arabic root, lemma, and POS; independent of input English glosses")
parser.add_argument("--qac-source", type=Path, help="Original QAC 0.4 morphology; required for independent Arabic checks to reject mismatched word counts")
parser.add_argument("--semantic-reference", type=Path, help="Reviewed TSV: verse_key, English token start, end, text, Arabic owner; unknown owners stay blank")
parser.add_argument("--require-semantic-accuracy", type=decimal.Decimal, help="Required percent over every printed word, including grammar, additions and unreviewed words")
parser.add_argument("--all-printed-words", action="store_true", help="Include grammar and translator additions; automatic with a semantic reference or required accuracy")
parser.add_argument("--strict-meaning-owners", action="store_true", help="Fail on any QAC meaning-owner disagreement, even if the wash reaches it early")
parser.add_argument("--word-by-word", action="store_true", help="Audit the actual lyricized word-card text as the second English source")
args = parser.parse_args()
args.all_printed_words |= args.require_semantic_accuracy is not None or args.semantic_reference is not None
root = args.output_dir
root.mkdir(parents=True, exist_ok=True)
source = Path(__file__).resolve().parents[1] / "app/src/main/java/com/beautifulquran"
probe = r'''
import com.beautifulquran.domain.EnglishWordAlignment
import java.io.File
import kotlin.math.roundToInt
fun main(args: Array<String>) {
    File(args[0]).forEachLine { line ->
        val columns = line.split('\t')
        val (key, published, gloss) = columns
        val text = if (args.size > 1) com.beautifulquran.domain.EnglishTypography.lyricize(
            gloss.split('|'), columns[4].split('|'),
        ).filter { it.isNotBlank() }.joinToString(" ") else published
        val alignment = EnglishWordAlignment.align(text, gloss.split('|'), columns[3].split('|'), columns[4].split('|')) ?: error(key)
        fun encode(spans: List<com.beautifulquran.domain.EnglishWordSpan>) = spans.joinToString(";") {
            "${(it.from * text.length).roundToInt()},${(it.to * text.length).roundToInt()},${it.position}"
        }
        println(key + (if (args.size > 1) "\t$text\t$gloss" else "") +
            "\t" + encode(alignment.spans) + "\t" + encode(alignment.washSpans))
    }
}
'''
with tempfile.TemporaryDirectory(prefix="english-alignment-audit-") as temporary:
    directory = Path(temporary)
    with sqlite3.connect(f'file:{source.parents[5]/"data/quran.db"}?mode=ro', uri=True) as db:
        arabic = collections.defaultdict(list)
        lemmas = collections.defaultdict(list)
        for surah, ayah, text, lemma in db.execute('''
            select w.surah_id,w.ayah_number,w.arabic,m.lemma from words w
            left join word_morphology m using(surah_id,ayah_number,position)
            order by w.surah_id,w.ayah_number,w.position'''):
            key = f'{surah}:{ayah}'
            arabic[key].append(text)
            lemmas[key].append(lemma or '')
    input_corpus = directory / 'input.tsv'
    input_corpus.write_text('\n'.join(line + '\t' + '|'.join(lemmas[line.split('\t')[0]]) +
                                     '\t' + '|'.join(arabic[line.split('\t')[0]])
                                     for line in args.corpus.read_text().splitlines()) + '\n')
    (directory / "Probe.kt").write_text(probe)
    sources = [source / f for f in ("domain/EnglishWordAlignment.kt", "domain/EnglishTypography.kt",
                                   "domain/ArabicNormalize.kt", "data/model/Models.kt")]
    subprocess.run(["kotlinc", *map(str, sources), str(directory / "Probe.kt"),
                    "-include-runtime", "-d", str(directory / "probe.jar")], check=True)
    result = subprocess.run(["java", "-jar", str(directory / "probe.jar"), str(input_corpus), *(["lyricize"] if args.word_by_word else [])],
                            capture_output=True, text=True, check=True)
    if args.word_by_word:
        rows = [line.split('\t') for line in result.stdout.splitlines()]
        (root / 'corpus.tsv').write_text('\n'.join('\t'.join(row[:3]) for row in rows) + '\n')
        result.stdout = '\n'.join('\t'.join((row[0], *row[3:])) for row in rows) + '\n'
    (root / "ownership.tsv").write_text(result.stdout)

stop = set("""
a all an and are as at be been but by did do does for from he her his i if in is it its no nor
not of on or she so that the their them these they this those to was we were will with you can
could had has have may might must our shall should there then what when where which who whom
whose would your my any both each every even more most oft ones only other others over surely
well down forth up about above after against along among around before behind below beside
besides between concerning during into off out through toward towards under until upon while
within without him me us ours yours yourself yourselves ourselves himself herself itself
themselves although because hence however otherwise therefore though used whatever whenever
wherever whether whoever
""".split())

def tokens(text):
    depth=0; out=[]
    for match in re.finditer(r"\[|\]|\(|\)|[A-Za-z]+(?:['’][A-Za-z]*)*",text):
        t=match.group()
        if t in '[(': depth+=1; continue
        if t in '])': depth=max(0,depth-1); continue
        k=t.lower().replace('’',"'").removesuffix("'s").replace("'",'')
        if len(k)>4 and k.endswith('s') and not k.endswith(('ss','us')): k=k[:-1]
        out.append((k,match.start(),match.end(),depth>0))
    return out

rows=[line.split('\t') for line in ((root / 'corpus.tsv') if args.word_by_word else args.corpus).read_text().splitlines()]
def decode(encoded):
    return [tuple(map(int,span.split(','))) for span in encoded.split(';')]

maps={k:(decode(owned),decode(wash)) for k,owned,wash in (l.split('\t') for l in (root/'ownership.tsv').read_text().splitlines())}
# Validate reviewed labels before using them to adjudicate dictionary guesses.
population={(key,a,b):(text[a:b],len(gs.split('|'))) for key,text,gs in rows
            for word,a,b,added in tokens(text) if args.all_printed_words or (not added and word not in stop)}
reference={}
if args.semantic_reference:
    for line in args.semantic_reference.read_text().splitlines():
        if not line or line.startswith('#'): continue
        key,start,end,word,expected=line.split('\t')
        point=(key,int(start),int(end))
        assert point in population and population[point][0]==word,(point,'reference differs from frozen corpus')
        assert point not in reference,(point,'duplicate label cannot increase accuracy')
        reference[point]=int(expected) if expected else None
        assert reference[point] is None or 1<=reference[point]<=population[point][1],(point,'invalid Arabic owner')
counts=collections.Counter();issues=[];candidate_reference_issues=[]
prior={k:list(map(int,e.split(','))) for k,e in (line.split('\t') for line in args.baseline_ends.read_text().splitlines())} if args.baseline_ends else {}
for key,text,gs in rows:
    glosses=gs.split('|'); owned,wash=maps[key]
    assert owned[0][0]==0 and owned[-1][1]==len(text),(key,'finish')
    assert all(a<b and 1<=p<=len(glosses) for a,b,p in owned),(key,'range')
    assert all(prev[1]==nxt[0] for prev,nxt in zip(owned,owned[1:])),(key,'tiling')
    assert all(a in (0,len(text)) or not(text[a-1].isalpha() and text[a].isalpha()) for a,_,_ in owned),(key,'split')
    counts['structural_verses']+=1
    assert wash[0][0]==0 and wash[-1][1]==len(text),(key,'wash finish')
    assert all(a<b and 1<=p<=len(glosses) for a,b,p in wash),(key,'wash range')
    assert all(prev[1]==nxt[0] and prev[2]<nxt[2] for prev,nxt in zip(wash,wash[1:])),(key,'wash gap or rewind')
    for position in range(1,len(glosses)+1):
        expected=max((b for a,b,p in owned if p<=position),default=0)
        reached=[(a,b) for a,b,p in wash if p<=position]
        assert (reached[-1][1] if reached else 0)==expected,(key,position,'delayed meaning')
        assert not reached or reached[0][0]==0,(key,position,'skipped prefix')
        counts['continuous_wash_steps']+=1
    spoken=collections.defaultdict(list);printed=collections.defaultdict(list)
    for i,g in enumerate(glosses):
        for word,_,_,added in tokens(g):
            if word not in stop and not added: spoken[word].append(i+1)
    for word,a,b,added in tokens(text):
        if not added and word not in stop: printed[word].append((a,b))
    for word,occurrences in printed.items():
        owners=spoken.get(word,[])
        if len(owners)!=len(occurrences) or len(set(owners))!=len(owners): continue
        # Equal repeats only within one sentence: lexical equality alone does not
        # distinguish meanings reused across clauses (e.g. the burdens in 2:286).
        if len(owners)>1 and len(set(len(re.findall(r'[.?!;]',text[:a])) for a,b in occurrences))>1: continue
        for (a,b),expected in zip(occurrences,owners):
            actual=next(p for lo,hi,p in owned if lo<=(a+b)//2<hi)
            counts['literal_correspondences']+=1
            if prior:
                before=next((i+1 for i,end in enumerate(prior[key]) if end>(a+b)//2),len(glosses))
                counts['prior_on_owner' if before==expected else 'prior_disagreement']+=1
            if actual==expected: counts['on_owner']+=1
            else:
                counts['early' if actual<expected else 'late']+=1
                issues.append((key,word,expected,actual))
for paths,exact in ((args.semantic_cases,True),(args.spoken_cases,False)):
    if not paths: continue
    corpus={key:(text,glosses) for key,text,glosses in rows}
    for path in paths:
        for line in path.read_text().splitlines():
            key,text,glosses,targets=line.split('\t')[:4]
            assert corpus[key]==(text,glosses),(key,'fixture differs from frozen corpus')
            for target in targets.split('|'):
                word,occurrence,position=re.fullmatch(r'(.+?)(?:#(\d+))?=(\d+)',target).groups()
                at=list(re.finditer(r'\b'+re.escape(word)+r'\b',text,re.I))[int(occurrence or 1)-1]
                middle=(at.start()+at.end())//2
                owned,wash=maps[key]
                actual=next(p for a,b,p in owned if a<=middle<b)
                if exact: assert actual==int(position),(key,word,'semantic owner',actual,position)
                assert next(p for a,b,p in wash if a<=at.end()-1<b)<=int(position),(key,word,'spoken meaning wash delayed')
                counts['annotated_semantic_cases' if exact else 'annotated_spoken_cases']+=1

meaning_issues=[]
if args.meaning_cases or args.arabic_cases:
    counts['meaning_wash_delays']=0
    def bare_arabic(text):
        return ''.join(c for c in text if unicodedata.category(c)!='Mn' and c!='ـ')
    database=sqlite3.connect(f'file:{source.parents[5]/"data/quran.db"}?mode=ro',uri=True)
    morphology={(f'{s}:{a}',p):(root,lemma,pos) for s,a,p,root,lemma,pos in
                database.execute('select surah_id,ayah_number,position,root,lemma,pos from word_morphology')}
    database.close()
    untrusted=set()
    if args.arabic_cases:
        if not args.qac_source: raise SystemExit('--arabic-cases requires --qac-source; clamped DB rows are not an independent position reference')
        qac_counts=collections.defaultdict(int)
        for line in args.qac_source.read_text().splitlines():
            location=re.match(r'\((\d+):(\d+):(\d+):\d+\)',line)
            if location:
                s,a,p=map(int,location.groups()); key=f'{s}:{a}'
                qac_counts[key]=max(qac_counts[key],p)
        untrusted={key for key,_,gs in rows if qac_counts[key]!=len(gs.split('|'))}
        (root/'untrusted-morphology.tsv').write_text('\n'.join(
            f"{key}\t{len(gs.split('|'))}\t{qac_counts[key]}" for key,_,gs in rows if key in untrusted)+'\n')
        counts['untrusted_morphology_verses']=len(untrusted)
    meaning_coverage={}
    rules = [(line, True) for line in args.meaning_cases.read_text().splitlines()] if args.meaning_cases else []
    if args.arabic_cases: rules += [(line, False) for line in args.arabic_cases.read_text().splitlines()]
    for line,uses_gloss in rules:
        if not line or line.startswith('#'): continue
        printed,meaning_root,lemma,cue=line.split('\t')
        pattern=re.compile(r'\b(?:'+printed+r')\b',re.I)
        covered=0
        for key,text,gs in rows:
            if key in untrusted: continue
            added=[(a,b) for _,a,b,is_added in tokens(text) if is_added]
            matches=[m for m in pattern.finditer(text) if not any(a<=m.start()<b for a,b in added)]
            owners=[p for p,g in enumerate(gs.split('|'),1) if
                    (re.search(cue,g,re.I) if uses_gloss else morphology.get((key,p),('','',''))[2]==cue) and
                    (meaning_root=='*' or morphology.get((key,p),('','',''))[0]==meaning_root) and
                    (lemma=='*' or (bare_arabic(morphology.get((key,p),('','',''))[1]) if uses_gloss
                                   else morphology.get((key,p),('','',''))[1])==lemma)]
            if not matches or len(matches)!=len(owners): continue
            for match,expected in zip(matches,owners):
                middle=(match.start()+match.end())//2
                owned,wash=maps[key]
                actual=next(p for a,b,p in owned if a<=middle<b)
                washed=next(p for a,b,p in wash if a<=match.end()-1<b)
                reviewed=reference.get((key,match.start(),match.end()))
                if reviewed is not None and reviewed!=expected:
                    candidate_reference_issues.append((key,match.group(),expected,reviewed,actual))
                    counts['candidate_reference_disagreement']+=1
                    expected=reviewed
                counts['morphology_meaning_cases']+=1
                if not uses_gloss: counts['independent_arabic_cases']+=1
                covered+=1
                counts['meaning_on_owner' if actual==expected else 'meaning_owner_disagreement']+=1
                if washed>expected: counts['meaning_wash_delays']+=1
                if actual!=expected or washed>expected: meaning_issues.append((key,match.group(),expected,actual,washed))
        assert covered, (printed, 'meaning rule covers no cases')
        meaning_coverage[f'{printed}:{meaning_root}:{lemma}']=covered
    (root/'meaning-coverage.json').write_text(json.dumps(meaning_coverage,indent=2)+'\n')

(root/'ownership-audit.json').write_text(json.dumps(dict(counts),indent=2)+'\n')
(root/'ownership-issues.tsv').write_text('\n'.join('\t'.join(map(str,r)) for r in issues)+'\n')
(root/'meaning-issues.tsv').write_text('\n'.join('\t'.join(map(str,r)) for r in meaning_issues)+'\n')
(root/'candidate-reference-issues.tsv').write_text('\n'.join('\t'.join(map(str,r)) for r in candidate_reference_issues)+'\n')

# Repeated timing/frame checks and dictionary candidates cannot fill in gold labels.
correct=0; reviewed=0; wrong=[]
template=[]
for (key,start,end),(word,_) in population.items():
    expected=reference.get((key,start,end))
    template.append(f'{key}\t{start}\t{end}\t{word}\t{expected if expected is not None else ""}')
    if expected is None: continue
    reviewed+=1
    actual=next(p for a,b,p in maps[key][0] if a<=(start+end)//2<b)
    if actual==expected: correct+=1
    else: wrong.append((key,start,end,word,expected,actual))
target=args.require_semantic_accuracy
if target is not None and not 0<target<=100: raise SystemExit('semantic accuracy must be a percent in (0,100]')
denominator=len(population)
lower=decimal.Decimal(100)*correct/denominator if denominator else decimal.Decimal(0)
certificate={'corpus_sha256':hashlib.sha256(((root/'corpus.tsv') if args.word_by_word else args.corpus).read_bytes()).hexdigest(),
             'mapper_sha256':hashlib.sha256((source/'domain/EnglishWordAlignment.kt').read_bytes()).hexdigest(),
             'reference_sha256':hashlib.sha256(args.semantic_reference.read_bytes()).hexdigest() if args.semantic_reference else None,
             'text_source':'word-cards' if args.word_by_word else 'published',
             ('population_printed_tokens' if args.all_printed_words else 'population_content_tokens'):denominator,
             'includes_grammar':args.all_printed_words,'includes_additions':args.all_printed_words,
             'reviewed':reviewed,'correct':correct,
             'disagreements':len(wrong),'unreviewed':denominator-reviewed,
             'verified_percent_of_full_population':str(lower),
             'required_percent':str(target) if target is not None else None,
             'established':target is not None and bool(denominator) and lower>=target}
(root/'semantic-certification.json').write_text(json.dumps(certificate,indent=2)+'\n')
(root/'semantic-reference-template.tsv').write_text('\n'.join(template)+'\n')
(root/'semantic-reference-disagreements.tsv').write_text('\n'.join('\t'.join(map(str,r)) for r in wrong)+'\n')
print('OWNERSHIP (literal diagnostic, not semantic accuracy):', dict(counts))
print(f"Literal agreement (diagnostic only): {counts['on_owner']/counts['literal_correspondences']:.3%}")
print('SEMANTIC REFERENCE:', {k:v for k,v in certificate.items() if not k.endswith('_sha256')})
if target is not None and not certificate['established']:
    raise SystemExit(f'{target}% semantic accuracy not established: {reviewed}/{denominator} reviewed, {len(wrong)} disagreements')
if counts['meaning_wash_delays']:
    raise SystemExit(f"{counts['meaning_wash_delays']} meanings highlighted late; see meaning-issues.tsv")
if args.strict_meaning_owners and counts['meaning_owner_disagreement']:
    raise SystemExit(f"{counts['meaning_owner_disagreement']} meanings have approximate owners; see meaning-issues.tsv")
