BEGIN;

DO $migration_guard$
BEGIN
    IF to_regclass('public.scoring_runs') IS NOT NULL
       OR to_regclass('public.scoring_question_results') IS NOT NULL
       OR to_regclass('public.scoring_section_results') IS NOT NULL
       OR to_regclass('public.scoring_skill_results') IS NOT NULL
       OR to_regclass('public.scoring_competency_results') IS NOT NULL
       OR to_regclass('public.scoring_dimension_results') IS NOT NULL THEN
        RAISE EXCEPTION
            'V009 target objects already exist; verify migration history before applying';
    END IF;
END
$migration_guard$;

CREATE TABLE public.scoring_runs (
    scoring_run_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_id BIGINT NOT NULL,
    test_version_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL REFERENCES public.users (user_id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    scoring_version VARCHAR(50) NOT NULL,
    analytics_version VARCHAR(50) NOT NULL,
    input_sha256 CHAR(64) NOT NULL CHECK (input_sha256 ~ '^[a-f0-9]{64}$'),
    taxonomy_release_id BIGINT REFERENCES public.taxonomy_releases (taxonomy_release_id) ON DELETE RESTRICT,
    raw_score NUMERIC(12,4) NOT NULL CHECK (raw_score >= 0),
    max_score NUMERIC(12,4) NOT NULL CHECK (max_score >= 0),
    accuracy NUMERIC(7,4) CHECK (accuracy IS NULL OR accuracy BETWEEN 0 AND 1),
    total_questions INTEGER NOT NULL CHECK (total_questions >= 0),
    answered_questions INTEGER NOT NULL CHECK (answered_questions >= 0),
    correct_questions INTEGER NOT NULL CHECK (correct_questions >= 0),
    incorrect_questions INTEGER NOT NULL CHECK (incorrect_questions >= 0),
    skipped_questions INTEGER NOT NULL CHECK (skipped_questions >= 0),
    total_time_seconds INTEGER CHECK (total_time_seconds IS NULL OR total_time_seconds >= 0),
    confidence NUMERIC(7,4) CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    provenance_json JSONB NOT NULL CHECK (jsonb_typeof(provenance_json) = 'object'),
    computed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_scoring_run_idempotency
        UNIQUE (attempt_id, scoring_version, request_id),
    CONSTRAINT uq_scoring_run_attempt_context
        UNIQUE (scoring_run_id, attempt_id, test_version_id),
    CONSTRAINT fk_scoring_run_attempt_version
        FOREIGN KEY (attempt_id, test_version_id)
        REFERENCES public.attempts (attempt_id, test_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_scoring_run_test_version_release
        FOREIGN KEY (test_version_id, taxonomy_release_id)
        REFERENCES public.test_versions (test_version_id, taxonomy_release_id) ON DELETE RESTRICT,
    CONSTRAINT ck_scoring_run_question_counts
        CHECK (answered_questions + skipped_questions = total_questions
            AND correct_questions + incorrect_questions = answered_questions),
    CONSTRAINT ck_scoring_run_score_bounds CHECK (raw_score <= max_score)
);

CREATE TABLE public.scoring_question_results (
    scoring_run_id BIGINT NOT NULL,
    attempt_id BIGINT NOT NULL,
    test_version_id BIGINT NOT NULL,
    test_item_id BIGINT NOT NULL,
    test_section_id BIGINT NOT NULL,
    question_version_id BIGINT NOT NULL,
    attempt_answer_id BIGINT,
    is_correct BOOLEAN,
    is_skipped BOOLEAN NOT NULL,
    score NUMERIC(12,4) NOT NULL CHECK (score >= 0),
    max_score NUMERIC(12,4) NOT NULL CHECK (max_score >= 0),
    time_spent_seconds INTEGER CHECK (time_spent_seconds IS NULL OR time_spent_seconds >= 0),
    cognitive_level VARCHAR(10),
    estimated_difficulty NUMERIC(7,4),
    PRIMARY KEY (scoring_run_id, test_item_id),
    CONSTRAINT fk_scoring_question_run
        FOREIGN KEY (scoring_run_id, attempt_id, test_version_id)
        REFERENCES public.scoring_runs (scoring_run_id, attempt_id, test_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_scoring_question_item
        FOREIGN KEY (test_item_id, test_version_id, question_version_id)
        REFERENCES public.test_items (test_item_id, test_version_id, question_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_scoring_question_section
        FOREIGN KEY (test_section_id, test_version_id)
        REFERENCES public.test_sections (test_section_id, test_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_scoring_question_answer
        FOREIGN KEY (attempt_answer_id, question_version_id)
        REFERENCES public.attempt_answers (attempt_answer_id, question_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_scoring_question_outcome
        CHECK ((is_skipped AND is_correct IS NULL AND score = 0)
            OR (NOT is_skipped AND is_correct IS NOT NULL))
);

CREATE TABLE public.scoring_section_results (
    scoring_run_id BIGINT NOT NULL REFERENCES public.scoring_runs (scoring_run_id) ON DELETE RESTRICT,
    test_section_id BIGINT NOT NULL REFERENCES public.test_sections (test_section_id) ON DELETE RESTRICT,
    raw_score NUMERIC(12,4) NOT NULL CHECK (raw_score >= 0),
    max_score NUMERIC(12,4) NOT NULL CHECK (max_score >= 0),
    accuracy NUMERIC(7,4) CHECK (accuracy IS NULL OR accuracy BETWEEN 0 AND 1),
    total_questions INTEGER NOT NULL CHECK (total_questions >= 0),
    answered_questions INTEGER NOT NULL CHECK (answered_questions >= 0),
    correct_questions INTEGER NOT NULL CHECK (correct_questions >= 0),
    incorrect_questions INTEGER NOT NULL CHECK (incorrect_questions >= 0),
    skipped_questions INTEGER NOT NULL CHECK (skipped_questions >= 0),
    time_spent_seconds INTEGER CHECK (time_spent_seconds IS NULL OR time_spent_seconds >= 0),
    PRIMARY KEY (scoring_run_id, test_section_id),
    CHECK (answered_questions + skipped_questions = total_questions),
    CHECK (correct_questions + incorrect_questions = answered_questions),
    CHECK (raw_score <= max_score)
);

CREATE TABLE public.scoring_skill_results (
    scoring_run_id BIGINT NOT NULL REFERENCES public.scoring_runs (scoring_run_id) ON DELETE RESTRICT,
    skill_id BIGINT NOT NULL REFERENCES public.skills (skill_id) ON DELETE RESTRICT,
    accuracy NUMERIC(7,4) NOT NULL CHECK (accuracy BETWEEN 0 AND 1),
    mastery_score NUMERIC(7,4) NOT NULL CHECK (mastery_score BETWEEN 0 AND 1),
    evidence_count INTEGER NOT NULL CHECK (evidence_count > 0),
    confidence NUMERIC(7,4) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    avg_time_seconds NUMERIC(12,4) CHECK (avg_time_seconds IS NULL OR avg_time_seconds >= 0),
    PRIMARY KEY (scoring_run_id, skill_id)
);

CREATE TABLE public.scoring_competency_results (
    scoring_run_id BIGINT NOT NULL REFERENCES public.scoring_runs (scoring_run_id) ON DELETE RESTRICT,
    competency_id BIGINT NOT NULL REFERENCES public.competencies (competency_id) ON DELETE RESTRICT,
    accuracy NUMERIC(7,4) NOT NULL CHECK (accuracy BETWEEN 0 AND 1),
    mastery_score NUMERIC(7,4) NOT NULL CHECK (mastery_score BETWEEN 0 AND 1),
    evidence_count INTEGER NOT NULL CHECK (evidence_count > 0),
    confidence NUMERIC(7,4) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    PRIMARY KEY (scoring_run_id, competency_id)
);

CREATE TABLE public.scoring_dimension_results (
    scoring_run_id BIGINT NOT NULL REFERENCES public.scoring_runs (scoring_run_id) ON DELETE RESTRICT,
    dimension_type VARCHAR(24) NOT NULL
        CHECK (dimension_type IN ('COGNITIVE_LEVEL', 'ESTIMATED_DIFFICULTY')),
    dimension_value VARCHAR(32) NOT NULL,
    total_questions INTEGER NOT NULL CHECK (total_questions > 0),
    answered_questions INTEGER NOT NULL CHECK (answered_questions >= 0),
    correct_questions INTEGER NOT NULL CHECK (correct_questions >= 0),
    accuracy NUMERIC(7,4) CHECK (accuracy IS NULL OR accuracy BETWEEN 0 AND 1),
    PRIMARY KEY (scoring_run_id, dimension_type, dimension_value)
);

CREATE INDEX idx_scoring_runs_user_computed
    ON public.scoring_runs (user_id, computed_at DESC);
CREATE INDEX idx_scoring_runs_attempt
    ON public.scoring_runs (attempt_id, computed_at DESC);
CREATE INDEX idx_scoring_skill_results_skill
    ON public.scoring_skill_results (skill_id, scoring_run_id);
CREATE INDEX idx_scoring_competency_results_competency
    ON public.scoring_competency_results (competency_id, scoring_run_id);

CREATE FUNCTION public.reject_scoring_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION 'Scoring run history is append-only'
        USING ERRCODE = '55000';
END
$function$;

CREATE TRIGGER trg_scoring_runs_immutable
    BEFORE UPDATE OR DELETE ON public.scoring_runs
    FOR EACH ROW EXECUTE FUNCTION public.reject_scoring_history_mutation();
CREATE TRIGGER trg_scoring_question_results_immutable
    BEFORE UPDATE OR DELETE ON public.scoring_question_results
    FOR EACH ROW EXECUTE FUNCTION public.reject_scoring_history_mutation();
CREATE TRIGGER trg_scoring_section_results_immutable
    BEFORE UPDATE OR DELETE ON public.scoring_section_results
    FOR EACH ROW EXECUTE FUNCTION public.reject_scoring_history_mutation();
CREATE TRIGGER trg_scoring_skill_results_immutable
    BEFORE UPDATE OR DELETE ON public.scoring_skill_results
    FOR EACH ROW EXECUTE FUNCTION public.reject_scoring_history_mutation();
CREATE TRIGGER trg_scoring_competency_results_immutable
    BEFORE UPDATE OR DELETE ON public.scoring_competency_results
    FOR EACH ROW EXECUTE FUNCTION public.reject_scoring_history_mutation();
CREATE TRIGGER trg_scoring_dimension_results_immutable
    BEFORE UPDATE OR DELETE ON public.scoring_dimension_results
    FOR EACH ROW EXECUTE FUNCTION public.reject_scoring_history_mutation();

COMMIT;
