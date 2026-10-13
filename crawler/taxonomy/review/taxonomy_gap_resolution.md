# Canonical Taxonomy Gap Resolution — CF-03.2A

## Scope and evidence

This is an internal, non-approved review proposal for the taxonomy in
`../competency_framework.json`. It was cross-checked against:

- `../skill_taxonomy_draft.md` (TSA candidates and 39-question pilot paths)
- `../hsa_2026_competency_draft.md` (HSA internal competency proposal)
- `../hsa_2026_skill_taxonomy_draft.md` (HSA Skill candidates)
- `../cognitive_levels.json` (internal M1–M3)
- `../../data/processed/hsa/hsa_2026_framework.json` (HSA 2026 structure draft)
- `../../../database/smart_assessment_ai.sql` (persistence constraints)

The HSA structure document cites a public structure source, but the competency,
Domain, Topic, and Skill model remains an internal draft. No claim is made that
the internal competency taxonomy is an official HUST or VNU taxonomy. The HSA
question-bank draft says no HSA Question Bank is available, so these mappings
are not validated against representative HSA items.

No source taxonomy, SQL schema/data, API, question metadata, or approval state
was changed. All candidate mappings remain `pending_review`.

## Measurability assessment boundary

Each CSV row includes a provisional `measurability_assessment`. Candidate
operational descriptions generally state an observable response action, but
this is not proof of reliable measurement. TSA entries require representative
item validation; HSA entries especially require future item evidence because
the HSA Question Bank is unavailable. The three TSA text-type candidates are
specifically flagged because stimulus classification may not be a measured
competency. The trigonometric-equation candidate has an observable operation
but an unresolved parent/scope boundary. No candidate receives a psychometric
or empirical validity claim in this analysis.

## Current structure and gaps

Counts below include both HSA optional branches as question-bearing sections;
`HSA_OPTIONAL` itself is only a selection container.

| Exam / question-bearing section | Competencies | Domains | Topics | Existing Skills | Skill candidates |
| --- | ---: | ---: | ---: | ---: | ---: |
| TSA / `TSA_MATH` | 4 | 7 | 2 | 0 | 19 |
| TSA / `TSA_READING` | 4 | 6 | 3 | 0 | 14 |
| TSA / `TSA_SCIENCE` | 4 | 7 | 10 | 0 | 15 |
| HSA / `HSA_MATH_DATA` | 4 | 7 | 18 | 0 | 18 |
| HSA / `HSA_LITERATURE_LANGUAGE` | 4 | 8 | 16 | 0 | 16 |
| HSA / `HSA_SCIENCE` | 4 | 11 | 31 | 0 | 31 |
| HSA / `HSA_ENGLISH` | 3 | 6 | 12 | 0 | 12 |
| **Total** | **27** | **52** | **92** | **0** | **125** |

### TSA Math

- `TSA_MATH_QUANT` has seven Domains, but only Algebra has Topics
  (`TSA_MATH_ALGEBRA_EQUATION` and `TSA_MATH_ALGEBRA_INEQUALITY`). Number,
  Function, Geometry, Combinatorics, Probability, and Statistics have no Topics.
- `TSA_MATH_MODEL`, `TSA_MATH_REASON`, and `TSA_MATH_PROBLEM` have no Domain
  children. Their candidates therefore cannot yet have a canonical Topic path.
- `SKILL_MATH_TRIG_EQUATION_SOLVE` is ambiguous: the draft places it in Function,
  while the only current equation Topic is under Algebra. It has no Topic ID in
  the mapping until that boundary is reviewed.

### TSA Reading

- The Text Type branch has three Topics (Literary, Informational,
  Argumentative). The other five Domains—Main Idea & Detail, Structure &
  Organization, Argument & Evidence, Inference, and Evaluation & Interpretation—
  have no Topics.
- `SKILL_READ_LITERARY_IDENTIFY`, `SKILL_READ_INFORMATIONAL_IDENTIFY`, and
  `SKILL_READ_ARGUMENTATIVE_IDENTIFY` point to existing Topic IDs but are marked
  `needs_review`: their operation may classify a stimulus rather than measure a
  transferable reading action. Text type may be better represented as a tag.
