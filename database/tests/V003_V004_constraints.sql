\set ON_ERROR_STOP on

BEGIN;

DO $assert_schema$
BEGIN
    IF to_regclass('public.taxonomy_catalogs') IS NULL
       OR to_regclass('public.taxonomy_releases') IS NULL
       OR to_regclass('public.taxonomy_release_members') IS NULL
       OR to_regclass('public.taxonomy_release_events') IS NULL
       OR to_regclass('public.question_mapping_sets') IS NULL
       OR to_regclass('public.question_mapping_set_items') IS NULL
       OR to_regclass('public.analytics_runs') IS NULL
       OR to_regclass('public.question_import_batches') IS NULL
       OR to_regclass('public.question_patterns') IS NULL
       OR to_regclass('public.question_pattern_versions') IS NULL
       OR to_regclass('public.question_pattern_skill_mappings') IS NULL THEN
        RAISE EXCEPTION 'A required CF-03.5B/Phase 04 table is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'test_versions'
          AND column_name = 'taxonomy_release_id'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'test_items'
          AND column_name = 'mapping_set_id'
    ) THEN
        RAISE EXCEPTION 'Test provenance columns are missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'question_versions'
          AND column_name = 'content_sha256'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'visual_asset'
          AND column_name = 'rights_status'
    ) THEN
        RAISE EXCEPTION 'Question Version hashing or asset rights provenance is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'public' AND indexname = 'uq_question_mapping_set_primary_item'
    ) THEN
        RAISE EXCEPTION 'Primary mapping partial unique index is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.question_import_batches'::regclass
          AND conname = 'uq_question_import_batch_source_sha256'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'idx_question_import_items_payload_hash'
    ) THEN
        RAISE EXCEPTION 'Question import idempotency or duplicate-detection index is missing';
    END IF;
END
$assert_schema$;

INSERT INTO public.users (username, email, password_hash, full_name)
VALUES ('phase04-test-reviewer', 'phase04-reviewer@example.invalid', 'not-a-credential', 'Test Reviewer');

INSERT INTO public.exams (code, name) VALUES ('CF035B_TEST', 'Isolated migration fixture');
INSERT INTO public.exam_versions (exam_id, version_code, name)
SELECT exam_id, 'fixture-v1', 'Fixture Version'
FROM public.exams WHERE code = 'CF035B_TEST';
INSERT INTO public.sections (exam_version_id, section_code, section_name, order_no)
SELECT exam_version_id, 'FIXTURE', 'Fixture Section', 1
FROM public.exam_versions ev JOIN public.exams e USING (exam_id)
WHERE e.code = 'CF035B_TEST';
INSERT INTO public.competencies (competency_code, competency_name)
VALUES ('CF035B_COMP', 'Fixture Competency');
INSERT INTO public.domains (competency_id, domain_code, domain_name)
SELECT competency_id, 'FIXTURE_DOMAIN', 'Fixture Domain'
FROM public.competencies WHERE competency_code = 'CF035B_COMP';
INSERT INTO public.topics (domain_id, topic_code, topic_name)
SELECT domain_id, 'FIXTURE_TOPIC', 'Fixture Topic'
FROM public.domains WHERE domain_code = 'FIXTURE_DOMAIN';
INSERT INTO public.skills (topic_id, skill_code, skill_name)
SELECT topic_id, 'CF035B_SKILL', 'Fixture Skill'
FROM public.topics WHERE topic_code = 'FIXTURE_TOPIC';
INSERT INTO public.questions (question_code)
VALUES ('CF035B_QUESTION');
INSERT INTO public.passages (title, content, passage_type, source_reference)
VALUES ('Fixture passage', 'Fixture passage text.', 'READING', 'isolated-test');
INSERT INTO public.question_versions
    (question_id, section_id, passage_id, version_number, stem, question_type,
     response_mode, content_sha256)
SELECT q.question_id, s.section_id, p.passage_id, 1, 'Fixture question',
       'SINGLE_CHOICE', 'SINGLE_OPTION', repeat('a', 64)
FROM public.questions q CROSS JOIN public.sections s
JOIN public.passages p ON p.title = 'Fixture passage'
WHERE q.question_code = 'CF035B_QUESTION' AND s.section_code = 'FIXTURE';
INSERT INTO public.taxonomy_catalogs (taxonomy_id, catalog_name, catalog_type, status)
VALUES ('CF035B_FIXTURE', 'Migration fixture only', 'CUSTOM', 'DRAFT');
INSERT INTO public.taxonomy_releases (
    taxonomy_catalog_id, release_code, taxonomy_version, status,
    content_hash, snapshot_locator, manifest_json
)
SELECT taxonomy_catalog_id, 'FIXTURE-REL-1', '0.0.1', 'RELEASE_CANDIDATE',
       repeat('a', 64), 'test-fixture-only', '{}'::jsonb
