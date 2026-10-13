# Proposed Taxonomy Changes — CF-03.2B

## Status and guardrails

All items below are design proposals for subject-matter review. They do not
create canonical IDs, change the source framework, or assert an official TSA
or HSA taxonomy. Existing IDs should be retained where the meaning is
unchanged. The candidate IDs and mapping states in CF-03.2A are preserved.

## TSA Math

### Add Topic proposals under existing quantitative Domains

The following are candidate Topic labels derived from the Skill draft. Topic
codes are deliberately omitted; allocation of stable codes should follow
review of taxonomy boundaries and naming conventions.

| Existing Domain | Candidate Topic label(s) | Candidate(s) served | Review question |
| --- | --- | --- | --- |
| `TSA_MATH_NUMBER` | Number properties; Digit properties | `SKILL_MATH_NUMBER_REMAINDER`; `SKILL_MATH_DIGIT_ANALYZE` | Are divisibility/remainder and decimal-digit reasoning separate reportable content areas? |
| `TSA_MATH_FUNCTION` | Graph and variation; Function expression; Trigonometric function/equation (unresolved) | `SKILL_MATH_FUNCTION_GRAPH_ANALYZE`; `SKILL_MATH_FUNCTION_EXPRESSION_MANIPULATE`; `SKILL_MATH_TRIG_EQUATION_SOLVE` | Is trigonometric equation solving a function topic, an equation topic, or an action cross-cutting multiple Topics? |
| `TSA_MATH_GEOMETRY` | Measurement in plane/solid geometry; Coordinate geometry | `SKILL_MATH_GEOMETRY_MEASURE`; `SKILL_MATH_COORDINATE_SPATIAL_ANALYZE` | Confirm conceptual split and whether coordinate geometry belongs here. |
| `TSA_MATH_COMBINATORICS` | Counting under constraints | `SKILL_MATH_COUNT_CONSTRAINED` | Confirm the scope does not duplicate probability counting. |
| `TSA_MATH_PROBABILITY` | Repeated trials and event probability | `SKILL_MATH_PROBABILITY_CALCULATE` | Confirm whether repeated trials is a durable Topic or only one item pattern. |
| `TSA_MATH_STATISTICS` | Descriptive statistics and data interpretation | `SKILL_MATH_STATISTICS_INTERPRET` | Decide whether calculations and interpretation need separate Topics. |

### Add Domain proposals under Competencies without Domain children

These are parent proposals only. Candidate Skills do not receive Domain or
Topic IDs until the framework owner reviews them.

| Competency | Candidate Domain label(s) | Associated candidate actions | Review question |
| --- | --- | --- | --- |
| `TSA_MATH_MODEL` | Mathematical modeling; Optimization in context | Formulate a model; interpret model results; define objective/constraints and optimize | Is optimization a distinct Domain or a Topic under modeling? |
| `TSA_MATH_REASON` | Algebraic reasoning; Geometric reasoning | Reason about parameter conditions; verify geometric relationships | Are these content Domains, or should reasoning be modelled as an action spanning content Domains? |
| `TSA_MATH_PROBLEM` | Problem-solving strategy | Decompose constraints; integrate multiple steps | Is problem solving a separate primary competency or a cross-cutting Skill dimension? |

## TSA Reading

### Separate stimulus metadata from learner action

The existing Topics `TSA_READ_LITERARY_TEXT`,
`TSA_READ_INFORMATIONAL_TEXT`, and `TSA_READ_ARGUMENTATIVE_TEXT` describe
stimulus genres. They should not be treated as Skills merely because an item
uses that genre. Preserve their IDs if the framework owner retains them as
stimulus/content Topics; alternatively, move their semantic role to a
stimulus-type tag only through an approved framework revision.

The corresponding candidate Skill records remain `needs_review`:

- `SKILL_READ_LITERARY_IDENTIFY`
- `SKILL_READ_INFORMATIONAL_IDENTIFY`
- `SKILL_READ_ARGUMENTATIVE_IDENTIFY`

Proposed learner-action Topics (no codes assigned) under existing Domains:

