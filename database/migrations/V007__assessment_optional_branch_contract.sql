BEGIN;

DO $migration_guard$
BEGIN
    IF to_regclass('public.assessment_optional_section_groups') IS NOT NULL
       OR to_regclass('public.assessment_optional_section_branches') IS NOT NULL
       OR to_regclass('public.assessment_optional_section_branch_sections') IS NOT NULL
       OR to_regclass('public.test_blueprint_optional_branches') IS NOT NULL THEN
        RAISE EXCEPTION
            'V007 target objects already exist; verify migration history before applying';
    END IF;
END
$migration_guard$;

-- A group is a logical optional-section container.  It is intentionally not a
-- question-bearing section; each branch maps explicitly to the real sections
-- from which Test Builder may select Question Versions.
CREATE TABLE public.assessment_optional_section_groups (
    optional_section_group_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exam_version_id BIGINT NOT NULL,
    group_code VARCHAR(100) NOT NULL CHECK (BTRIM(group_code) <> ''),
    group_name VARCHAR(255) NOT NULL CHECK (BTRIM(group_name) <> ''),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_optional_section_group_code
        UNIQUE (exam_version_id, group_code),
    CONSTRAINT fk_optional_section_group_exam_version
        FOREIGN KEY (exam_version_id)
        REFERENCES public.exam_versions (exam_version_id)
        ON DELETE RESTRICT
);

