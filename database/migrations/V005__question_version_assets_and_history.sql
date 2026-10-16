BEGIN;

DO $migration_guard$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'question_versions'
          AND column_name = 'content_sha256'
    ) OR EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'visual_asset'
          AND column_name = 'rights_status'
    ) THEN
        RAISE EXCEPTION
            'V005 target columns already exist; verify migration history before applying';
    END IF;
END
$migration_guard$;

ALTER TABLE public.question_versions
    ADD COLUMN content_sha256 CHAR(64),
    ADD COLUMN supersedes_question_version_id BIGINT,
    ADD CONSTRAINT fk_question_version_supersedes
        FOREIGN KEY (supersedes_question_version_id)
        REFERENCES public.question_versions (question_version_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT uq_question_version_supersedes
        UNIQUE (supersedes_question_version_id),
    ADD CONSTRAINT chk_question_version_content_sha256
        CHECK (
            content_sha256 IS NULL
            OR content_sha256 ~ '^[a-f0-9]{64}$'
        ),
    ADD CONSTRAINT chk_question_version_not_self_superseded
        CHECK (
            supersedes_question_version_id IS NULL
            OR supersedes_question_version_id <> question_version_id
        );

CREATE UNIQUE INDEX uq_question_versions_one_latest
    ON public.question_versions (question_id)
    WHERE is_latest;

ALTER TABLE public.visual_asset
    ADD COLUMN source_asset_id VARCHAR(255),
    ADD COLUMN provenance_json JSONB NOT NULL DEFAULT '{}'::JSONB,
    ADD COLUMN rights_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
        CHECK (rights_status IN ('UNVERIFIED', 'VERIFIED', 'REJECTED')),
    ADD COLUMN rights_evidence_uri TEXT,
    ADD COLUMN rights_verified_by BIGINT,
    ADD COLUMN rights_verified_at TIMESTAMPTZ,
    ADD CONSTRAINT fk_visual_asset_rights_reviewer
        FOREIGN KEY (rights_verified_by)
        REFERENCES public.users (user_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT chk_visual_asset_provenance_object
        CHECK (jsonb_typeof(provenance_json) = 'object'),
    ADD CONSTRAINT chk_visual_asset_rights_evidence
        CHECK (
            rights_status <> 'VERIFIED'
            OR (
                NULLIF(BTRIM(rights_evidence_uri), '') IS NOT NULL
                AND rights_verified_by IS NOT NULL
                AND rights_verified_at IS NOT NULL
            )
        );

CREATE INDEX idx_visual_asset_question_version
    ON public.visual_asset (question_version_id, visual_asset_id);

CREATE FUNCTION public.guard_question_version_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    parent_question_id BIGINT;
    parent_version_number INTEGER;
    latest_version_number INTEGER;
BEGIN
    IF NEW.content_sha256 IS NULL
       OR NEW.content_sha256 !~ '^[a-f0-9]{64}$'
       OR NEW.review_status NOT IN ('DRAFT', 'PENDING_REVIEW')
       OR NOT NEW.is_latest THEN
        RAISE EXCEPTION
            'New Question Versions require a content hash and must enter as latest drafts'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.supersedes_question_version_id IS NULL THEN
        IF NEW.version_number <> 1 THEN
            RAISE EXCEPTION 'The initial Question Version must be version 1'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    SELECT question_id, version_number
    INTO parent_question_id, parent_version_number
    FROM public.question_versions
    WHERE question_version_id = NEW.supersedes_question_version_id
    FOR UPDATE;

    SELECT MAX(version_number)
    INTO latest_version_number
    FROM public.question_versions
    WHERE question_id = NEW.question_id;

    IF parent_question_id IS NULL
       OR parent_question_id <> NEW.question_id
       OR NEW.version_number <> parent_version_number + 1
       OR NEW.version_number <> latest_version_number + 1 THEN
        RAISE EXCEPTION
            'A revision must supersede the latest version of the same question in sequence'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_versions_revision_insert
    BEFORE INSERT ON public.question_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_version_insert();

CREATE OR REPLACE FUNCTION public.guard_question_version_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.review_status <> 'DRAFT'
           OR EXISTS (
               SELECT 1
               FROM public.test_items i
               JOIN public.test_versions v
                 ON v.test_version_id = i.test_version_id
               WHERE i.question_version_id = OLD.question_version_id
                 AND v.status = 'PUBLISHED'
           ) THEN
            RAISE EXCEPTION 'Reviewed or published Question Versions cannot be deleted'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF (to_jsonb(NEW) - ARRAY['review_status', 'is_latest'])
       IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['review_status', 'is_latest']) THEN
        RAISE EXCEPTION 'Question Version content and provenance are immutable after creation'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.review_status IN ('APPROVED', 'REJECTED')
       AND NEW.review_status IS DISTINCT FROM OLD.review_status THEN
        RAISE EXCEPTION 'Final Question Version decisions are terminal'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.review_status = 'APPROVED'
       AND NOT (OLD.is_latest AND NOT NEW.is_latest
                AND NEW.review_status = OLD.review_status) THEN
        RAISE EXCEPTION 'Approved Question Versions are immutable except when superseded'
            USING ERRCODE = '55000';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.test_items i
        JOIN public.test_versions v
          ON v.test_version_id = i.test_version_id
        WHERE i.question_version_id = OLD.question_version_id
          AND v.status = 'PUBLISHED'
    ) AND NEW.review_status IS DISTINCT FROM OLD.review_status THEN
        RAISE EXCEPTION 'Question Versions used by published tests cannot change review status'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.review_status IS DISTINCT FROM OLD.review_status
       AND NOT (
           (OLD.review_status = 'DRAFT' AND NEW.review_status = 'PENDING_REVIEW')
           OR (OLD.review_status = 'PENDING_REVIEW' AND NEW.review_status = 'IN_REVIEW')
           OR (OLD.review_status = 'IN_REVIEW'
               AND NEW.review_status IN (
                   'PENDING_REVIEW', 'NEEDS_REVISION', 'APPROVED', 'REJECTED'
               ))
           OR (OLD.review_status = 'NEEDS_REVISION' AND NEW.review_status = 'IN_REVIEW')
       ) THEN
        RAISE EXCEPTION 'Invalid Question Version review transition: % -> %',
            OLD.review_status, NEW.review_status
            USING ERRCODE = '23514';
    END IF;

    IF NEW.review_status IS DISTINCT FROM OLD.review_status
       AND NEW.review_status NOT IN ('PENDING_REVIEW', 'IN_REVIEW')
       AND NOT EXISTS (
           SELECT 1
           FROM public.question_review r
           WHERE r.question_version_id = OLD.question_version_id
             AND r.status_before = OLD.review_status
             AND r.review_status = NEW.review_status
       ) THEN
        RAISE EXCEPTION 'Question Version transitions require an append-only review record'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$function$;