FROM public.taxonomy_catalogs WHERE taxonomy_id = 'CF035B_FIXTURE';
INSERT INTO public.taxonomy_releases (
    taxonomy_catalog_id, release_code, taxonomy_version, status,
    content_hash, snapshot_locator, manifest_json
)
SELECT taxonomy_catalog_id, 'FIXTURE-REL-2', '0.0.2', 'RELEASE_CANDIDATE',
       repeat('b', 64), 'test-fixture-only', '{}'::jsonb
FROM public.taxonomy_catalogs WHERE taxonomy_id = 'CF035B_FIXTURE';
INSERT INTO public.taxonomy_release_members (taxonomy_release_id, skill_id, membership_status)
SELECT r.taxonomy_release_id, s.skill_id, 'ACTIVE'
FROM public.taxonomy_releases r CROSS JOIN public.skills s
WHERE r.release_code = 'FIXTURE-REL-1' AND s.skill_code = 'CF035B_SKILL';
INSERT INTO public.question_mapping_sets
    (question_version_id, taxonomy_release_id, mapping_revision, status)
SELECT v.question_version_id, r.taxonomy_release_id, 1, 'DRAFT'
FROM public.question_versions v CROSS JOIN public.taxonomy_releases r
WHERE v.version_number = 1 AND r.release_code = 'FIXTURE-REL-1';

INSERT INTO public.visual_asset
    (question_version_id, asset_type, file_path, source_asset_id, provenance_json)
SELECT question_version_id, 'IMAGE', 'visuals/fixture.png', 'fixture-asset',
       '{"source":"isolated migration test"}'::jsonb
FROM public.question_versions
WHERE version_number = 1;
INSERT INTO public.question_options
    (question_version_id, option_key, option_text, is_correct, order_no)
SELECT question_version_id, 'A', 'Fixture option', TRUE, 1
FROM public.question_versions
WHERE version_number = 1;
INSERT INTO public.question_answers
    (question_version_id, answer_type, answer_value)
SELECT question_version_id, 'OPTION', 'A'
FROM public.question_versions
WHERE version_number = 1;

DO $assert_question_version_protection$
DECLARE
    original_version_id BIGINT;
    fixture_question_id BIGINT;
    fixture_section_id BIGINT;
    fixture_passage_id BIGINT;
    fixture_asset_id BIGINT;
BEGIN
    SELECT qv.question_version_id, qv.question_id, qv.section_id, qv.passage_id
    INTO original_version_id, fixture_question_id, fixture_section_id, fixture_passage_id
    FROM public.question_versions qv
    JOIN public.questions q ON q.question_id = qv.question_id
    WHERE q.question_code = 'CF035B_QUESTION' AND qv.version_number = 1;

    BEGIN
        UPDATE public.question_versions
        SET stem = 'Changed historical content'
        WHERE question_version_id = original_version_id;
        RAISE EXCEPTION 'Expected Question Version content to be immutable';
    EXCEPTION WHEN SQLSTATE '55000' THEN
        NULL;
    END;

    SELECT visual_asset_id INTO fixture_asset_id
    FROM public.visual_asset WHERE source_asset_id = 'fixture-asset';
    BEGIN
        UPDATE public.visual_asset SET file_path = 'changed.png'
        WHERE visual_asset_id = fixture_asset_id;
        RAISE EXCEPTION 'Expected Visual Asset content to be immutable';
    EXCEPTION WHEN SQLSTATE '55000' THEN
        NULL;
    END;

    BEGIN
        UPDATE public.question_options SET option_text = 'Changed option'
        WHERE question_version_id = original_version_id;
        RAISE EXCEPTION 'Expected Question Version option content to be immutable';
    EXCEPTION WHEN SQLSTATE '55000' THEN
        NULL;
    END;

    BEGIN
        UPDATE public.question_answers SET answer_value = 'B'
        WHERE question_version_id = original_version_id;
        RAISE EXCEPTION 'Expected Question Version answer content to be immutable';
    EXCEPTION WHEN SQLSTATE '55000' THEN
        NULL;
    END;

    BEGIN
        UPDATE public.passages SET content = 'Changed passage'
        WHERE passage_id = fixture_passage_id;
        RAISE EXCEPTION 'Expected referenced passage content to be immutable';
    EXCEPTION WHEN SQLSTATE '55000' THEN
        NULL;
    END;

    UPDATE public.question_versions
    SET is_latest = FALSE
    WHERE question_version_id = original_version_id;

    INSERT INTO public.question_versions
        (question_id, section_id, version_number, supersedes_question_version_id,
         stem, question_type, response_mode, content_sha256)
    VALUES
        (fixture_question_id, fixture_section_id, 2, original_version_id,
         'Revised fixture question', 'SINGLE_CHOICE', 'SINGLE_OPTION', repeat('b', 64));

    IF (SELECT COUNT(*) FROM public.question_versions qv
        WHERE qv.question_id = fixture_question_id AND qv.is_latest) <> 1 THEN
        RAISE EXCEPTION 'A question must have exactly one latest version';
    END IF;