CREATE TABLE public.assessment_optional_section_branches (
    optional_section_branch_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    optional_section_group_id BIGINT NOT NULL,
    branch_code VARCHAR(100) NOT NULL CHECK (BTRIM(branch_code) <> ''),
    branch_name VARCHAR(255) NOT NULL CHECK (BTRIM(branch_name) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_optional_section_branch_code
        UNIQUE (optional_section_group_id, branch_code),
    CONSTRAINT uq_optional_section_branch_context
        UNIQUE (optional_section_branch_id, optional_section_group_id),
    CONSTRAINT fk_optional_section_branch_group
        FOREIGN KEY (optional_section_group_id)
        REFERENCES public.assessment_optional_section_groups (optional_section_group_id)
        ON DELETE RESTRICT
);

CREATE TABLE public.assessment_optional_section_branch_sections (
    optional_section_branch_id BIGINT NOT NULL,
    section_id BIGINT NOT NULL,
    PRIMARY KEY (optional_section_branch_id, section_id),
    CONSTRAINT uq_optional_branch_question_section UNIQUE (section_id),
    CONSTRAINT fk_optional_branch_section_branch
        FOREIGN KEY (optional_section_branch_id)
        REFERENCES public.assessment_optional_section_branches (optional_section_branch_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_optional_branch_section_section
        FOREIGN KEY (section_id)
        REFERENCES public.sections (section_id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_optional_section_groups_exam_version
    ON public.assessment_optional_section_groups (exam_version_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_optional_branch_sections_branch
    ON public.assessment_optional_section_branch_sections (optional_section_branch_id);

CREATE FUNCTION public.guard_optional_branch_section_exam_version()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    branch_exam_version_id BIGINT;
    section_exam_version_id BIGINT;
BEGIN
    SELECT g.exam_version_id
    INTO branch_exam_version_id
    FROM public.assessment_optional_section_branches b
    JOIN public.assessment_optional_section_groups g
      ON g.optional_section_group_id = b.optional_section_group_id
    WHERE b.optional_section_branch_id = NEW.optional_section_branch_id;

    SELECT exam_version_id
    INTO section_exam_version_id
    FROM public.sections
    WHERE section_id = NEW.section_id;

    IF branch_exam_version_id IS NULL OR section_exam_version_id IS NULL
       OR branch_exam_version_id <> section_exam_version_id THEN
        RAISE EXCEPTION 'An optional branch can only contain sections from its Exam Version'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_optional_branch_section_exam_version
    BEFORE INSERT OR UPDATE ON public.assessment_optional_section_branch_sections
    FOR EACH ROW EXECUTE FUNCTION public.guard_optional_branch_section_exam_version();

CREATE FUNCTION public.guard_optional_branch_group_exam_version()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.exam_version_id IS DISTINCT FROM OLD.exam_version_id
       AND EXISTS (
           SELECT 1
           FROM public.assessment_optional_section_branches b
           JOIN public.assessment_optional_section_branch_sections bs
             ON bs.optional_section_branch_id = b.optional_section_branch_id
           WHERE b.optional_section_group_id = OLD.optional_section_group_id
       ) THEN
        RAISE EXCEPTION 'The Exam Version cannot change after optional branch sections are configured'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_optional_branch_group_exam_version
    BEFORE UPDATE OF exam_version_id ON public.assessment_optional_section_groups
    FOR EACH ROW EXECUTE FUNCTION public.guard_optional_branch_group_exam_version();

CREATE TABLE public.test_blueprint_optional_branches (
    blueprint_version_id BIGINT NOT NULL,
    optional_section_group_id BIGINT NOT NULL,
    optional_section_branch_id BIGINT NOT NULL,
    PRIMARY KEY (blueprint_version_id, optional_section_group_id),
    CONSTRAINT fk_blueprint_optional_branch_version
        FOREIGN KEY (blueprint_version_id)
        REFERENCES public.test_blueprint_versions (blueprint_version_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_blueprint_optional_branch_group
        FOREIGN KEY (optional_section_group_id)
        REFERENCES public.assessment_optional_section_groups (optional_section_group_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_blueprint_optional_branch_choice
        FOREIGN KEY (optional_section_branch_id, optional_section_group_id)
        REFERENCES public.assessment_optional_section_branches (
            optional_section_branch_id,
            optional_section_group_id
        )
        ON DELETE RESTRICT
);

CREATE INDEX idx_blueprint_optional_branches_branch
    ON public.test_blueprint_optional_branches (optional_section_branch_id);

CREATE FUNCTION public.guard_referenced_optional_branch_configuration()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    affected_group_ids BIGINT[] := ARRAY[]::BIGINT[];
    affected_branch_id BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'assessment_optional_section_groups' THEN
        IF TG_OP <> 'INSERT' THEN
            affected_group_ids := array_append(affected_group_ids, OLD.optional_section_group_id);
        END IF;
        IF TG_OP <> 'DELETE' THEN
            affected_group_ids := array_append(affected_group_ids, NEW.optional_section_group_id);
        END IF;
    ELSIF TG_TABLE_NAME = 'assessment_optional_section_branches' THEN
        IF TG_OP <> 'INSERT' THEN
            affected_group_ids := array_append(affected_group_ids, OLD.optional_section_group_id);
        END IF;
        IF TG_OP <> 'DELETE' THEN
            affected_group_ids := array_append(affected_group_ids, NEW.optional_section_group_id);
        END IF;
    ELSIF TG_TABLE_NAME = 'assessment_optional_section_branch_sections' THEN
        IF TG_OP <> 'INSERT' THEN
            SELECT optional_section_group_id
            INTO affected_branch_id
            FROM public.assessment_optional_section_branches
            WHERE optional_section_branch_id = OLD.optional_section_branch_id;
            affected_group_ids := array_append(affected_group_ids, affected_branch_id);
        END IF;
        IF TG_OP <> 'DELETE' THEN
            SELECT optional_section_group_id
            INTO affected_branch_id
            FROM public.assessment_optional_section_branches
            WHERE optional_section_branch_id = NEW.optional_section_branch_id;
            affected_group_ids := array_append(affected_group_ids, affected_branch_id);
        END IF;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.test_blueprint_optional_branches selected_branch
        WHERE selected_branch.optional_section_group_id = ANY(affected_group_ids)
    ) THEN
        RAISE EXCEPTION 'Optional branch configuration cannot change after a Blueprint Version selects it'
            USING ERRCODE = '55000';
    END IF;

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$function$;

CREATE TRIGGER trg_optional_section_group_referenced_immutable
    BEFORE UPDATE OR DELETE ON public.assessment_optional_section_groups
    FOR EACH ROW EXECUTE FUNCTION public.guard_referenced_optional_branch_configuration();

CREATE TRIGGER trg_optional_section_branch_referenced_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON public.assessment_optional_section_branches
    FOR EACH ROW EXECUTE FUNCTION public.guard_referenced_optional_branch_configuration();

CREATE TRIGGER trg_optional_branch_sections_referenced_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON public.assessment_optional_section_branch_sections
    FOR EACH ROW EXECUTE FUNCTION public.guard_referenced_optional_branch_configuration();

CREATE FUNCTION public.guard_blueprint_optional_branch_contract()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    target_blueprint_version_id BIGINT;
    blueprint_exam_version_id BIGINT;
BEGIN
    target_blueprint_version_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.blueprint_version_id ELSE NEW.blueprint_version_id END;

    SELECT b.exam_version_id
    INTO blueprint_exam_version_id
    FROM public.test_blueprint_versions v
    JOIN public.test_blueprints b ON b.blueprint_id = v.blueprint_id
    WHERE v.blueprint_version_id = target_blueprint_version_id;

    IF blueprint_exam_version_id IS NULL THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.test_blueprint_optional_branches selected_branch
        JOIN public.assessment_optional_section_groups g
          ON g.optional_section_group_id = selected_branch.optional_section_group_id
        WHERE selected_branch.blueprint_version_id = target_blueprint_version_id
          AND (g.exam_version_id <> blueprint_exam_version_id OR g.status <> 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'Blueprint optional branch must be active and belong to its Exam Version'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.test_blueprint_versions v
        CROSS JOIN LATERAL jsonb_array_elements(v.section_rules) rule
        JOIN public.assessment_optional_section_branch_sections branch_section
          ON branch_section.section_id = NULLIF(rule ->> 'sectionId', '')::BIGINT
        JOIN public.assessment_optional_section_branches branch
          ON branch.optional_section_branch_id = branch_section.optional_section_branch_id
        LEFT JOIN public.test_blueprint_optional_branches selected_branch
          ON selected_branch.blueprint_version_id = v.blueprint_version_id
         AND selected_branch.optional_section_group_id = branch.optional_section_group_id
        WHERE v.blueprint_version_id = target_blueprint_version_id
          AND (
              selected_branch.optional_section_branch_id IS NULL
              OR selected_branch.optional_section_branch_id <> branch.optional_section_branch_id
          )
    ) THEN
        RAISE EXCEPTION 'Every optional question section must use exactly one configured blueprint branch'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.test_blueprint_optional_branches selected_branch
        WHERE selected_branch.blueprint_version_id = target_blueprint_version_id
          AND NOT EXISTS (
              SELECT 1
              FROM public.test_blueprint_versions v
              CROSS JOIN LATERAL jsonb_array_elements(v.section_rules) rule
              JOIN public.assessment_optional_section_branch_sections branch_section
                ON branch_section.section_id = NULLIF(rule ->> 'sectionId', '')::BIGINT
              WHERE v.blueprint_version_id = target_blueprint_version_id
                AND branch_section.optional_section_branch_id
                    = selected_branch.optional_section_branch_id
          )
    ) THEN
        RAISE EXCEPTION 'A configured blueprint branch must include at least one question section'
            USING ERRCODE = '23514';
    END IF;

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$function$;

CREATE CONSTRAINT TRIGGER trg_blueprint_optional_branch_contract_version
    AFTER INSERT OR UPDATE OR DELETE ON public.test_blueprint_versions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.guard_blueprint_optional_branch_contract();

CREATE CONSTRAINT TRIGGER trg_blueprint_optional_branch_contract_choice
    AFTER INSERT OR UPDATE OR DELETE ON public.test_blueprint_optional_branches
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.guard_blueprint_optional_branch_contract();

COMMIT;