- Main idea, explicit retrieval, statement completion, structure, argument,
  inference, and evaluation candidates require Topic design before canonical
  mapping. Statement completion may describe an answer format, not a distinct
  competency.

### TSA Science

- Data Interpretation, Scientific Reasoning, and Problem Solving have Topics
  that directly support the mapped candidate actions.
- Biology / Health has only the Cancer & Public Health Topic. Health terminology
  is not represented.
- Chemistry, Physics, and Earth & Environmental Science have no Topics.
- Concept-area Skills (for example, chemistry properties or physics
  relationships) need distinguishable operational boundaries from broad
  content-area labels.

### HSA

- All HSA candidate rows have an existing Topic with a direct label/concept
  correspondence in the current internal tree. This is a structural match, not
  approval of the candidate, validation against HSA questions, or confirmation
  that every proposed action is independently measurable.
- HSA Science must remain under `HSA_SCIENCE`, not its parent
  `HSA_OPTIONAL`. Subject-specific candidates carry their stated
  `subject_choice`; cross-subject candidates leave it blank because the actual
  subject must be determined from each future question.
- HSA English remains a separate optional branch. Its proficiency scale is not
  a Cognitive Level.

## Candidate actions without an existing Topic

The following TSA candidates have blank `topic_id` and `needs_new_topic`:

- **TSA Math quantitative:** `SKILL_MATH_NUMBER_REMAINDER`,
  `SKILL_MATH_DIGIT_ANALYZE`, `SKILL_MATH_FUNCTION_GRAPH_ANALYZE`,
  `SKILL_MATH_FUNCTION_EXPRESSION_MANIPULATE`,
  `SKILL_MATH_GEOMETRY_MEASURE`,
  `SKILL_MATH_COORDINATE_SPATIAL_ANALYZE`,
  `SKILL_MATH_COUNT_CONSTRAINED`, `SKILL_MATH_PROBABILITY_CALCULATE`,
  `SKILL_MATH_STATISTICS_INTERPRET`.
- **TSA Math modeling/reasoning/problem solving:**
  `SKILL_MATH_MODEL_FORMULATE`, `SKILL_MATH_MODEL_INTERPRET`,
  `SKILL_MATH_OPTIMIZE`, `SKILL_MATH_PARAMETER_REASON`,
  `SKILL_MATH_GEOMETRY_VERIFY`, `SKILL_MATH_CONSTRAINT_DECOMPOSE`,
  `SKILL_MATH_MULTI_STEP_SOLVE`. Their parent Domain is also absent.
- **TSA Reading:** `SKILL_READ_MAIN_IDEA_IDENTIFY`,
  `SKILL_READ_DETAIL_RETRIEVE`, `SKILL_READ_STATEMENT_COMPLETE`,
  `SKILL_READ_STRUCTURE_ANALYZE`, `SKILL_READ_EVIDENCE_ANALYZE`,
  `SKILL_READ_ARGUMENT_ANALYZE`, `SKILL_READ_INFERENCE_DRAW`,
  `SKILL_READ_CAUSE_MOTIVE_INFER`, `SKILL_READ_MESSAGE_EVALUATE`,
  `SKILL_READ_ARGUMENT_EVALUATE`, `SKILL_READ_TITLE_INTERPRET`.
- **TSA Science:** `SKILL_SCI_HEALTH_TERM_APPLY`,
  `SKILL_SCI_CHEM_PROPERTY_EXPLAIN`,
  `SKILL_SCI_CHEM_QUANTITATIVE_CALCULATE`,
  `SKILL_SCI_PHYSICS_RELATION_APPLY`, `SKILL_SCI_ENVIRONMENT_APPLY`.

The mapping CSV records an existing canonical Domain where one exists. It does
not invent Topic IDs or pretend that a missing Domain has been resolved.

## Overlap and measurement issues for review

1. **TSA Math equation operations:** algebra equation solving and trigonometric
   equation solving share an action but may belong to different content Topics.
   Review whether one is a reusable Skill across Topics or two separately
   reportable Skills.