| Existing Domain | Candidate Topic label(s) | Candidates | Review question |
| --- | --- | --- | --- |
| `TSA_READ_MAIN_IDEA_DETAIL` | Main idea; Explicit detail retrieval; Statement completion (format/action boundary unresolved) | `SKILL_READ_MAIN_IDEA_IDENTIFY`; `SKILL_READ_DETAIL_RETRIEVE`; `SKILL_READ_STATEMENT_COMPLETE` | Is statement completion an independently assessed reading action, or only a response format for retrieval/inference? |
| `TSA_READ_STRUCTURE_ORGANIZATION` | Text structure and paragraph function | `SKILL_READ_STRUCTURE_ANALYZE` | Define evidence beyond identifying paragraph position or format. |
| `TSA_READ_ARGUMENT_EVIDENCE` | Claim/evidence relationship; Argument logic | `SKILL_READ_EVIDENCE_ANALYZE`; `SKILL_READ_ARGUMENT_ANALYZE` | Establish the distinction between locating evidence and evaluating an argument's logic. |
| `TSA_READ_INFERENCE` | Implicit conclusion; Cause, motive, and consequence inference | `SKILL_READ_INFERENCE_DRAW`; `SKILL_READ_CAUSE_MOTIVE_INFER` | Determine if cause/motive inference is a stable subskill or a content-specific instance. |
| `TSA_READ_EVALUATION_INTERPRETATION` | Message interpretation; Argument quality; Title/whole-text interpretation | `SKILL_READ_MESSAGE_EVALUATE`; `SKILL_READ_ARGUMENT_EVALUATE`; `SKILL_READ_TITLE_INTERPRET` | Separate evaluation of claims/evidence from global interpretation and synthesis. |

## TSA Science

Proposals below fill missing Topic coverage without assigning new IDs:

| Existing Domain | Candidate Topic label(s) | Candidate(s) served | Review question |
| --- | --- | --- | --- |
| `TSA_SCI_CONCEPT_BIOLOGY_HEALTH` | Health terminology and concepts | `SKILL_SCI_HEALTH_TERM_APPLY` | Is terminology recall/application separable from broader health knowledge? |
| `TSA_SCI_CONCEPT_CHEMISTRY` | Substance properties and chemical phenomena; Quantitative reaction/experiment reasoning | `SKILL_SCI_CHEM_PROPERTY_EXPLAIN`; `SKILL_SCI_CHEM_QUANTITATIVE_CALCULATE` | Distinguish conceptual explanation from stoichiometric or experimental calculation. |
| `TSA_SCI_CONCEPT_PHYSICS` | Physical relationships and model application | `SKILL_SCI_PHYSICS_RELATION_APPLY` | Define content scope and evidence from the response, not stimulus alone. |
| `TSA_SCI_CONCEPT_EARTH_ENVIRONMENT` | Earth and environmental systems | `SKILL_SCI_ENVIRONMENT_APPLY` | Clarify boundaries between environmental science, geography, and public health. |

The existing `TSA_SCI_CONCEPT_CANCER_PUBLIC_HEALTH` Topic is unchanged. Review
whether health terminology is a sibling Topic, belongs inside that existing
Topic, or spans additional Biology/Health concepts before assigning a code.

## HSA branch and cross-cutting rules

- Preserve `HSA_OPTIONAL` as a selection container. Question-bearing paths use
  `HSA_SCIENCE` or `HSA_ENGLISH`.
- Retain per-question `subject_choice` for Science when the item belongs to a
  particular subject. Cross-subject candidate actions do not justify a
  fabricated default subject.
- Keep subject-content actions distinct from cross-cutting data/reasoning
  actions. For items requiring both, define the primary-path tie-breaker using
  the intended scoring claim and response evidence.
- Do not merge HSA candidate IDs with TSA IDs based on similar names.
- Keep English proficiency A2–C1 distinct from M1–M3 and difficulty levels
  1–3; this proposal supplies no conversion rule.

## Stable-ID and versioning constraints

1. Assign codes only after a human-approved parent path and scope exist.
2. Preserve candidate IDs during review; do not infer approved codes from
   proposed labels.
3. Codes should identify a stable construct, not a mutable display name,
   difficulty, cognitive level, proficiency, item format, or version.
4. Version and approval semantics must be designed separately from IDs before
   any database integration. Current database `skills.status` is not a
   draft/review/approved workflow.
