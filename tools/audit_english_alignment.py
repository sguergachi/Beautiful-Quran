#!/usr/bin/env python3
"""Audit printed ownership against literal gloss correspondences, not semantics.

Input TSV: verse_key, published_translation, Arabic-ordered glosses joined by |.
Uses an external QF cache export; never downloads or changes packaged data.
"""
import argparse
import collections
import json
import re
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("corpus", type=Path)
parser.add_argument("--output-dir", type=Path, required=True)
parser.add_argument("--baseline-ends", type=Path, help="Optional TSV of verse_key and comma-separated character ends")
args = parser.parse_args()
root = args.output_dir
root.mkdir(parents=True, exist_ok=True)
source = Path(__file__).resolve().parents[1] / "app/src/main/java/com/beautifulquran"
probe = r'''
import com.beautifulquran.domain.EnglishWordAlignment
import java.io.File
import kotlin.math.roundToInt
fun main(args: Array<String>) {
    File(args[0]).forEachLine { line ->
        val (key, text, gloss) = line.split('\t')
        val alignment = EnglishWordAlignment.align(text, gloss.split('|')) ?: error(key)
        println(key + "\t" + alignment.spans.joinToString(";") {
            "${(it.from * text.length).roundToInt()},${(it.to * text.length).roundToInt()},${it.position}"
        })
    }
}
'''
with tempfile.TemporaryDirectory(prefix="english-alignment-audit-") as temporary:
    directory = Path(temporary)
    (directory / "Probe.kt").write_text(probe)
    sources = [source / f for f in ("domain/EnglishWordAlignment.kt", "domain/EnglishTypography.kt",
                                   "domain/ArabicNormalize.kt", "data/model/Models.kt")]
    subprocess.run(["kotlinc", *map(str, sources), str(directory / "Probe.kt"),
                    "-include-runtime", "-d", str(directory / "probe.jar")], check=True)
    result = subprocess.run(["java", "-jar", str(directory / "probe.jar"), str(args.corpus)],
                            capture_output=True, text=True, check=True)
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

rows=[line.split('\t') for line in args.corpus.read_text().splitlines()]
spans={k:[tuple(map(int,s.split(','))) for s in encoded.split(';')] for k,encoded in (l.split('\t') for l in (root/'ownership.tsv').read_text().splitlines())}
counts=collections.Counter();issues=[]
prior={k:list(map(int,e.split(','))) for k,e in (line.split('\t') for line in args.baseline_ends.read_text().splitlines())} if args.baseline_ends else {}
for key,text,gs in rows:
    glosses=gs.split('|'); owned=spans[key]
    assert owned[0][0]==0 and owned[-1][1]==len(text),(key,'finish')
    assert all(a<b and 1<=p<=len(glosses) for a,b,p in owned),(key,'range')
    assert all(prev[1]==nxt[0] for prev,nxt in zip(owned,owned[1:])),(key,'tiling')
    assert all(a in (0,len(text)) or not(text[a-1].isalpha() and text[a].isalpha()) for a,_,_ in owned),(key,'split')
    counts['structural_verses']+=1
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
(root/'ownership-audit.json').write_text(json.dumps(dict(counts),indent=2)+'\n')
(root/'ownership-issues.tsv').write_text('\n'.join('\t'.join(map(str,r)) for r in issues)+'\n')
print('OWNERSHIP (literal diagnostic, not semantic accuracy):', dict(counts))
print(f"Literal agreement: {counts['on_owner']/counts['literal_correspondences']:.3%}; semantic accuracy unverified")