END
$assert_question_version_protection$;

DO $assert_initial_review_states$
DECLARE
    reviewer_id BIGINT;
    pattern_id BIGINT;
BEGIN
    SELECT user_id INTO reviewer_id
    FROM public.users WHERE username = 'phase04-test-reviewer';

    BEGIN
        INSERT INTO public.question_mapping_sets
            (question_version_id, taxonomy_release_id, mapping_revision, status)
        SELECT v.question_version_id, r.taxonomy_release_id, 99, 'REVIEWED'
        FROM public.question_versions v
        CROSS JOIN public.taxonomy_releases r
        WHERE v.version_number = 1 AND r.release_code = 'FIXTURE-REL-1';
        RAISE EXCEPTION 'Expected a new mapping set to require DRAFT status';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    INSERT INTO public.question_patterns (pattern_code)
    VALUES ('CF035B_PATTERN');
    SELECT question_pattern_id INTO pattern_id
    FROM public.question_patterns WHERE pattern_code = 'CF035B_PATTERN';

    BEGIN
        INSERT INTO public.question_pattern_versions (
            question_pattern_id, version_number, pattern_type, structure_json,
            reasoning_json, distractor_json, provenance_json, content_sha256,
            miner_version, source_question_count, rights_status, rights_evidence_uri,
            rights_verified_by, rights_verified_at, review_status, reviewed_by,
            reviewed_at, review_reason, review_evidence
        ) VALUES (
            pattern_id, 1, 'FIXTURE', '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
            '{}'::jsonb, repeat('d', 64), 'test', 1, 'VERIFIED',
            'https://example.invalid/test-only-rights', reviewer_id,
            CURRENT_TIMESTAMP, 'APPROVED', reviewer_id, CURRENT_TIMESTAMP,
            'Synthetic test only.', '["TEST-ONLY"]'::jsonb
        );
        RAISE EXCEPTION 'Expected new Question Pattern Versions to begin unapproved';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    INSERT INTO public.question_import_batches
        (source_name, source_sha256, imported_by)
    VALUES ('migration-test-only', repeat('e', 64), reviewer_id);

    BEGIN
        INSERT INTO public.question_import_batches
            (source_name, source_sha256, imported_by)
        VALUES ('migration-test-duplicate', repeat('e', 64), reviewer_id);
        RAISE EXCEPTION 'Expected repeated import fingerprints to be rejected';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO public.question_import_items (
            import_batch_id, external_question_id, payload_json, payload_sha256,
            rights_status, rights_evidence_uri, rights_verified_by, rights_verified_at
        )
        SELECT import_batch_id, 'fixture', '{"fixture":true}'::jsonb, repeat('f', 64),
               'VERIFIED', 'https://example.invalid/test-only-rights',
               reviewer_id, CURRENT_TIMESTAMP
        FROM public.question_import_batches
        WHERE source_name = 'migration-test-only';
        RAISE EXCEPTION 'Expected imported question rights to begin unverified';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END
$assert_initial_review_states$;

DO $assert_release_uniqueness$
BEGIN
    BEGIN
        INSERT INTO public.taxonomy_releases (
            taxonomy_catalog_id, release_code, taxonomy_version, status,
            content_hash, snapshot_locator, manifest_json
        )
        SELECT taxonomy_catalog_id, 'FIXTURE-REL-1', '0.0.3', 'DRAFT',
               repeat('c', 64), 'test-fixture-only', '{}'::jsonb
        FROM public.taxonomy_catalogs WHERE taxonomy_id = 'CF035B_FIXTURE';
        RAISE EXCEPTION 'Expected duplicate release code to fail';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO public.taxonomy_releases (
            taxonomy_catalog_id, release_code, taxonomy_version, status,
            content_hash, snapshot_locator, manifest_json
        )
        SELECT taxonomy_catalog_id, 'FIXTURE-REL-BAD-HASH', '0.0.9', 'DRAFT',
               'bad-hash', 'test-fixture-only', '{}'::jsonb
        FROM public.taxonomy_catalogs WHERE taxonomy_id = 'CF035B_FIXTURE';
        RAISE EXCEPTION 'Expected invalid SHA-256 format to fail';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END
