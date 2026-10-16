BEGIN;

DO $migration_guard$
BEGIN
    IF to_regclass('public.taxonomy_catalogs') IS NOT NULL
       OR to_regclass('public.taxonomy_releases') IS NOT NULL
       OR to_regclass('public.taxonomy_release_members') IS NOT NULL
       OR to_regclass('public.taxonomy_release_events') IS NOT NULL
       OR to_regclass('public.question_mapping_sets') IS NOT NULL
       OR to_regclass('public.question_mapping_set_items') IS NOT NULL
       OR to_regclass('public.analytics_runs') IS NOT NULL THEN
        RAISE EXCEPTION
            'V003 target objects already exist; verify migration history before applying';
    END IF;
END
$migration_guard$;

CREATE TABLE public.taxonomy_catalogs (
    taxonomy_catalog_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    taxonomy_id VARCHAR(64) NOT NULL UNIQUE,
    catalog_name VARCHAR(255) NOT NULL,
    catalog_type VARCHAR(32) NOT NULL
        CHECK (catalog_type IN ('TSA', 'HSA', 'CUSTOM')),
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE public.taxonomy_releases (
    taxonomy_release_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    taxonomy_catalog_id BIGINT NOT NULL,
    release_code VARCHAR(64) NOT NULL,
    taxonomy_version VARCHAR(32) NOT NULL,
    parent_release_id BIGINT,
    status VARCHAR(32) NOT NULL
        CHECK (status IN (
            'DRAFT', 'IN_REVIEW', 'RELEASE_CANDIDATE',
            'PUBLISHED', 'RETIRED', 'SUPERSEDED'
        )),
    content_hash CHAR(64) NOT NULL
        CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    snapshot_locator TEXT NOT NULL
        CHECK (BTRIM(snapshot_locator) <> ''),
    manifest_json JSONB NOT NULL,
    approval_references JSONB,
    source_artifact_hashes JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    approved_by VARCHAR(255),
    approval_decision_ref VARCHAR(255),
    CONSTRAINT uq_taxonomy_release_code
        UNIQUE (taxonomy_catalog_id, release_code),
    CONSTRAINT uq_taxonomy_release_version
        UNIQUE (taxonomy_catalog_id, taxonomy_version),
    CONSTRAINT uq_taxonomy_release_id_catalog
        UNIQUE (taxonomy_release_id, taxonomy_catalog_id),
    CONSTRAINT fk_taxonomy_releases_catalog
        FOREIGN KEY (taxonomy_catalog_id)
        REFERENCES public.taxonomy_catalogs (taxonomy_catalog_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_taxonomy_releases_parent
        FOREIGN KEY (parent_release_id)
        REFERENCES public.taxonomy_releases (taxonomy_release_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_taxonomy_parent_self
        CHECK (parent_release_id IS NULL OR parent_release_id <> taxonomy_release_id),
    CONSTRAINT chk_taxonomy_release_published_at
        CHECK (
            (status <> 'PUBLISHED' OR published_at IS NOT NULL)
            AND (
                published_at IS NULL
                OR status IN ('PUBLISHED', 'RETIRED', 'SUPERSEDED')
            )
        ),
    CONSTRAINT chk_taxonomy_release_approval_refs
        CHECK (
            status <> 'PUBLISHED'
            OR (
                approval_references IS NOT NULL
                AND jsonb_typeof(approval_references) = 'array'
                AND approval_references <> '[]'::JSONB
                AND approved_by IS NOT NULL
                AND BTRIM(approved_by) <> ''
                AND approval_decision_ref IS NOT NULL
                AND BTRIM(approval_decision_ref) <> ''
            )
        )
);

CREATE TABLE public.taxonomy_release_members (
    taxonomy_release_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    membership_status VARCHAR(16) NOT NULL
        CHECK (membership_status IN ('ACTIVE', 'RETIRED', 'SUPERSEDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (taxonomy_release_id, skill_id),
    CONSTRAINT fk_taxonomy_release_members_release
        FOREIGN KEY (taxonomy_release_id)
        REFERENCES public.taxonomy_releases (taxonomy_release_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_taxonomy_release_members_skill
        FOREIGN KEY (skill_id)
        REFERENCES public.skills (skill_id)
        ON DELETE RESTRICT
);

CREATE TABLE public.taxonomy_release_events (
    taxonomy_release_event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    taxonomy_release_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL
        CHECK (event_type IN (
            'DRAFTED', 'APPROVED', 'CANDIDATE',
            'PUBLISHED', 'RETIRED', 'SUPERSEDED'
        )),
    event_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor VARCHAR(255),
    reason_code VARCHAR(64),
    reason_text TEXT,
    evidence_uri TEXT,
    metadata_json JSONB,
    CONSTRAINT uq_taxonomy_release_event
        UNIQUE (taxonomy_release_id, event_type, event_time, actor),
    CONSTRAINT fk_taxonomy_release_events_release
        FOREIGN KEY (taxonomy_release_id)
        REFERENCES public.taxonomy_releases (taxonomy_release_id)
        ON DELETE RESTRICT
);

CREATE TABLE public.question_mapping_sets (
    mapping_set_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_version_id BIGINT NOT NULL,
    taxonomy_release_id BIGINT NOT NULL,
    mapping_revision INTEGER NOT NULL CHECK (mapping_revision > 0),
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('DRAFT', 'REVIEWED', 'PUBLISHED', 'RETIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    metadata_json JSONB,
    CONSTRAINT uq_question_mapping_set_revision
        UNIQUE (question_version_id, mapping_revision),
    CONSTRAINT uq_question_mapping_set_release_revision
        UNIQUE (question_version_id, taxonomy_release_id, mapping_revision),
    CONSTRAINT uq_question_mapping_set_release
        UNIQUE (mapping_set_id, taxonomy_release_id),
    CONSTRAINT uq_question_mapping_set_context
        UNIQUE (mapping_set_id, question_version_id, taxonomy_release_id),
    CONSTRAINT fk_question_mapping_set_question_version
        FOREIGN KEY (question_version_id)
        REFERENCES public.question_versions (question_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_mapping_set_release
        FOREIGN KEY (taxonomy_release_id)
        REFERENCES public.taxonomy_releases (taxonomy_release_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_question_mapping_set_published_at
        CHECK (
            (status <> 'PUBLISHED' OR published_at IS NOT NULL)
            AND (
                published_at IS NULL
                OR status IN ('PUBLISHED', 'RETIRED')
            )
        )
);

CREATE TABLE public.question_mapping_set_items (
    mapping_set_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    taxonomy_release_id BIGINT NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    weight NUMERIC(5,4)
        CHECK (weight IS NULL OR (weight >= 0 AND weight <= 1)),
    role VARCHAR(16) NOT NULL
        CHECK (role IN ('PRIMARY', 'SECONDARY', 'EXCLUDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (mapping_set_id, skill_id),
    CONSTRAINT fk_question_mapping_set_item_set
        FOREIGN KEY (mapping_set_id)
        REFERENCES public.question_mapping_sets (mapping_set_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_question_mapping_set_item_set_release
        FOREIGN KEY (mapping_set_id, taxonomy_release_id)
        REFERENCES public.question_mapping_sets
            (mapping_set_id, taxonomy_release_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_question_mapping_set_item_release_skill
        FOREIGN KEY (taxonomy_release_id, skill_id)
        REFERENCES public.taxonomy_release_members (taxonomy_release_id, skill_id)
        ON DELETE RESTRICT
);

CREATE TABLE public.analytics_runs (
    analysis_run_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_id BIGINT NOT NULL,
    source_taxonomy_release_id BIGINT,
    analysis_taxonomy_release_id BIGINT NOT NULL,
    scoring_version VARCHAR(50) NOT NULL,
    analytics_version VARCHAR(50) NOT NULL,
    run_type VARCHAR(32) NOT NULL
        CHECK (run_type IN ('INITIAL', 'RE_ANALYSIS', 'PROFILE_REFRESH')),
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('DRAFT', 'COMPLETED', 'FAILED', 'REPLACED')),
    sample_size INTEGER CHECK (sample_size IS NULL OR sample_size >= 0),
    confidence NUMERIC(5,4)
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    computed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    provenance_json JSONB,
    CONSTRAINT uq_analytics_run_analysis_release
        UNIQUE (analysis_run_id, analysis_taxonomy_release_id),
    CONSTRAINT fk_analytics_runs_attempt
        FOREIGN KEY (attempt_id)
        REFERENCES public.attempts (attempt_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_analytics_runs_source_release
        FOREIGN KEY (source_taxonomy_release_id)
        REFERENCES public.taxonomy_releases (taxonomy_release_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_analytics_runs_analysis_release
        FOREIGN KEY (analysis_taxonomy_release_id)
        REFERENCES public.taxonomy_releases (taxonomy_release_id)
        ON DELETE RESTRICT
);

ALTER TABLE public.test_versions
    ADD COLUMN taxonomy_release_id BIGINT,
    ADD CONSTRAINT uq_test_version_release
        UNIQUE (test_version_id, taxonomy_release_id),
    ADD CONSTRAINT fk_test_versions_taxonomy_release
        FOREIGN KEY (taxonomy_release_id)
        REFERENCES public.taxonomy_releases (taxonomy_release_id)
        ON DELETE RESTRICT;

ALTER TABLE public.test_items
    ADD COLUMN mapping_set_id BIGINT,
    ADD COLUMN taxonomy_release_id BIGINT,
    ADD CONSTRAINT chk_test_item_mapping_release_pair
        CHECK ((mapping_set_id IS NULL) = (taxonomy_release_id IS NULL)),
    ADD CONSTRAINT fk_test_items_test_version_release
        FOREIGN KEY (test_version_id, taxonomy_release_id)
        REFERENCES public.test_versions (test_version_id, taxonomy_release_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_test_items_mapping_context
        FOREIGN KEY (mapping_set_id, question_version_id, taxonomy_release_id)
        REFERENCES public.question_mapping_sets
            (mapping_set_id, question_version_id, taxonomy_release_id)
        ON DELETE RESTRICT;

ALTER TABLE public.skill_performances
    ADD COLUMN taxonomy_release_id BIGINT,
    ADD COLUMN analytics_run_id BIGINT,
    ADD COLUMN scoring_version VARCHAR(50),
    ADD COLUMN analytics_version VARCHAR(50),
    ADD CONSTRAINT chk_skill_performance_analytics_context
        CHECK ((taxonomy_release_id IS NULL) = (analytics_run_id IS NULL)),
    ADD CONSTRAINT fk_skill_performances_taxonomy_release
        FOREIGN KEY (taxonomy_release_id)
        REFERENCES public.taxonomy_releases (taxonomy_release_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_skill_performances_analytics_run
        FOREIGN KEY (analytics_run_id, taxonomy_release_id)
        REFERENCES public.analytics_runs
            (analysis_run_id, analysis_taxonomy_release_id)
        ON DELETE RESTRICT;

ALTER TABLE public.competency_profiles
    ADD COLUMN taxonomy_release_id BIGINT,
    ADD COLUMN analytics_run_id BIGINT,
    ADD COLUMN scoring_version VARCHAR(50),
    ADD COLUMN analytics_version VARCHAR(50),
    ADD CONSTRAINT chk_competency_profile_analytics_context
        CHECK ((taxonomy_release_id IS NULL) = (analytics_run_id IS NULL)),
    ADD CONSTRAINT fk_competency_profiles_taxonomy_release
        FOREIGN KEY (taxonomy_release_id)
        REFERENCES public.taxonomy_releases (taxonomy_release_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_competency_profiles_analytics_run
        FOREIGN KEY (analytics_run_id, taxonomy_release_id)
        REFERENCES public.analytics_runs
            (analysis_run_id, analysis_taxonomy_release_id)
        ON DELETE RESTRICT;

CREATE INDEX idx_taxonomy_releases_catalog_status
    ON public.taxonomy_releases (taxonomy_catalog_id, status);
CREATE INDEX idx_taxonomy_release_members_skill_release
    ON public.taxonomy_release_members (skill_id, taxonomy_release_id);
CREATE INDEX idx_question_mapping_sets_question_status_release
    ON public.question_mapping_sets
        (question_version_id, status, taxonomy_release_id);
CREATE INDEX idx_question_mapping_set_items_release_skill
    ON public.question_mapping_set_items (taxonomy_release_id, skill_id);
CREATE UNIQUE INDEX uq_question_mapping_set_primary_item
    ON public.question_mapping_set_items (mapping_set_id)
    WHERE is_primary;
CREATE INDEX idx_analytics_runs_attempt_release_status
    ON public.analytics_runs
        (attempt_id, analysis_taxonomy_release_id, status);
CREATE INDEX idx_skill_performances_release_run
    ON public.skill_performances (taxonomy_release_id, analytics_run_id);
CREATE INDEX idx_competency_profiles_release_run
    ON public.competency_profiles (taxonomy_release_id, analytics_run_id);
CREATE INDEX idx_test_items_mapping_release
    ON public.test_items (mapping_set_id, taxonomy_release_id)
    WHERE mapping_set_id IS NOT NULL;

CREATE FUNCTION public.guard_taxonomy_release_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    has_approval_evidence BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'PUBLISHED' OR OLD.published_at IS NOT NULL THEN
            RAISE EXCEPTION 'Published taxonomy releases cannot be deleted'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status = 'PUBLISHED' THEN
            RAISE EXCEPTION 'Taxonomy releases must be reviewed before publication'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'PUBLISHED' OR OLD.published_at IS NOT NULL THEN
        IF OLD.status = 'PUBLISHED'
           AND NEW.status IN ('RETIRED', 'SUPERSEDED')
           AND (
               (to_jsonb(OLD) - 'status') = (to_jsonb(NEW) - 'status')
           )
           AND EXISTS (
               SELECT 1
               FROM public.taxonomy_release_events e
               WHERE e.taxonomy_release_id = OLD.taxonomy_release_id
                 AND e.event_type = NEW.status
                 AND NULLIF(BTRIM(e.actor), '') IS NOT NULL
                 AND NULLIF(BTRIM(e.evidence_uri), '') IS NOT NULL
                 AND NULLIF(BTRIM(e.reason_text), '') IS NOT NULL
           ) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'Published taxonomy release content is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status = 'PUBLISHED' THEN
        IF OLD.status <> 'RELEASE_CANDIDATE' THEN
            RAISE EXCEPTION
                'Publishing requires a release candidate and explicit human approval references'
                USING ERRCODE = '23514';
        END IF;

        SELECT EXISTS (
            SELECT 1
            FROM public.taxonomy_release_events e
            WHERE e.taxonomy_release_id = OLD.taxonomy_release_id
              AND e.event_type = 'APPROVED'
              AND NULLIF(BTRIM(e.actor), '') IS NOT NULL
              AND NULLIF(BTRIM(e.evidence_uri), '') IS NOT NULL
              AND NULLIF(BTRIM(e.reason_text), '') IS NOT NULL
        )
        INTO has_approval_evidence;

        IF NOT has_approval_evidence THEN
            RAISE EXCEPTION
                'Publishing a taxonomy release requires an approval event and evidence reference'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_taxonomy_release_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON public.taxonomy_releases
    FOR EACH ROW EXECUTE FUNCTION public.guard_taxonomy_release_mutation();

CREATE FUNCTION public.guard_taxonomy_release_member_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    release_id BIGINT;
    release_status VARCHAR(32);
    release_published_at TIMESTAMPTZ;
BEGIN
    release_id := CASE WHEN TG_OP = 'INSERT'
        THEN NEW.taxonomy_release_id ELSE OLD.taxonomy_release_id END;

    SELECT r.status, r.published_at
    INTO release_status, release_published_at
    FROM public.taxonomy_releases r
    WHERE r.taxonomy_release_id = release_id
    FOR UPDATE;

    IF NOT FOUND THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'Taxonomy release % does not exist', release_id
            USING ERRCODE = '23503';
    END IF;

    IF release_status = 'PUBLISHED' OR release_published_at IS NOT NULL THEN
        RAISE EXCEPTION 'Membership of a published taxonomy release is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'UPDATE'
       AND NEW.taxonomy_release_id IS DISTINCT FROM OLD.taxonomy_release_id THEN
        SELECT r.status, r.published_at
        INTO release_status, release_published_at
        FROM public.taxonomy_releases r
        WHERE r.taxonomy_release_id = NEW.taxonomy_release_id
        FOR UPDATE;

        IF FOUND
           AND (release_status = 'PUBLISHED' OR release_published_at IS NOT NULL) THEN
            RAISE EXCEPTION 'Membership cannot be moved into a published taxonomy release'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_taxonomy_release_members_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON public.taxonomy_release_members
    FOR EACH ROW EXECUTE FUNCTION public.guard_taxonomy_release_member_mutation();

CREATE FUNCTION public.guard_taxonomy_release_event_append_only()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION 'Taxonomy release events are append-only'
        USING ERRCODE = '55000';
END
$function$;

CREATE TRIGGER trg_taxonomy_release_events_append_only
    BEFORE UPDATE OR DELETE ON public.taxonomy_release_events
    FOR EACH ROW EXECUTE FUNCTION public.guard_taxonomy_release_event_append_only();

CREATE FUNCTION public.guard_question_mapping_set_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    set_id BIGINT;
    set_status VARCHAR(20);
    set_published_at TIMESTAMPTZ;
    set_found BOOLEAN;
    used_by_published_test BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' OR NEW.published_at IS NOT NULL THEN
            RAISE EXCEPTION 'New mapping sets must begin as drafts'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    set_id := OLD.mapping_set_id;

    SELECT s.status, s.published_at
    INTO set_status, set_published_at
    FROM public.question_mapping_sets s
    WHERE s.mapping_set_id = set_id
    FOR UPDATE;
    set_found := FOUND;

    IF set_found THEN
        IF set_status = 'PUBLISHED' OR set_published_at IS NOT NULL THEN
            RAISE EXCEPTION 'Published mapping sets are immutable'
                USING ERRCODE = '55000';
        END IF;
        IF set_status = 'REVIEWED'
           AND TG_OP = 'UPDATE'
           AND NEW.status = 'PUBLISHED'
           AND (to_jsonb(OLD) - ARRAY['status', 'published_at'])
               = (to_jsonb(NEW) - ARRAY['status', 'published_at']) THEN
            RETURN NEW;
        END IF;
        IF set_status = 'REVIEWED' THEN
            RAISE EXCEPTION 'Reviewed mapping sets are immutable; create a new revision'
                USING ERRCODE = '55000';
        END IF;

        SELECT EXISTS (
            SELECT 1
            FROM public.test_items i
            JOIN public.test_versions v
              ON v.test_version_id = i.test_version_id
            WHERE i.mapping_set_id = set_id
              AND v.status = 'PUBLISHED'
        )
        INTO used_by_published_test;

        IF used_by_published_test THEN
            RAISE EXCEPTION 'Mapping sets used by published tests are immutable'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    IF NEW.mapping_set_id <> OLD.mapping_set_id THEN
        SELECT s.status, s.published_at
        INTO set_status, set_published_at
        FROM public.question_mapping_sets s
        WHERE s.mapping_set_id = NEW.mapping_set_id
        FOR UPDATE;

        IF FOUND AND (set_status = 'PUBLISHED' OR set_published_at IS NOT NULL) THEN
            RAISE EXCEPTION 'Published mapping sets are immutable'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    IF NEW.status = 'PUBLISHED'
       AND (OLD.status <> 'REVIEWED' OR NEW.published_at IS NULL) THEN
        RAISE EXCEPTION 'A mapping set must be reviewed before publication'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_mapping_sets_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON public.question_mapping_sets
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_mapping_set_mutation();

CREATE FUNCTION public.guard_question_mapping_item_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    set_id BIGINT;
    set_status VARCHAR(20);
    set_published_at TIMESTAMPTZ;
    set_found BOOLEAN;
    used_by_published_test BOOLEAN;
BEGIN
    set_id := CASE WHEN TG_OP = 'INSERT'
        THEN NEW.mapping_set_id ELSE OLD.mapping_set_id END;

    SELECT s.status, s.published_at
    INTO set_status, set_published_at
    FROM public.question_mapping_sets s
    WHERE s.mapping_set_id = set_id
    FOR UPDATE;
    set_found := FOUND;

    IF set_found THEN
        IF set_status IN ('REVIEWED', 'PUBLISHED') OR set_published_at IS NOT NULL THEN
            RAISE EXCEPTION 'Items of a reviewed or published mapping set are immutable'
                USING ERRCODE = '55000';
        END IF;

        SELECT EXISTS (
            SELECT 1
            FROM public.test_items i
            JOIN public.test_versions v
              ON v.test_version_id = i.test_version_id
            WHERE i.mapping_set_id = set_id
              AND v.status = 'PUBLISHED'
        )
        INTO used_by_published_test;

        IF used_by_published_test THEN
            RAISE EXCEPTION 'Items used by a published test are immutable'
                USING ERRCODE = '55000';
        END IF;
    ELSIF TG_OP <> 'DELETE' THEN
        RAISE EXCEPTION 'Mapping set % does not exist', set_id
            USING ERRCODE = '23503';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.mapping_set_id <> OLD.mapping_set_id THEN
        SELECT s.status, s.published_at
        INTO set_status, set_published_at
        FROM public.question_mapping_sets s
        WHERE s.mapping_set_id = NEW.mapping_set_id
        FOR UPDATE;

        IF FOUND AND (set_status = 'PUBLISHED' OR set_published_at IS NOT NULL) THEN
            RAISE EXCEPTION 'Items cannot be moved into a published mapping set'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_mapping_set_items_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON public.question_mapping_set_items
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_mapping_item_mutation();

CREATE FUNCTION public.guard_question_mapping_membership()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.taxonomy_release_members
        WHERE taxonomy_release_id = NEW.taxonomy_release_id
          AND skill_id = NEW.skill_id
          AND membership_status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Question mapping items require an active Skill in the bound taxonomy release'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_mapping_item_active_skill
    BEFORE INSERT OR UPDATE ON public.question_mapping_set_items
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_mapping_membership();

CREATE FUNCTION public.guard_question_mapping_set_publication()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    item_count BIGINT;
    primary_count BIGINT;
BEGIN
    IF NEW.status = 'PUBLISHED' AND OLD.status <> 'PUBLISHED' THEN
        SELECT COUNT(*), COUNT(*) FILTER (WHERE is_primary)
        INTO item_count, primary_count
        FROM public.question_mapping_set_items
        WHERE mapping_set_id = NEW.mapping_set_id;

        IF item_count = 0 OR primary_count <> 1 THEN
            RAISE EXCEPTION 'Published mapping sets require items and exactly one primary Skill'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_question_mapping_set_publish_integrity
    BEFORE UPDATE ON public.question_mapping_sets
    FOR EACH ROW EXECUTE FUNCTION public.guard_question_mapping_set_publication();

CREATE FUNCTION public.guard_test_version_release()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    has_unmapped_item BOOLEAN;
    has_unpublished_mapping BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status = 'PUBLISHED' AND NEW.taxonomy_release_id IS NULL THEN
            RAISE EXCEPTION 'New published test versions require a taxonomy release'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.status <> 'PUBLISHED' THEN
            RETURN NEW;
        END IF;
    END IF;

    IF OLD.status = 'PUBLISHED'
       AND NEW.taxonomy_release_id IS DISTINCT FROM OLD.taxonomy_release_id THEN
        RAISE EXCEPTION 'A published test version cannot change taxonomy release'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status <> 'PUBLISHED' AND NEW.status = 'PUBLISHED' THEN
        IF NEW.taxonomy_release_id IS NULL THEN
            RAISE EXCEPTION 'New published test versions require a taxonomy release'
                USING ERRCODE = '23514';
        END IF;

        SELECT EXISTS (
            SELECT 1
            FROM public.test_items i
            WHERE i.test_version_id = NEW.test_version_id
              AND i.mapping_set_id IS NULL
        )
        INTO has_unmapped_item;

        IF has_unmapped_item THEN
            RAISE EXCEPTION 'Every item in a release-bound test must select a mapping set'
                USING ERRCODE = '23514';
        END IF;

        SELECT EXISTS (
            SELECT 1
            FROM public.test_items i
            JOIN public.question_mapping_sets s
              ON s.mapping_set_id = i.mapping_set_id
            WHERE i.test_version_id = NEW.test_version_id
              AND (s.status <> 'PUBLISHED' OR s.published_at IS NULL)
        )
        INTO has_unpublished_mapping;

        IF has_unpublished_mapping THEN
            RAISE EXCEPTION 'A published test requires published mapping sets'
                USING ERRCODE = '23514';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM public.taxonomy_releases r
            WHERE r.taxonomy_release_id = NEW.taxonomy_release_id
              AND r.status = 'PUBLISHED'
        ) THEN
            RAISE EXCEPTION 'A published test requires a published taxonomy release'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_test_versions_release_guard
    BEFORE INSERT OR UPDATE ON public.test_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_test_version_release();

CREATE FUNCTION public.guard_taxonomy_catalog_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Taxonomy catalog identities are retained'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.taxonomy_id IS DISTINCT FROM OLD.taxonomy_id
       AND EXISTS (
           SELECT 1 FROM public.taxonomy_releases
           WHERE taxonomy_catalog_id = OLD.taxonomy_catalog_id
       ) THEN
        RAISE EXCEPTION 'Taxonomy catalog identity is immutable once a release exists'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_taxonomy_catalog_identity
    BEFORE UPDATE OR DELETE ON public.taxonomy_catalogs
    FOR EACH ROW EXECUTE FUNCTION public.guard_taxonomy_catalog_mutation();

CREATE FUNCTION public.guard_test_item_release_provenance()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    version_status VARCHAR(20);
    version_release_id BIGINT;
    version_found BOOLEAN;
    version_id BIGINT;
BEGIN
    version_id := CASE WHEN TG_OP = 'INSERT'
        THEN NEW.test_version_id ELSE OLD.test_version_id END;

    SELECT v.status, v.taxonomy_release_id
    INTO version_status, version_release_id
    FROM public.test_versions v
    WHERE v.test_version_id = version_id
    FOR UPDATE;
    version_found := FOUND;

    IF version_found
       AND version_status = 'PUBLISHED'
       AND version_release_id IS NOT NULL THEN
        RAISE EXCEPTION 'Items in a release-bound published test are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.test_version_id <> OLD.test_version_id THEN
        SELECT v.status, v.taxonomy_release_id
        INTO version_status, version_release_id
        FROM public.test_versions v
        WHERE v.test_version_id = NEW.test_version_id
        FOR UPDATE;

        IF FOUND
           AND version_status = 'PUBLISHED'
           AND version_release_id IS NOT NULL THEN
            RAISE EXCEPTION 'Items cannot be moved into a release-bound published test'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_test_items_release_guard
    BEFORE INSERT OR UPDATE OR DELETE ON public.test_items
    FOR EACH ROW EXECUTE FUNCTION public.guard_test_item_release_provenance();

CREATE FUNCTION public.guard_analytics_run_append_only()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.status = 'DRAFT'
       AND NEW.status IN ('COMPLETED', 'FAILED')
       AND (to_jsonb(OLD) - 'status') = (to_jsonb(NEW) - 'status') THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Analytics runs are append-only; only DRAFT finalization is allowed'
        USING ERRCODE = '55000';
END
$function$;

CREATE TRIGGER trg_analytics_runs_append_only
    BEFORE UPDATE OR DELETE ON public.analytics_runs
    FOR EACH ROW EXECUTE FUNCTION public.guard_analytics_run_append_only();

COMMIT;
