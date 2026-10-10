BEGIN;

-- 0. SCHEMA

CREATE SCHEMA IF NOT EXISTS public;

SET search_path TO public;

-- 1. UTILITY FUNCTION

CREATE OR REPLACE FUNCTION set_updated_at()

RETURNS TRIGGER

LANGUAGE plpgsql

AS $$

BEGIN

    NEW.updated_at = CURRENT_TIMESTAMP;

    RETURN NEW;

END;

$$;

-- 2. IDENTITY & RBAC

-- 2.1 USERS

CREATE TABLE users (

    user_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    username VARCHAR(100) NOT NULL UNIQUE,

    email VARCHAR(255) NOT NULL UNIQUE,

    password_hash TEXT NOT NULL,

    full_name VARCHAR(255) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'

        CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED')),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP

);

-- 2.2 ROLES

CREATE TABLE roles (

    role_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    role_code VARCHAR(50) NOT NULL UNIQUE,

    role_name VARCHAR(100) NOT NULL,

    description TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP

);

-- 2.3 USER_ROLES

CREATE TABLE user_roles (

    user_id BIGINT NOT NULL,

    role_id BIGINT NOT NULL,

    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    assigned_by BIGINT,

    PRIMARY KEY (user_id, role_id),

    CONSTRAINT fk_user_roles_user

        FOREIGN KEY (user_id)

        REFERENCES users(user_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_user_roles_role

        FOREIGN KEY (role_id)

        REFERENCES roles(role_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_user_roles_assigned_by
        FOREIGN KEY (assigned_by)
        REFERENCES users(user_id)
        ON DELETE SET NULL

);

-- 2.4 CLASSES

CREATE TABLE classes (

    class_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    class_code VARCHAR(50) NOT NULL UNIQUE,

    class_name VARCHAR(150) NOT NULL,

    description TEXT,

    teacher_id BIGINT NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_classes_teacher
        FOREIGN KEY (teacher_id)
        REFERENCES users(user_id)
        ON DELETE RESTRICT

);

-- 2.5 CLASS_MEMBERS

CREATE TABLE class_members (

    class_id BIGINT NOT NULL,

    user_id BIGINT NOT NULL,

    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'LEFT')),

    PRIMARY KEY (class_id, user_id),

    CONSTRAINT fk_class_members_class
        FOREIGN KEY (class_id)
        REFERENCES classes(class_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_class_members_user
        FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE CASCADE

);

-- 2.6 AUDIT_LOGS

CREATE TABLE audit_logs (

    audit_log_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    user_id BIGINT NOT NULL,

    action VARCHAR(100) NOT NULL,

    entity_type VARCHAR(50) NOT NULL,

    entity_id BIGINT,

    old_data JSONB,

    new_data JSONB,

    ip_address VARCHAR(45),

    user_agent TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_audit_logs_user
        FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE RESTRICT

);

-- 3. ASSESSMENT & COMPETENCY FRAMEWORK

-- 3.1 EXAMS

CREATE TABLE exams (

    exam_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    code VARCHAR(50) NOT NULL UNIQUE,

    name VARCHAR(255) NOT NULL,

    description TEXT,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'

        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP

);

-- 3.2 EXAM_VERSIONS

CREATE TABLE exam_versions (

    exam_version_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    exam_id BIGINT NOT NULL,

    version_code VARCHAR(50) NOT NULL,

    year SMALLINT,

    name VARCHAR(255) NOT NULL,

    description TEXT,

    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'

        CHECK (

            status IN (

                'DRAFT',

                'ACTIVE',

                'INACTIVE',

                'ARCHIVED'

            )

        ),

    effective_from TIMESTAMPTZ,

    effective_to TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_exam_version_code

        UNIQUE (exam_id, version_code),

    CONSTRAINT fk_exam_versions_exam

        FOREIGN KEY (exam_id)

        REFERENCES exams(exam_id)

        ON DELETE RESTRICT,

    CONSTRAINT chk_exam_version_dates

        CHECK (

            effective_to IS NULL

            OR effective_from IS NULL

            OR effective_to > effective_from

        )

);

-- 3.3 SECTIONS

CREATE TABLE sections (

    section_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    exam_version_id BIGINT NOT NULL,

    section_code VARCHAR(50) NOT NULL,

    section_name VARCHAR(255) NOT NULL,

    description TEXT,

    order_no INTEGER NOT NULL

        CHECK (order_no > 0),

    question_count INTEGER

        CHECK (question_count IS NULL OR question_count >= 0),

    duration_minutes INTEGER

        CHECK (

            duration_minutes IS NULL

            OR duration_minutes > 0

        ),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_section_code

        UNIQUE (exam_version_id, section_code),

    CONSTRAINT uq_section_order

        UNIQUE (exam_version_id, order_no),

    CONSTRAINT fk_sections_exam_version

        FOREIGN KEY (exam_version_id)

        REFERENCES exam_versions(exam_version_id)

        ON DELETE CASCADE

);

-- 3.4 COMPETENCIES

CREATE TABLE competencies (

    competency_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    competency_code VARCHAR(100) NOT NULL UNIQUE,

    competency_name VARCHAR(255) NOT NULL,

    description TEXT,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'

        CHECK (status IN ('ACTIVE', 'INACTIVE')),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP

);

-- 3.5 SECTION_COMPETENCIES

CREATE TABLE section_competencies (

    section_id BIGINT NOT NULL,

    competency_id BIGINT NOT NULL,

    weight NUMERIC(7,4)

        CHECK (

            weight IS NULL

            OR (weight >= 0 AND weight <= 1)

        ),

    is_primary BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (section_id, competency_id),

    CONSTRAINT fk_section_competencies_section

        FOREIGN KEY (section_id)

        REFERENCES sections(section_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_section_competencies_competency

        FOREIGN KEY (competency_id)

        REFERENCES competencies(competency_id)

        ON DELETE CASCADE

);

-- 3.6 DOMAINS

CREATE TABLE domains (

    domain_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    competency_id BIGINT NOT NULL,

    domain_code VARCHAR(100) NOT NULL,

    domain_name VARCHAR(255) NOT NULL,

    description TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_domain_code

        UNIQUE (competency_id, domain_code),

    CONSTRAINT fk_domains_competency

        FOREIGN KEY (competency_id)

        REFERENCES competencies(competency_id)

        ON DELETE CASCADE

);

-- 3.7 TOPICS

CREATE TABLE topics (

    topic_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    domain_id BIGINT NOT NULL,

    topic_code VARCHAR(100) NOT NULL,

    topic_name VARCHAR(255) NOT NULL,

    description TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_topic_code

        UNIQUE (domain_id, topic_code),

    CONSTRAINT fk_topics_domain

        FOREIGN KEY (domain_id)

        REFERENCES domains(domain_id)

        ON DELETE CASCADE

);

-- 3.8 SKILLS

CREATE TABLE skills (

    skill_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    topic_id BIGINT NOT NULL,

    skill_code VARCHAR(100) NOT NULL,

    skill_name VARCHAR(255) NOT NULL,

    description TEXT,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'

        CHECK (status IN ('ACTIVE', 'INACTIVE')),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_skill_code

        UNIQUE (topic_id, skill_code),

    CONSTRAINT fk_skills_topic

        FOREIGN KEY (topic_id)

        REFERENCES topics(topic_id)

        ON DELETE CASCADE

);

-- 4. QUESTION BANK

-- 4.1 PASSAGES

CREATE TABLE passages (

    passage_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    title VARCHAR(500),

    content TEXT NOT NULL,

    passage_type VARCHAR(50)

        CHECK (

            passage_type IS NULL

            OR passage_type IN (

                'READING',

                'SCIENTIFIC',

                'DATA',

                'CONTEXT',

                'OTHER'

            )

        ),

    source_reference TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP

);

-- 4.2 QUESTIONS

CREATE TABLE questions (

    question_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    question_code VARCHAR(100) NOT NULL UNIQUE,

    parent_question_id BIGINT,

    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'

        CHECK (

            status IN (

                'DRAFT',

                'ACTIVE',

                'INACTIVE',

                'ARCHIVED'

            )

        ),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_questions_parent

        FOREIGN KEY (parent_question_id)

        REFERENCES questions(question_id)

        ON DELETE SET NULL

);

-- 4.3 QUESTION_VERSIONS

CREATE TABLE question_versions (

    question_version_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    question_id BIGINT NOT NULL,

    section_id BIGINT NOT NULL,

    passage_id BIGINT,

    version_number INTEGER NOT NULL

        CHECK (version_number > 0),

    stem TEXT NOT NULL,

    question_type VARCHAR(50) NOT NULL

        CHECK (

            question_type IN (

                'SINGLE_CHOICE',

                'MULTIPLE_CHOICE',

                'TRUE_FALSE',

                'NUMERIC',

                'SHORT_ANSWER'

            )

        ),

    response_mode VARCHAR(50) NOT NULL

        CHECK (

            response_mode IN (

                'SINGLE_OPTION',

                'MULTI_OPTION',

                'BOOLEAN',

                'NUMERIC',

                'TEXT'

            )

        ),

    cognitive_level VARCHAR(10)

        CHECK (

            cognitive_level IS NULL

            OR cognitive_level IN ('M1', 'M2', 'M3')

        ),

    estimated_difficulty NUMERIC(7,4)

        CHECK (

            estimated_difficulty IS NULL

            OR estimated_difficulty >= 0

        ),

    explanation TEXT,

    author_type VARCHAR(20) NOT NULL DEFAULT 'HUMAN'

        CHECK (

            author_type IN (

                'HUMAN',

                'AI',

                'MIXED'

            )

        ),

    review_status VARCHAR(30) NOT NULL DEFAULT 'DRAFT'

        CHECK (

            review_status IN (

                'DRAFT',

                'PENDING_REVIEW',

                'APPROVED',

                'REJECTED'

            )

        ),

    is_latest BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_question_version

        UNIQUE (question_id, version_number),

    CONSTRAINT fk_question_versions_question

        FOREIGN KEY (question_id)

        REFERENCES questions(question_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_question_versions_section

        FOREIGN KEY (section_id)

        REFERENCES sections(section_id)

        ON DELETE RESTRICT,

    CONSTRAINT fk_question_versions_passage

        FOREIGN KEY (passage_id)

        REFERENCES passages(passage_id)

        ON DELETE SET NULL

);

-- 4.4 QUESTION_OPTIONS

CREATE TABLE question_options (

    option_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    question_version_id BIGINT NOT NULL,

    option_key VARCHAR(10) NOT NULL,

    option_text TEXT NOT NULL,

    is_correct BOOLEAN NOT NULL DEFAULT FALSE,

    order_no INTEGER NOT NULL

        CHECK (order_no > 0),

    CONSTRAINT uq_question_option_key

        UNIQUE (question_version_id, option_key),

    CONSTRAINT uq_question_option_order

        UNIQUE (question_version_id, order_no),

    CONSTRAINT uq_question_option_id_version
        UNIQUE (option_id, question_version_id),

    CONSTRAINT fk_question_options_version
        FOREIGN KEY (question_version_id)
        REFERENCES question_versions(question_version_id)

        ON DELETE CASCADE

);

-- 4.5 QUESTION_ANSWERS

CREATE TABLE question_answers (

    answer_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    question_version_id BIGINT NOT NULL,

    answer_type VARCHAR(30) NOT NULL

        CHECK (

            answer_type IN (

                'OPTION',

                'TEXT',

                'NUMERIC',

                'BOOLEAN'

            )

        ),

    answer_value TEXT,

    numeric_value NUMERIC,

    tolerance NUMERIC

        CHECK (

            tolerance IS NULL

            OR tolerance >= 0

        ),

    is_correct BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_question_answers_version

        FOREIGN KEY (question_version_id)

        REFERENCES question_versions(question_version_id)

        ON DELETE CASCADE

);

-- 4.6 QUESTION_COMPETENCY_MAP

CREATE TABLE question_competency_map (

    question_version_id BIGINT NOT NULL,

    skill_id BIGINT NOT NULL,

    is_primary BOOLEAN NOT NULL DEFAULT FALSE,

    weight NUMERIC(7,4)

        CHECK (

            weight IS NULL

            OR (weight >= 0 AND weight <= 1)

        ),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (question_version_id, skill_id),

    CONSTRAINT fk_question_competency_question

        FOREIGN KEY (question_version_id)

        REFERENCES question_versions(question_version_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_question_competency_skill

        FOREIGN KEY (skill_id)

        REFERENCES skills(skill_id)

        ON DELETE RESTRICT

);

-- 4.7 QUESTION_PSYCHOMETRIC

CREATE TABLE question_psychometric (

    psychometric_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    question_version_id BIGINT NOT NULL,

    sample_size INTEGER

        CHECK (

            sample_size IS NULL

            OR sample_size >= 0

        ),

    facility_index NUMERIC(7,4)

        CHECK (

            facility_index IS NULL

            OR (

                facility_index >= 0

                AND facility_index <= 1

            )

        ),

    discrimination_index NUMERIC(10,4),

    empirical_difficulty NUMERIC(10,4),

    calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_question_psychometric_version

        FOREIGN KEY (question_version_id)

        REFERENCES question_versions(question_version_id)

        ON DELETE CASCADE

);

-- 4.8 QUESTION_REVIEW

CREATE TABLE question_review (

    review_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    question_version_id BIGINT NOT NULL,

    reviewer_id BIGINT NOT NULL,

    review_status VARCHAR(30) NOT NULL

        CHECK (

            review_status IN (

                'PENDING',

                'APPROVED',

                'REJECTED',

                'NEEDS_REVISION'

            )

        ),

    review_note TEXT,

    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_question_review_version

        FOREIGN KEY (question_version_id)

        REFERENCES question_versions(question_version_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_question_review_reviewer

        FOREIGN KEY (reviewer_id)

        REFERENCES users(user_id)

        ON DELETE RESTRICT

);

-- 4.9 VISUAL_ASSET

CREATE TABLE visual_asset (

    visual_asset_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    question_version_id BIGINT NOT NULL,

    asset_type VARCHAR(50) NOT NULL

        CHECK (

            asset_type IN (

                'IMAGE',

                'CHART',

                'TABLE',

                'DIAGRAM',

                'FORMULA',

                'OTHER'

            )

        ),

    file_path TEXT NOT NULL,

    caption TEXT,

    alt_text TEXT,

    page_number INTEGER

        CHECK (

            page_number IS NULL

            OR page_number > 0

        ),

    bbox JSONB,

    needs_review BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_visual_asset_question

        FOREIGN KEY (question_version_id)

        REFERENCES question_versions(question_version_id)

        ON DELETE CASCADE

);

-- 5. TEST & CBT ENGINE

-- 5.1 TESTS

CREATE TABLE tests (

    test_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    test_code VARCHAR(100) NOT NULL UNIQUE,

    title VARCHAR(255) NOT NULL,

    description TEXT,

    exam_version_id BIGINT NOT NULL,

    test_type VARCHAR(50) NOT NULL

        CHECK (

            test_type IN (

                'DIAGNOSTIC',

                'MINI_MOCK',

                'FULL_MOCK',

                'PRACTICE',

                'CUSTOM'

            )

        ),

    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'

        CHECK (

            status IN (

                'DRAFT',

                'PUBLISHED',

                'ARCHIVED'

            )

        ),

    created_by BIGINT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_tests_exam_version

        FOREIGN KEY (exam_version_id)

        REFERENCES exam_versions(exam_version_id)

        ON DELETE RESTRICT,

    CONSTRAINT fk_tests_created_by

        FOREIGN KEY (created_by)

        REFERENCES users(user_id)

        ON DELETE RESTRICT

);

-- 5.2 TEST_VERSIONS

CREATE TABLE test_versions (

    test_version_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    test_id BIGINT NOT NULL,

    version_number INTEGER NOT NULL

        CHECK (version_number > 0),

    duration_minutes INTEGER NOT NULL

        CHECK (duration_minutes > 0),

    total_questions INTEGER NOT NULL

        CHECK (total_questions >= 0),

    total_score NUMERIC(12,4) NOT NULL

        CHECK (total_score >= 0),

    shuffle_questions BOOLEAN NOT NULL DEFAULT FALSE,

    shuffle_options BOOLEAN NOT NULL DEFAULT FALSE,

    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'

        CHECK (

            status IN (

                'DRAFT',

                'PUBLISHED',

                'ARCHIVED'

            )

        ),

    is_latest BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_test_version

        UNIQUE (test_id, version_number),

    CONSTRAINT fk_test_versions_test

        FOREIGN KEY (test_id)

        REFERENCES tests(test_id)

        ON DELETE CASCADE

);

-- 5.3 TEST_SECTIONS

CREATE TABLE test_sections (

    test_section_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    test_version_id BIGINT NOT NULL,

    section_id BIGINT NOT NULL,

    section_order INTEGER NOT NULL

        CHECK (section_order > 0),

    question_count INTEGER

        CHECK (

            question_count IS NULL

            OR question_count >= 0

        ),

    duration_minutes INTEGER

        CHECK (

            duration_minutes IS NULL

            OR duration_minutes > 0

        ),

    score_weight NUMERIC(7,4)

        CHECK (

            score_weight IS NULL

            OR score_weight >= 0

        ),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_test_section

        UNIQUE (test_version_id, section_id),

    CONSTRAINT uq_test_section_order

        UNIQUE (test_version_id, section_order),

    CONSTRAINT uq_test_section_id_version
        UNIQUE (test_section_id, test_version_id),

    CONSTRAINT fk_test_sections_test_version
        FOREIGN KEY (test_version_id)
        REFERENCES test_versions(test_version_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_test_sections_section

        FOREIGN KEY (section_id)

        REFERENCES sections(section_id)

        ON DELETE RESTRICT

);

-- 5.4 TEST_ITEMS

CREATE TABLE test_items (

    test_item_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    test_version_id BIGINT NOT NULL,

    test_section_id BIGINT NOT NULL,

    question_version_id BIGINT NOT NULL,

    item_order INTEGER NOT NULL

        CHECK (item_order > 0),

    points NUMERIC(12,4) NOT NULL DEFAULT 1

        CHECK (points >= 0),

    is_required BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_test_item_order

        UNIQUE (test_version_id, item_order),

    CONSTRAINT uq_test_item_context

        UNIQUE (test_item_id, test_version_id, question_version_id),

    CONSTRAINT fk_test_items_section_version
        FOREIGN KEY (test_section_id, test_version_id)
        REFERENCES test_sections(test_section_id, test_version_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_test_items_question_version

        FOREIGN KEY (question_version_id)

        REFERENCES question_versions(question_version_id)

        ON DELETE RESTRICT

);

-- 5.5 ATTEMPTS

CREATE TABLE attempts (

    attempt_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    test_version_id BIGINT NOT NULL,

    user_id BIGINT NOT NULL,

    attempt_number INTEGER NOT NULL

        CHECK (attempt_number > 0),

    status VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS'

        CHECK (

            status IN (

                'IN_PROGRESS',

                'SUBMITTED',

                'AUTO_SUBMITTED',

                'ABANDONED'

            )

        ),

    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    submitted_at TIMESTAMPTZ,

    time_spent_seconds INTEGER

        CHECK (

            time_spent_seconds IS NULL

            OR time_spent_seconds >= 0

        ),

    auto_submitted BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_attempt_number

        UNIQUE (user_id, test_version_id, attempt_number),

    CONSTRAINT uq_attempt_id_version

        UNIQUE (attempt_id, test_version_id),

    CONSTRAINT fk_attempts_test_version
        FOREIGN KEY (test_version_id)
        REFERENCES test_versions(test_version_id)

        ON DELETE RESTRICT,

    CONSTRAINT fk_attempts_user

        FOREIGN KEY (user_id)

        REFERENCES users(user_id)

        ON DELETE RESTRICT,

    CONSTRAINT chk_attempt_submit_time

        CHECK (

            submitted_at IS NULL

            OR submitted_at >= started_at

        )

);

-- 5.6 ATTEMPT_ANSWERS

-- Repeated context keys let composite foreign keys enforce item and option ownership.
CREATE TABLE attempt_answers (

    attempt_answer_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    attempt_id BIGINT NOT NULL,

    test_item_id BIGINT NOT NULL,

    test_version_id BIGINT NOT NULL,

    question_version_id BIGINT NOT NULL,

    answer_text TEXT,

    numeric_answer NUMERIC,

    is_correct BOOLEAN,

    score NUMERIC(12,4)

        CHECK (

            score IS NULL

            OR score >= 0

        ),

    time_spent_seconds INTEGER

        CHECK (

            time_spent_seconds IS NULL

            OR time_spent_seconds >= 0

        ),

    marked_for_review BOOLEAN NOT NULL DEFAULT FALSE,

    answered_at TIMESTAMPTZ,

    CONSTRAINT uq_attempt_answer_item

        UNIQUE (attempt_id, test_item_id),

    CONSTRAINT uq_attempt_answer_id_question_version
        UNIQUE (attempt_answer_id, question_version_id),

    CONSTRAINT fk_attempt_answers_attempt_version
        FOREIGN KEY (attempt_id, test_version_id)
        REFERENCES attempts(attempt_id, test_version_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_attempt_answers_test_item_context
        FOREIGN KEY (test_item_id, test_version_id, question_version_id)
        REFERENCES test_items(test_item_id, test_version_id, question_version_id)
        ON DELETE RESTRICT

);

-- 5.7 ATTEMPT_SELECTED_OPTIONS

CREATE TABLE attempt_selected_options (

    attempt_answer_id BIGINT NOT NULL,

    option_id BIGINT NOT NULL,

    question_version_id BIGINT NOT NULL,

    selected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (attempt_answer_id, option_id),

    CONSTRAINT fk_selected_options_answer_question
        FOREIGN KEY (attempt_answer_id, question_version_id)
        REFERENCES attempt_answers(attempt_answer_id, question_version_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_selected_options_option_question
        FOREIGN KEY (option_id, question_version_id)
        REFERENCES question_options(option_id, question_version_id)
        ON DELETE RESTRICT

);

-- 5.8 ATTEMPT_EVENTS

CREATE TABLE attempt_events (

    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    attempt_id BIGINT NOT NULL,

    test_item_id BIGINT,

    event_type VARCHAR(50) NOT NULL,

    event_data JSONB,

    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_attempt_events_attempt

        FOREIGN KEY (attempt_id)

        REFERENCES attempts(attempt_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_attempt_events_test_item

        FOREIGN KEY (test_item_id)

        REFERENCES test_items(test_item_id)

        ON DELETE SET NULL

);

-- 6. ANALYTICS & RECOMMENDATION

-- 6.1 ATTEMPT_RESULTS

CREATE TABLE attempt_results (

    attempt_result_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    attempt_id BIGINT NOT NULL UNIQUE,

    raw_score NUMERIC(12,4),

    normalized_score NUMERIC(12,4),

    accuracy NUMERIC(7,4)

        CHECK (

            accuracy IS NULL

            OR (

                accuracy >= 0

                AND accuracy <= 1

            )

        ),

    total_questions INTEGER NOT NULL DEFAULT 0

        CHECK (total_questions >= 0),

    answered_questions INTEGER NOT NULL DEFAULT 0

        CHECK (answered_questions >= 0),

    correct_questions INTEGER NOT NULL DEFAULT 0

        CHECK (correct_questions >= 0),

    incorrect_questions INTEGER NOT NULL DEFAULT 0

        CHECK (incorrect_questions >= 0),

    skipped_questions INTEGER NOT NULL DEFAULT 0

        CHECK (skipped_questions >= 0),

    total_time_seconds INTEGER

        CHECK (

            total_time_seconds IS NULL

            OR total_time_seconds >= 0

        ),

    percentile_internal NUMERIC(7,4)

        CHECK (

            percentile_internal IS NULL

            OR (

                percentile_internal >= 0

                AND percentile_internal <= 1

            )

        ),

    calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_attempt_results_attempt

        FOREIGN KEY (attempt_id)

        REFERENCES attempts(attempt_id)

        ON DELETE CASCADE

);

-- 6.2 SECTION_RESULTS

CREATE TABLE section_results (

    section_result_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    attempt_id BIGINT NOT NULL,

    test_section_id BIGINT NOT NULL,

    raw_score NUMERIC(12,4),

    accuracy NUMERIC(7,4)

        CHECK (

            accuracy IS NULL

            OR (

                accuracy >= 0

                AND accuracy <= 1

            )

        ),

    total_questions INTEGER NOT NULL DEFAULT 0

        CHECK (total_questions >= 0),

    correct_questions INTEGER NOT NULL DEFAULT 0

        CHECK (correct_questions >= 0),

    time_spent_seconds INTEGER

        CHECK (

            time_spent_seconds IS NULL

            OR time_spent_seconds >= 0

        ),

    calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_section_result

        UNIQUE (attempt_id, test_section_id),

    CONSTRAINT fk_section_results_attempt

        FOREIGN KEY (attempt_id)

        REFERENCES attempts(attempt_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_section_results_test_section

        FOREIGN KEY (test_section_id)

        REFERENCES test_sections(test_section_id)

        ON DELETE RESTRICT

);

-- 6.3 QUESTION_RESULTS

CREATE TABLE question_results (

    question_result_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    attempt_id BIGINT NOT NULL,

    test_item_id BIGINT NOT NULL,

    attempt_answer_id BIGINT,

    is_correct BOOLEAN,

    score NUMERIC(12,4)

        CHECK (

            score IS NULL

            OR score >= 0

        ),

    time_spent_seconds INTEGER

        CHECK (

            time_spent_seconds IS NULL

            OR time_spent_seconds >= 0

        ),

    is_skipped BOOLEAN NOT NULL DEFAULT FALSE,

    confidence_level NUMERIC(7,4)

        CHECK (

            confidence_level IS NULL

            OR (

                confidence_level >= 0

                AND confidence_level <= 1

            )

        ),

    calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_question_result

        UNIQUE (attempt_id, test_item_id),

    CONSTRAINT uq_question_result_answer

        UNIQUE (attempt_answer_id),

    CONSTRAINT fk_question_results_attempt

        FOREIGN KEY (attempt_id)

        REFERENCES attempts(attempt_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_question_results_test_item

        FOREIGN KEY (test_item_id)

        REFERENCES test_items(test_item_id)

        ON DELETE RESTRICT,

    CONSTRAINT fk_question_results_attempt_answer

        FOREIGN KEY (attempt_answer_id)

        REFERENCES attempt_answers(attempt_answer_id)

        ON DELETE SET NULL

);

-- 6.4 SKILL_PERFORMANCES

CREATE TABLE skill_performances (

    skill_performance_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    user_id BIGINT NOT NULL,

    skill_id BIGINT NOT NULL,

    attempt_id BIGINT NOT NULL,

    accuracy NUMERIC(7,4)

        CHECK (

            accuracy >= 0

            AND accuracy <= 1

        ),

    mastery_score NUMERIC(7,4)

        CHECK (

            mastery_score >= 0

            AND mastery_score <= 1

        ),

    question_count INTEGER NOT NULL DEFAULT 0

        CHECK (question_count >= 0),

    correct_count INTEGER NOT NULL DEFAULT 0

        CHECK (correct_count >= 0),

    avg_time_seconds NUMERIC(12,4)

        CHECK (

            avg_time_seconds IS NULL

            OR avg_time_seconds >= 0

        ),

    confidence NUMERIC(7,4)

        CHECK (

            confidence IS NULL

            OR (

                confidence >= 0

                AND confidence <= 1

            )

        ),

    calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_skill_performance_attempt

        UNIQUE (user_id, skill_id, attempt_id),

    CONSTRAINT fk_skill_performance_user

        FOREIGN KEY (user_id)

        REFERENCES users(user_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_skill_performance_skill

        FOREIGN KEY (skill_id)

        REFERENCES skills(skill_id)

        ON DELETE RESTRICT,

    CONSTRAINT fk_skill_performance_attempt

        FOREIGN KEY (attempt_id)

        REFERENCES attempts(attempt_id)

        ON DELETE CASCADE

);

-- 6.5 COMPETENCY_PROFILES

CREATE TABLE competency_profiles (

    profile_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    user_id BIGINT NOT NULL,

    competency_id BIGINT NOT NULL,

    mastery_score NUMERIC(7,4)

        CHECK (

            mastery_score >= 0

            AND mastery_score <= 1

        ),

    accuracy NUMERIC(7,4)

        CHECK (

            accuracy >= 0

            AND accuracy <= 1

        ),

    question_count INTEGER NOT NULL DEFAULT 0

        CHECK (question_count >= 0),

    confidence NUMERIC(7,4)

        CHECK (

            confidence IS NULL

            OR (

                confidence >= 0

                AND confidence <= 1

            )

        ),

    sample_size INTEGER NOT NULL DEFAULT 0

        CHECK (sample_size >= 0),

    trend VARCHAR(30)

        CHECK (

            trend IS NULL

            OR trend IN (

                'IMPROVING',

                'STABLE',

                'DECLINING',

                'INSUFFICIENT_DATA'

            )

        ),

    calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_competency_profile

        UNIQUE (user_id, competency_id),

    CONSTRAINT fk_competency_profile_user

        FOREIGN KEY (user_id)

        REFERENCES users(user_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_competency_profile_competency

        FOREIGN KEY (competency_id)

        REFERENCES competencies(competency_id)

        ON DELETE RESTRICT

);

-- 6.6 ERROR_ANALYSES

CREATE TABLE error_analyses (

    error_analysis_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    question_result_id BIGINT NOT NULL,

    error_type VARCHAR(50) NOT NULL

        CHECK (

            error_type IN (

                'KNOWLEDGE',

                'REASONING',

                'MISREAD',

                'CALCULATION',

                'DISTRACTOR',

                'TIME',

                'GUESSING',

                'OTHER'

            )

        ),

    error_description TEXT,

    confidence NUMERIC(7,4)

        CHECK (

            confidence IS NULL

            OR (

                confidence >= 0

                AND confidence <= 1

            )

        ),

    detected_by VARCHAR(20) NOT NULL

        CHECK (

            detected_by IN (

                'RULE',

                'AI',

                'TEACHER'

            )

        ),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_error_analysis_question_result

        FOREIGN KEY (question_result_id)

        REFERENCES question_results(question_result_id)

        ON DELETE CASCADE

);

-- 6.7 RECOMMENDATIONS

CREATE TABLE recommendations (

    recommendation_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    user_id BIGINT NOT NULL,

    skill_id BIGINT,

    competency_id BIGINT,

    recommendation_type VARCHAR(50) NOT NULL,

    title VARCHAR(255) NOT NULL,

    description TEXT,

    priority INTEGER NOT NULL DEFAULT 1

        CHECK (priority > 0),

    reason TEXT,

    confidence NUMERIC(7,4)

        CHECK (

            confidence IS NULL

            OR (

                confidence >= 0

                AND confidence <= 1

            )

        ),

    status VARCHAR(30) NOT NULL DEFAULT 'PENDING'

        CHECK (

            status IN (

                'PENDING',

                'ACCEPTED',

                'COMPLETED',

                'DISMISSED'

            )

        ),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_recommendations_user

        FOREIGN KEY (user_id)

        REFERENCES users(user_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_recommendations_skill

        FOREIGN KEY (skill_id)

        REFERENCES skills(skill_id)

        ON DELETE SET NULL,

    CONSTRAINT fk_recommendations_competency

        FOREIGN KEY (competency_id)

        REFERENCES competencies(competency_id)

        ON DELETE SET NULL

);

-- 6.8 LEARNING_PLANS

CREATE TABLE learning_plans (

    learning_plan_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    user_id BIGINT NOT NULL,

    name VARCHAR(255) NOT NULL,

    description TEXT,

    target_exam VARCHAR(50),

    start_date DATE,

    end_date DATE,

    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT'

        CHECK (

            status IN (

                'DRAFT',

                'ACTIVE',

                'COMPLETED',

                'CANCELLED'

            )

        ),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_learning_plans_user

        FOREIGN KEY (user_id)

        REFERENCES users(user_id)

        ON DELETE CASCADE,

    CONSTRAINT chk_learning_plan_dates

        CHECK (

            end_date IS NULL

            OR start_date IS NULL

            OR end_date >= start_date

        )

);

-- 6.9 LEARNING_PLAN_ITEMS

CREATE TABLE learning_plan_items (

    plan_item_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    learning_plan_id BIGINT NOT NULL,

    skill_id BIGINT,

    recommendation_id BIGINT,

    item_order INTEGER NOT NULL

        CHECK (item_order > 0),

    activity_type VARCHAR(50) NOT NULL,

    target_question_count INTEGER

        CHECK (

            target_question_count IS NULL

            OR target_question_count >= 0

        ),

    target_accuracy NUMERIC(7,4)

        CHECK (

            target_accuracy IS NULL

            OR (

                target_accuracy >= 0

                AND target_accuracy <= 1

            )

        ),

    status VARCHAR(30) NOT NULL DEFAULT 'PENDING'

        CHECK (

            status IN (

                'PENDING',

                'IN_PROGRESS',

                'COMPLETED',

                'SKIPPED'

            )

        ),

    due_date DATE,

    completed_at TIMESTAMPTZ,

    CONSTRAINT uq_learning_plan_item_order

        UNIQUE (learning_plan_id, item_order),

    CONSTRAINT fk_learning_plan_items_plan

        FOREIGN KEY (learning_plan_id)

        REFERENCES learning_plans(learning_plan_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_learning_plan_items_skill

        FOREIGN KEY (skill_id)

        REFERENCES skills(skill_id)

        ON DELETE SET NULL,

    CONSTRAINT fk_learning_plan_items_recommendation

        FOREIGN KEY (recommendation_id)

        REFERENCES recommendations(recommendation_id)

        ON DELETE SET NULL

);

-- 7. AI & CONTENT INTELLIGENCE

-- 7.1 AI_MODELS

CREATE TABLE ai_models (

    ai_model_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    provider VARCHAR(100) NOT NULL,

    model_name VARCHAR(255) NOT NULL,

    model_version VARCHAR(100),

    model_type VARCHAR(30) NOT NULL

        CHECK (

            model_type IN (

                'LLM',

                'VLM',

                'EMBEDDING',

                'OTHER'

            )

        ),

    supports_text BOOLEAN NOT NULL DEFAULT TRUE,

    supports_vision BOOLEAN NOT NULL DEFAULT FALSE,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'

        CHECK (

            status IN (

                'ACTIVE',

                'INACTIVE'

            )

        ),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_ai_model

        UNIQUE (provider, model_name, model_version)

);

-- 7.2 PROMPT_TEMPLATES

CREATE TABLE prompt_templates (

    prompt_template_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    template_code VARCHAR(100) NOT NULL UNIQUE,

    template_name VARCHAR(255) NOT NULL,

    purpose VARCHAR(100) NOT NULL,

    description TEXT,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'

        CHECK (

            status IN (

                'ACTIVE',

                'INACTIVE'

            )

        ),

    created_by BIGINT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_prompt_templates_creator

        FOREIGN KEY (created_by)

        REFERENCES users(user_id)

        ON DELETE SET NULL

);

-- 7.3 PROMPT_VERSIONS

CREATE TABLE prompt_versions (

    prompt_version_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    prompt_template_id BIGINT NOT NULL,

    version_number INTEGER NOT NULL

        CHECK (version_number > 0),

    system_prompt TEXT NOT NULL,

    user_prompt_template TEXT,

    output_schema JSONB,

    temperature NUMERIC(5,4)

        CHECK (

            temperature IS NULL

            OR temperature >= 0

        ),

    max_tokens INTEGER

        CHECK (

            max_tokens IS NULL

            OR max_tokens > 0

        ),

    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_prompt_version

        UNIQUE (prompt_template_id, version_number),

    CONSTRAINT fk_prompt_versions_template

        FOREIGN KEY (prompt_template_id)

        REFERENCES prompt_templates(prompt_template_id)

        ON DELETE CASCADE

);

-- 7.4 AI_GENERATIONS

CREATE TABLE ai_generations (

    generation_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    ai_model_id BIGINT NOT NULL,

    prompt_version_id BIGINT NOT NULL,

    user_id BIGINT,

    generation_type VARCHAR(50) NOT NULL

        CHECK (

            generation_type IN (

                'QUESTION_GENERATION',

                'QUESTION_EXPLANATION',

                'ERROR_ANALYSIS',

                'RECOMMENDATION',

                'CONTENT_CLASSIFICATION',

                'OTHER'

            )

        ),

    status VARCHAR(30) NOT NULL DEFAULT 'PENDING'

        CHECK (

            status IN (

                'PENDING',

                'RUNNING',

                'SUCCESS',

                'FAILED',

                'CANCELLED'

            )

        ),

    temperature NUMERIC(5,4),

    input_token_count INTEGER

        CHECK (

            input_token_count IS NULL

            OR input_token_count >= 0

        ),

    output_token_count INTEGER

        CHECK (

            output_token_count IS NULL

            OR output_token_count >= 0

        ),

    total_token_count INTEGER

        CHECK (

            total_token_count IS NULL

            OR total_token_count >= 0

        ),

    latency_ms BIGINT

        CHECK (

            latency_ms IS NULL

            OR latency_ms >= 0

        ),

    error_message TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ai_generation_model

        FOREIGN KEY (ai_model_id)

        REFERENCES ai_models(ai_model_id)

        ON DELETE RESTRICT,

    CONSTRAINT fk_ai_generation_prompt

        FOREIGN KEY (prompt_version_id)

        REFERENCES prompt_versions(prompt_version_id)

        ON DELETE RESTRICT,

    CONSTRAINT fk_ai_generation_user

        FOREIGN KEY (user_id)

        REFERENCES users(user_id)

        ON DELETE SET NULL

);

-- 7.5 AI_GENERATION_INPUTS

CREATE TABLE ai_generation_inputs (

    generation_input_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    generation_id BIGINT NOT NULL,

    input_type VARCHAR(50) NOT NULL,

    skill_id BIGINT,

    question_version_id BIGINT,

    passage_id BIGINT,

    visual_asset_id BIGINT,

    input_text TEXT,

    input_data JSONB,

    order_no INTEGER NOT NULL DEFAULT 1

        CHECK (order_no > 0),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ai_input_generation

        FOREIGN KEY (generation_id)

        REFERENCES ai_generations(generation_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_ai_input_skill

        FOREIGN KEY (skill_id)

        REFERENCES skills(skill_id)

        ON DELETE SET NULL,

    CONSTRAINT fk_ai_input_question

        FOREIGN KEY (question_version_id)

        REFERENCES question_versions(question_version_id)

        ON DELETE SET NULL,

    CONSTRAINT fk_ai_input_passage

        FOREIGN KEY (passage_id)

        REFERENCES passages(passage_id)

        ON DELETE SET NULL,

    CONSTRAINT fk_ai_input_visual

        FOREIGN KEY (visual_asset_id)

        REFERENCES visual_asset(visual_asset_id)

        ON DELETE SET NULL,

    CONSTRAINT chk_ai_input_has_data

        CHECK (

            input_text IS NOT NULL

            OR input_data IS NOT NULL

            OR skill_id IS NOT NULL

            OR question_version_id IS NOT NULL

            OR passage_id IS NOT NULL

            OR visual_asset_id IS NOT NULL

        )

);

-- 7.6 AI_GENERATION_OUTPUTS

CREATE TABLE ai_generation_outputs (

    generation_output_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    generation_id BIGINT NOT NULL,

    output_type VARCHAR(50) NOT NULL,

    raw_output TEXT,

    structured_output JSONB,

    parsed_successfully BOOLEAN NOT NULL DEFAULT FALSE,

    created_question_version_id BIGINT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ai_output_generation

        FOREIGN KEY (generation_id)

        REFERENCES ai_generations(generation_id)

        ON DELETE CASCADE,

    CONSTRAINT fk_ai_output_question

        FOREIGN KEY (created_question_version_id)

        REFERENCES question_versions(question_version_id)

        ON DELETE SET NULL

);

-- 7.7 AI_VALIDATIONS

CREATE TABLE ai_validations (

    validation_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    generation_id BIGINT NOT NULL,

    validation_type VARCHAR(50) NOT NULL,

    validation_status VARCHAR(30) NOT NULL

        CHECK (

            validation_status IN (

                'PENDING',

                'PASSED',

                'FAILED',

                'WARNING'

            )

        ),

    validation_score NUMERIC(7,4)

        CHECK (

            validation_score IS NULL

            OR (

                validation_score >= 0

                AND validation_score <= 1

            )

        ),

    validation_result JSONB,

    error_message TEXT,

    validated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ai_validation_generation

        FOREIGN KEY (generation_id)

        REFERENCES ai_generations(generation_id)

        ON DELETE CASCADE

);

-- 8. INDEXES

-- Identity / RBAC

CREATE INDEX idx_user_roles_role_id

    ON user_roles(role_id);

-- Assessment

CREATE INDEX idx_exam_versions_exam_id

    ON exam_versions(exam_id);

CREATE INDEX idx_sections_exam_version_id

    ON sections(exam_version_id);

CREATE INDEX idx_section_competencies_competency_id

    ON section_competencies(competency_id);

CREATE INDEX idx_domains_competency_id

    ON domains(competency_id);

CREATE INDEX idx_topics_domain_id

    ON topics(domain_id);

CREATE INDEX idx_skills_topic_id

    ON skills(topic_id);

-- Question bank

CREATE INDEX idx_questions_parent_question_id

    ON questions(parent_question_id);

CREATE INDEX idx_question_versions_question_id

    ON question_versions(question_id);

CREATE INDEX idx_question_versions_section_id

    ON question_versions(section_id);

CREATE INDEX idx_question_versions_passage_id

    ON question_versions(passage_id);

CREATE INDEX idx_question_options_version_id

    ON question_options(question_version_id);

CREATE INDEX idx_question_answers_version_id

    ON question_answers(question_version_id);

CREATE INDEX idx_question_competency_skill_id

    ON question_competency_map(skill_id);

CREATE INDEX idx_question_psychometric_version_id

    ON question_psychometric(question_version_id);

CREATE INDEX idx_question_review_version_id

    ON question_review(question_version_id);

CREATE INDEX idx_question_review_reviewer_id

    ON question_review(reviewer_id);

CREATE INDEX idx_visual_asset_question_version_id

    ON visual_asset(question_version_id);

-- Test

CREATE INDEX idx_tests_exam_version_id

    ON tests(exam_version_id);

CREATE INDEX idx_tests_created_by

    ON tests(created_by);

CREATE INDEX idx_test_versions_test_id

    ON test_versions(test_id);

CREATE INDEX idx_test_sections_test_version_id

    ON test_sections(test_version_id);

CREATE INDEX idx_test_sections_section_id

    ON test_sections(section_id);

CREATE INDEX idx_test_items_test_version_id

    ON test_items(test_version_id);

CREATE INDEX idx_test_items_test_section_id

    ON test_items(test_section_id);

CREATE INDEX idx_test_items_section_version

    ON test_items(test_section_id, test_version_id);

CREATE INDEX idx_test_items_question_version_id

    ON test_items(question_version_id);

-- Attempts

CREATE INDEX idx_attempts_test_version_id

    ON attempts(test_version_id);

CREATE INDEX idx_attempts_user_id

    ON attempts(user_id);

CREATE INDEX idx_attempt_answers_attempt_id

    ON attempt_answers(attempt_id);

CREATE INDEX idx_attempt_answers_test_item_id

    ON attempt_answers(test_item_id);

CREATE INDEX idx_attempt_answers_test_item_context

    ON attempt_answers(test_item_id, test_version_id, question_version_id);

CREATE INDEX idx_attempt_selected_options_option_id

    ON attempt_selected_options(option_id);

CREATE INDEX idx_attempt_selected_options_option_question

    ON attempt_selected_options(option_id, question_version_id);

CREATE INDEX idx_attempt_events_attempt_id

    ON attempt_events(attempt_id);

CREATE INDEX idx_attempt_events_test_item_id

    ON attempt_events(test_item_id);

-- Analytics

CREATE INDEX idx_section_results_attempt_id

    ON section_results(attempt_id);

CREATE INDEX idx_question_results_attempt_id

    ON question_results(attempt_id);

CREATE INDEX idx_question_results_test_item_id

    ON question_results(test_item_id);

CREATE INDEX idx_skill_performances_user_id

    ON skill_performances(user_id);

CREATE INDEX idx_skill_performances_skill_id

    ON skill_performances(skill_id);

CREATE INDEX idx_skill_performances_attempt_id

    ON skill_performances(attempt_id);

CREATE INDEX idx_competency_profiles_user_id

    ON competency_profiles(user_id);

CREATE INDEX idx_competency_profiles_competency_id

    ON competency_profiles(competency_id);

CREATE INDEX idx_error_analyses_question_result_id

    ON error_analyses(question_result_id);

CREATE INDEX idx_recommendations_user_id

    ON recommendations(user_id);

CREATE INDEX idx_recommendations_skill_id

    ON recommendations(skill_id);

CREATE INDEX idx_recommendations_competency_id

    ON recommendations(competency_id);

CREATE INDEX idx_learning_plans_user_id

    ON learning_plans(user_id);

CREATE INDEX idx_learning_plan_items_plan_id

    ON learning_plan_items(learning_plan_id);

CREATE INDEX idx_learning_plan_items_skill_id

    ON learning_plan_items(skill_id);

-- AI

CREATE INDEX idx_prompt_versions_template_id

    ON prompt_versions(prompt_template_id);

CREATE INDEX idx_ai_generations_model_id

    ON ai_generations(ai_model_id);

CREATE INDEX idx_ai_generations_prompt_version_id

    ON ai_generations(prompt_version_id);

CREATE INDEX idx_ai_generations_user_id

    ON ai_generations(user_id);

CREATE INDEX idx_ai_generation_inputs_generation_id

    ON ai_generation_inputs(generation_id);

CREATE INDEX idx_ai_generation_inputs_skill_id

    ON ai_generation_inputs(skill_id);

CREATE INDEX idx_ai_generation_inputs_question_id

    ON ai_generation_inputs(question_version_id);

CREATE INDEX idx_ai_generation_inputs_passage_id

    ON ai_generation_inputs(passage_id);

CREATE INDEX idx_ai_generation_inputs_visual_id

    ON ai_generation_inputs(visual_asset_id);

CREATE INDEX idx_ai_generation_outputs_generation_id

    ON ai_generation_outputs(generation_id);

CREATE INDEX idx_ai_generation_outputs_question_id

    ON ai_generation_outputs(created_question_version_id);

CREATE INDEX idx_ai_validations_generation_id

    ON ai_validations(generation_id);

-- Identity / class / audit indexes

CREATE INDEX idx_user_roles_assigned_by
    ON user_roles(assigned_by);

CREATE INDEX idx_classes_teacher_id
    ON classes(teacher_id);

CREATE INDEX idx_class_members_user_id
    ON class_members(user_id);

CREATE INDEX idx_audit_logs_user_id
    ON audit_logs(user_id);

CREATE INDEX idx_audit_logs_entity
    ON audit_logs(entity_type, entity_id);

-- 9. PARTIAL UNIQUE INDEXES

-- Only one latest question version per logical question

CREATE UNIQUE INDEX uq_question_versions_latest

    ON question_versions(question_id)

    WHERE is_latest = TRUE;

-- Only one latest test version per test

CREATE UNIQUE INDEX uq_test_versions_latest

    ON test_versions(test_id)

    WHERE is_latest = TRUE;

-- Only one active prompt version per template

CREATE UNIQUE INDEX uq_prompt_versions_active

    ON prompt_versions(prompt_template_id)

    WHERE is_active = TRUE;

-- Only one primary skill mapping per question version

CREATE UNIQUE INDEX uq_question_competency_primary

    ON question_competency_map(question_version_id)

    WHERE is_primary = TRUE;

-- 10. UPDATED_AT TRIGGERS

CREATE TRIGGER trg_users_updated_at

BEFORE UPDATE ON users

FOR EACH ROW

EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_classes_updated_at
BEFORE UPDATE ON classes
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_exams_updated_at

BEFORE UPDATE ON exams

FOR EACH ROW

EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_passages_updated_at

BEFORE UPDATE ON passages

FOR EACH ROW

EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_questions_updated_at

BEFORE UPDATE ON questions

FOR EACH ROW

EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tests_updated_at

BEFORE UPDATE ON tests

FOR EACH ROW

EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_learning_plans_updated_at

BEFORE UPDATE ON learning_plans

FOR EACH ROW

EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_prompt_templates_updated_at

BEFORE UPDATE ON prompt_templates

FOR EACH ROW

EXECUTE FUNCTION set_updated_at();

-- 11. VIEWS

-- 11.1 QUESTION BANK OVERVIEW

CREATE OR REPLACE VIEW v_question_bank_overview AS

SELECT

    q.question_id,

    q.question_code,

    q.status AS question_status,

    qv.question_version_id,

    qv.version_number,

    qv.stem,

    qv.question_type,

    qv.response_mode,

    qv.cognitive_level,

    qv.estimated_difficulty,

    qv.author_type,

    qv.review_status,

    e.code AS exam_code,

    e.name AS exam_name,

    ev.version_code AS exam_version_code,

    s.section_id,

    s.section_code,

    s.section_name

FROM questions q

JOIN question_versions qv

    ON qv.question_id = q.question_id

    AND qv.is_latest = TRUE

JOIN sections s

    ON s.section_id = qv.section_id

JOIN exam_versions ev

    ON ev.exam_version_id = s.exam_version_id

JOIN exams e

    ON e.exam_id = ev.exam_id;

-- 11.2 ATTEMPT SUMMARY

CREATE OR REPLACE VIEW v_attempt_summary AS

SELECT

    a.attempt_id,

    a.user_id,

    u.username,

    u.full_name,

    a.test_version_id,

    t.test_id,

    t.test_code,

    t.title,

    a.attempt_number,

    a.status,

    a.started_at,

    a.submitted_at,

    a.time_spent_seconds,

    ar.raw_score,

    ar.normalized_score,

    ar.accuracy,

    ar.total_questions,

    ar.answered_questions,

    ar.correct_questions,

    ar.incorrect_questions,

    ar.skipped_questions,

    ar.percentile_internal,

    ar.calculated_at

FROM attempts a

JOIN users u

    ON u.user_id = a.user_id

JOIN test_versions tv

    ON tv.test_version_id = a.test_version_id

JOIN tests t

    ON t.test_id = tv.test_id

LEFT JOIN attempt_results ar

    ON ar.attempt_id = a.attempt_id;

-- 11.3 USER COMPETENCY PROFILE

CREATE OR REPLACE VIEW v_user_competency_profile AS

SELECT

    cp.profile_id,

    cp.user_id,

    u.username,

    u.full_name,

    cp.competency_id,

    c.competency_code,

    c.competency_name,

    cp.mastery_score,

    cp.accuracy,

    cp.question_count,

    cp.confidence,

    cp.sample_size,

    cp.trend,

    cp.calculated_at

FROM competency_profiles cp

JOIN users u

    ON u.user_id = cp.user_id

JOIN competencies c

    ON c.competency_id = cp.competency_id;

-- 11.4 TEST STRUCTURE

CREATE OR REPLACE VIEW v_test_structure AS

SELECT

    t.test_id,

    t.test_code,

    t.title,

    tv.test_version_id,

    tv.version_number,

    ts.test_section_id,

    ts.section_order,

    s.section_code,

    s.section_name,

    ti.test_item_id,

    ti.item_order,

    ti.points,

    q.question_id,

    q.question_code,

    qv.question_version_id,

    qv.question_type,

    qv.response_mode,

    qv.cognitive_level

FROM tests t

JOIN test_versions tv

    ON tv.test_id = t.test_id

JOIN test_sections ts

    ON ts.test_version_id = tv.test_version_id

JOIN sections s

    ON s.section_id = ts.section_id

JOIN test_items ti

    ON ti.test_section_id = ts.test_section_id

JOIN question_versions qv

    ON qv.question_version_id = ti.question_version_id

JOIN questions q

    ON q.question_id = qv.question_id;

-- 12. SEED DATA

-- 12.1 SYSTEM ROLES

INSERT INTO roles (

    role_code,

    role_name,

    description

)

VALUES

(

    'STUDENT',

    'Student',

    'Học sinh thực hiện bài đánh giá và theo dõi hồ sơ năng lực.'

),

(

    'TEACHER',

    'Teacher',

    'Giáo viên hoặc mentor quản lý học sinh, bài kiểm tra và phân tích.'

),

(

    'ADMIN',

    'Administrator',

    'Quản trị hệ thống và người dùng.'

)ON CONFLICT (role_code) DO NOTHING;

-- 12.2 BASIC EXAM IDENTITIES

INSERT INTO exams (

    code,

    name,

    description,

    status

)

VALUES

(

    'TSA',

    'Đánh giá tư duy',

    'Định hướng kỳ thi Đánh giá tư duy TSA.',

    'ACTIVE'

),

(

    'HSA',

    'Đánh giá năng lực',

    'Định hướng kỳ thi Đánh giá năng lực HSA.',

    'ACTIVE'

)

ON CONFLICT (code) DO NOTHING;

-- 13. COMMENTS

COMMENT ON TABLE users IS

'Người dùng của hệ thống Smart Assessment AI.';

COMMENT ON TABLE roles IS

'Các vai trò RBAC của hệ thống.';

COMMENT ON TABLE exams IS

'Kỳ thi mục tiêu như TSA hoặc HSA.';

COMMENT ON TABLE exam_versions IS

'Phiên bản kỳ thi theo năm hoặc cấu trúc.';

COMMENT ON TABLE sections IS

'Các phần thi thuộc một phiên bản kỳ thi.';

COMMENT ON TABLE competencies IS

'Nhóm năng lực dùng để phân tích năng lực học sinh.';

COMMENT ON TABLE domains IS

'Miền kiến thức thuộc competency.';

COMMENT ON TABLE topics IS

'Chủ đề thuộc domain.';

COMMENT ON TABLE skills IS

'Kỹ năng cụ thể dùng để mapping câu hỏi và phân tích.';

COMMENT ON TABLE questions IS

'Định danh logic của câu hỏi.';

COMMENT ON TABLE question_versions IS

'Phiên bản bất biến của nội dung câu hỏi.';

COMMENT ON TABLE question_options IS

'Các phương án lựa chọn của một question version.';

COMMENT ON TABLE question_answers IS

'Đáp án chuẩn của question version.';

COMMENT ON TABLE question_competency_map IS

'Mapping question version tới skill trong competency framework.';

COMMENT ON TABLE question_psychometric IS

'Các chỉ số thống kê/psychometric của câu hỏi.';

COMMENT ON TABLE question_review IS

'Lịch sử kiểm duyệt câu hỏi bởi con người.';

COMMENT ON TABLE visual_asset IS

'Tài nguyên hình ảnh, biểu đồ, bảng hoặc sơ đồ của câu hỏi.';

COMMENT ON TABLE tests IS

'Bài kiểm tra logic được xây dựng cho học sinh.';

COMMENT ON TABLE test_versions IS

'Phiên bản bất biến của bài kiểm tra.';

COMMENT ON TABLE test_sections IS

'Các section trong một test version.';

COMMENT ON TABLE test_items IS

'Câu hỏi cụ thể được đưa vào một test version.';

COMMENT ON TABLE attempts IS

'Lần thực hiện bài kiểm tra của người dùng.';

COMMENT ON TABLE attempt_answers IS

'Câu trả lời của học sinh cho từng test item.';

COMMENT ON TABLE attempt_selected_options IS

'Các option được học sinh lựa chọn.';

COMMENT ON TABLE attempt_events IS

'Sự kiện phát sinh trong quá trình làm bài CBT.';

COMMENT ON TABLE attempt_results IS

'Tổng kết kết quả của một attempt.';

COMMENT ON TABLE section_results IS

'Kết quả theo từng section của attempt.';

COMMENT ON TABLE question_results IS

'Kết quả của từng câu hỏi trong attempt.';

COMMENT ON TABLE skill_performances IS

'Lịch sử hiệu suất của học sinh theo skill và attempt.';

COMMENT ON TABLE competency_profiles IS

'Hồ sơ năng lực hiện tại của học sinh theo competency.';

COMMENT ON TABLE error_analyses IS

'Phân tích loại lỗi của học sinh.';

COMMENT ON TABLE recommendations IS

'Khuyến nghị học tập được sinh từ kết quả phân tích.';

COMMENT ON TABLE learning_plans IS

'Lộ trình học tập cá nhân hóa của học sinh.';

COMMENT ON TABLE learning_plan_items IS

'Các nhiệm vụ trong learning plan.';

COMMENT ON TABLE ai_models IS

'Các mô hình AI/VLM/LLM được hệ thống sử dụng.';

COMMENT ON TABLE prompt_templates IS

'Template prompt dùng cho các nghiệp vụ AI.';

COMMENT ON TABLE prompt_versions IS

'Phiên bản cụ thể của prompt template.';

COMMENT ON TABLE ai_generations IS

'Log mỗi lần hệ thống gọi AI.';

COMMENT ON TABLE ai_generation_inputs IS

'Dữ liệu đầu vào của một AI generation.';

COMMENT ON TABLE ai_generation_outputs IS

'Kết quả đầu ra của AI generation.';

COMMENT ON TABLE ai_validations IS

'Kết quả validation tự động đối với AI output.';

-- 14. FINAL VALIDATION

DO $$

DECLARE

    table_count INTEGER;

BEGIN

    SELECT COUNT(*)

    INTO table_count

    FROM information_schema.tables

    WHERE table_schema = 'public'

      AND table_type = 'BASE TABLE';

    IF table_count <> 47 THEN

        RAISE EXCEPTION

            'Schema validation failed: expected 47 tables, found %',

            table_count;

    END IF;

END

$$;

COMMIT;