2. **TSA Reading stimulus type vs. action:** identifying literary/informational/
   argumentative type can become stimulus metadata. Direct retrieval and
   statement completion can also overlap; answer format alone should not define
   a Skill.
3. **TSA Science representation:** table and chart interpretation differ by
   representation but share the core action. Review whether representation is a
   stimulus tag or a separate measured Skill.
4. **HSA Math modelling sequence:** formulate, interpret, define
   objective/constraints, and optimize are adjacent steps in one workflow. Keep
   separate only if item evidence can reliably distinguish their scores.
5. **HSA Literature/Language:** text type (literary/informational) is potentially
   a tag; argument/evidence analysis and argument-quality evaluation must retain
   an explicit analysis-vs-evaluation distinction.
6. **HSA Science primary path:** a question may simultaneously require subject
   knowledge, data interpretation, and evidence-based reasoning. The one-primary-
   path rule needs a tie-break policy; `subject_choice` remains orthogonal and
   mandatory per Science question.
7. **Cognitive/difficulty/proficiency:** M1–M3, HSA blueprint difficulty
   levels 1–3, and HSA English proficiency levels A2–C1 measure different
   constructs. No equivalence or automatic conversion is proposed.

## Canonical ID and persistence notes

- Keep each existing candidate ID unchanged during review; do not allocate new
  Skill IDs in CF-03.2A. The source drafts already distinguish candidate IDs
  from display names and operational descriptions.
- For future approved IDs, retain an immutable code based on exam/branch,
  concept, and observable action. Do not encode version, approval state,
  proficiency, difficulty, or a mutable display name. Use `HSA_SCIENCE` or
  `HSA_ENGLISH`, never `HSA_OPTIONAL`, for branch-specific IDs.
- TSA IDs currently omit the `TSA` prefix while HSA IDs include `HSA`; decide
  whether to preserve this established candidate convention or namespace a
  later approved release. Do not silently rename candidates now.
- SQL uses numeric surrogate primary keys. `competency_code`, `domain_code`,
  `topic_code`, and `skill_code` are the string-code columns; Topic codes are
  unique within a Domain and Skill codes within a Topic. CSV IDs here are
  framework codes, not PostgreSQL numeric IDs.
- SQL `skills.status` supports only `ACTIVE`/`INACTIVE` (default `ACTIVE`).
  The taxonomy DDL has no draft/review/approved workflow and no direct taxonomy
  version key on Domain/Topic/Skill. Never load these pending rows into that
  schema as-is. The framework JSON's `draft` status is not enforced by the DB.
- SQL exam sections belong to `exam_versions`, but canonical taxonomy codes in
  this review are not yet tied to a specific database version row. Versioning
  and approval must be designed before integration.

## Review decisions required before CF-03.2B

1. Decide whether TSA Math should add Domains to the three currently empty
   Competencies and how the six empty quantitative Domains should be decomposed.
2. Decide whether text type is a Topic axis, a stimulus tag, or both, and whether
   statement-completion format merits a Skill.
3. Decide how fine-grained TSA Science content Topics should be, especially
   whether the HSA subject-level divisions are applicable or would overfit.
4. Approve measurable boundaries and scoring evidence for overlapping candidate
   actions; no candidate is approved by this report.
5. Define the primary-path tie-breaker for HSA Science items that require both
   content and data/reasoning.
6. Confirm HSA per-item subject-choice handling and independently define HSA
   English proficiency calibration. Do not derive proficiency from M1–M3 or
   difficulty.
7. Design taxonomy version/review states and safe DB representation before
   CF-03.5.

## Proposed next steps

1. Human review and resolve the TSA missing Domain/Topic decisions above.
2. In CF-03.2B, write operational definitions, inclusion/exclusion criteria,
   observable evidence, and representative question forms for each candidate.
3. Reconcile candidate overlap, retain only review-approved IDs, and validate
   parent assignment against representative items.
4. Run structural checks on a proposed version without editing the active
   framework or database.
5. Define approval/versioning and migration semantics before any PostgreSQL
   integration. Keep the resulting taxonomy in draft until an authorized review
   explicitly approves it.