$assert_release_uniqueness$;

DO $assert_mapping_release_membership$
DECLARE
    mapping_id BIGINT;
    v_skill_id BIGINT;
    release_two BIGINT;
BEGIN
    SELECT mapping_set_id INTO mapping_id FROM public.question_mapping_sets LIMIT 1;
    SELECT s.skill_id INTO v_skill_id
    FROM public.skills s WHERE s.skill_code = 'CF035B_SKILL';
    SELECT taxonomy_release_id INTO release_two FROM public.taxonomy_releases
    WHERE release_code = 'FIXTURE-REL-2';

    BEGIN
        INSERT INTO public.question_mapping_set_items
            (mapping_set_id, skill_id, taxonomy_release_id, role)
        VALUES (mapping_id, v_skill_id, release_two, 'PRIMARY');
        RAISE EXCEPTION 'Expected mapping-set/release mismatch to fail';
    EXCEPTION WHEN foreign_key_violation OR check_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO public.question_mapping_set_items
            (mapping_set_id, skill_id, taxonomy_release_id, role)
        VALUES (mapping_id, 9223372036854770000, release_two, 'SECONDARY');
        RAISE EXCEPTION 'Expected a Skill outside the release to fail';
    EXCEPTION WHEN foreign_key_violation OR check_violation THEN
        NULL;
    END;
END
$assert_mapping_release_membership$;

INSERT INTO public.tests (test_code, title, exam_version_id, test_type, created_by)
SELECT 'CF035B_TEST_TEST', 'Legacy compatibility fixture', ev.exam_version_id,
       'PRACTICE', u.user_id
FROM public.exam_versions ev
JOIN public.exams e ON e.exam_id = ev.exam_id
CROSS JOIN public.users u
WHERE e.code = 'CF035B_TEST' AND u.username = 'phase04-test-reviewer';
INSERT INTO public.test_versions (test_id, version_number, duration_minutes, total_questions, total_score)
SELECT test_id, 1, 15, 1, 1
FROM public.tests WHERE test_code = 'CF035B_TEST_TEST';
INSERT INTO public.test_sections (test_version_id, section_id, section_order)
SELECT tv.test_version_id, s.section_id, 1
FROM public.test_versions tv
JOIN public.tests t ON t.test_id = tv.test_id
CROSS JOIN public.sections s
WHERE t.test_code = 'CF035B_TEST_TEST' AND s.section_code = 'FIXTURE';
INSERT INTO public.test_items (test_version_id, test_section_id, question_version_id, item_order)
SELECT tv.test_version_id, ts.test_section_id, qv.question_version_id, 1
FROM public.test_versions tv
JOIN public.tests t ON t.test_id = tv.test_id
JOIN public.test_sections ts ON ts.test_version_id = tv.test_version_id
JOIN public.sections s ON s.section_id = ts.section_id
JOIN public.question_versions qv ON qv.version_number = 1
JOIN public.questions q ON q.question_id = qv.question_id
WHERE t.test_code = 'CF035B_TEST_TEST'
  AND s.section_code = 'FIXTURE' AND q.question_code = 'CF035B_QUESTION';

DO $assert_legacy_nullable$
BEGIN
    IF (SELECT COUNT(*) FROM public.test_versions WHERE taxonomy_release_id IS NULL) <> 1
       OR (SELECT COUNT(*) FROM public.test_items WHERE mapping_set_id IS NULL) <> 1
       OR NOT EXISTS (
           SELECT 1 FROM information_schema.columns
           WHERE table_schema = 'public'
             AND table_name = 'skill_performances'
             AND column_name = 'taxonomy_release_id'
             AND is_nullable = 'YES'
       ) THEN
        RAISE EXCEPTION 'Legacy nullable provenance rows were not preserved';
    END IF;
END
$assert_legacy_nullable$;

INSERT INTO public.taxonomy_release_events
    (taxonomy_release_id, event_type, actor, reason_text, evidence_uri)
SELECT taxonomy_release_id, 'APPROVED', 'fixture-human-reviewer',
       'Synthetic evidence used only to exercise an isolated database constraint.',
       'https://example.invalid/test-only-approval'
