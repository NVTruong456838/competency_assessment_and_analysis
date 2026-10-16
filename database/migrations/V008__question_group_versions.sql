BEGIN;

DO $migration_guard$
BEGIN
    IF to_regclass('public.question_groups') IS NOT NULL
       OR to_regclass('public.question_group_versions') IS NOT NULL
       OR to_regclass('public.question_group_version_items') IS NOT NULL
       OR to_regclass('public.test_items_question_group_version_id_fkey') IS NOT NULL THEN
        RAISE EXCEPTION
            'V008 target objects already exist; verify migration history before applying';
    END IF;
END
$migration_guard$;

CREATE TABLE public.question_groups (
    question_group_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_code VARCHAR(100) NOT NULL UNIQUE CHECK (BTRIM(group_code) <> ''),
    created_by BIGINT NOT NULL REFERENCES public.users (user_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE public.question_group_versions (
    question_group_version_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_group_id BIGINT NOT NULL REFERENCES public.question_groups (question_group_id) ON DELETE RESTRICT,
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    title VARCHAR(255) NOT NULL CHECK (BTRIM(title) <> ''),
    stimulus_type VARCHAR(20) NOT NULL
        CHECK (stimulus_type IN ('PASSAGE', 'IMAGE', 'TABLE', 'CHART')),
    stimulus_json JSONB NOT NULL,
    provenance_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    rights_evidence_uri TEXT NOT NULL CHECK (BTRIM(rights_evidence_uri) <> ''),
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (review_status IN ('DRAFT', 'PENDING_REVIEW', 'IN_REVIEW',
                                 'NEEDS_REVISION', 'APPROVED', 'REJECTED', 'PUBLISHED')),
    content_sha256 CHAR(64),
    published_at TIMESTAMPTZ,
    published_by BIGINT REFERENCES public.users (user_id) ON DELETE RESTRICT,
    created_by BIGINT NOT NULL REFERENCES public.users (user_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_question_group_version UNIQUE (question_group_id, version_number),
    CONSTRAINT uq_question_group_version_context UNIQUE (question_group_version_id, question_group_id),
    CONSTRAINT ck_question_group_provenance_object
        CHECK (jsonb_typeof(provenance_json) = 'object'),
    CONSTRAINT ck_question_group_stimulus_object
        CHECK (jsonb_typeof(stimulus_json) = 'object'),
    CONSTRAINT ck_question_group_hash
        CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_question_group_publication CHECK (
        (review_status = 'PUBLISHED' AND published_at IS NOT NULL
            AND published_by IS NOT NULL AND content_sha256 IS NOT NULL)
        OR (review_status <> 'PUBLISHED' AND published_at IS NULL
            AND published_by IS NULL)
    )
);

CREATE TABLE public.question_group_version_items (
    question_group_version_id BIGINT NOT NULL
        REFERENCES public.question_group_versions (question_group_version_id) ON DELETE RESTRICT,
    question_version_id BIGINT NOT NULL
        REFERENCES public.question_versions (question_version_id) ON DELETE RESTRICT,
    item_order INTEGER NOT NULL CHECK (item_order > 0),
    PRIMARY KEY (question_group_version_id, question_version_id),
    CONSTRAINT uq_question_group_item_order UNIQUE (question_group_version_id, item_order)
);

CREATE TABLE public.question_group_version_reviews (
    question_group_version_review_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_group_version_id BIGINT NOT NULL
        REFERENCES public.question_group_versions (question_group_version_id) ON DELETE RESTRICT,
    reviewer_id BIGINT NOT NULL REFERENCES public.users (user_id) ON DELETE RESTRICT,
    review_status VARCHAR(20) NOT NULL
        CHECK (review_status IN ('PENDING_REVIEW', 'IN_REVIEW', 'NEEDS_REVISION', 'APPROVED', 'REJECTED')),
    reason TEXT NOT NULL CHECK (BTRIM(reason) <> ''),
    evidence_refs JSONB NOT NULL CHECK (
        jsonb_typeof(evidence_refs) = 'array' AND evidence_refs <> '[]'::jsonb
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE public.test_items
    ADD COLUMN question_group_version_id BIGINT,
    ADD CONSTRAINT fk_test_item_question_group_version
        FOREIGN KEY (question_group_version_id)
        REFERENCES public.question_group_versions (question_group_version_id) ON DELETE RESTRICT;

CREATE INDEX idx_question_group_versions_review
    ON public.question_group_versions (review_status, question_group_id);
CREATE INDEX idx_question_group_items_order
    ON public.question_group_version_items (question_group_version_id, item_order);
CREATE INDEX idx_test_items_question_group_version
    ON public.test_items (question_group_version_id)
    WHERE question_group_version_id IS NOT NULL;

CREATE FUNCTION public.guard_question_group_version_mutability()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF OLD.review_status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Published Question Group Versions are immutable'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_group_version_immutable
    BEFORE UPDATE OR DELETE ON public.question_group_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_group_version_mutability();

CREATE FUNCTION public.guard_question_group_publication_gate()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    item_count INTEGER;
    eligible_count INTEGER;
BEGIN
    IF NEW.review_status = 'PUBLISHED'
       AND (TG_OP = 'INSERT' OR OLD.review_status <> 'PUBLISHED') THEN
        SELECT COUNT(*) INTO item_count
        FROM public.question_group_version_items
        WHERE question_group_version_id = NEW.question_group_version_id;

        SELECT COUNT(*) INTO eligible_count
        FROM public.question_group_version_items gi
        JOIN public.question_versions qv
          ON qv.question_version_id = gi.question_version_id
         AND qv.is_latest AND qv.review_status = 'APPROVED'
        JOIN public.questions q
          ON q.question_id = qv.question_id AND q.status = 'ACTIVE'
        JOIN public.question_review qr
          ON qr.question_version_id = qv.question_version_id
         AND qr.review_status = 'APPROVED'
         AND NULLIF(BTRIM(qr.source_rights_evidence_uri), '') IS NOT NULL
        WHERE gi.question_group_version_id = NEW.question_group_version_id;

        IF item_count < 2 OR eligible_count <> item_count THEN
            RAISE EXCEPTION 'Question Group publication requires at least two published, rights-approved members'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_group_publication_gate
    BEFORE INSERT OR UPDATE OF review_status ON public.question_group_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_group_publication_gate();

CREATE FUNCTION public.guard_question_group_item_mutability()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    group_version_id BIGINT;
    group_id BIGINT;
    version_status VARCHAR(20);
    item_section_id BIGINT;
    group_section_id BIGINT;
BEGIN
    group_version_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.question_group_version_id ELSE NEW.question_group_version_id END;

    SELECT review_status, question_group_id
    INTO version_status, group_id
    FROM public.question_group_versions
    WHERE question_group_version_id = group_version_id
    FOR UPDATE;

    IF version_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Question Group membership can only change while the version is a draft'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP <> 'DELETE' THEN
        SELECT section_id INTO item_section_id
        FROM public.question_versions
        WHERE question_version_id = NEW.question_version_id;

        SELECT qv.section_id INTO group_section_id
        FROM public.question_group_version_items gi
        JOIN public.question_versions qv ON qv.question_version_id = gi.question_version_id
        WHERE gi.question_group_version_id = NEW.question_group_version_id
        LIMIT 1;

        IF item_section_id IS NULL OR (group_section_id IS NOT NULL AND group_section_id <> item_section_id) THEN
            RAISE EXCEPTION 'Question Group members must belong to the same Section'
                USING ERRCODE = '23514';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM public.question_group_version_items gi
            JOIN public.question_versions qv ON qv.question_version_id = gi.question_version_id
            WHERE gi.question_group_version_id = NEW.question_group_version_id
              AND (qv.section_id <> item_section_id OR NOT qv.is_latest)
        ) OR EXISTS (
            SELECT 1
            FROM public.question_versions qv
            JOIN public.questions q ON q.question_id = qv.question_id
            WHERE qv.question_version_id = NEW.question_version_id
              AND (
                  NOT qv.is_latest
                  OR (q.status = 'ACTIVE' AND NOT EXISTS (
                      SELECT 1
                      FROM public.question_group_version_items prior_item
                      JOIN public.question_group_versions prior_version
                        ON prior_version.question_group_version_id = prior_item.question_group_version_id
                      WHERE prior_item.question_version_id = NEW.question_version_id
                        AND prior_version.question_group_id = group_id
                  ))
              )
        ) OR EXISTS (
            SELECT 1
            FROM public.question_group_version_items gi
            JOIN public.question_group_versions existing_group
              ON existing_group.question_group_version_id = gi.question_group_version_id
            WHERE gi.question_version_id = NEW.question_version_id
              AND existing_group.question_group_id <> group_id
        ) THEN
            RAISE EXCEPTION 'Only latest unpublished Question Versions from one Section may join a group'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_group_item_mutability
    BEFORE INSERT OR UPDATE OR DELETE ON public.question_group_version_items
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_group_item_mutability();

CREATE FUNCTION public.guard_test_item_group_consistency()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    group_is_published BOOLEAN;
BEGIN
    IF NEW.question_group_version_id IS NULL THEN
        IF EXISTS (
            SELECT 1 FROM public.question_group_version_items gi
            WHERE gi.question_version_id = NEW.question_version_id
        ) THEN
            RAISE EXCEPTION 'A grouped Question Version must retain its Question Group snapshot in Test Items'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT qgv.review_status = 'PUBLISHED'
    INTO group_is_published
    FROM public.question_group_versions qgv
    JOIN public.question_group_version_items gi
      ON gi.question_group_version_id = qgv.question_group_version_id
     AND gi.question_version_id = NEW.question_version_id
    WHERE qgv.question_group_version_id = NEW.question_group_version_id;

    IF group_is_published IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Test Item Question Group snapshot must match its Question Version membership'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_test_item_group_consistency
    BEFORE INSERT OR UPDATE OF question_version_id, question_group_version_id ON public.test_items
    FOR EACH ROW EXECUTE FUNCTION public.guard_test_item_group_consistency();

COMMIT;
