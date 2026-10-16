BEGIN;

DO $migration_guard$
BEGIN
    IF to_regclass('public.question_patterns') IS NOT NULL
       OR to_regclass('public.question_pattern_versions') IS NOT NULL
       OR to_regclass('public.question_import_batches') IS NOT NULL
       OR to_regclass('public.question_import_items') IS NOT NULL THEN
        RAISE EXCEPTION
            'V004 target objects already exist; verify migration history before applying';
    END IF;
END
$migration_guard$;

ALTER TABLE public.question_versions
    DROP CONSTRAINT question_versions_review_status_check,
    ADD CONSTRAINT question_versions_review_status_check
        CHECK (review_status IN (
            'DRAFT', 'PENDING_REVIEW', 'IN_REVIEW',
            'NEEDS_REVISION', 'APPROVED', 'REJECTED'
        ));

ALTER TABLE public.question_review
    DROP CONSTRAINT question_review_review_status_check,
    ADD CONSTRAINT question_review_review_status_check
        CHECK (review_status IN (
            'PENDING', 'PENDING_REVIEW', 'IN_REVIEW', 'APPROVED',
            'REJECTED', 'NEEDS_REVISION'
        ));

ALTER TABLE public.question_review
    ADD COLUMN status_before VARCHAR(30),
    ADD COLUMN decision_id VARCHAR(80),
    ADD COLUMN reason TEXT,
    ADD COLUMN evidence_refs JSONB,
    ADD COLUMN taxonomy_release_id BIGINT,
    ADD COLUMN taxonomy_version VARCHAR(32),
    ADD COLUMN source_rights_evidence_uri TEXT,
    ADD COLUMN supersedes_review_id BIGINT,
    ADD CONSTRAINT uq_question_review_decision
        UNIQUE (decision_id),
    ADD CONSTRAINT fk_question_review_taxonomy_release
        FOREIGN KEY (taxonomy_release_id)
        REFERENCES public.taxonomy_releases (taxonomy_release_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_question_review_supersedes
        FOREIGN KEY (supersedes_review_id)
        REFERENCES public.question_review (review_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT chk_question_review_approval_evidence
        CHECK (
            review_status <> 'APPROVED'
            OR (
                NULLIF(BTRIM(reason), '') IS NOT NULL
                AND jsonb_typeof(evidence_refs) = 'array'
                AND evidence_refs <> '[]'::JSONB
                AND taxonomy_release_id IS NOT NULL
                AND NULLIF(BTRIM(taxonomy_version), '') IS NOT NULL
                AND NULLIF(BTRIM(source_rights_evidence_uri), '') IS NOT NULL
            )
        ) NOT VALID;

CREATE TABLE public.question_mapping_set_events (
    mapping_set_event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    decision_id VARCHAR(80) NOT NULL UNIQUE,
    mapping_set_id BIGINT NOT NULL,
    taxonomy_release_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    status_before VARCHAR(20) NOT NULL,
    status_after VARCHAR(20) NOT NULL,
    reason TEXT NOT NULL CHECK (BTRIM(reason) <> ''),
    evidence_refs JSONB NOT NULL
        CHECK (jsonb_typeof(evidence_refs) = 'array' AND evidence_refs <> '[]'::JSONB),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_question_mapping_set_events_set
        FOREIGN KEY (mapping_set_id)
        REFERENCES public.question_mapping_sets (mapping_set_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_mapping_set_events_release
        FOREIGN KEY (taxonomy_release_id)
        REFERENCES public.taxonomy_releases (taxonomy_release_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_mapping_set_events_actor
        FOREIGN KEY (actor_id)
        REFERENCES public.users (user_id)
        ON DELETE RESTRICT
);

CREATE FUNCTION public.guard_question_mapping_set_review()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    release_status VARCHAR(32);
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT (
            (OLD.status = 'DRAFT' AND NEW.status IN ('REVIEWED', 'RETIRED'))
            OR (OLD.status = 'REVIEWED' AND NEW.status IN ('PUBLISHED', 'RETIRED'))
        ) THEN
            RAISE EXCEPTION 'Invalid mapping-set status transition: % -> %',
                OLD.status, NEW.status
                USING ERRCODE = '23514';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM public.question_mapping_set_events e
            WHERE e.mapping_set_id = OLD.mapping_set_id
              AND e.taxonomy_release_id = NEW.taxonomy_release_id
              AND e.status_before = OLD.status
              AND e.status_after = NEW.status
        ) THEN
            RAISE EXCEPTION 'Mapping-set status transitions require an append-only review event'
                USING ERRCODE = '23514';
        END IF;

        IF NEW.status = 'PUBLISHED' THEN
            SELECT status INTO release_status
            FROM public.taxonomy_releases
            WHERE taxonomy_release_id = NEW.taxonomy_release_id;

            IF release_status IS DISTINCT FROM 'PUBLISHED' THEN
                RAISE EXCEPTION 'Mapping sets can only be published against a published taxonomy release'
                    USING ERRCODE = '23514';
            END IF;

            IF NOT EXISTS (
                SELECT 1
                FROM public.question_versions qv
                JOIN public.question_review qr
                  ON qr.question_version_id = qv.question_version_id
                 AND qr.review_status = 'APPROVED'
                 AND qr.taxonomy_release_id = NEW.taxonomy_release_id
                 AND NULLIF(BTRIM(qr.source_rights_evidence_uri), '') IS NOT NULL
                WHERE qv.question_version_id = NEW.question_version_id
                  AND qv.review_status = 'APPROVED'
            ) THEN
                RAISE EXCEPTION
                    'Mapping sets require an approved Question Version with matching taxonomy and rights evidence'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_mapping_set_review_gate
    BEFORE UPDATE ON public.question_mapping_sets
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_mapping_set_review();

CREATE FUNCTION public.guard_question_mapping_set_event_append_only()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION 'Question Mapping Set events are append-only'
        USING ERRCODE = '55000';
END
$function$;

CREATE TRIGGER trg_question_mapping_set_events_append_only
    BEFORE UPDATE OR DELETE ON public.question_mapping_set_events
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_mapping_set_event_append_only();

CREATE TABLE public.question_import_batches (
    import_batch_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_name VARCHAR(255) NOT NULL,
    source_locator TEXT,
    source_sha256 CHAR(64) NOT NULL
        CHECK (source_sha256 ~ '^[a-f0-9]{64}$'),
    imported_by BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'STAGED'
        CHECK (status IN ('STAGED', 'VALIDATED', 'REJECTED', 'PARTIAL')),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    validation_summary JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT fk_question_import_batches_user
        FOREIGN KEY (imported_by)
        REFERENCES public.users (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_question_import_batch_source_sha256
        UNIQUE (source_sha256)
);

CREATE TABLE public.question_import_items (
    import_item_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_batch_id BIGINT NOT NULL,
    external_question_id VARCHAR(255) NOT NULL,
    payload_json JSONB NOT NULL,
    payload_sha256 CHAR(64) NOT NULL
        CHECK (payload_sha256 ~ '^[a-f0-9]{64}$'),
    validation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (validation_status IN ('PENDING', 'VALID', 'INVALID', 'MATERIALIZED')),
    validation_errors JSONB NOT NULL DEFAULT '[]'::JSONB
        CHECK (jsonb_typeof(validation_errors) = 'array'),
    rights_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
        CHECK (rights_status IN ('UNVERIFIED', 'VERIFIED', 'REJECTED')),
    rights_evidence_uri TEXT,
    rights_verified_by BIGINT,
    rights_verified_at TIMESTAMPTZ,
    question_version_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_question_import_external_id
        UNIQUE (import_batch_id, external_question_id),
    CONSTRAINT uq_question_import_materialized_version
        UNIQUE (question_version_id),
    CONSTRAINT fk_question_import_items_batch
        FOREIGN KEY (import_batch_id)
        REFERENCES public.question_import_batches (import_batch_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_import_items_rights_reviewer
        FOREIGN KEY (rights_verified_by)
        REFERENCES public.users (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_import_items_question_version
        FOREIGN KEY (question_version_id)
        REFERENCES public.question_versions (question_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_question_import_rights_evidence
        CHECK (
            rights_status <> 'VERIFIED'
            OR (
                NULLIF(BTRIM(rights_evidence_uri), '') IS NOT NULL
                AND rights_verified_by IS NOT NULL
                AND rights_verified_at IS NOT NULL
            )
        )
);

CREATE TABLE public.question_patterns (
    question_pattern_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pattern_code VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE public.question_pattern_versions (
    pattern_version_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_pattern_id BIGINT NOT NULL,
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    pattern_type VARCHAR(64) NOT NULL,
    structure_json JSONB NOT NULL,
    reasoning_json JSONB NOT NULL,
    distractor_json JSONB NOT NULL,
    provenance_json JSONB NOT NULL,
    content_sha256 CHAR(64) NOT NULL
        CHECK (content_sha256 ~ '^[a-f0-9]{64}$'),
    miner_version VARCHAR(32) NOT NULL,
    source_question_count INTEGER NOT NULL CHECK (source_question_count > 0),
    rights_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
        CHECK (rights_status IN ('UNVERIFIED', 'VERIFIED', 'REJECTED')),
    rights_evidence_uri TEXT,
    rights_verified_by BIGINT,
    rights_verified_at TIMESTAMPTZ,
    review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (review_status IN (
            'PENDING_REVIEW', 'IN_REVIEW', 'NEEDS_REVISION',
            'APPROVED', 'REJECTED'
        )),
    reviewed_by BIGINT,
    reviewed_at TIMESTAMPTZ,
    review_reason TEXT,
    review_evidence JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_question_pattern_version
        UNIQUE (question_pattern_id, version_number),
    CONSTRAINT uq_question_pattern_version_id
        UNIQUE (pattern_version_id, question_pattern_id),
    CONSTRAINT fk_question_pattern_versions_pattern
        FOREIGN KEY (question_pattern_id)
        REFERENCES public.question_patterns (question_pattern_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_pattern_versions_rights_reviewer
        FOREIGN KEY (rights_verified_by)
        REFERENCES public.users (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_pattern_versions_reviewer
        FOREIGN KEY (reviewed_by)
        REFERENCES public.users (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_question_pattern_rights_evidence
        CHECK (
            rights_status <> 'VERIFIED'
            OR (
                NULLIF(BTRIM(rights_evidence_uri), '') IS NOT NULL
                AND rights_verified_by IS NOT NULL
                AND rights_verified_at IS NOT NULL
            )
        ),
    CONSTRAINT chk_question_pattern_approval_evidence
        CHECK (
            review_status <> 'APPROVED'
            OR (
                rights_status = 'VERIFIED'
                AND reviewed_by IS NOT NULL
                AND reviewed_at IS NOT NULL
                AND NULLIF(BTRIM(review_reason), '') IS NOT NULL
                AND jsonb_typeof(review_evidence) = 'array'
                AND review_evidence <> '[]'::JSONB
            )
        )
);

CREATE TABLE public.question_pattern_skill_mappings (
    pattern_skill_mapping_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pattern_version_id BIGINT NOT NULL,
    taxonomy_release_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    relationship_type VARCHAR(20) NOT NULL
        CHECK (relationship_type IN ('PRIMARY', 'SUPPORTING')),
    confidence NUMERIC(5,4)
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (review_status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    reviewed_by BIGINT,
    reviewed_at TIMESTAMPTZ,
    review_reason TEXT,
    evidence_refs JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_question_pattern_skill_release
        UNIQUE (pattern_version_id, taxonomy_release_id, skill_id),
    CONSTRAINT fk_question_pattern_skill_version
        FOREIGN KEY (pattern_version_id)
        REFERENCES public.question_pattern_versions (pattern_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_pattern_skill_release_member
        FOREIGN KEY (taxonomy_release_id, skill_id)
        REFERENCES public.taxonomy_release_members (taxonomy_release_id, skill_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_pattern_skill_reviewer
        FOREIGN KEY (reviewed_by)
        REFERENCES public.users (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_question_pattern_skill_approval
        CHECK (
            review_status <> 'APPROVED'
            OR (
                reviewed_by IS NOT NULL
                AND reviewed_at IS NOT NULL
                AND jsonb_typeof(evidence_refs) = 'array'
                AND evidence_refs <> '[]'::JSONB
            )
        )
);

CREATE TABLE public.question_pattern_reviews (
    pattern_review_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    decision_id VARCHAR(80) NOT NULL UNIQUE,
    pattern_version_id BIGINT NOT NULL,
    pattern_skill_mapping_id BIGINT,
    reviewer_id BIGINT NOT NULL,
    status_before VARCHAR(20) NOT NULL,
    status_after VARCHAR(20) NOT NULL,
    reason TEXT NOT NULL CHECK (BTRIM(reason) <> ''),
    evidence_refs JSONB NOT NULL
        CHECK (jsonb_typeof(evidence_refs) = 'array' AND evidence_refs <> '[]'::JSONB),
    rights_evidence_uri TEXT,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    supersedes_review_id BIGINT,
    CONSTRAINT fk_question_pattern_reviews_version
        FOREIGN KEY (pattern_version_id)
        REFERENCES public.question_pattern_versions (pattern_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_pattern_reviews_mapping
        FOREIGN KEY (pattern_skill_mapping_id)
        REFERENCES public.question_pattern_skill_mappings (pattern_skill_mapping_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_pattern_reviews_reviewer
        FOREIGN KEY (reviewer_id)
        REFERENCES public.users (user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_pattern_reviews_supersedes
        FOREIGN KEY (supersedes_review_id)
        REFERENCES public.question_pattern_reviews (pattern_review_id)
        ON DELETE RESTRICT
);

CREATE FUNCTION public.guard_question_import_item_initial_state()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.validation_status <> 'PENDING'
       OR NEW.rights_status <> 'UNVERIFIED'
       OR NEW.rights_evidence_uri IS NOT NULL
       OR NEW.rights_verified_by IS NOT NULL
       OR NEW.rights_verified_at IS NOT NULL
       OR NEW.question_version_id IS NOT NULL THEN
        RAISE EXCEPTION 'Imported questions must begin unverified and unstaged for review'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_import_item_initial_state
    BEFORE INSERT ON public.question_import_items
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_import_item_initial_state();

CREATE FUNCTION public.guard_question_pattern_version_initial_state()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.review_status <> 'PENDING_REVIEW'
       OR NEW.rights_status <> 'UNVERIFIED'
       OR NEW.reviewed_by IS NOT NULL
       OR NEW.reviewed_at IS NOT NULL
       OR NEW.review_reason IS NOT NULL
       OR NEW.review_evidence IS NOT NULL
       OR NEW.rights_verified_by IS NOT NULL
       OR NEW.rights_verified_at IS NOT NULL
       OR NEW.rights_evidence_uri IS NOT NULL THEN
        RAISE EXCEPTION 'New Question Pattern Versions must begin pending review with unverified rights'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_pattern_versions_initial_state
    BEFORE INSERT ON public.question_pattern_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_pattern_version_initial_state();

CREATE FUNCTION public.guard_question_pattern_skill_initial_state()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.review_status <> 'PENDING_REVIEW'
       OR NEW.reviewed_by IS NOT NULL
       OR NEW.reviewed_at IS NOT NULL
       OR NEW.review_reason IS NOT NULL
       OR NEW.evidence_refs IS NOT NULL THEN
        RAISE EXCEPTION 'New Question Pattern Skill mappings must begin pending review'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_pattern_skill_initial_state
    BEFORE INSERT ON public.question_pattern_skill_mappings
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_pattern_skill_initial_state();

CREATE FUNCTION public.guard_question_review_append_only()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION 'Question review decisions are append-only'
        USING ERRCODE = '55000';
END
$function$;

CREATE TRIGGER trg_question_review_append_only
    BEFORE UPDATE OR DELETE ON public.question_review
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_review_append_only();

CREATE FUNCTION public.guard_question_version_lifecycle()
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

    IF OLD.review_status IN ('APPROVED', 'REJECTED')
       AND NEW.review_status IS DISTINCT FROM OLD.review_status THEN
        RAISE EXCEPTION 'Final Question Version decisions are terminal'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.review_status = 'APPROVED'
       OR EXISTS (
           SELECT 1
           FROM public.test_items i
           JOIN public.test_versions v
             ON v.test_version_id = i.test_version_id
           WHERE i.question_version_id = OLD.question_version_id
             AND v.status = 'PUBLISHED'
       ) THEN
        RAISE EXCEPTION 'Approved or published Question Versions are immutable'
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

CREATE FUNCTION public.guard_new_question_version_review_status()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.review_status NOT IN ('DRAFT', 'PENDING_REVIEW') THEN
        RAISE EXCEPTION 'New Question Versions must enter the human review workflow as drafts'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_versions_initial_status
    BEFORE INSERT ON public.question_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_new_question_version_review_status();

CREATE TRIGGER trg_question_versions_lifecycle
    BEFORE UPDATE OR DELETE ON public.question_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_version_lifecycle();

CREATE FUNCTION public.guard_question_import_item_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Question import evidence cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'UPDATE'
       AND (
           NEW.import_batch_id IS DISTINCT FROM OLD.import_batch_id
           OR NEW.external_question_id IS DISTINCT FROM OLD.external_question_id
           OR NEW.payload_json IS DISTINCT FROM OLD.payload_json
           OR NEW.payload_sha256 IS DISTINCT FROM OLD.payload_sha256
       ) THEN
        RAISE EXCEPTION 'Question import payload and source identity are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_import_items_immutable_evidence
    BEFORE UPDATE OR DELETE ON public.question_import_items
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_import_item_mutation();

CREATE FUNCTION public.guard_question_pattern_review_append_only()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION 'Question Pattern review events are append-only'
        USING ERRCODE = '55000';
END
$function$;

CREATE TRIGGER trg_question_pattern_reviews_append_only
    BEFORE UPDATE OR DELETE ON public.question_pattern_reviews
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_pattern_review_append_only();

CREATE FUNCTION public.guard_question_pattern_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.review_status <> 'PENDING_REVIEW' THEN
            RAISE EXCEPTION 'Reviewed Question Pattern Versions cannot be deleted'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.review_status = 'APPROVED' THEN
        RAISE EXCEPTION 'Approved Question Pattern Versions are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.review_status = 'APPROVED' THEN
        IF OLD.review_status <> 'IN_REVIEW'
           OR NEW.rights_status <> 'VERIFIED'
           OR NOT EXISTS (
               SELECT 1
               FROM public.question_pattern_skill_mappings m
               WHERE m.pattern_version_id = OLD.pattern_version_id
                 AND m.review_status = 'APPROVED'
           )
           OR NOT EXISTS (
               SELECT 1
               FROM public.question_pattern_reviews r
               WHERE r.pattern_version_id = OLD.pattern_version_id
                 AND r.pattern_skill_mapping_id IS NULL
                 AND r.status_before = 'IN_REVIEW'
                 AND r.status_after = 'APPROVED'
                 AND r.reviewer_id = NEW.reviewed_by
                 AND r.reason = NEW.review_reason
                 AND r.rights_evidence_uri = NEW.rights_evidence_uri
                 AND r.evidence_refs = NEW.review_evidence
           ) THEN
            RAISE EXCEPTION
                'Pattern approval requires verified rights and a matching human review event'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.review_status IS DISTINCT FROM OLD.review_status
       AND NOT (
           (OLD.review_status = 'PENDING_REVIEW' AND NEW.review_status = 'IN_REVIEW')
           OR (OLD.review_status = 'IN_REVIEW'
               AND NEW.review_status IN (
                   'PENDING_REVIEW', 'NEEDS_REVISION', 'APPROVED', 'REJECTED'
               ))
           OR (OLD.review_status = 'NEEDS_REVISION' AND NEW.review_status = 'IN_REVIEW')
       ) THEN
        RAISE EXCEPTION 'Invalid Question Pattern review transition: % -> %',
            OLD.review_status, NEW.review_status
            USING ERRCODE = '23514';
    END IF;

    IF NEW.review_status IS DISTINCT FROM OLD.review_status
       AND NOT EXISTS (
           SELECT 1
           FROM public.question_pattern_reviews r
           WHERE r.pattern_version_id = OLD.pattern_version_id
             AND r.pattern_skill_mapping_id IS NULL
             AND r.status_before = OLD.review_status
             AND r.status_after = NEW.review_status
       ) THEN
        RAISE EXCEPTION 'Question Pattern transitions require an append-only review event'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_pattern_versions_lifecycle
    BEFORE UPDATE OR DELETE ON public.question_pattern_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_pattern_version_mutation();

CREATE FUNCTION public.guard_question_pattern_skill_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    pattern_status VARCHAR(20);
BEGIN
    SELECT v.review_status
    INTO pattern_status
    FROM public.question_pattern_versions v
    WHERE v.pattern_version_id = CASE WHEN TG_OP = 'DELETE'
        THEN OLD.pattern_version_id ELSE NEW.pattern_version_id END
    FOR UPDATE;

    IF pattern_status = 'APPROVED' THEN
        RAISE EXCEPTION 'Skill mappings of an approved pattern version are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'UPDATE'
       AND (
           NEW.pattern_version_id IS DISTINCT FROM OLD.pattern_version_id
           OR NEW.taxonomy_release_id IS DISTINCT FROM OLD.taxonomy_release_id
           OR NEW.skill_id IS DISTINCT FROM OLD.skill_id
           OR NEW.relationship_type IS DISTINCT FROM OLD.relationship_type
       ) THEN
        RAISE EXCEPTION 'Question Pattern Skill mapping identity is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Question Pattern Skill mapping history cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.review_status IS DISTINCT FROM OLD.review_status THEN
        IF OLD.review_status <> 'PENDING_REVIEW'
           OR NEW.review_status NOT IN ('APPROVED', 'REJECTED')
           OR NOT EXISTS (
               SELECT 1
               FROM public.question_pattern_reviews r
               WHERE r.pattern_skill_mapping_id = OLD.pattern_skill_mapping_id
                 AND r.status_before = OLD.review_status
                 AND r.status_after = NEW.review_status
                 AND r.reviewer_id = NEW.reviewed_by
                 AND r.reason = NEW.review_reason
                 AND r.evidence_refs = NEW.evidence_refs
           ) THEN
            RAISE EXCEPTION 'Skill mapping decisions require a matching human review event'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_pattern_skill_mappings_lifecycle
    BEFORE UPDATE OR DELETE ON public.question_pattern_skill_mappings
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_pattern_skill_mutation();

CREATE FUNCTION public.guard_question_publication()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.status = 'ACTIVE'
       AND (TG_OP = 'INSERT' OR OLD.status <> 'ACTIVE')
       AND NOT EXISTS (
           SELECT 1
           FROM public.question_versions v
           JOIN public.question_review r
             ON r.question_version_id = v.question_version_id
            AND r.review_status = 'APPROVED'
           JOIN public.question_mapping_sets m
             ON m.question_version_id = v.question_version_id
            AND m.status = 'PUBLISHED'
            AND m.published_at IS NOT NULL
           WHERE v.question_id = NEW.question_id
             AND v.is_latest
             AND v.review_status = 'APPROVED'
             AND r.taxonomy_release_id = m.taxonomy_release_id
             AND NULLIF(BTRIM(r.source_rights_evidence_uri), '') IS NOT NULL
       ) THEN
        RAISE EXCEPTION
            'Question publication requires an approved latest version, release-bound mapping, and rights evidence'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_questions_publication_gate
    BEFORE INSERT OR UPDATE OF status ON public.questions
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_publication();

CREATE INDEX idx_question_import_batches_status_time
    ON public.question_import_batches (status, imported_at DESC);
CREATE INDEX idx_question_import_items_batch_status
    ON public.question_import_items (import_batch_id, validation_status);
CREATE INDEX idx_question_import_items_source_rights
    ON public.question_import_items (rights_status, question_version_id);
CREATE INDEX idx_question_import_items_payload_hash
    ON public.question_import_items (payload_sha256);
CREATE INDEX idx_question_pattern_versions_review_rights
    ON public.question_pattern_versions (review_status, rights_status);
CREATE INDEX idx_question_pattern_skill_release_skill
    ON public.question_pattern_skill_mappings
        (taxonomy_release_id, skill_id, review_status);
CREATE INDEX idx_question_pattern_reviews_version_time
    ON public.question_pattern_reviews (pattern_version_id, reviewed_at DESC);

COMMIT;