FROM public.taxonomy_releases WHERE release_code = 'FIXTURE-REL-1';
UPDATE public.taxonomy_releases
SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
    approval_references = '["TEST-ONLY"]'::jsonb,
    approved_by = 'fixture-human-reviewer',
    approval_decision_ref = 'TEST-ONLY'
WHERE release_code = 'FIXTURE-REL-1';

DO $assert_published_immutable$
DECLARE
    release_id BIGINT;
    mapping_id BIGINT;
    v_skill_id BIGINT;
BEGIN
    SELECT taxonomy_release_id INTO release_id FROM public.taxonomy_releases
    WHERE release_code = 'FIXTURE-REL-1';
    SELECT mapping_set_id INTO mapping_id FROM public.question_mapping_sets LIMIT 1;
    SELECT s.skill_id INTO v_skill_id
    FROM public.skills s WHERE s.skill_code = 'CF035B_SKILL';

    BEGIN
        UPDATE public.taxonomy_releases SET content_hash = repeat('f', 64)
        WHERE taxonomy_release_id = release_id;
        RAISE EXCEPTION 'Expected published release mutation to fail';
    EXCEPTION WHEN SQLSTATE '55000' THEN
        NULL;
    END;

    BEGIN
        UPDATE public.taxonomy_release_members SET membership_status = 'RETIRED'
        WHERE taxonomy_release_id = release_id AND skill_id = v_skill_id;
        RAISE EXCEPTION 'Expected published membership mutation to fail';
    EXCEPTION WHEN SQLSTATE '55000' THEN
        NULL;
    END;

    BEGIN
        UPDATE public.taxonomy_release_events SET reason_text = 'tampered'
        WHERE taxonomy_release_id = release_id AND event_type = 'APPROVED';
        RAISE EXCEPTION 'Expected release event mutation to fail';
    EXCEPTION WHEN SQLSTATE '55000' THEN
        NULL;
    END;

    INSERT INTO public.question_mapping_set_items
        (mapping_set_id, skill_id, taxonomy_release_id, role, is_primary)
    VALUES (mapping_id, v_skill_id, release_id, 'PRIMARY', TRUE);
    INSERT INTO public.question_mapping_set_events
        (mapping_set_id, taxonomy_release_id, decision_id, actor_id,
         status_before, status_after, reason, evidence_refs)
    SELECT mapping_id, release_id, 'TEST-ONLY-MAPPING', user_id,
           'DRAFT', 'REVIEWED', 'Synthetic test only.', '["TEST-ONLY"]'::jsonb
    FROM public.users WHERE username = 'phase04-test-reviewer';
    UPDATE public.question_mapping_sets SET status = 'REVIEWED'
    WHERE mapping_set_id = mapping_id;

    BEGIN
        INSERT INTO public.question_mapping_set_events
            (mapping_set_id, taxonomy_release_id, decision_id, actor_id,
             status_before, status_after, reason, evidence_refs)
        SELECT mapping_id, release_id, 'TEST-ONLY-PUBLISH', user_id,
               'REVIEWED', 'PUBLISHED', 'Synthetic test only.', '["TEST-ONLY"]'::jsonb
        FROM public.users WHERE username = 'phase04-test-reviewer';
        UPDATE public.question_mapping_sets
        SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
        WHERE mapping_set_id = mapping_id;
        RAISE EXCEPTION 'Expected mapping publication without Question approval to fail';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        UPDATE public.question_mapping_set_items SET role = 'SECONDARY'
        WHERE mapping_set_id = mapping_id AND skill_id = v_skill_id;
        RAISE EXCEPTION 'Expected reviewed mapping-set item mutation to fail';
    EXCEPTION WHEN SQLSTATE '55000' THEN
        NULL;
    END;

    BEGIN
        UPDATE public.question_mapping_set_events SET reason = 'tampered'
        WHERE decision_id = 'TEST-ONLY-MAPPING';
        RAISE EXCEPTION 'Expected mapping-set event mutation to fail';
    EXCEPTION WHEN SQLSTATE '55000' THEN
        NULL;
    END;
END
$assert_published_immutable$;

DO $assert_release_idempotence_marker$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.taxonomy_releases
        WHERE taxonomy_catalog_id IS NULL OR content_hash IS NULL
    ) THEN
        RAISE EXCEPTION 'Unexpected orphan or nullable taxonomy release';
    END IF;
END
$assert_release_idempotence_marker$;

ROLLBACK;
