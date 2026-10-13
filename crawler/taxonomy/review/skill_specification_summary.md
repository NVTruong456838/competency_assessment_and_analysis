# CF-03.2B Skill Specification Summary

## Outcome

Created review dossiers for all 125 Skill candidates: 48 TSA and 77 HSA.
Candidate IDs and `mapping_status` values are copied from CF-03.2A. Every
specification has `review_status=pending_review`. None is approved or linked to
a question.

## Specification completeness

All 125 rows contain the requested identity, path, definition/action, scope,
assessment evidence, distinguishing criteria, mapping status, and review
status fields. This means the rows are **prepared for human review**, not that
the Skills are fully defined or validated.

| Review dimension | TSA | HSA | Total |
| --- | ---: | ---: | ---: |
| Candidate dossiers created | 48 | 77 | 125 |
| Candidate IDs with a canonical Topic path in CF-03.2A | 15 | 77 | 92 |
| Candidate IDs without canonical Topic path (`needs_new_topic` or `ambiguous`) | 17 | 0 | 17 |
| Candidate IDs marked `needs_review` for text-type semantics | 3 | 0 | 3 |
| Candidate definitions with representative source example form | 48 | 0 | 48 |
| Candidate definitions needing representative-item evidence before measurement claims | 48 | 77 | 125 |
| Candidate dossiers requiring SME boundary/rubric decisions | 48 | 77 | 125 |

The table separates structural mapping from measurement evidence. TSA examples
are draft forms, not validated representative items for each Skill; the TSA
draft references 39 extracted items and contains coverage notes. HSA's Skill
draft supplies operational descriptions but states an HSA Question Bank is not
available. Therefore no Skill is claimed to have demonstrated reliability or
validity.

## Mapping states preserved from CF-03.2A

| Exam | Candidates | matched | needs_new_topic | ambiguous | needs_review | duplicate_candidate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| TSA | 48 | 12 | 32 | 1 | 3 | 0 |
| HSA | 77 | 77 | 0 | 0 | 0 | 0 |
| **Total** | **125** | **89** | **32** | **1** | **3** | **0** |

`matched` describes only a structural Topic correspondence. In this
specification it is not treated as evidence that a Skill is distinguishable or
measurable.

## Proposals and unresolved decisions

- TSA Math: add reviewed Topics beneath six existing quantitative Domains and
  decide Domain structures for Modeling, Reasoning, and Problem Solving.
- TSA Reading: decide whether Literary/Informational/Argumentative are stimulus
  attributes, and whether statement completion is an action or answer format.
- TSA Science: propose Topic labels for health terminology, chemistry,
  physics, and environmental science without assigning canonical IDs.
- HSA Science: define a primary-path tie-breaker for subject content versus
  data/reasoning, and keep per-question subject choice where applicable.
- HSA English: obtain representative items and calibrate proficiency
  independently of cognitive level and difficulty.
- All candidates: provide positive/negative boundary examples and scoring
  criteria, then verify candidate IDs, versioning, and approval semantics.

## Files created

- `tsa_skill_specification.csv` — 48 rows and 15 specification fields.
- `hsa_skill_specification.csv` — 77 rows, including `subject_choice`, and 16
  specification fields.
- `proposed_taxonomy_changes.md` — non-canonical Domain/Topic proposals and
  review questions.
- `skill_review_checklist.md` — per-Skill SME and assessment evidence checklist.
- `skill_specification_summary.md` — this report.

## Validation result

- 48 TSA and 77 HSA rows; every candidate appears once and no IDs overlap.
- `skill_id` and `mapping_status` match the CF-03.2A mapping artifacts.
- Every non-empty parent ID exists and each non-empty Topic has the matching
  Domain, Competency, and question-bearing Section.
- Every requested specification field is populated. Where source evidence is
  missing, the cell explicitly says `requires_review`; it is not silently
  assumed.
- Every `review_status` is `pending_review`; no approval values are present.
- HSA optional branches remain `HSA_SCIENCE` and `HSA_ENGLISH`. English
  proficiency is not converted to M1–M3 or difficulty.
- CF-03.2A mapping CSVs, source taxonomy JSON, and the database were not edited
  for this work.

## Recommendation for CF-03.3

Run structural and semantic gates against these specification CSVs and the
proposal document. CF-03.3 should verify field completeness, hierarchy,
candidate coverage, duplicate/overlap disclosures, branch/subject rules,
proficiency separation, and that no proposed Topic is mistaken for a
canonical ID. Require a human decision record for all unresolved construct and
parent-boundary questions before any approved taxonomy JSON or database load is
prepared.