CREATE FUNCTION public.guard_question_version_asset()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    affected_version_id BIGINT;
    version_status VARCHAR(30);
BEGIN
    affected_version_id := CASE
        WHEN TG_OP = 'DELETE' THEN OLD.question_version_id
        ELSE NEW.question_version_id
    END;

    SELECT review_status INTO version_status
    FROM public.question_versions
    WHERE question_version_id = affected_version_id;

    IF TG_OP = 'INSERT' THEN
        IF version_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Visual assets can only be added to draft Question Versions'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Visual Asset provenance cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF version_status IN ('APPROVED', 'REJECTED')
       OR OLD.question_version_id IS DISTINCT FROM NEW.question_version_id
       OR (to_jsonb(NEW) - ARRAY[
               'rights_status', 'rights_evidence_uri', 'rights_verified_by',
               'rights_verified_at', 'needs_review'
           ]) IS DISTINCT FROM
          (to_jsonb(OLD) - ARRAY[
               'rights_status', 'rights_evidence_uri', 'rights_verified_by',
               'rights_verified_at', 'needs_review'
           ]) THEN
        RAISE EXCEPTION 'Visual asset content is immutable outside its human rights-review fields'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_visual_asset_question_version_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.visual_asset
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_version_asset();

CREATE FUNCTION public.guard_question_version_answer_content()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    version_id BIGINT;
    version_status VARCHAR(30);
BEGIN
    IF TG_OP = 'INSERT' THEN
        version_id := NEW.question_version_id;
        SELECT review_status INTO version_status
        FROM public.question_versions
        WHERE question_version_id = version_id;
        IF version_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Answers can only be added to draft Question Versions'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Question Version options and answers are append-only'
        USING ERRCODE = '55000';
END
$function$;

CREATE TRIGGER trg_question_options_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON public.question_options
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_version_answer_content();

CREATE TRIGGER trg_question_answers_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON public.question_answers
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_version_answer_content();

CREATE FUNCTION public.guard_published_question_passage()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.question_versions qv
        WHERE qv.passage_id = OLD.passage_id
    ) THEN
        RAISE EXCEPTION 'Passages referenced by a Question Version are immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$function$;

CREATE TRIGGER trg_passages_published_immutable
    BEFORE UPDATE OR DELETE ON public.passages
    FOR EACH ROW EXECUTE FUNCTION public.guard_published_question_passage();

COMMIT;
