package vn.smartassessment.api.question.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.ImportBatchRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.ImportBatchResult;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.ImportItemResult;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.ImportPreviewItem;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.ImportPreviewResult;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MappingItemRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MappingSetRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MappingSetReviewRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MaterializeRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MaterializedQuestion;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternCandidateRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternCandidateResult;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternReviewRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternSkillMappingRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternSkillReviewRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionReviewRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionRevisionRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionGroupRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionGroupItemRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionGroupReviewRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionGroupResult;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.TaxonomyCandidateRegistrationRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.TaxonomyCandidateResult;

@Service
public class QuestionFoundationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Path taxonomyRoot;

    public QuestionFoundationService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${app.taxonomy.release-root:}") String taxonomyReleaseRoot) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.taxonomyRoot = taxonomyReleaseRoot.isBlank() ? null : Path.of(taxonomyReleaseRoot).toAbsolutePath().normalize();
    }

    private JsonNode parseJson(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored staging JSON is invalid.", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = mapper.createObjectNode();
            TreeSet<String> fieldNames = new TreeSet<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                canonical.set(fieldName, canonicalize(node.get(fieldName)));
            }
            return canonical;
        }
        if (node.isArray()) {
            var canonical = mapper.createArrayNode();
            node.forEach(value -> canonical.add(canonicalize(value)));
            return canonical;
        }
        return node.deepCopy();
    }

    private String canonicalJson(JsonNode node) {
        return json(canonicalize(node));
    }

    private static boolean containsQuestionContent(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT);
                if (Set.of("stem", "question", "question_text", "answer", "correct_answer", "options")
                        .contains(key) || containsQuestionContent(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsQuestionContent(child)) return true;
            }
        }
        return false;
    }

    @Transactional
    public TaxonomyCandidateResult registerTaxonomyCandidate(
            TaxonomyCandidateRegistrationRequest request, String actor) {
        Path snapshotPath = resolveSnapshot(request.snapshotLocator());
        JsonNode snapshot = readJson(snapshotPath);
        JsonNode manifest = readJson(snapshotPath.resolveSibling("manifest.json"));
        String taxonomyId = text(manifest, "taxonomy_id");
        String releaseId = text(manifest, "release_id");
        String version = text(manifest, "taxonomy_version");
        if (!request.taxonomyId().equals(taxonomyId)
                || !text(snapshot, "taxonomy_id").equals(taxonomyId)
                || !text(snapshot, "taxonomy_version").equals(version)
                || releaseId.isBlank() || version.isBlank()) {
            throw badRequest("Taxonomy snapshot and manifest identity do not match.");
        }
        String snapshotHash = sha256(readBytes(snapshotPath));
        if (!snapshotHash.equalsIgnoreCase(text(manifest, "snapshot_sha256"))) {
            throw badRequest("Taxonomy snapshot hash does not match its manifest.");
        }
        JsonNode sourceHashes = manifest.path("source_artifact_hashes");
        verifySourceHashes(sourceHashes, snapshot.path("source_artifacts"));
        String managerStatus = text(manifest, "status");
        String status = switch (managerStatus) {
            case "draft" -> "DRAFT";
            case "in_review" -> "IN_REVIEW";
            case "release_candidate" -> "RELEASE_CANDIDATE";
            default -> "";
        };
        if (status.isBlank()) {
            throw badRequest("Only draft, in-review, or release-candidate snapshots may be registered.");
        }
        String catalogType = request.catalogType().toUpperCase(Locale.ROOT);
        if (!Set.of("TSA", "HSA", "CUSTOM").contains(catalogType)) {
            throw badRequest("catalogType must be TSA, HSA, or CUSTOM.");
        }

        jdbc.update("""
                INSERT INTO taxonomy_catalogs (taxonomy_id, catalog_name, catalog_type, status)
                VALUES (?, ?, ?, 'DRAFT')
                ON CONFLICT (taxonomy_id) DO NOTHING
                """, taxonomyId, request.taxonomyName(), catalogType);
        long catalogId = jdbc.queryForObject(
                "SELECT taxonomy_catalog_id FROM taxonomy_catalogs WHERE taxonomy_id = ?",
                Long.class, taxonomyId);
        Map<String, Object> catalog = jdbc.queryForMap(
                "SELECT catalog_name, catalog_type FROM taxonomy_catalogs WHERE taxonomy_catalog_id = ?",
                catalogId);
        if (!request.taxonomyName().equals(catalog.get("catalog_name"))
                || !catalogType.equals(catalog.get("catalog_type"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Taxonomy catalog identity already exists with different metadata.");
        }
        Integer duplicate = jdbc.queryForObject("""
                SELECT COUNT(*) FROM taxonomy_releases
                WHERE taxonomy_catalog_id = ? AND
                      (release_code = ? OR taxonomy_version = ?)
                """, Integer.class, catalogId, releaseId, version);
        if (duplicate != null && duplicate > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Taxonomy release/version is already registered.");
        }
        Long parentId = null;
        String parentCode = text(manifest, "parent_release_id");
        if (!parentCode.isBlank()) {
            List<Long> parents = jdbc.query("""
                    SELECT taxonomy_release_id FROM taxonomy_releases
                    WHERE taxonomy_catalog_id = ? AND release_code = ? AND status = 'PUBLISHED'
                    """, (rs, row) -> rs.getLong(1), catalogId, parentCode);
            if (parents.size() != 1) {
                throw badRequest("A declared parent release must already be published in this catalog.");
            }
            parentId = parents.get(0);
        }
        String locator = snapshotPath.toString();
        long releaseDbId = jdbc.queryForObject("""
                INSERT INTO taxonomy_releases (
                    taxonomy_catalog_id, release_code, taxonomy_version, parent_release_id,
                    status, content_hash, snapshot_locator, manifest_json, source_artifact_hashes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                RETURNING taxonomy_release_id
                """, Long.class, catalogId, releaseId, version, parentId, status, snapshotHash, locator,
                json(manifest), json(sourceHashes));
        jdbc.update("""
                INSERT INTO taxonomy_release_events
                    (taxonomy_release_id, event_type, actor, reason_code, reason_text, evidence_uri)
                VALUES (?, 'CANDIDATE', ?, 'CANDIDATE_REGISTERED', 'Candidate registered; not approved or published.', ?)
                """, releaseDbId, actor, locator);
        return new TaxonomyCandidateResult(catalogId, releaseDbId, releaseId, version, status, snapshotHash);
    }

    public Map<String, Object> verifyTaxonomyRelease(long releaseDbId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT release_code, taxonomy_version, content_hash, snapshot_locator, manifest_json::text
                FROM taxonomy_releases WHERE taxonomy_release_id = ?
                """, releaseDbId);
        if (rows.isEmpty()) {
            throw notFound("Taxonomy release not found.");
        }
        Map<String, Object> row = rows.get(0);
        Path path = Path.of((String) row.get("snapshot_locator"));
        JsonNode manifest = readJson(path.resolveSibling("manifest.json"));
        String actualHash = sha256(readBytes(path));
        boolean valid = actualHash.equalsIgnoreCase(((String) row.get("content_hash")).trim())
                && actualHash.equalsIgnoreCase(text(manifest, "snapshot_sha256"))
                && row.get("release_code").equals(text(manifest, "release_id"))
                && row.get("taxonomy_version").equals(text(manifest, "taxonomy_version"));
        return Map.of("taxonomyReleaseId", releaseDbId, "valid", valid, "actualSha256", actualHash);
    }

    @Transactional
    public ImportBatchResult stageQuestions(ImportBatchRequest request, String subject) {
        long userId = userId(subject);
        List<String> payloadHashes = new ArrayList<>();
        List<JsonNode> questions = request.questions();
        for (JsonNode question : questions) {
            String canonical = canonicalJson(question);
            payloadHashes.add(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        ObjectNode batchIdentity = mapper.createObjectNode();
        batchIdentity.put("sourceName", request.sourceName());
        if (request.sourceLocator() == null) {
            batchIdentity.putNull("sourceLocator");
        } else {
            batchIdentity.put("sourceLocator", request.sourceLocator());
        }
        batchIdentity.set("payloadHashes", mapper.valueToTree(payloadHashes));
        String batchHash = sha256(json(batchIdentity).getBytes(StandardCharsets.UTF_8));
        List<Long> insertedBatchIds = jdbc.query("""
                INSERT INTO question_import_batches
                    (source_name, source_locator, source_sha256, imported_by, validation_summary)
                VALUES (?, ?, ?, ?, '{}'::jsonb)
                ON CONFLICT (source_sha256) DO NOTHING
                RETURNING import_batch_id
                """, (rs, row) -> rs.getLong(1), request.sourceName(), request.sourceLocator(), batchHash, userId);
        if (insertedBatchIds.isEmpty()) {
            Long existingBatchId = jdbc.queryForObject(
                    "SELECT import_batch_id FROM question_import_batches WHERE source_sha256 = ?",
                    Long.class, batchHash);
            return loadImportBatch(existingBatchId);
        }
        long batchId = insertedBatchIds.get(0);

        List<ImportItemResult> results = new ArrayList<>();
        Set<String> externalIds = new HashSet<>();
        Set<String> seenPayloadHashes = new HashSet<>();
        int valid = 0;
        for (int i = 0; i < questions.size(); i++) {
            JsonNode question = questions.get(i);
            String externalId = externalQuestionId(question, i);
            List<String> errors = validateImportItem(
                    question, externalId, payloadHashes.get(i), externalIds, seenPayloadHashes);
            String status = errors.isEmpty() ? "VALID" : "INVALID";
            if (errors.isEmpty()) {
                valid++;
            }
            long itemId = jdbc.queryForObject("""
                    INSERT INTO question_import_items
                        (import_batch_id, external_question_id, payload_json, payload_sha256)
                    VALUES (?, ?, ?::jsonb, ?)
                    RETURNING import_item_id
                    """, Long.class, batchId, externalId, json(question), payloadHashes.get(i));
            jdbc.update("""
                    UPDATE question_import_items
                    SET validation_status = ?, validation_errors = ?::jsonb
                    WHERE import_item_id = ?
                    """, status, json(errors), itemId);
            results.add(new ImportItemResult(itemId, externalId, status, errors));
        }
        String batchStatus = valid == questions.size() ? "VALIDATED" : valid == 0 ? "REJECTED" : "PARTIAL";
        jdbc.update("""
                UPDATE question_import_batches
                SET status = ?, validation_summary = ?::jsonb
                WHERE import_batch_id = ?
                """, batchStatus, json(Map.of("total", questions.size(), "valid", valid,
                "invalid", questions.size() - valid)), batchId);
        return new ImportBatchResult(batchId, questions.size(), valid, questions.size() - valid, results);
    }

    public ImportPreviewResult previewQuestions(ImportBatchRequest request) {
        List<ImportPreviewItem> items = new ArrayList<>();
        Set<String> externalIds = new HashSet<>();
        Set<String> seenPayloadHashes = new HashSet<>();
        List<String> payloadHashes = new ArrayList<>();
        for (JsonNode question : request.questions()) {
            payloadHashes.add(sha256(canonicalJson(question).getBytes(StandardCharsets.UTF_8)));
        }
        int valid = 0;
        for (int i = 0; i < request.questions().size(); i++) {
            JsonNode question = request.questions().get(i);
            String externalId = externalQuestionId(question, i);
            List<String> errors = validateImportItem(
                    question, externalId, payloadHashes.get(i), externalIds, seenPayloadHashes);
            String status = errors.isEmpty() ? "VALID" : "INVALID";
            if (errors.isEmpty()) {
                valid++;
            }
            items.add(new ImportPreviewItem(i, externalId, status, errors));
        }
        return new ImportPreviewResult(items.size(), valid, items.size() - valid, items);
    }

    private ImportBatchResult loadImportBatch(long batchId) {
        Map<String, Object> batch = jdbc.queryForMap("""
                SELECT validation_summary::text AS validation_summary
                FROM question_import_batches WHERE import_batch_id = ?
                """, batchId);
        JsonNode summary = parseJson((String) batch.get("validation_summary"));
        List<ImportItemResult> items = jdbc.query("""
                SELECT import_item_id, external_question_id, validation_status,
                       validation_errors::text AS validation_errors
                FROM question_import_items
                WHERE import_batch_id = ?
                ORDER BY import_item_id
                """, (rs, row) -> {
            JsonNode errors = parseJson(rs.getString("validation_errors"));
            List<String> messages = new ArrayList<>();
            errors.forEach(error -> messages.add(error.asText()));
            return new ImportItemResult(
                    rs.getLong("import_item_id"),
                    rs.getString("external_question_id"),
                    rs.getString("validation_status"),
                    messages);
        }, batchId);
        int valid = summary.path("valid").asInt();
        return new ImportBatchResult(batchId, items.size(), valid, items.size() - valid, items);
    }

    private List<String> validateImportItem(
            JsonNode question,
            String externalId,
            String payloadHash,
            Set<String> externalIds,
            Set<String> seenPayloadHashes) {
        List<String> errors = validateStagedQuestion(question);
        if (!externalIds.add(externalId)) {
            errors.add("Duplicate question_id in this import batch.");
        }
        if (!seenPayloadHashes.add(payloadHash)) {
            errors.add("Duplicate normalized payload in this import batch.");
        } else {
            List<Long> duplicates = jdbc.query("""
                    SELECT import_item_id
                    FROM question_import_items
                    WHERE payload_sha256 = ?
                    ORDER BY import_item_id
                    LIMIT 1
                    """, (rs, row) -> rs.getLong(1), payloadHash);
            if (!duplicates.isEmpty()) {
                errors.add("Duplicate normalized payload already exists in staging (item "
                        + duplicates.get(0) + ").");
            }
        }
        JsonNode passage = question.path("passage");
        if (!passage.isMissingNode() && !passage.isNull()
                && !(passage.isTextual() && !passage.asText().isBlank())
                && !(passage.isObject() && !text(passage, "content").isBlank())) {
            errors.add("passage must be text or an object with non-empty content.");
        }
        JsonNode assets = question.path("visual_assets");
        if (!assets.isMissingNode() && !assets.isNull() && !assets.isArray()) {
            errors.add("visual_assets must be an array.");
        } else if (assets.isArray()) {
            for (JsonNode asset : assets) {
                String path = text(asset, "path");
                if (path.isBlank()) path = text(asset, "file_path");
                if (!asset.isObject() || path.isBlank() || !isSafeAssetPath(path)) {
                    errors.add("Each visual asset requires a safe relative path.");
                    break;
                }
            }
        }
        return errors;
    }

    private static boolean isSafeAssetPath(String value) {
        String path = value.replace('\\', '/');
        return !path.startsWith("/")
                && !path.matches("^[A-Za-z]:.*")
                && java.util.Arrays.stream(path.split("/")).noneMatch(".."::equals);
    }

    private static String externalQuestionId(JsonNode question, int index) {
        String externalId = text(question, "question_id");
        return externalId.isBlank() ? "item-" + (index + 1) : externalId;
    }

    @Transactional
    public MaterializedQuestion materializeQuestion(long importItemId, MaterializeRequest request) {
        Map<String, Object> item = lockImportItem(importItemId);
        if (!"VALID".equals(item.get("validation_status"))) {
            throw badRequest("Only validated staging items can be materialized.");
        }
        JsonNode question = parseJson((String) item.get("payload"));
        String sourceExam = text(question.path("metadata"), "exam").toUpperCase(Locale.ROOT);
        Integer examMatches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sections s
                JOIN exam_versions ev ON ev.exam_version_id = s.exam_version_id
                JOIN exams e ON e.exam_id = ev.exam_id
                WHERE s.section_id = ? AND UPPER(e.code) = ?
                """, Integer.class, request.sectionId(), sourceExam);
        if (examMatches == null || examMatches != 1) {
            throw badRequest("Selected section does not match the source question's exam.");
        }
        String questionType = text(question, "question_type").toUpperCase(Locale.ROOT);
        String responseMode = switch (questionType) {
            case "SINGLE_CHOICE" -> "SINGLE_OPTION";
            case "MULTIPLE_CHOICE" -> "MULTI_OPTION";
            case "TRUE_FALSE" -> "BOOLEAN";
            case "NUMERIC" -> "NUMERIC";
            case "SHORT_ANSWER" -> "TEXT";
            default -> throw badRequest("Unsupported question_type.");
        };
        String difficultyText = estimatedDifficulty(question);
        java.math.BigDecimal difficulty = difficultyText == null
                ? null : new java.math.BigDecimal(difficultyText);
        String authorType = request.authorType().toUpperCase(Locale.ROOT);
        if (!Set.of("HUMAN", "AI", "MIXED").contains(authorType)) {
            throw badRequest("authorType must be HUMAN, AI, or MIXED.");
        }
        long questionId = jdbc.queryForObject("""
                INSERT INTO questions (question_code, status)
                VALUES (?, 'DRAFT') RETURNING question_id
                """, Long.class, "IMP-" + UUID.randomUUID());
        Long passageId = createPassage(question);
        String contentHash = questionContentHash(question);
        long versionId = jdbc.queryForObject("""
                INSERT INTO question_versions
                    (question_id, section_id, passage_id, version_number, stem, question_type, response_mode,
                     cognitive_level, estimated_difficulty, explanation, author_type, review_status, is_latest,
                     content_sha256)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', TRUE, ?)
                RETURNING question_version_id
                """, Long.class, questionId, request.sectionId(), passageId, text(question, "stem"),
                questionType, responseMode, nullableText(question.path("metadata"), "cognitive_level"),
                difficulty, nullableText(question, "explanation"), authorType, contentHash);

        persistOptionsAndAnswers(versionId, question, questionType);
        persistVisualAssets(versionId, question);
        jdbc.update("UPDATE question_import_items SET validation_status = 'MATERIALIZED', question_version_id = ? WHERE import_item_id = ?",
                versionId, importItemId);
        return new MaterializedQuestion(questionId, versionId, "DRAFT");
    }

    @Transactional
    public MaterializedQuestion createQuestionRevision(
            long sourceVersionId, QuestionRevisionRequest request) {
        List<Map<String, Object>> sourceRows = jdbc.queryForList("""
                SELECT question_id, section_id, version_number, review_status, is_latest
                FROM question_versions
                WHERE question_version_id = ?
                FOR UPDATE
                """, sourceVersionId);
        if (sourceRows.isEmpty()) {
            throw notFound("Question Version not found.");
        }
        Map<String, Object> source = sourceRows.get(0);
        String sourceStatus = (String) source.get("review_status");
        if (!Boolean.TRUE.equals(source.get("is_latest"))
                || !Set.of("APPROVED", "REJECTED", "NEEDS_REVISION").contains(sourceStatus)) {
            throw badRequest("Only the latest reviewed Question Version can be revised.");
        }

        JsonNode content = request.content();
        List<String> errors = validateStagedQuestion(content);
        if (!errors.isEmpty()) {
            throw badRequest("Question revision is invalid: " + String.join(" ", errors));
        }
        long questionId = ((Number) source.get("question_id")).longValue();
        long sectionId = ((Number) source.get("section_id")).longValue();
        Integer matchingExam = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM sections s
                JOIN exam_versions ev ON ev.exam_version_id = s.exam_version_id
                JOIN exams e ON e.exam_id = ev.exam_id
                WHERE s.section_id = ? AND UPPER(e.code) = ?
                """, Integer.class, sectionId,
                text(content.path("metadata"), "exam").toUpperCase(Locale.ROOT));
        if (matchingExam == null || matchingExam != 1) {
            throw badRequest("Question revision exam must match the existing section.");
        }
        String authorType = request.authorType().toUpperCase(Locale.ROOT);
        if (!Set.of("HUMAN", "AI", "MIXED").contains(authorType)) {
            throw badRequest("authorType must be HUMAN, AI, or MIXED.");
        }
        int nextVersion = ((Number) source.get("version_number")).intValue() + 1;
        Long passageId = createPassage(content);
        String questionType = text(content, "question_type").toUpperCase(Locale.ROOT);
        String responseMode = responseMode(questionType);
        String difficultyText = estimatedDifficulty(content);
        java.math.BigDecimal difficulty = difficultyText == null
                ? null : new java.math.BigDecimal(difficultyText);
        String contentHash = questionContentHash(content);

        jdbc.update("UPDATE question_versions SET is_latest = FALSE WHERE question_version_id = ?",
                sourceVersionId);
        long newVersionId = jdbc.queryForObject("""
                INSERT INTO question_versions
                    (question_id, section_id, passage_id, version_number,
                     supersedes_question_version_id, stem, question_type, response_mode,
                     cognitive_level, estimated_difficulty, explanation, author_type,
                     review_status, is_latest, content_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', TRUE, ?)
                RETURNING question_version_id
                """, Long.class, questionId, sectionId, passageId, nextVersion, sourceVersionId,
                text(content, "stem"), questionType, responseMode,
                nullableText(content.path("metadata"), "cognitive_level"), difficulty,
                nullableText(content, "explanation"), authorType, contentHash);
        persistOptionsAndAnswers(newVersionId, content, questionType);
        persistVisualAssets(newVersionId, content);
        return new MaterializedQuestion(questionId, newVersionId, "DRAFT");
    }

    public List<Map<String, Object>> questionVersionHistory(long questionId) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE question_id = ?", Integer.class, questionId);
        if (exists == null || exists == 0) {
            throw notFound("Question not found.");
        }
        return jdbc.queryForList("""
                SELECT question_version_id, version_number, supersedes_question_version_id,
                       content_sha256, review_status, is_latest, created_at
                FROM question_versions
                WHERE question_id = ?
                ORDER BY version_number
                """, questionId);
    }

    private Long createPassage(JsonNode question) {
        JsonNode passage = question.path("passage");
        if (passage.isMissingNode() || passage.isNull()) {
            return null;
        }
        String content = passage.isTextual() ? passage.asText() : text(passage, "content");
        if (content.isBlank()) {
            throw badRequest("passage must contain non-empty text.");
        }
        String title = passage.isObject() ? nullableText(passage, "title") : null;
        String type = passage.isObject() ? nullableText(passage, "passage_type") : null;
        if (type != null && !Set.of("READING", "SCIENTIFIC", "DATA", "CONTEXT", "OTHER")
                .contains(type.toUpperCase(Locale.ROOT))) {
            type = "OTHER";
        }
        String sourceReference = passage.isObject()
                ? nullableText(passage, "source_reference")
                : nullableText(question.path("provenance"), "source_url");
        return jdbc.queryForObject("""
                INSERT INTO passages (title, content, passage_type, source_reference)
                VALUES (?, ?, ?, ?)
                RETURNING passage_id
                """, Long.class, title, content, type, sourceReference);
    }

    private void persistVisualAssets(long questionVersionId, JsonNode question) {
        JsonNode assets = question.path("visual_assets");
        if (!assets.isArray()) {
            return;
        }
        for (JsonNode asset : assets) {
            String path = text(asset, "path");
            if (path.isBlank()) path = text(asset, "file_path");
            String kind = text(asset, "kind");
            if (kind.isBlank()) kind = text(asset, "asset_type");
            String assetType = normalizedAssetType(kind);
            JsonNode bbox = asset.path("bbox");
            Integer pageNumber = asset.path("page_number").canConvertToInt()
                    ? asset.path("page_number").intValue() : null;
            ObjectNode provenance = mapper.createObjectNode().put("kind", kind)
                    .put("document_id", text(question.path("provenance"), "document_id"));
            if (pageNumber != null) provenance.put("page_number", pageNumber);
            if (!bbox.isMissingNode() && !bbox.isNull()) provenance.set("bbox", bbox.deepCopy());
            jdbc.update("""
                    INSERT INTO visual_asset
                        (question_version_id, asset_type, file_path, source_asset_id,
                         caption, alt_text, page_number, bbox, needs_review, provenance_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, TRUE, ?::jsonb)
                    """, questionVersionId, assetType, path,
                    nullableText(asset, "asset_id"), nullableText(asset, "caption"),
                    nullableText(asset, "alt_text"), pageNumber,
                    bbox.isMissingNode() || bbox.isNull() ? null : json(bbox),
                    json(provenance));
        }
    }

    private void persistOptionsAndAnswers(long questionVersionId, JsonNode question, String questionType) {
        JsonNode options = question.path("options");
        JsonNode correctAnswers = question.path("correct_answer");
        Set<String> correctOptionKeys = new HashSet<>();
        correctAnswers.forEach(answer -> correctOptionKeys.add(answer.asText()));
        int order = 0;
        for (JsonNode option : options) {
            jdbc.update("""
                    INSERT INTO question_options
                        (question_version_id, option_key, option_text, is_correct, order_no)
                    VALUES (?, ?, ?, ?, ?)
                    """, questionVersionId, text(option, "key"), text(option, "text"),
                    correctOptionKeys.contains(text(option, "key")), ++order);
        }
        if (!correctAnswers.isArray() || correctAnswers.isEmpty()) {
            throw badRequest("A correct answer is required before materializing a Question Version.");
        }
        String answerType = switch (questionType) {
            case "SINGLE_CHOICE", "MULTIPLE_CHOICE" -> "OPTION";
            case "TRUE_FALSE" -> "BOOLEAN";
            case "NUMERIC" -> "NUMERIC";
            default -> "TEXT";
        };
        Set<String> availableOptions = new HashSet<>();
        options.forEach(option -> availableOptions.add(text(option, "key")));
        for (JsonNode answer : correctAnswers) {
            String value = answer.asText();
            if (answerType.equals("OPTION") && !availableOptions.contains(value)) {
                throw badRequest("correct_answer references an option that is not present.");
            }
            if (answerType.equals("NUMERIC")) {
                try {
                    jdbc.update("""
                            INSERT INTO question_answers
                                (question_version_id, answer_type, answer_value, numeric_value)
                            VALUES (?, 'NUMERIC', ?, ?)
                            """, questionVersionId, value, new java.math.BigDecimal(value));
                } catch (NumberFormatException exception) {
                    throw badRequest("Numeric correct_answer must be a valid number.");
                }
            } else {
                jdbc.update("""
                        INSERT INTO question_answers (question_version_id, answer_type, answer_value)
                        VALUES (?, ?, ?)
                        """, questionVersionId, answerType, value);
            }
        }
    }

    private String questionContentHash(JsonNode question) {
        return sha256(canonicalJson(question).getBytes(StandardCharsets.UTF_8));
    }

    private static String responseMode(String questionType) {
        return switch (questionType) {
            case "SINGLE_CHOICE" -> "SINGLE_OPTION";
            case "MULTIPLE_CHOICE" -> "MULTI_OPTION";
            case "TRUE_FALSE" -> "BOOLEAN";
            case "NUMERIC" -> "NUMERIC";
            case "SHORT_ANSWER" -> "TEXT";
            default -> throw badRequest("Unsupported question_type.");
        };
    }

    private static String normalizedAssetType(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (normalized.contains("CHART")) return "CHART";
        if (normalized.contains("TABLE")) return "TABLE";
        if (normalized.contains("DIAGRAM")) return "DIAGRAM";
        if (normalized.contains("FORMULA")) return "FORMULA";
        return "IMAGE";
    }

    private static String estimatedDifficulty(JsonNode question) {
        String difficulty = nullableText(question.path("metadata"), "estimated_difficulty");
        if (difficulty != null) return difficulty;
        JsonNode planned = question.path("metadata").path("difficulty").path("planned");
        return planned.isNumber() ? planned.asText() : null;
    }

    @Transactional
    public long createMappingSet(long questionVersionId, MappingSetRequest request) {
        ensureQuestionEditable(questionVersionId);
        ensureReleaseExists(request.taxonomyReleaseId());
        long revision = jdbc.queryForObject(
                "SELECT COALESCE(MAX(mapping_revision), 0) + 1 FROM question_mapping_sets WHERE question_version_id = ?",
                Long.class, questionVersionId);
        long mappingSetId = jdbc.queryForObject("""
                INSERT INTO question_mapping_sets
                    (question_version_id, taxonomy_release_id, mapping_revision, status)
                VALUES (?, ?, ?, 'DRAFT') RETURNING mapping_set_id
                """, Long.class, questionVersionId, request.taxonomyReleaseId(), revision);
        Set<Long> skillIds = new HashSet<>();
        int primaryCount = 0;
        for (MappingItemRequest item : request.items()) {
            if (!skillIds.add(item.skillId())) {
                throw badRequest("Mapping set contains a duplicate skill.");
            }
            String role = item.role().toUpperCase(Locale.ROOT);
            if (!Set.of("PRIMARY", "SECONDARY", "EXCLUDED").contains(role)
                    || item.primary() != "PRIMARY".equals(role)) {
                throw badRequest("Each mapping item's primary flag must agree with its role.");
            }
            if (item.primary()) {
                primaryCount++;
            }
            jdbc.update("""
                    INSERT INTO question_mapping_set_items
                        (mapping_set_id, skill_id, taxonomy_release_id, is_primary, weight, role)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, mappingSetId, item.skillId(), request.taxonomyReleaseId(),
                    item.primary(), item.weight(), role);
        }
        if (primaryCount != 1) {
            throw badRequest("A mapping set must contain exactly one primary Skill.");
        }
        return mappingSetId;
    }

    @Transactional
    public void reviewMappingSet(long mappingSetId, MappingSetReviewRequest request, String subject) {
        long userId = userId(subject);
        String status = request.status().toUpperCase(Locale.ROOT);
        if (!Set.of("REVIEWED", "PUBLISHED", "RETIRED").contains(status)) {
            throw badRequest("Invalid mapping set review status.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT m.status, m.question_version_id FROM question_mapping_sets m
                WHERE mapping_set_id = ? FOR UPDATE
                """, mappingSetId);
        if (rows.isEmpty()) {
            throw notFound("Mapping set not found.");
        }
        String before = (String) rows.get(0).get("status");
        long versionId = ((Number) rows.get(0).get("question_version_id")).longValue();
        if ("PUBLISHED".equals(status)) {
            String questionStatus = jdbc.queryForObject(
                    "SELECT review_status FROM question_versions WHERE question_version_id = ?",
                    String.class, versionId);
            if (!"APPROVED".equals(questionStatus)) {
                throw badRequest("A mapping set cannot be published before its Question Version is approved.");
            }
        }
        jdbc.update("""
                INSERT INTO question_mapping_set_events
                    (mapping_set_id, taxonomy_release_id, decision_id, actor_id,
                     status_before, status_after, reason, evidence_refs)
                SELECT mapping_set_id, taxonomy_release_id, ?, ?, ?, ?, ?, ?::jsonb
                FROM question_mapping_sets WHERE mapping_set_id = ?
                """, "QMS-DEC-" + UUID.randomUUID(), userId, before, status,
                request.reason(), json(request.evidenceRefs()), mappingSetId);
        jdbc.update("""
                UPDATE question_mapping_sets SET status = ?,
                    published_at = CASE WHEN ? = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE published_at END
                WHERE mapping_set_id = ?
                """, status, status, mappingSetId);
    }

    @Transactional
    public void reviewQuestion(long versionId, QuestionReviewRequest request, String subject) {
        long userId = userId(subject);
        String next = request.status().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING_REVIEW", "IN_REVIEW", "NEEDS_REVISION", "APPROVED", "REJECTED").contains(next)) {
            throw badRequest("Invalid Question Version review status.");
        }
        String before;
        try {
            before = jdbc.queryForObject(
                    "SELECT review_status FROM question_versions WHERE question_version_id = ? FOR UPDATE",
                    String.class, versionId);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw notFound("Question Version not found.");
        }
        Long releaseId = request.taxonomyReleaseId();
        if (next.equals("APPROVED")) {
            if (releaseId == null || request.taxonomyVersion() == null || request.rightsEvidenceUri() == null
                    || request.rightsEvidenceUri().isBlank()) {
                throw badRequest("Approval requires a taxonomy release, version, and rights evidence.");
            }
            Integer published = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM taxonomy_releases
                    WHERE taxonomy_release_id = ? AND taxonomy_version = ? AND status = 'PUBLISHED'
                    """, Integer.class, releaseId, request.taxonomyVersion());
            Integer reviewedMapping = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM question_mapping_sets
                    WHERE question_version_id = ? AND taxonomy_release_id = ? AND status IN ('REVIEWED', 'PUBLISHED')
                    """, Integer.class, versionId, releaseId);
            if (published == null || published != 1 || reviewedMapping == null || reviewedMapping < 1) {
                throw badRequest("Approval requires a published taxonomy and reviewed mapping set for that release.");
            }
            verifyRightsEvidence(versionId, userId, request.rightsEvidenceUri());
        }
        jdbc.update("""
                INSERT INTO question_review
                    (question_version_id, reviewer_id, review_status, review_note, status_before,
                     decision_id, taxonomy_release_id, taxonomy_version, reason, evidence_refs,
                     source_rights_evidence_uri, supersedes_review_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, versionId, userId, next, request.reason(), before, "Q-DEC-" + UUID.randomUUID(),
                releaseId, request.taxonomyVersion(), request.reason(), json(request.evidenceRefs()),
                request.rightsEvidenceUri(), request.supersedesReviewId());
        jdbc.update("UPDATE question_versions SET review_status = ? WHERE question_version_id = ?", next, versionId);
    }

    @Transactional
    public QuestionGroupResult createQuestionGroup(QuestionGroupRequest request, String actor) {
        long creatorId = userId(actor);
        String stimulusType = request.stimulusType().trim().toUpperCase(Locale.ROOT);
        validateGroupStimulus(stimulusType, request.stimulus());
        if (!request.provenance().isObject()) {
            throw badRequest("Question Group provenance must be a JSON object.");
        }
        if (containsSensitiveAnswer(request.stimulus())) {
            throw badRequest("Question Group stimulus cannot contain answers or explanations.");
        }
        if (request.rightsEvidenceUri().isBlank()) {
            throw badRequest("Question Group rights evidence is required.");
        }
        if (request.questionVersionIds().stream().distinct().count() != request.questionVersionIds().size()) {
            throw badRequest("A Question Version may appear only once in a Question Group.");
        }
        String groupCode = "QG-" + UUID.randomUUID();
        long groupId = jdbc.queryForObject("""
                INSERT INTO question_groups (group_code, created_by)
                VALUES (?, ?) RETURNING question_group_id
                """, Long.class, groupCode, creatorId);
        return createQuestionGroupVersion(groupId, request, actor);
    }

    @Transactional
    public QuestionGroupResult createQuestionGroupVersion(
            long groupId, QuestionGroupRequest request, String actor) {
        long creatorId = userId(actor);
        validateGroupStimulus(request.stimulusType().trim().toUpperCase(Locale.ROOT), request.stimulus());
        if (!request.provenance().isObject() || containsSensitiveAnswer(request.stimulus())) {
            throw badRequest("Question Group provenance or stimulus is invalid.");
        }
        if (request.questionVersionIds().stream().distinct().count() != request.questionVersionIds().size()) {
            throw badRequest("A Question Version may appear only once in a Question Group.");
        }
        String groupCode;
        try {
            groupCode = jdbc.queryForObject("""
                    SELECT group_code FROM question_groups WHERE question_group_id = ? FOR UPDATE
                    """, String.class, groupId);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw notFound("Question Group not found.");
        }
        Integer versionNumber = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) + 1
                FROM question_group_versions
                WHERE question_group_id = ?
                """, Integer.class, groupId);
        if (request.rightsEvidenceUri().isBlank()) {
            throw badRequest("Question Group rights evidence is required.");
        }
        long versionId = jdbc.queryForObject("""
                INSERT INTO question_group_versions
                    (question_group_id, version_number, title, stimulus_type, stimulus_json,
                     provenance_json, rights_evidence_uri, created_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                RETURNING question_group_version_id
                """, Long.class, groupId, versionNumber, request.title().trim(),
                request.stimulusType().trim().toUpperCase(Locale.ROOT),
                json(request.stimulus()), json(request.provenance()), request.rightsEvidenceUri().trim(), creatorId);
        for (int index = 0; index < request.questionVersionIds().size(); index++) {
            addQuestionGroupItem(versionId,
                    new QuestionGroupItemRequest(request.questionVersionIds().get(index), index + 1));
        }
        return new QuestionGroupResult(groupId, versionId, groupCode, versionNumber, "DRAFT", null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> questionGroups() {
        return jdbc.queryForList("""
                SELECT qg.question_group_id, qg.group_code,
                       latest.question_group_version_id, latest.version_number,
                       latest.title, latest.stimulus_type, latest.review_status,
                       latest.content_sha256, latest.created_at
                FROM question_groups qg
                JOIN LATERAL (
                    SELECT question_group_version_id, version_number, title,
                           stimulus_type, review_status, content_sha256, created_at
                    FROM question_group_versions
                    WHERE question_group_id = qg.question_group_id
                    ORDER BY version_number DESC
                    LIMIT 1
                ) latest ON TRUE
                ORDER BY latest.created_at DESC, qg.question_group_id DESC
                LIMIT 100
                """);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> questionGroupVersion(long versionId) {
        List<Map<String, Object>> versions = jdbc.queryForList("""
                SELECT qgv.question_group_id, qgv.question_group_version_id, qg.group_code,
                       qgv.version_number, qgv.title, qgv.stimulus_type,
                qgv.stimulus_json::text AS stimulus_json,
                qgv.provenance_json::text AS provenance_json,
                qgv.rights_evidence_uri,
                       qgv.review_status, qgv.content_sha256
                FROM question_group_versions qgv
                JOIN question_groups qg ON qg.question_group_id = qgv.question_group_id
                WHERE qgv.question_group_version_id = ?
                """, versionId);
        if (versions.isEmpty()) throw notFound("Question Group Version not found.");
        Map<String, Object> result = new java.util.LinkedHashMap<>(versions.get(0));
        result.put("stimulus", parseJson((String) result.remove("stimulus_json")));
        result.put("provenance", parseJson((String) result.remove("provenance_json")));
        result.put("items", jdbc.queryForList("""
                SELECT gi.question_version_id, gi.item_order, qv.review_status, qv.section_id
                FROM question_group_version_items gi
                JOIN question_versions qv ON qv.question_version_id = gi.question_version_id
                WHERE gi.question_group_version_id = ?
                ORDER BY gi.item_order
                """, versionId));
        result.put("reviews", jdbc.queryForList("""
                SELECT review_status, reason, evidence_refs, created_at
                FROM question_group_version_reviews
                WHERE question_group_version_id = ?
                ORDER BY question_group_version_review_id
                """, versionId));
        return result;
    }

    @Transactional
    public void addQuestionGroupItem(long versionId, QuestionGroupItemRequest request) {
        Map<String, Object> version = lockQuestionGroupVersion(versionId);
        if (!"DRAFT".equals(version.get("review_status"))) {
            throw badRequest("Question Group membership is editable only while the version is draft.");
        }
        if (request.itemOrder() < 1) throw badRequest("Question Group item order must be positive.");
        Integer eligible = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM question_versions qv
                JOIN questions q ON q.question_id = qv.question_id
                WHERE qv.question_version_id = ? AND qv.is_latest
                  AND (
                      (q.status <> 'ACTIVE'
                       AND qv.review_status IN ('DRAFT', 'PENDING_REVIEW', 'IN_REVIEW', 'NEEDS_REVISION'))
                      OR EXISTS (
                          SELECT 1
                          FROM question_group_version_items prior_item
                          JOIN question_group_versions prior_version
                            ON prior_version.question_group_version_id = prior_item.question_group_version_id
                          WHERE prior_item.question_version_id = qv.question_version_id
                            AND prior_version.question_group_id = ?
                      )
                  )
                """, Integer.class, request.questionVersionId(), version.get("question_group_id"));
        if (eligible == null || eligible != 1) {
            throw badRequest("Only a latest unpublished Question Version may be added to a group.");
        }
        Integer alreadyGrouped = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM question_group_version_items gi
                JOIN question_group_versions existing_version
                  ON existing_version.question_group_version_id = gi.question_group_version_id
                WHERE gi.question_version_id = ?
                  AND existing_version.question_group_id <> ?
                """, Integer.class, request.questionVersionId(), version.get("question_group_id"));
        if (alreadyGrouped != null && alreadyGrouped > 0) {
            throw badRequest("Question Version already belongs to a Question Group.");
        }
        jdbc.update("""
                INSERT INTO question_group_version_items
                    (question_group_version_id, question_version_id, item_order)
                VALUES (?, ?, ?)
                """, versionId, request.questionVersionId(), request.itemOrder());
    }

    @Transactional
    public void removeQuestionGroupItem(long versionId, long questionVersionId) {
        lockQuestionGroupVersion(versionId);
        int removed = jdbc.update("""
                DELETE FROM question_group_version_items
                WHERE question_group_version_id = ? AND question_version_id = ?
                """, versionId, questionVersionId);
        if (removed == 0) throw notFound("Question Group membership not found.");
    }

    @Transactional
    public void reviewQuestionGroup(long versionId, QuestionGroupReviewRequest request, String actor) {
        long reviewerId = userId(actor);
        Map<String, Object> version = lockQuestionGroupVersion(versionId);
        String currentStatus = (String) version.get("review_status");
        String newStatus = request.status().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING_REVIEW", "IN_REVIEW", "NEEDS_REVISION", "APPROVED", "REJECTED")
                .contains(newStatus)) {
            throw badRequest("Unsupported Question Group review status.");
        }
        boolean allowed = switch (currentStatus) {
            case "DRAFT" -> "PENDING_REVIEW".equals(newStatus);
            case "PENDING_REVIEW" -> Set.of("IN_REVIEW", "NEEDS_REVISION", "REJECTED").contains(newStatus);
            case "IN_REVIEW" -> Set.of("APPROVED", "NEEDS_REVISION", "REJECTED").contains(newStatus);
            case "NEEDS_REVISION" -> "PENDING_REVIEW".equals(newStatus);
            default -> false;
        };
        if (!allowed) throw badRequest("Invalid Question Group review state transition.");
        if ("APPROVED".equals(newStatus)) {
            Integer members = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM question_group_version_items
                    WHERE question_group_version_id = ?
                    """, Integer.class, versionId);
            if (members == null || members < 2) {
                throw badRequest("A Question Group must contain at least two Question Versions.");
            }
        }
        jdbc.update("""
                INSERT INTO question_group_version_reviews
                    (question_group_version_id, reviewer_id, review_status, reason, evidence_refs)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, versionId, reviewerId, newStatus, request.reason().trim(), json(request.evidenceRefs()));
        jdbc.update("""
                UPDATE question_group_versions SET review_status = ?
                WHERE question_group_version_id = ?
                """, newStatus, versionId);
    }

    @Transactional
    public void publishQuestionGroup(long versionId, String actor) {
        long publisherId = userId(actor);
        Map<String, Object> version = lockQuestionGroupVersion(versionId);
        if (!"APPROVED".equals(version.get("review_status"))) {
            throw badRequest("Question Group must have human approval before publication.");
        }
        Integer eligibleMembers = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM question_group_version_items gi
                JOIN question_versions qv
                  ON qv.question_version_id = gi.question_version_id
                 AND qv.is_latest AND qv.review_status = 'APPROVED'
                JOIN questions q ON q.question_id = qv.question_id AND q.status = 'ACTIVE'
                JOIN question_review qr
                  ON qr.question_version_id = qv.question_version_id
                 AND qr.review_status = 'APPROVED'
                 AND NULLIF(BTRIM(qr.source_rights_evidence_uri), '') IS NOT NULL
                WHERE gi.question_group_version_id = ?
                """, Integer.class, versionId);
        Integer memberCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM question_group_version_items
                WHERE question_group_version_id = ?
                """, Integer.class, versionId);
        if (memberCount == null || memberCount < 2 || !memberCount.equals(eligibleMembers)) {
            throw badRequest("Every Question Group member must be published with approved rights evidence.");
        }
        List<Map<String, Object>> members = jdbc.queryForList("""
                SELECT question_version_id, item_order
                FROM question_group_version_items
                WHERE question_group_version_id = ?
                ORDER BY item_order
                """, versionId);
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("title", version.get("title"));
        snapshot.put("stimulusType", version.get("stimulus_type"));
        snapshot.put("stimulus", version.get("stimulus_json"));
        snapshot.put("provenance", version.get("provenance_json"));
        snapshot.put("rightsEvidenceUri", version.get("rights_evidence_uri"));
        snapshot.put("members", members);
        String contentHash = sha256(json(snapshot).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                UPDATE question_group_versions
                SET review_status = 'PUBLISHED', content_sha256 = ?, published_at = CURRENT_TIMESTAMP,
                    published_by = ?
                WHERE question_group_version_id = ?
                """, contentHash, publisherId, versionId);
    }

    private Map<String, Object> lockQuestionGroupVersion(long versionId) {
        List<Map<String, Object>> versions = jdbc.queryForList("""
                SELECT question_group_id, review_status, title, stimulus_type,
                       stimulus_json::text AS stimulus_json,
                       provenance_json::text AS provenance_json, rights_evidence_uri
                FROM question_group_versions
                WHERE question_group_version_id = ?
                FOR UPDATE
                """, versionId);
        if (versions.isEmpty()) throw notFound("Question Group Version not found.");
        return versions.get(0);
    }

    private void validateGroupStimulus(String type, JsonNode stimulus) {
        if (!Set.of("PASSAGE", "IMAGE", "TABLE", "CHART").contains(type)
                || !stimulus.isObject()) {
            throw badRequest("Question Group stimulus type or payload is invalid.");
        }
        boolean valid = switch (type) {
            case "PASSAGE" -> nonBlank(stimulus, "title") && nonBlank(stimulus, "content");
            case "IMAGE" -> nonBlank(stimulus, "assetId") && nonBlank(stimulus, "altText")
                    && nonBlank(stimulus, "path") && safeLocalAssetPath(stimulus.path("path").asText(""));
            case "TABLE" -> validTableStimulus(stimulus);
            case "CHART" -> validChartStimulus(stimulus);
            default -> false;
        };
        if (!valid) throw badRequest("Question Group stimulus does not match its declared type.");
    }

    private static boolean nonBlank(JsonNode node, String field) {
        return node.path(field).isTextual() && !node.path(field).asText().isBlank();
    }

    private static boolean containsSensitiveAnswer(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT);
                if (Set.of("correct_answer", "answer_key", "explanation", "answer_explanation")
                        .contains(key) || containsSensitiveAnswer(field.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) if (containsSensitiveAnswer(value)) return true;
        }
        return false;
    }

    private static boolean safeLocalAssetPath(String path) {
        return path.isBlank() || (path.startsWith("/assets/") || path.startsWith("/uploads/"))
                && !path.contains("..") && !path.startsWith("//");
    }

    private static boolean validTableStimulus(JsonNode stimulus) {
        JsonNode columns = stimulus.path("columns");
        JsonNode rows = stimulus.path("rows");
        if (!columns.isArray() || columns.isEmpty() || !rows.isArray() || rows.isEmpty()) return false;
        for (JsonNode column : columns) {
            if (!nonBlank(column, "key") || !nonBlank(column, "label")) return false;
        }
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() != columns.size()) return false;
            for (JsonNode cell : row) {
                if (!(cell.isTextual() || cell.isNumber() || cell.isBoolean() || cell.isNull())) return false;
            }
        }
        return true;
    }

    private static boolean validChartStimulus(JsonNode stimulus) {
        JsonNode labels = stimulus.path("labels");
        JsonNode series = stimulus.path("series");
        if (!labels.isArray() || labels.isEmpty() || !series.isArray() || series.isEmpty()) return false;
        for (JsonNode label : labels) if (!label.isTextual() || label.asText().isBlank()) return false;
        for (JsonNode item : series) {
            JsonNode values = item.path("values");
            if (!nonBlank(item, "name") || !values.isArray() || values.size() != labels.size()) return false;
            for (JsonNode value : values) if (!value.isNumber()) return false;
        }
        return true;
    }

    @Transactional
    public void publishQuestionVersion(long versionId) {
        List<Long> eligibleQuestionIds = jdbc.query("""
                SELECT q.question_id
                FROM question_versions v
                JOIN questions q ON q.question_id = v.question_id
                WHERE v.question_version_id = ?
                  AND v.is_latest
                  AND v.review_status = 'APPROVED'
                  AND EXISTS (
                      SELECT 1
                      FROM question_review r
                      JOIN question_mapping_sets m
                        ON m.question_version_id = r.question_version_id
                       AND m.taxonomy_release_id = r.taxonomy_release_id
                       AND m.status = 'PUBLISHED'
                       AND m.published_at IS NOT NULL
                      WHERE r.question_version_id = v.question_version_id
                        AND r.review_status = 'APPROVED'
                        AND NULLIF(BTRIM(r.source_rights_evidence_uri), '') IS NOT NULL
                        AND NOT EXISTS (
                            SELECT 1 FROM visual_asset a
                            WHERE a.question_version_id = v.question_version_id
                              AND (a.rights_status <> 'VERIFIED' OR a.needs_review)
                        )
                  )
                FOR UPDATE OF q
                """, (rs, row) -> rs.getLong(1), versionId);
        if (eligibleQuestionIds.isEmpty()) {
            Integer versionExists = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM question_versions WHERE question_version_id = ?
                    """, Integer.class, versionId);
            if (versionExists == null || versionExists == 0) {
                throw notFound("Question Version not found.");
            }
            throw badRequest(
                    "Only the latest approved Question Version with matching published mapping and rights evidence can be published.");
        }
        jdbc.update("""
                UPDATE questions SET status = 'ACTIVE'
                WHERE question_id = ? AND status <> 'ACTIVE'
                """, eligibleQuestionIds.get(0));
    }

    @Transactional
    public PatternCandidateResult createPatternCandidate(PatternCandidateRequest request) {
        if (!request.candidateKey().matches("[a-fA-F0-9]{64}") || request.sourceQuestionCount() < 1) {
            throw badRequest("candidateKey must be a SHA-256 value and sourceQuestionCount must be positive.");
        }
        if (containsQuestionContent(request.structure())
                || containsQuestionContent(request.reasoning())
                || containsQuestionContent(request.distractorObservations())
                || containsQuestionContent(request.sourceEvidence())) {
            throw badRequest("Pattern metadata cannot contain raw question or answer content.");
        }
        ObjectNode content = mapper.createObjectNode();
        content.set("structure", request.structure());
        content.set("reasoning", request.reasoning());
        content.set("distractorObservations", request.distractorObservations());
        content.set("sourceEvidence", request.sourceEvidence());
        String contentHash = sha256(json(content).getBytes(StandardCharsets.UTF_8));
        String patternCode = "QP-" + request.candidateKey().substring(0, 24).toUpperCase(Locale.ROOT);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_patterns WHERE pattern_code = ?",
                Integer.class, patternCode);
        if (exists != null && exists > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pattern candidate already exists.");
        }
        long patternId = jdbc.queryForObject(
                "INSERT INTO question_patterns (pattern_code) VALUES (?) RETURNING question_pattern_id",
                Long.class, patternCode);
        long versionId = jdbc.queryForObject("""
                INSERT INTO question_pattern_versions
                    (question_pattern_id, version_number, pattern_type, structure_json, reasoning_json,
                     distractor_json, provenance_json, content_sha256, miner_version, source_question_count)
                VALUES (?, 1, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
                RETURNING pattern_version_id
                """, Long.class, patternId, request.patternType(), json(request.structure()),
                json(request.reasoning()), json(request.distractorObservations()),
                json(request.sourceEvidence()), contentHash, request.minerVersion(), request.sourceQuestionCount());
        return new PatternCandidateResult(patternId, versionId, patternCode, 1, contentHash,
                "PENDING_REVIEW", "UNVERIFIED");
    }

    @Transactional
    public long mapPatternToSkill(long patternVersionId, PatternSkillMappingRequest request) {
        ensureReleaseExists(request.taxonomyReleaseId());
        return jdbc.queryForObject("""
                INSERT INTO question_pattern_skill_mappings
                    (pattern_version_id, taxonomy_release_id, skill_id, relationship_type, confidence)
                VALUES (?, ?, ?, ?, ?)
                RETURNING pattern_skill_mapping_id
                """, Long.class, patternVersionId, request.taxonomyReleaseId(), request.skillId(),
                request.relationshipType().toUpperCase(Locale.ROOT), request.confidence());
    }

    @Transactional
    public void reviewPatternSkillMapping(long mappingId, PatternSkillReviewRequest request, String subject) {
        long reviewerId = userId(subject);
        String next = request.status().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED", "REJECTED").contains(next)) {
            throw badRequest("Pattern-Skill mapping review must be APPROVED or REJECTED.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT pattern_version_id, review_status FROM question_pattern_skill_mappings
                WHERE pattern_skill_mapping_id = ? FOR UPDATE
                """, mappingId);
        if (rows.isEmpty()) {
            throw notFound("Pattern-Skill mapping not found.");
        }
        long patternVersionId = ((Number) rows.get(0).get("pattern_version_id")).longValue();
        String before = (String) rows.get(0).get("review_status");
        String evidence = json(request.evidenceRefs());
        jdbc.update("""
                INSERT INTO question_pattern_reviews
                    (decision_id, pattern_version_id, pattern_skill_mapping_id, reviewer_id,
                     status_before, status_after, reason, evidence_refs)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, "QP-MAP-DEC-" + UUID.randomUUID(), patternVersionId, mappingId,
                reviewerId, before, next, request.reason(), evidence);
        jdbc.update("""
                UPDATE question_pattern_skill_mappings
                SET review_status = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP,
                    review_reason = ?, evidence_refs = ?::jsonb
                WHERE pattern_skill_mapping_id = ?
                """, next, reviewerId, request.reason(), evidence, mappingId);
    }

    @Transactional
    public void reviewPattern(long versionId, PatternReviewRequest request, String subject) {
        long reviewerId = userId(subject);
        String next = request.status().toUpperCase(Locale.ROOT);
        if (!Set.of("IN_REVIEW", "PENDING_REVIEW", "NEEDS_REVISION", "APPROVED", "REJECTED").contains(next)) {
            throw badRequest("Invalid Question Pattern review status.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT review_status, rights_status FROM question_pattern_versions
                WHERE pattern_version_id = ? FOR UPDATE
                """, versionId);
        if (rows.isEmpty()) {
            throw notFound("Question Pattern Version not found.");
        }
        String before = (String) rows.get(0).get("review_status");
        String rights = (String) rows.get(0).get("rights_status");
        String rightsUri = request.rightsEvidenceUri();
        if (next.equals("APPROVED")) {
            if (!"IN_REVIEW".equals(before) || rightsUri == null || rightsUri.isBlank()) {
                throw badRequest("Pattern approval requires IN_REVIEW status and rights evidence.");
            }
            Integer approvedSkillMappings = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM question_pattern_skill_mappings
                    WHERE pattern_version_id = ? AND review_status = 'APPROVED'
                    """, Integer.class, versionId);
            if (approvedSkillMappings == null || approvedSkillMappings == 0) {
                throw badRequest("At least one Skill mapping must be reviewed before pattern approval.");
            }
            rights = "VERIFIED";
        }
        String refs = json(request.evidenceRefs());
        jdbc.update("""
                INSERT INTO question_pattern_reviews
                    (decision_id, pattern_version_id, reviewer_id, status_before, status_after,
                     reason, evidence_refs, rights_evidence_uri)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, "QP-DEC-" + UUID.randomUUID(), versionId, reviewerId, before, next,
                request.reason(), refs, rightsUri);
        jdbc.update("""
                UPDATE question_pattern_versions SET review_status = ?, rights_status = ?,
                    rights_evidence_uri = CASE WHEN ? = 'APPROVED' THEN ? ELSE rights_evidence_uri END,
                    rights_verified_by = CASE WHEN ? = 'APPROVED' THEN ? ELSE rights_verified_by END,
                    rights_verified_at = CASE WHEN ? = 'APPROVED' THEN CURRENT_TIMESTAMP ELSE rights_verified_at END,
                    reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP, review_reason = ?,
                    review_evidence = ?::jsonb
                WHERE pattern_version_id = ?
                """, next, rights, next, rightsUri, next, reviewerId, next, reviewerId,
                request.reason(), refs, versionId);
    }

    public List<Map<String, Object>> approvedPatterns(long skillId, long taxonomyReleaseId) {
        return jdbc.queryForList("""
                SELECT p.pattern_code AS pattern_id, v.pattern_version_id,
                       m.skill_id, m.taxonomy_release_id,
                       v.version_number AS version, v.pattern_type,
                       v.structure_json AS structure, v.reasoning_json AS reasoning,
                       v.distractor_json AS distractor_observations,
                       v.content_sha256 AS version_hash,
                       v.review_status, v.rights_status
                FROM question_pattern_skill_mappings m
                JOIN question_pattern_versions v ON v.pattern_version_id = m.pattern_version_id
                JOIN question_patterns p ON p.question_pattern_id = v.question_pattern_id
                JOIN taxonomy_releases r ON r.taxonomy_release_id = m.taxonomy_release_id
                WHERE m.skill_id = ? AND m.taxonomy_release_id = ?
                  AND m.review_status = 'APPROVED'
                  AND v.review_status = 'APPROVED' AND v.rights_status = 'VERIFIED'
                  AND r.status = 'PUBLISHED'
                ORDER BY p.pattern_code
                """, skillId, taxonomyReleaseId);
    }

    public List<Map<String, Object>> publishedQuestions(String exam) {
        if (exam != null && !exam.isBlank() && !Set.of("TSA", "HSA").contains(exam.toUpperCase(Locale.ROOT))) {
            throw badRequest("exam must be TSA or HSA.");
        }
        return jdbc.queryForList("""
                SELECT q.question_id, q.question_code, v.question_version_id,
                       v.version_number, v.stem, v.question_type, v.response_mode,
                       v.cognitive_level, v.estimated_difficulty,
                       s.section_code, s.section_name, e.code AS exam,
                       m.taxonomy_release_id, r.release_code, r.taxonomy_version,
                       p.title AS passage_title, p.content AS passage_content,
                       (SELECT COALESCE(jsonb_agg(jsonb_build_object(
                           'key', o.option_key, 'text', o.option_text
                       ) ORDER BY o.order_no), '[]'::jsonb)
                        FROM question_options o WHERE o.question_version_id = v.question_version_id) AS options,
                       (SELECT COALESCE(jsonb_agg(jsonb_build_object(
                           'type', a.asset_type, 'path', a.file_path, 'caption', a.caption,
                           'alt_text', a.alt_text, 'page_number', a.page_number, 'bbox', a.bbox
                       ) ORDER BY a.visual_asset_id), '[]'::jsonb)
                        FROM visual_asset a WHERE a.question_version_id = v.question_version_id) AS visual_assets
                FROM questions q
                JOIN question_versions v ON v.question_id = q.question_id
                JOIN sections s ON s.section_id = v.section_id
                LEFT JOIN passages p ON p.passage_id = v.passage_id
                JOIN exam_versions ev ON ev.exam_version_id = s.exam_version_id
                JOIN exams e ON e.exam_id = ev.exam_id
                JOIN question_mapping_sets m
                  ON m.question_version_id = v.question_version_id AND m.status = 'PUBLISHED'
                JOIN taxonomy_releases r
                  ON r.taxonomy_release_id = m.taxonomy_release_id
                 AND r.status IN ('PUBLISHED', 'RETIRED', 'SUPERSEDED')
                WHERE q.status = 'ACTIVE' AND v.review_status = 'APPROVED'
                  AND (? IS NULL OR UPPER(e.code) = ?)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM question_versions newer
                      JOIN question_mapping_sets newer_mapping
                        ON newer_mapping.question_version_id = newer.question_version_id
                       AND newer_mapping.status = 'PUBLISHED'
                      WHERE newer.question_id = q.question_id
                        AND newer.review_status = 'APPROVED'
                        AND newer.version_number > v.version_number
                  )
                ORDER BY e.code, s.section_code, q.question_code
                """, exam == null || exam.isBlank() ? null : exam.toUpperCase(Locale.ROOT),
                exam == null || exam.isBlank() ? null : exam.toUpperCase(Locale.ROOT));
    }

    public List<Map<String, Object>> questionReviewQueue() {
        return jdbc.queryForList("""
                SELECT v.question_version_id, q.question_code, v.version_number, v.section_id,
                       v.stem, v.explanation, v.question_type, v.review_status,
                       v.author_type, v.created_at,
                       (SELECT COALESCE(jsonb_agg(jsonb_build_object(
                           'key', o.option_key, 'text', o.option_text
                       ) ORDER BY o.order_no), '[]'::jsonb)
                        FROM question_options o WHERE o.question_version_id = v.question_version_id) AS options,
                       (SELECT COALESCE(jsonb_agg(jsonb_build_object(
                           'type', a.answer_type, 'value', a.answer_value,
                           'numericValue', a.numeric_value
                       )), '[]'::jsonb)
                        FROM question_answers a WHERE a.question_version_id = v.question_version_id) AS answers
                FROM question_versions v
                JOIN questions q ON q.question_id = v.question_id
                WHERE v.review_status IN ('PENDING_REVIEW', 'IN_REVIEW', 'NEEDS_REVISION')
                ORDER BY v.created_at, v.question_version_id
                """);
    }

    public List<Map<String, Object>> importItems(long batchId) {
        return jdbc.queryForList("""
                SELECT import_item_id, external_question_id, payload_json,
                       payload_sha256, validation_status, validation_errors,
                       rights_status, rights_evidence_uri, question_version_id, created_at
                FROM question_import_items
                WHERE import_batch_id = ?
                ORDER BY import_item_id
                """, batchId);
    }

    public List<Map<String, Object>> mappingSetReviewQueue() {
        return jdbc.queryForList("""
                SELECT m.mapping_set_id, m.question_version_id, m.taxonomy_release_id,
                       m.mapping_revision, m.status, m.created_at,
                       COALESCE(jsonb_agg(jsonb_build_object(
                           'skillId', i.skill_id, 'role', i.role,
                           'primary', i.is_primary, 'weight', i.weight
                       ) ORDER BY i.is_primary DESC, i.skill_id)
                       FILTER (WHERE i.skill_id IS NOT NULL), '[]'::jsonb) AS items
                FROM question_mapping_sets m
                LEFT JOIN question_mapping_set_items i ON i.mapping_set_id = m.mapping_set_id
                WHERE m.status IN ('DRAFT', 'REVIEWED')
                GROUP BY m.mapping_set_id
                ORDER BY m.created_at, m.mapping_set_id
                """);
    }

    public List<Map<String, Object>> patternReviewQueue() {
        return jdbc.queryForList("""
                SELECT p.pattern_code, v.pattern_version_id, v.version_number,
                       v.pattern_type, v.source_question_count, v.content_sha256,
                       v.rights_status, v.review_status, v.created_at,
                       v.structure_json, v.reasoning_json, v.distractor_json,
                       v.provenance_json
                FROM question_pattern_versions v
                JOIN question_patterns p ON p.question_pattern_id = v.question_pattern_id
                WHERE v.review_status IN ('PENDING_REVIEW', 'IN_REVIEW', 'NEEDS_REVISION')
                ORDER BY v.created_at, v.pattern_version_id
                """);
    }

    public List<Map<String, Object>> importBatches() {
        return jdbc.queryForList("""
                SELECT b.import_batch_id, b.source_name, b.source_locator, b.source_sha256,
                       b.status, b.imported_at, b.validation_summary,
                       COUNT(i.import_item_id) AS item_count
                FROM question_import_batches b
                LEFT JOIN question_import_items i ON i.import_batch_id = b.import_batch_id
                GROUP BY b.import_batch_id
                ORDER BY b.imported_at DESC, b.import_batch_id DESC
                """);
    }

    private void verifyRightsEvidence(long versionId, long reviewerId, String evidenceUri) {
        Integer rejected = jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM question_import_items
                     WHERE question_version_id = ? AND rights_status = 'REJECTED')
                  + (SELECT COUNT(*) FROM visual_asset
                     WHERE question_version_id = ? AND rights_status = 'REJECTED')
                """, Integer.class, versionId, versionId);
        if (rejected != null && rejected > 0) {
            throw badRequest("Rejected source-rights evidence must be resolved before approval.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT import_item_id, rights_status FROM question_import_items
                WHERE question_version_id = ? FOR UPDATE
                """, versionId);
        if (!rows.isEmpty()) {
            jdbc.update("""
                    UPDATE question_import_items SET rights_status = 'VERIFIED', rights_evidence_uri = ?,
                        rights_verified_by = ?, rights_verified_at = CURRENT_TIMESTAMP
                    WHERE question_version_id = ?
                    """, evidenceUri, reviewerId, versionId);
        }
        jdbc.update("""
                UPDATE visual_asset
                SET rights_status = 'VERIFIED', rights_evidence_uri = ?,
                    rights_verified_by = ?, rights_verified_at = CURRENT_TIMESTAMP,
                    needs_review = FALSE
                WHERE question_version_id = ?
                """, evidenceUri, reviewerId, versionId);
    }

    private void ensureQuestionEditable(long questionVersionId) {
        String status;
        try {
            status = jdbc.queryForObject("SELECT review_status FROM question_versions WHERE question_version_id = ? FOR UPDATE",
                    String.class, questionVersionId);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw notFound("Question Version not found.");
        }
        if (!Set.of("DRAFT", "PENDING_REVIEW", "NEEDS_REVISION").contains(status)) {
            throw badRequest("Question Version is not editable.");
        }
    }

    private void ensureReleaseExists(long releaseId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM taxonomy_releases WHERE taxonomy_release_id = ?",
                Integer.class, releaseId);
        if (count == null || count != 1) {
            throw notFound("Taxonomy release not found.");
        }
    }

    private long userId(String subject) {
        List<Long> ids = jdbc.query("""
                SELECT user_id FROM users WHERE email = ? OR phone_number = ?
                """, (rs, row) -> rs.getLong(1), subject, subject);
        if (ids.size() != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user record was not found.");
        }
        return ids.get(0);
    }

    private Map<String, Object> lockImportItem(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT validation_status, payload_json::text AS payload
                FROM question_import_items WHERE import_item_id = ? FOR UPDATE
                """, id);
        if (rows.isEmpty()) {
            throw notFound("Staged question not found.");
        }
        return rows.get(0);
    }

    private List<String> validateStagedQuestion(JsonNode question) {
        List<String> errors = new ArrayList<>();
        if (!question.isObject()) {
            errors.add("Question payload must be an object.");
            return errors;
        }
        if (text(question, "stem").isBlank()) errors.add("stem is required.");
        String exam = text(question.path("metadata"), "exam").toUpperCase(Locale.ROOT);
        if (!Set.of("TSA", "HSA").contains(exam)) errors.add("metadata.exam must be TSA or HSA.");
        String cognitiveLevel = text(question.path("metadata"), "cognitive_level").toUpperCase(Locale.ROOT);
        if (!cognitiveLevel.isBlank() && !Set.of("M1", "M2", "M3").contains(cognitiveLevel)) {
            errors.add("metadata.cognitive_level must be M1, M2, or M3.");
        }
        String difficulty = text(question.path("metadata"), "estimated_difficulty");
        if (!difficulty.isBlank()) {
            try {
                if (new java.math.BigDecimal(difficulty).signum() < 0) {
                    errors.add("metadata.estimated_difficulty must be non-negative.");
                }
            } catch (NumberFormatException exception) {
                errors.add("metadata.estimated_difficulty must be numeric.");
            }
        }
        String questionType = text(question, "question_type").toUpperCase(Locale.ROOT);
        if (!Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "NUMERIC", "SHORT_ANSWER")
                .contains(questionType)) {
            errors.add("question_type is unsupported.");
        }
        if (!question.path("options").isArray()) errors.add("options must be an array.");
        else if (question.path("options").isEmpty()
                && !Set.of("NUMERIC", "SHORT_ANSWER", "TRUE_FALSE").contains(questionType)) {
            errors.add("Choice questions require at least one option.");
        }
        JsonNode answers = question.path("correct_answer");
        if (!answers.isArray() || answers.isEmpty()) {
            errors.add("correct_answer is required before a question can enter the Question Bank.");
        } else {
            Set<String> answerValues = new HashSet<>();
            for (JsonNode answer : answers) {
                if (!answer.isValueNode() || answer.asText().isBlank()) {
                    errors.add("Each correct_answer must be a non-empty scalar value.");
                    break;
                }
                if (!answerValues.add(answer.asText())) {
                    errors.add("correct_answer values must be unique.");
                    break;
                }
                if ("NUMERIC".equals(questionType)) {
                    try {
                        new java.math.BigDecimal(answer.asText());
                    } catch (NumberFormatException exception) {
                        errors.add("Numeric correct_answer values must be valid numbers.");
                        break;
                    }
                }
                if ("TRUE_FALSE".equals(questionType)
                        && !Set.of("true", "false").contains(answer.asText().toLowerCase(Locale.ROOT))) {
                    errors.add("TRUE_FALSE correct_answer must be true or false.");
                    break;
                }
            }
            if ("SINGLE_CHOICE".equals(questionType) && answers.size() != 1) {
                errors.add("SINGLE_CHOICE requires exactly one correct answer.");
            }
            if ("TRUE_FALSE".equals(questionType) && answers.size() != 1) {
                errors.add("TRUE_FALSE requires exactly one correct answer.");
            }
        }
        if (question.path("options").isArray()) {
            Set<String> optionKeys = new HashSet<>();
            for (JsonNode option : question.path("options")) {
                String key = text(option, "key");
                if (key.isBlank() || text(option, "text").isBlank()) {
                    errors.add("Each option requires a key and text.");
                } else if (!optionKeys.add(key)) {
                    errors.add("Option keys must be unique.");
                }
            }
            if (answers.isArray()
                    && Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE").contains(questionType)) {
                for (JsonNode answer : answers) {
                    if (!optionKeys.contains(answer.asText())) {
                        errors.add("correct_answer references an option that is not present.");
                        break;
                    }
                }
            }
        }
        return errors;
    }

    private Path resolveSnapshot(String locator) {
        Path root = findTaxonomyRoot();
        Path candidate;
        try {
            candidate = Path.of(locator);
        } catch (java.nio.file.InvalidPathException exception) {
            throw badRequest("snapshotLocator is not a valid path.");
        }
        Path path = candidate.isAbsolute() ? candidate.normalize() : root.resolve(candidate).normalize();
        if (!Files.isRegularFile(path) || !path.getFileName().toString().equals("snapshot.json")) {
            throw badRequest("snapshotLocator must identify snapshot.json within the configured taxonomy release root.");
        }
        try {
            Path realRoot = root.toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot)) {
                throw badRequest("snapshotLocator must remain inside the taxonomy release root.");
            }
            return realPath;
        } catch (java.io.IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Taxonomy snapshot path cannot be resolved.", exception);
        }
    }

    private Path findTaxonomyRoot() {
        if (taxonomyRoot != null) return taxonomyRoot;
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("crawler").resolve("taxonomy").resolve("releases").normalize();
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Taxonomy release root is not configured or present.");
    }

    private void verifySourceHashes(JsonNode expected, JsonNode artifacts) {
        if (!expected.isObject() || !artifacts.isObject()
                || expected.size() != artifacts.size()) {
            throw badRequest("Manifest source artifact hashes are missing.");
        }
        expected.fields().forEachRemaining(entry -> {
            JsonNode artifact = artifacts.path(entry.getKey());
            byte[] artifactBytes = artifact.isTextual()
                    ? artifact.asText().getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (!artifact.isTextual() || !sha256(artifactBytes)
                    .equalsIgnoreCase(entry.getValue().asText())) {
                throw badRequest("Taxonomy source artifact hash mismatch: " + entry.getKey());
            }
        });
    }

    private JsonNode readJson(Path path) {
        try {
            return mapper.readTree(Files.readAllBytes(path));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Taxonomy artifact cannot be read.", exception);
        }
    }

    private byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Taxonomy snapshot cannot be read.", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize database JSON value.", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String nullableText(JsonNode node, String field) {
        String value = text(node, field);
        return value.isBlank() ? null : value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
