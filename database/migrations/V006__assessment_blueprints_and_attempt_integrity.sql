BEGIN;

DO $migration_guard$
BEGIN
    IF to_regclass('public.test_blueprints') IS NOT NULL
       OR to_regclass('public.test_blueprint_versions') IS NOT NULL THEN
        RAISE EXCEPTION
            'V006 target objects already exist; verify migration history before applying';
    END IF;
END
$migration_guard$;

CREATE TABLE public.test_blueprints (
    blueprint_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    blueprint_code VARCHAR(100) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL CHECK (BTRIM(title) <> ''),
    description TEXT,
    exam_version_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_test_blueprints_exam_version
        FOREIGN KEY (exam_version_id)
        REFERENCES public.exam_versions (exam_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_test_blueprints_creator
        FOREIGN KEY (created_by)
        REFERENCES public.users (user_id)
        ON DELETE RESTRICT
);

CREATE TABLE public.test_blueprint_versions (
    blueprint_version_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    blueprint_id BIGINT NOT NULL,
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    test_type VARCHAR(50) NOT NULL
        CHECK (test_type IN ('DIAGNOSTIC', 'MINI_MOCK', 'FULL_MOCK', 'PRACTICE', 'CUSTOM')),
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes > 0),
    section_rules JSONB NOT NULL
        CHECK (jsonb_typeof(section_rules) = 'array' AND section_rules <> '[]'::JSONB),
    selection_rules JSONB NOT NULL DEFAULT '{}'::JSONB
        CHECK (jsonb_typeof(selection_rules) = 'object'),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    is_latest BOOLEAN NOT NULL DEFAULT TRUE,
    content_sha256 CHAR(64) NOT NULL
        CHECK (content_sha256 ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_test_blueprint_version UNIQUE (blueprint_id, version_number),
    CONSTRAINT uq_test_blueprint_version_context UNIQUE (blueprint_version_id, blueprint_id),
    CONSTRAINT fk_test_blueprint_versions_blueprint
        FOREIGN KEY (blueprint_id)
        REFERENCES public.test_blueprints (blueprint_id)
        ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_test_blueprint_one_latest
    ON public.test_blueprint_versions (blueprint_id)
    WHERE is_latest;

ALTER TABLE public.tests
    ADD COLUMN blueprint_version_id BIGINT,
    ADD CONSTRAINT fk_tests_blueprint_version
        FOREIGN KEY (blueprint_version_id)
        REFERENCES public.test_blueprint_versions (blueprint_version_id)
        ON DELETE RESTRICT;

ALTER TABLE public.test_versions
    ADD COLUMN builder_seed BIGINT,
    ADD COLUMN content_sha256 CHAR(64),
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN published_by BIGINT,
    ADD CONSTRAINT chk_test_version_content_hash
        CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[a-f0-9]{64}$'),
    ADD CONSTRAINT chk_test_version_publication_metadata
        CHECK (
            (status <> 'PUBLISHED' OR
             (published_at IS NOT NULL AND published_by IS NOT NULL AND content_sha256 IS NOT NULL))
            AND (published_at IS NULL OR status IN ('PUBLISHED', 'ARCHIVED'))
        ),
    ADD CONSTRAINT fk_test_version_publisher
        FOREIGN KEY (published_by)
        REFERENCES public.users (user_id)
        ON DELETE RESTRICT;

ALTER TABLE public.attempts
    ADD COLUMN ends_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_attempt_deadline
        CHECK (ends_at IS NULL OR ends_at > started_at);

ALTER TABLE public.attempt_answers
    ADD COLUMN revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    ADD COLUMN last_request_id UUID;

ALTER TABLE public.attempt_events
    ADD COLUMN idempotency_key UUID,
    ADD CONSTRAINT uq_attempt_event_idempotency UNIQUE (idempotency_key);

CREATE INDEX idx_attempts_due_auto_submit
    ON public.attempts (ends_at)
    WHERE status = 'IN_PROGRESS' AND ends_at IS NOT NULL;
CREATE INDEX idx_attempts_user_status_started
    ON public.attempts (user_id, status, started_at DESC);
CREATE INDEX idx_test_versions_published
    ON public.test_versions (status, test_id)
    WHERE status = 'PUBLISHED';
CREATE INDEX idx_test_items_version_order
    ON public.test_items (test_version_id, item_order);

CREATE FUNCTION public.guard_test_blueprint_version()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (
            SELECT 1 FROM public.tests
            WHERE blueprint_version_id = OLD.blueprint_version_id
        ) THEN
            RAISE EXCEPTION 'A blueprint version used by a test cannot be deleted'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.tests
        WHERE blueprint_version_id = OLD.blueprint_version_id
    ) AND (to_jsonb(NEW) - ARRAY['is_latest', 'status'])
        IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['is_latest', 'status']) THEN
        RAISE EXCEPTION 'A blueprint version used by a test is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_test_blueprint_version_guard
    BEFORE UPDATE OR DELETE ON public.test_blueprint_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_test_blueprint_version();

CREATE FUNCTION public.guard_test_version_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    version_status VARCHAR(20);
    target_version_id BIGINT;
BEGIN
    target_version_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.test_version_id ELSE NEW.test_version_id END;
    SELECT status INTO version_status
    FROM public.test_versions
    WHERE test_version_id = target_version_id
    FOR UPDATE;

    IF version_status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Published Test Versions and their items are immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$function$;

CREATE TRIGGER trg_test_sections_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON public.test_sections
    FOR EACH ROW EXECUTE FUNCTION public.guard_test_version_immutability();
CREATE TRIGGER trg_test_items_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON public.test_items
    FOR EACH ROW EXECUTE FUNCTION public.guard_test_version_immutability();

CREATE FUNCTION public.guard_test_version_status()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Published Test Versions cannot be changed or removed'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.status = 'PUBLISHED' THEN
        IF NEW.content_sha256 IS NULL
           OR NEW.published_at IS NULL
           OR NEW.published_by IS NULL
           OR NEW.total_questions <= 0
           OR (SELECT COUNT(*) FROM public.test_items
               WHERE test_version_id = NEW.test_version_id) <> NEW.total_questions THEN
            RAISE EXCEPTION 'Publishing requires a complete immutable Test Version snapshot'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$function$;

CREATE TRIGGER trg_test_version_status_guard
    BEFORE UPDATE OR DELETE ON public.test_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_test_version_status();

CREATE FUNCTION public.guard_attempt_answer_write()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    attempt_status VARCHAR(30);
    deadline TIMESTAMPTZ;
    target_attempt_id BIGINT;
BEGIN
    target_attempt_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.attempt_id ELSE NEW.attempt_id END;
    SELECT status, ends_at INTO attempt_status, deadline
    FROM public.attempts
    WHERE attempt_id = target_attempt_id
    FOR UPDATE;

    IF attempt_status <> 'IN_PROGRESS' OR (deadline IS NOT NULL AND deadline <= CURRENT_TIMESTAMP) THEN
        RAISE EXCEPTION 'Answers cannot be changed after submission or the server deadline'
            USING ERRCODE = '55000';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$function$;

CREATE TRIGGER trg_attempt_answer_write_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.attempt_answers
    FOR EACH ROW EXECUTE FUNCTION public.guard_attempt_answer_write();

COMMIT;
