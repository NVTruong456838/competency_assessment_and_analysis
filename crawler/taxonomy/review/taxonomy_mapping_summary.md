# CF-03.2A Taxonomy Mapping Summary

## Result

Processed 125 distinct candidate Skill IDs: 48 TSA and 77 HSA. Every row is
marked `pending_review`; none is approved or linked to a question.

| Exam | Section | Candidates | matched | needs_new_topic | ambiguous | needs_review | duplicate_candidate |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| TSA | `TSA_MATH` | 19 | 2 | 16 | 1 | 0 | 0 |
| TSA | `TSA_READING` | 14 | 0 | 11 | 0 | 3 | 0 |
| TSA | `TSA_SCIENCE` | 15 | 10 | 5 | 0 | 0 | 0 |
| HSA | `HSA_MATH_DATA` | 18 | 18 | 0 | 0 | 0 | 0 |
| HSA | `HSA_LITERATURE_LANGUAGE` | 16 | 16 | 0 | 0 | 0 | 0 |
| HSA | `HSA_SCIENCE` | 31 | 31 | 0 | 0 | 0 | 0 |
| HSA | `HSA_ENGLISH` | 12 | 12 | 0 | 0 | 0 | 0 |
| **Total** |  | **125** | **89** | **32** | **1** | **3** | **0** |

`matched` means a candidate has a direct structural Topic correspondence in the
current draft; it is not a decision that the Skill is measurable, approved, or
supported by exam-item evidence. Three TSA text-type candidates retain the
canonical Topic ID but are `needs_review` because the proposed action may be
stimulus classification rather than a Skill.

## Files created

- `tsa_skill_candidate_mapping.csv` — 48 TSA rows.
- `hsa_skill_candidate_mapping.csv` — 77 HSA rows; records Science
  `subject_choice` where the candidate itself is subject-specific. A blank value
  on cross-subject rows means the actual question must supply it. Both mapping
  CSVs include a per-candidate provisional `measurability_assessment`; it is not
  an empirical validity claim.
- `taxonomy_gap_resolution.md` — section counts, missing structures, overlap
  risks, stable-ID and PostgreSQL considerations, human-review decisions.
- `taxonomy_mapping_summary.md` — this count summary and validation record.

## Key findings

- TSA Math has 16 candidates with no canonical Topic and one ambiguous
  trigonometric-equation candidate. The modeling, reasoning, and problem-solving
  Competencies also lack Domains.
- TSA Reading has 11 candidates needing Topics and three text-type candidates
  needing conceptual review.
- TSA Science has five candidates needing Topics, concentrated in health
  terminology and Chemistry/Physics/Environment content.
- HSA has a structural Topic match for each candidate. The mapping remains
  provisional because the framework and Skill drafts are internal, and the HSA
  Question Bank is not available for empirical review.
- Candidate operational descriptions provide proposed observable actions, but
  all measurability judgements remain provisional until representative items
  establish that each action can be distinguished and scored reliably.
- HSA Science remains under the `HSA_SCIENCE` branch; `HSA_OPTIONAL` is not used
  as a competency, Domain, or Topic. Per-question subject choice is still
  required even when a candidate Skill applies across subjects.
- HSA English proficiency (A2–C1) remains distinct from M1–M3 and difficulty
  levels 1–3.

## Validation performed

- Candidate counts verified from the candidate tables: TSA 48; HSA 77.
- Candidate IDs are unique within each source candidate set. TSA question-path
  examples repeat candidate Skill IDs, but those occurrences are not additional
  candidate definitions.
- Every non-empty Domain and Topic code in both CSV files was checked against
  the canonical framework. Every non-empty Topic has its canonical parent
  Domain and Competency in the same path.
- No `topic_id` was fabricated for `needs_new_topic` or `ambiguous` mappings.
- The only `needs_review` rows are the three TSA text-type candidates, and each
  references an existing Topic while explicitly preserving the semantic review
  requirement.
- Every row uses an allowed `mapping_status` and `review_status=pending_review`.
- Every candidate row has a provisional measurability disposition. It explicitly
  flags the three TSA text-type candidates and the unresolved TSA trigonometric
  equation boundary.
- No row is `approved`; no question mapping was added.
- HSA Science rows use `HSA_SCIENCE`, never the `HSA_OPTIONAL` container.
- No Skill candidate ID is `SKILL_HSA_<SECTION>`; that string in the HSA draft
  is a convention placeholder, not a candidate.
- No database, schema, API, source taxonomy JSON, or question-bank file was
  intentionally edited for this task.

## Gate

CF-03.2A mapping artifacts are prepared for human review. CF-03.2B should wait
for decisions on TSA structure, text-type treatment, candidate overlap, HSA
Science primary-path policy, and taxonomy versioning/approval.
