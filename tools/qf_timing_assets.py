"""Keep QF timing content out of release assets without waiving other deltas."""
import hashlib
import json

QF_TIMING_SLUGS = frozenset({
    "Alafasy_128kbps", "Husary_64kbps", "Abdul_Basit_Murattal_64kbps",
    "Minshawy_Murattal_128kbps", "Abdurrahmaan_As-Sudais_192kbps", "Hani_Rifai_192kbps",
})
RUNTIME_DELIVERY = "authenticated Content Sync runtime cache; no bundled QF timings"


def corpus_digest(rows):
    """Bind every occurrence, boundary, onset, and row identity with a small manifest."""
    values = [[*key, value["payloadHash"]] for key, value in sorted(rows.items())]
    return hashlib.sha256(json.dumps(values, separators=(",", ":")).encode()).hexdigest()


def verify_asset_transition(before, after, manifest):
    """Exclude only the exact removed corpora, then leave the normal delta gate intact."""
    if (manifest.get("schemaVersion") != 1 or manifest.get("runtimeReciters") != [1, 2, 3, 4, 5, 7] or
            manifest.get("delivery") != RUNTIME_DELIVERY):
        raise ValueError("Invalid authenticated timing delivery manifest")
    if any(key[0] in QF_TIMING_SLUGS for key in after):
        raise ValueError("QF-derived timings remain in the public database asset")
    if (len(after) != manifest["retainedTimingRows"] or
            corpus_digest(after) != manifest["retainedTimingsSha256"]):
        raise ValueError("Public independent timing corpus does not match its delivery manifest")
    removed = {key: value for key, value in before.items() if key[0] in QF_TIMING_SLUGS}
    if removed and (len(removed) != manifest["removedTimingRows"] or
                    corpus_digest(removed) != manifest["removedTimingsSha256"]):
        raise ValueError("Only the exact reviewed pre-transition QF corpora may leave the asset")
    return {key: value for key, value in before.items() if key[0] not in QF_TIMING_SLUGS}
