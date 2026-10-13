# Taxonomy Validation Report

- Generated: `2026-10-12T13:50:28.491557+00:00`
- Publication ready: **FALSE**
- Candidates: **125**
- Mapping-valid candidates: **89**
- Mapping blockers: **36**

## Candidate counts

| Exam | Candidates |
| --- | ---: |
| TSA | 48 |
| HSA | 77 |

### By section

| Exam / Section | Candidates | Mapping valid | Mapping blockers |
| --- | ---: | ---: | ---: |
| TSA/TSA_MATH | 19 | 2 | 17 |
| TSA/TSA_READING | 14 | 0 | 14 |
| TSA/TSA_SCIENCE | 15 | 10 | 5 |
| HSA/HSA_MATH_DATA | 18 | 18 | 0 |
| HSA/HSA_LITERATURE_LANGUAGE | 16 | 16 | 0 |
| HSA/HSA_SCIENCE | 31 | 31 | 0 |
| HSA/HSA_ENGLISH | 12 | 12 | 0 |

| Mapping status | Count |
| --- | ---: |
| `ambiguous` | 1 |
| `duplicate_candidate` | 0 |
| `matched` | 89 |
| `needs_new_topic` | 32 |
| `needs_review` | 3 |

## Structural Validation: PASS

- Passed: `True`
- Failed: 0
- Warnings: 1
- Blockers: 0

### Warnings

- `framework_not_approved` — Framework status is 'draft'; structural validity does not imply publication approval. (`framework.status`)

## Mapping Readiness: FAIL

- Passed: `False`
- Failed: 0
- Warnings: 0
- Blockers: 36

### Blockers

- `topic_not_yet_defined` — Mapping SKILL_MATH_NUMBER_REMAINDER requires a proposed Topic and review; it is not mapping-ready. (`SKILL_MATH_NUMBER_REMAINDER`)
- `topic_not_yet_defined` — Mapping SKILL_MATH_DIGIT_ANALYZE requires a proposed Topic and review; it is not mapping-ready. (`SKILL_MATH_DIGIT_ANALYZE`)
- `topic_not_yet_defined` — Mapping SKILL_MATH_FUNCTION_GRAPH_ANALYZE requires a proposed Topic and review; it is not mapping-ready. (`SKILL_MATH_FUNCTION_GRAPH_ANALYZE`)
- `topic_not_yet_defined` — Mapping SKILL_MATH_FUNCTION_EXPRESSION_MANIPULATE requires a proposed Topic and review; it is not mapping-ready. (`SKILL_MATH_FUNCTION_EXPRESSION_MANIPULATE`)
- `mapping_parent_ambiguous` — Mapping SKILL_MATH_TRIG_EQUATION_SOLVE requires a human decision on its primary Topic path. (`SKILL_MATH_TRIG_EQUATION_SOLVE`)
- `topic_not_yet_defined` — Mapping SKILL_MATH_GEOMETRY_MEASURE requires a proposed Topic and review; it is not mapping-ready. (`SKILL_MATH_GEOMETRY_MEASURE`)
- `topic_not_yet_defined` — Mapping SKILL_MATH_COORDINATE_SPATIAL_ANALYZE requires a proposed Topic and review; it is not mapping-ready. (`SKILL_MATH_COORDINATE_SPATIAL_ANALYZE`)
- `topic_not_yet_defined` — Mapping SKILL_MATH_COUNT_CONSTRAINED requires a proposed Topic and review; it is not mapping-ready. (`SKILL_MATH_COUNT_CONSTRAINED`)
- `topic_not_yet_defined` — Mapping SKILL_MATH_PROBABILITY_CALCULATE requires a proposed Topic and review; it is not mapping-ready. (`SKILL_MATH_PROBABILITY_CALCULATE`)
- `topic_not_yet_defined` — Mapping SKILL_MATH_STATISTICS_INTERPRET requires a proposed Topic and review; it is not mapping-ready. (`SKILL_MATH_STATISTICS_INTERPRET`)
- `topic_not_yet_defined` — Mapping SKILL_MATH_MODEL_FORMULATE requires a proposed Topic and review; it is not mapping-ready. (`SKILL_MATH_MODEL_FORMULATE`)
- `topic_not_yet_defined` — Mapping SKILL_MATH_MODEL_INTERPRET requires a proposed Topic and review; it is not mapping-ready. (`SKILL_MATH_MODEL_INTERPRET`)
- 24 additional detailed entries are listed in the JSON report.

## Approval Readiness: FAIL

- Passed: `False`
- Failed: 0
- Warnings: 1
- Blockers: 130

### Warnings

- `approval_gate_is_non_mutating` — This validator does not approve candidates or change framework status. (`approval_readiness`)

### Blockers

- `framework_approval_missing` — The framework is not in approved status. (`framework.status`)
- `candidate_pending_human_review` — Candidate SKILL_MATH_NUMBER_REMAINDER is still pending human review. (`SKILL_MATH_NUMBER_REMAINDER`)
- `candidate_pending_human_review` — Candidate SKILL_MATH_DIGIT_ANALYZE is still pending human review. (`SKILL_MATH_DIGIT_ANALYZE`)
- `candidate_pending_human_review` — Candidate SKILL_MATH_EQUATION_SOLVE is still pending human review. (`SKILL_MATH_EQUATION_SOLVE`)
- `candidate_pending_human_review` — Candidate SKILL_MATH_INEQUALITY_SOLVE is still pending human review. (`SKILL_MATH_INEQUALITY_SOLVE`)
- `candidate_pending_human_review` — Candidate SKILL_MATH_FUNCTION_GRAPH_ANALYZE is still pending human review. (`SKILL_MATH_FUNCTION_GRAPH_ANALYZE`)
- `candidate_pending_human_review` — Candidate SKILL_MATH_FUNCTION_EXPRESSION_MANIPULATE is still pending human review. (`SKILL_MATH_FUNCTION_EXPRESSION_MANIPULATE`)
- `candidate_pending_human_review` — Candidate SKILL_MATH_TRIG_EQUATION_SOLVE is still pending human review. (`SKILL_MATH_TRIG_EQUATION_SOLVE`)
- `candidate_pending_human_review` — Candidate SKILL_MATH_GEOMETRY_MEASURE is still pending human review. (`SKILL_MATH_GEOMETRY_MEASURE`)
- `candidate_pending_human_review` — Candidate SKILL_MATH_COORDINATE_SPATIAL_ANALYZE is still pending human review. (`SKILL_MATH_COORDINATE_SPATIAL_ANALYZE`)
- `candidate_pending_human_review` — Candidate SKILL_MATH_COUNT_CONSTRAINED is still pending human review. (`SKILL_MATH_COUNT_CONSTRAINED`)
- `candidate_pending_human_review` — Candidate SKILL_MATH_PROBABILITY_CALCULATE is still pending human review. (`SKILL_MATH_PROBABILITY_CALCULATE`)
- 118 additional detailed entries are listed in the JSON report.

## Human review decisions

Open decisions requiring review: **130**

Detailed decision records are included in the JSON report.

This report is a non-approving validation result. Structural validity does not imply mapping or approval readiness.
