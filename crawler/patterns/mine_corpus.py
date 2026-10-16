"""Mine unapproved Question Pattern candidates from processed TSA/HSA corpora."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any

from crawler.patterns.mining import QuestionPatternMiner


def mine_corpus(root: Path) -> dict[str, Any]:
    files = [
        root / "crawler" / "data" / "processed" / exam / f"{exam}_question_bank.json"
        for exam in ("tsa", "hsa")
    ]
    miner = QuestionPatternMiner()
    questions: list[dict[str, Any]] = []
    skipped: list[dict[str, str]] = []
    counts: Counter[str] = Counter()

    for source_file in files:
        if not source_file.is_file():
            raise FileNotFoundError(f"Normalized question bank not found: {source_file}")
        records = json.loads(source_file.read_text(encoding="utf-8"))
        if not isinstance(records, list):
            raise ValueError(f"Expected a JSON array in {source_file}")
        for index, record in enumerate(records):
            exam = source_file.parent.name.upper()
            if not isinstance(record, dict):
                skipped.append({"source_file": source_file.name, "index": str(index), "reason": "not an object"})
                continue
            try:
                miner.analyze([record])
            except (TypeError, ValueError) as error:
                skipped.append({
                    "source_file": source_file.name,
                    "question_id": str(record.get("question_id", index)),
                    "reason": str(error),
                })
                continue
            counts[exam] += 1
            questions.append(record)

    result = miner.analyze(questions)
    result["corpus"] = {
        "question_bank_files": [source_file.name for source_file in files],
        "records_analyzed": len(questions),
        "records_skipped": len(skipped),
        "records_by_exam": dict(sorted(counts.items())),
        "all_candidates_unapproved": True,
    }
    result["skipped_records"] = skipped
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="Repository root (defaults to the parent repository).",
    )
    parser.add_argument("--output", type=Path, help="Write candidate JSON to this path.")
    parser.add_argument(
        "--summary-only",
        action="store_true",
        help="Print corpus counts rather than candidate provenance JSON.",
    )
    args = parser.parse_args()

    result = mine_corpus(args.root.resolve())
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    if args.summary_only:
        print(json.dumps({
            **result["corpus"],
            "candidate_patterns": len(result["patterns"]),
            "pattern_status": result["status"],
            "unverified_rights": sum(
                pattern["rights_status"] == "UNVERIFIED" for pattern in result["patterns"]
            ),
        }, ensure_ascii=False))
    elif not args.output:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
