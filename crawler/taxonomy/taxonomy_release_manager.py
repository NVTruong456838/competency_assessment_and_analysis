"""Create and verify immutable, file-based taxonomy release snapshots."""

from __future__ import annotations

import csv
import hashlib
import io
import json
import os
import re
import shutil
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from crawler.validation.human_review_workflow import validate_decision_register


MANIFEST_NAME = "manifest.json"
SNAPSHOT_NAME = "snapshot.json"
HISTORY_NAME = "history.jsonl"
MANIFEST_SCHEMA_VERSION = "1.0"
_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
_VERSION_PATTERN = re.compile(r"^\d+\.\d+\.\d+$")
_DECISION_FIELDS = {
    "decision_id",
    "target_type",
    "target_id",
    "target_is_proposed",
    "exam",
    "review_status",
    "decision",
    "reason",
    "reviewer",
    "reviewer_type",
    "reviewed_at",
    "evidence_refs",
    "proposed_change",
    "taxonomy_version",
    "created_at",
    "supersedes_decision_id",
}
_AUDIT_FIELDS = {
    "event_id",
    "decision_id",
    "target_type",
    "target_id",
    "target_is_proposed",
    "status_before",
    "status_after",
    "performed_by",
    "actor_type",
    "occurred_at",
    "reason",
    "evidence_refs",
    "taxonomy_version",
}
_SKILL_SEMANTIC_FIELDS = (
    "skill_name",
    "exam",
    "section_id",
    "competency_id",
    "domain_id",
    "topic_id",
    "operational_definition",
    "observable_action",
    "in_scope",
    "out_of_scope",
    "assessment_evidence",
    "distinguishing_criteria",
)
_COUNT_FIELDS = ("exams", "sections", "competencies", "domains", "topics", "skills")


class ReleaseGateError(ValueError):
    """Raised when a release transition is blocked by policy or invalid data."""

    def __init__(self, blockers: list[str]):
        self.blockers = blockers
        super().__init__("Taxonomy release blocked: " + "; ".join(blockers))


class TaxonomyReleaseManager:
    """Manage taxonomy candidates, publication gates, integrity, and retirement."""

    def __init__(self, artifact_root: str | Path, release_root: str | Path):
        self.artifact_root = Path(artifact_root).resolve()
        self.release_root = Path(release_root).resolve()

    def create_release_candidate(
        self,
        framework_path: str | Path,
        taxonomy_id: str,
        taxonomy_version: str,
        release_id: str,
        source_artifact_paths: list[str | Path],
        parent_release_id: str | None = None,
        created_at: str | None = None,
    ) -> dict[str, Any]:
        """Snapshot a draft or approved framework as a non-published candidate."""

        self._validate_identity(taxonomy_id, taxonomy_version, release_id)
        timestamp = created_at or self._now()
        if not self._has_timezone(timestamp):
            raise ValueError("created_at must be an ISO-8601 timestamp with timezone.")

        framework_file = self._resolve_artifact(framework_path)
        try:
            framework = json.loads(framework_file.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise ValueError(f"Cannot load framework JSON: {framework_file}") from error
        if not isinstance(framework, dict):
            raise ValueError("Framework root must be a JSON object.")

        artifact_files = {framework_file}
        for artifact_path in source_artifact_paths:
            artifact_files.add(self._resolve_artifact(artifact_path))
        artifacts: dict[str, str] = {}
        source_hashes: dict[str, str] = {}
        for artifact_file in sorted(artifact_files, key=lambda path: path.as_posix()):
            try:
                content = artifact_file.read_bytes().decode("utf-8")
            except (OSError, UnicodeError) as error:
                raise ValueError(f"Source artifact must be readable UTF-8: {artifact_file}") from error
            relative_path = artifact_file.relative_to(self.artifact_root).as_posix()
            artifacts[relative_path] = content
            source_hashes[relative_path] = self._sha256(content.encode("utf-8"))

        self.release_root.mkdir(parents=True, exist_ok=True)
        manifests = self._load_manifests()
        for existing in manifests:
            if existing.get("release_id") == release_id:
                raise ValueError(f"release_id has already been used: {release_id}")
            if (
                existing.get("taxonomy_id") == taxonomy_id
                and existing.get("taxonomy_version") == taxonomy_version
            ):
                raise ValueError(
                    f"taxonomy version has already been used: {taxonomy_id}@{taxonomy_version}"
                )

        if parent_release_id:
            _, parent_manifest = self._find_manifest(parent_release_id)
            if parent_manifest.get("status") != "published":
                raise ValueError("parent_release_id must refer to a published release.")
            if parent_manifest.get("taxonomy_id") != taxonomy_id:
                raise ValueError("Parent release belongs to a different taxonomy_id.")
        published_history = [
            existing
            for existing in manifests
            if existing.get("taxonomy_id") == taxonomy_id
            and existing.get("status") == "published"
        ]
        if published_history and not parent_release_id:
            raise ValueError("parent_release_id is required after the first published release.")

        bundle = {
            "schema_version": MANIFEST_SCHEMA_VERSION,
            "taxonomy_id": taxonomy_id,
            "taxonomy_version": taxonomy_version,
            "framework": framework,
            "source_artifacts": artifacts,
        }
        snapshot_content = self._canonical_json(bundle) + "\n"
        snapshot_hash = self._sha256(snapshot_content.encode("utf-8"))
        manifest = {
            "schema_version": MANIFEST_SCHEMA_VERSION,
            "taxonomy_id": taxonomy_id,
            "taxonomy_version": taxonomy_version,
            "release_id": release_id,
            "parent_release_id": parent_release_id,
            "status": "release_candidate",
            "snapshot_file": SNAPSHOT_NAME,
            "snapshot_sha256": snapshot_hash,
            "source_artifact_hashes": source_hashes,
            "counts": self._count_taxonomy(framework, artifacts),
            "approval_decision_refs": [],
            "approved_by": [],
            "created_at": timestamp,
            "published_at": None,
        }

        for previous_release in published_history:
            previous_bundle = self._read_bundle_for_manifest(previous_release)
            self._validate_stable_skill_ids(previous_bundle, bundle)

        self._validate_manifest_shape(manifest)
        candidate_dir = self._release_path(taxonomy_id, taxonomy_version, release_id)
        candidate_dir.parent.mkdir(parents=True, exist_ok=True)
        if candidate_dir.exists():
            raise FileExistsError(f"Release path already exists: {candidate_dir}")

        staging_dir = Path(
            tempfile.mkdtemp(prefix=f".{release_id}.", dir=candidate_dir.parent)
        )
        try:
            (staging_dir / SNAPSHOT_NAME).write_text(
                snapshot_content,
                encoding="utf-8",
                newline="\n",
            )
            self._write_json_exclusive(staging_dir / MANIFEST_NAME, manifest)
            os.rename(staging_dir, candidate_dir)
        except Exception:
            if staging_dir.exists():
                shutil.rmtree(staging_dir)
            raise
        return manifest

    def publish_release(
        self,
        release_id: str,
        validation_report_path: str | Path,
        decision_register_path: str | Path,
        approval_decision_refs: list[str],
        published_at: str | None = None,
    ) -> dict[str, Any]:
        """Promote a candidate only when all readiness and human evidence gates pass."""

        release_dir, manifest = self._find_manifest(release_id)
        if manifest.get("status") == "published":
            raise ValueError(f"Published release is immutable: {release_id}")
        if manifest.get("status") != "release_candidate":
            raise ValueError(f"Release is not a candidate: {release_id}")
        timestamp = published_at or self._now()
        if not self._has_timezone(timestamp):
            raise ValueError("published_at must be an ISO-8601 timestamp with timezone.")

        integrity_errors = self.verify_release(release_id)
        if integrity_errors:
            raise ReleaseGateError(integrity_errors)
        bundle = json.loads((release_dir / SNAPSHOT_NAME).read_text(encoding="utf-8"))
        framework = bundle.get("framework", {})
        if framework.get("status") != "approved":
            raise ReleaseGateError(
                [f"Framework status must be 'approved'; found {framework.get('status')!r}."]
            )

        report_file = self._resolve_artifact(validation_report_path)
        register_file = self._resolve_artifact(decision_register_path)
        self._verify_live_source_artifacts(bundle)
        source_names = set(manifest["source_artifact_hashes"])
        for required_file, label in (
            (report_file, "validation report"),
            (register_file, "decision register"),
        ):
            relative = required_file.relative_to(self.artifact_root).as_posix()
            if relative not in source_names:
                raise ReleaseGateError(
                    [f"{label} is not included in the release candidate snapshot: {relative}."]
                )

        try:
            report = json.loads(report_file.read_text(encoding="utf-8"))
            register = json.loads(register_file.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise ReleaseGateError([f"Cannot load release evidence: {error}"]) from error
        blockers = self._readiness_blockers(report)
        if blockers:
            raise ReleaseGateError(blockers)

        decision_errors = self._validate_register(register, bundle, report)
        if decision_errors:
            raise ReleaseGateError(decision_errors)
        approvals, approval_errors = self._resolve_approvals(
            register,
            approval_decision_refs,
            manifest["taxonomy_version"],
        )
        approved_decisions = {
            decision["decision_id"]: decision
            for decision in register["decisions"]
            if isinstance(decision, dict) and isinstance(decision.get("decision_id"), str)
        }
        if not any(
            approved_decisions.get(reference, {}).get("target_type")
            in {"framework", "taxonomy_change"}
            for reference in approval_decision_refs
            if isinstance(reference, str)
        ):
            approval_errors.append(
                "At least one referenced approval must cover the framework or taxonomy change."
            )
        if approval_errors:
            raise ReleaseGateError(approval_errors)

        manifest.update(
            {
                "status": "published",
                "approval_decision_refs": approval_decision_refs,
                "approved_by": approvals,
                "published_at": timestamp,
            }
        )
        self._validate_manifest_shape(manifest)
        self._replace_candidate_manifest(release_dir / MANIFEST_NAME, manifest)
        return manifest

    def verify_release(self, release_id: str) -> list[str]:
        """Verify manifest shape, snapshot digest, bundled source hashes, and counts."""

        errors: list[str] = []
        try:
            release_dir, manifest = self._find_manifest(release_id)
        except (FileNotFoundError, ValueError) as error:
            return [str(error)]
        errors.extend(self._manifest_errors(manifest))
        snapshot_path = release_dir / SNAPSHOT_NAME
        try:
            snapshot_content = snapshot_path.read_text(encoding="utf-8")
            bundle = json.loads(snapshot_content)
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            return errors + [f"Snapshot is missing or invalid: {error}"]

        actual_hash = self._sha256((self._canonical_json(bundle) + "\n").encode("utf-8"))
        if actual_hash != manifest.get("snapshot_sha256"):
            errors.append("Snapshot SHA-256 does not match the release manifest.")
        if not isinstance(bundle, dict):
            return errors + ["Snapshot root must be a JSON object."]
        if bundle.get("taxonomy_id") != manifest.get("taxonomy_id"):
            errors.append("Snapshot taxonomy_id does not match the manifest.")
        if bundle.get("taxonomy_version") != manifest.get("taxonomy_version"):
            errors.append("Snapshot taxonomy_version does not match the manifest.")
        artifact_contents = bundle.get("source_artifacts")
        if not isinstance(artifact_contents, dict):
            errors.append("Snapshot source_artifacts must be an object.")
            artifact_contents = {}
        expected_hashes = manifest.get("source_artifact_hashes", {})
        if not isinstance(expected_hashes, dict):
            expected_hashes = {}
        if set(expected_hashes) != set(artifact_contents):
            errors.append("Snapshot source artifact list does not match the manifest.")
        for path, content in artifact_contents.items():
            if not isinstance(content, str):
                errors.append(f"Snapshot source artifact is not text: {path}")
                continue
            digest = self._sha256(content.encode("utf-8"))
            if expected_hashes.get(path) != digest:
                errors.append(f"Source artifact SHA-256 does not match: {path}")
        if (
            isinstance(bundle.get("framework"), dict)
            and self._count_taxonomy(bundle["framework"], artifact_contents)
            != manifest.get("counts")
        ):
            errors.append("Taxonomy counts do not match the snapshot.")
        return errors

    def retire_release(
        self,
        release_id: str,
        retired_by: str,
        reason: str,
        occurred_at: str | None = None,
    ) -> dict[str, Any]:
        """Append a retirement event without changing the published manifest."""

        release_dir, manifest = self._find_manifest(release_id)
        if manifest.get("status") != "published":
            raise ValueError("Only a published release can be retired.")
        if self._is_retired(release_dir):
            raise ValueError(f"Release is already retired: {release_id}")
        if not isinstance(retired_by, str) or not retired_by.strip():
            raise ValueError("retired_by is required.")
        if not isinstance(reason, str) or not reason.strip():
            raise ValueError("A retirement reason is required.")
        timestamp = occurred_at or self._now()
        if not self._has_timezone(timestamp):
            raise ValueError("occurred_at must be an ISO-8601 timestamp with timezone.")
        event = {
            "event_id": f"RET-{uuid.uuid4().hex}",
            "release_id": release_id,
            "status": "retired",
            "retired_by": retired_by.strip(),
            "reason": reason.strip(),
            "occurred_at": timestamp,
        }
        with (release_dir / HISTORY_NAME).open("a", encoding="utf-8", newline="\n") as file:
            file.write(self._canonical_json(event) + "\n")
            file.flush()
            os.fsync(file.fileno())
        return event

    def list_releases(self) -> list[dict[str, Any]]:
        """Return manifests with effective lifecycle status from append-only history."""

        releases: list[dict[str, Any]] = []
        for manifest in self._load_manifests():
            path, _ = self._find_manifest(manifest["release_id"])
            item = dict(manifest)
            if self._is_retired(path):
                item["effective_status"] = "retired"
            else:
                item["effective_status"] = item["status"]
            releases.append(item)
        return sorted(
            releases,
            key=lambda entry: (
                entry["taxonomy_id"],
                entry["taxonomy_version"],
                entry["release_id"],
            ),
        )

    def _resolve_artifact(self, path: str | Path) -> Path:
        raw_path = Path(path)
        if ".." in raw_path.parts:
            raise ValueError(f"Artifact path traversal is not allowed: {path}")
        resolved = raw_path.resolve() if raw_path.is_absolute() else (self.artifact_root / raw_path).resolve()
        try:
            resolved.relative_to(self.artifact_root)
        except ValueError as error:
            raise ValueError(f"Artifact path is outside the allowed root: {path}") from error
        if not resolved.is_file():
            raise ValueError(f"Artifact file does not exist: {path}")
        return resolved

    def _verify_live_source_artifacts(self, bundle: dict[str, Any]) -> None:
        for relative, expected_content in bundle["source_artifacts"].items():
            path = self._resolve_artifact(relative)
            try:
                actual_content = path.read_bytes().decode("utf-8")
            except (OSError, UnicodeError) as error:
                raise ReleaseGateError([f"Cannot read source artifact {relative}: {error}"]) from error
            if actual_content != expected_content:
                raise ReleaseGateError(
                    [f"Source artifact changed after candidate creation: {relative}."]
                )

    def _validate_register(
        self,
        register: Any,
        bundle: dict[str, Any],
        report: dict[str, Any],
    ) -> list[str]:
        if not isinstance(register, dict):
            return ["Decision register must be a JSON object."]
        if set(register) != {"schema_version", "decisions", "audit_events"}:
            return ["Decision register must match the decision register schema fields."]
        decisions = register.get("decisions")
        events = register.get("audit_events")
        if not isinstance(decisions, list) or not isinstance(events, list):
            return ["Decision register decisions and audit_events must be arrays."]
        errors: list[str] = []
        for index, decision in enumerate(decisions):
            if not isinstance(decision, dict) or set(decision) != _DECISION_FIELDS:
                errors.append(f"decisions[{index}] does not match the decision schema.")
                continue
            if (
                not isinstance(decision.get("target_type"), str)
                or not isinstance(decision.get("review_status"), str)
                or (
                    decision.get("decision") is not None
                    and not isinstance(decision.get("decision"), str)
                )
            ):
                errors.append(f"decisions[{index}] contains invalid enum field types.")
            decision_id = decision.get("decision_id")
            if not isinstance(decision_id, str) or not re.fullmatch(
                r"DEC-[A-Za-z0-9][A-Za-z0-9._-]*", decision_id
            ):
                errors.append(f"decisions[{index}].decision_id is invalid.")
            evidence = decision.get("evidence_refs")
            if not isinstance(evidence, list) or any(not isinstance(item, str) for item in evidence):
                errors.append(f"decisions[{index}].evidence_refs must be strings.")
        for index, event in enumerate(events):
            if not isinstance(event, dict) or set(event) != _AUDIT_FIELDS:
                errors.append(f"audit_events[{index}] does not match the audit schema.")
                continue
            if any(
                not isinstance(event.get(field), str)
                for field in ("target_type", "status_before", "status_after")
            ):
                errors.append(f"audit_events[{index}] contains invalid enum field types.")
            event_id = event.get("event_id")
            if not isinstance(event_id, str) or not re.fullmatch(
                r"EVT-[A-Za-z0-9][A-Za-z0-9._-]*", event_id
            ):
                errors.append(f"audit_events[{index}].event_id is invalid.")
            evidence = event.get("evidence_refs")
            if not isinstance(evidence, list) or any(not isinstance(item, str) for item in evidence):
                errors.append(f"audit_events[{index}].evidence_refs must be strings.")
        if errors:
            return errors
        target_ids = self._known_target_ids(bundle)
        mapping_ready_ids: set[str] | None = None
        if report.get("mapping_readiness", {}).get("passed") is True:
            mapping_ready_ids = self._skill_ids(bundle)
        return errors + validate_decision_register(
            register,
            target_ids,
            mapping_ready_ids,
        )

    def _resolve_approvals(
        self,
        register: dict[str, Any],
        approval_decision_refs: list[str],
        taxonomy_version: str,
    ) -> tuple[list[dict[str, str]], list[str]]:
        if not isinstance(approval_decision_refs, list) or not approval_decision_refs:
            return [], ["At least one approval_decision_ref is required."]
        if any(not isinstance(reference, str) for reference in approval_decision_refs):
            return [], ["approval_decision_refs must contain decision ID strings."]
        if len(set(approval_decision_refs)) != len(approval_decision_refs):
            return [], ["approval_decision_refs must be unique."]
        decisions = {
            decision["decision_id"]: decision
            for decision in register["decisions"]
            if isinstance(decision, dict) and isinstance(decision.get("decision_id"), str)
        }
        events = register["audit_events"]
        approvals = []
        errors = []
        for decision_id in approval_decision_refs:
            decision = decisions.get(decision_id)
            if not decision or decision.get("review_status") != "approved":
                errors.append(f"Approval reference is not an approved decision: {decision_id}.")
                continue
            if decision.get("taxonomy_version") != taxonomy_version:
                errors.append(
                    f"Approval {decision_id} is for a different taxonomy version."
                )
            reviewer = decision.get("reviewer")
            reviewed_at = decision.get("reviewed_at")
            matching_events = [
                event
                for event in events
                if event.get("decision_id") == decision_id
                and event.get("status_after") == "approved"
                and event.get("actor_type") == "human"
                and event.get("performed_by") == reviewer
                and event.get("occurred_at") == reviewed_at
            ]
            if not matching_events:
                errors.append(
                    f"Approval {decision_id} has no matching human audit event and timestamp."
                )
                continue
            approvals.append(
                {
                    "decision_id": decision_id,
                    "reviewer": reviewer,
                    "reviewed_at": reviewed_at,
                }
            )
        return approvals, errors

    @staticmethod
    def _readiness_blockers(report: Any) -> list[str]:
        if not isinstance(report, dict):
            return ["Validation report must be a JSON object."]
        blockers: list[str] = []
        for gate_name in (
            "structural_validation",
            "mapping_readiness",
            "approval_readiness",
        ):
            gate = report.get(gate_name)
            if not isinstance(gate, dict):
                blockers.append(f"Validation report is missing {gate_name}.")
                continue
            if gate.get("passed") is not True:
                blockers.append(f"{gate_name} did not pass.")
            failed = gate.get("failed", [])
            gate_blockers = gate.get("blockers", [])
            if not isinstance(failed, list) or not isinstance(gate_blockers, list):
                blockers.append(f"{gate_name} failed/blockers must be arrays.")
                continue
            for issue in failed + gate_blockers:
                if isinstance(issue, dict):
                    blockers.append(
                        f"{gate_name}: {issue.get('code', 'blocker')} — "
                        f"{issue.get('message', issue.get('location', 'unspecified'))}"
                    )
                else:
                    blockers.append(f"{gate_name}: invalid blocker record.")
        if report.get("publication_ready") is not True:
            blockers.append("publication_ready is not true.")
        if not report.get("generated_at") or not TaxonomyReleaseManager._has_timezone(
            report.get("generated_at")
        ):
            blockers.append("Validation report requires a timezone-aware generated_at.")
        return list(dict.fromkeys(blockers))

    def _load_manifests(self) -> list[dict[str, Any]]:
        if not self.release_root.exists():
            return []
        manifests = []
        for manifest_path in self.release_root.rglob(MANIFEST_NAME):
            try:
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            except (OSError, UnicodeError, json.JSONDecodeError) as error:
                raise ValueError(f"Invalid release manifest: {manifest_path}") from error
            if isinstance(manifest, dict):
                manifests.append(manifest)
        return manifests

    def _find_manifest(self, release_id: str) -> tuple[Path, dict[str, Any]]:
        found = []
        for path in self.release_root.rglob(MANIFEST_NAME):
            try:
                manifest = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, UnicodeError, json.JSONDecodeError) as error:
                raise ValueError(f"Invalid release manifest: {path}") from error
            if isinstance(manifest, dict) and manifest.get("release_id") == release_id:
                found.append((path.parent, manifest))
        if not found:
            raise FileNotFoundError(f"Unknown release_id: {release_id}")
        if len(found) != 1:
            raise ValueError(f"Release ID is not unique in release history: {release_id}")
        return found[0]

    def _release_path(self, taxonomy_id: str, version: str, release_id: str) -> Path:
        return self.release_root / taxonomy_id / version / release_id

    def _read_bundle_for_manifest(self, manifest: dict[str, Any]) -> dict[str, Any]:
        release_dir, _ = self._find_manifest(manifest["release_id"])
        errors = self.verify_release(manifest["release_id"])
        if errors:
            raise ReleaseGateError(errors)
        return json.loads((release_dir / SNAPSHOT_NAME).read_text(encoding="utf-8"))

    def _is_retired(self, release_dir: Path) -> bool:
        history_path = release_dir / HISTORY_NAME
        if not history_path.exists():
            return False
        for line in history_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            event = json.loads(line)
            if event.get("status") == "retired":
                return True
        return False

    @staticmethod
    def _validate_identity(taxonomy_id: str, version: str, release_id: str) -> None:
        if not isinstance(taxonomy_id, str) or not _ID_PATTERN.fullmatch(taxonomy_id):
            raise ValueError("taxonomy_id must be a stable alphanumeric identifier.")
        if not isinstance(version, str) or not _VERSION_PATTERN.fullmatch(version):
            raise ValueError("taxonomy_version must use MAJOR.MINOR.PATCH format.")
        if not isinstance(release_id, str) or not _ID_PATTERN.fullmatch(release_id):
            raise ValueError("release_id must be a stable alphanumeric identifier.")

    @staticmethod
    def _validate_manifest_shape(manifest: dict[str, Any]) -> None:
        errors = TaxonomyReleaseManager._manifest_errors(manifest)
        if errors:
            raise ValueError("Invalid release manifest: " + "; ".join(errors))

    @staticmethod
    def _manifest_errors(manifest: Any) -> list[str]:
        required = {
            "schema_version",
            "taxonomy_id",
            "taxonomy_version",
            "release_id",
            "parent_release_id",
            "status",
            "snapshot_file",
            "snapshot_sha256",
            "source_artifact_hashes",
            "counts",
            "approval_decision_refs",
            "approved_by",
            "created_at",
            "published_at",
        }
        if not isinstance(manifest, dict):
            return ["Manifest must be a JSON object."]
        errors = []
        extra = manifest.keys() - required
        if extra:
            errors.append("Manifest has unknown fields: " + ", ".join(sorted(extra)) + ".")
        missing = required - manifest.keys()
        if missing:
            errors.append("Manifest missing fields: " + ", ".join(sorted(missing)) + ".")
        if manifest.get("schema_version") != MANIFEST_SCHEMA_VERSION:
            errors.append(f"schema_version must be {MANIFEST_SCHEMA_VERSION!r}.")
        if not isinstance(manifest.get("taxonomy_id"), str) or not _ID_PATTERN.fullmatch(
            manifest.get("taxonomy_id", "")
        ):
            errors.append("taxonomy_id is invalid.")
        if not isinstance(manifest.get("taxonomy_version"), str) or not _VERSION_PATTERN.fullmatch(
            manifest.get("taxonomy_version", "")
        ):
            errors.append("taxonomy_version must use MAJOR.MINOR.PATCH format.")
        if not isinstance(manifest.get("release_id"), str) or not _ID_PATTERN.fullmatch(
            manifest.get("release_id", "")
        ):
            errors.append("release_id is invalid.")
        parent_id = manifest.get("parent_release_id")
        if parent_id is not None and (
            not isinstance(parent_id, str) or not _ID_PATTERN.fullmatch(parent_id)
        ):
            errors.append("parent_release_id is invalid.")
        if manifest.get("status") not in {"draft", "in_review", "release_candidate", "published", "retired"}:
            errors.append("Manifest status is invalid.")
        if manifest.get("snapshot_file") != SNAPSHOT_NAME:
            errors.append(f"snapshot_file must be {SNAPSHOT_NAME!r}.")
        if not isinstance(manifest.get("snapshot_sha256"), str) or not re.fullmatch(
            r"[a-f0-9]{64}", manifest.get("snapshot_sha256", "")
        ):
            errors.append("snapshot_sha256 must be a lowercase SHA-256 digest.")
        hashes = manifest.get("source_artifact_hashes")
        if not isinstance(hashes, dict) or any(
            not isinstance(path, str)
            or not path
            or Path(path.replace("\\", "/")).is_absolute()
            or ".." in Path(path.replace("\\", "/")).parts
            or bool(re.match(r"^[A-Za-z]:", path))
            or not isinstance(digest, str)
            or not re.fullmatch(r"[a-f0-9]{64}", digest)
            for path, digest in (hashes.items() if isinstance(hashes, dict) else [])
        ):
            errors.append("source_artifact_hashes must map paths to SHA-256 digests.")
        if not isinstance(manifest.get("counts"), dict) or set(manifest.get("counts", {})) != set(_COUNT_FIELDS):
            errors.append("counts must contain exams, sections, competencies, domains, topics, and skills.")
        elif any(
            not isinstance(count, int) or isinstance(count, bool) or count < 0
            for count in manifest["counts"].values()
        ):
            errors.append("Taxonomy counts must be non-negative integers.")
        if not isinstance(manifest.get("approval_decision_refs"), list):
            errors.append("approval_decision_refs must be an array.")
        elif any(
            not isinstance(reference, str)
            or not re.fullmatch(r"DEC-[A-Za-z0-9][A-Za-z0-9._-]*", reference)
            for reference in manifest["approval_decision_refs"]
        ) or len(set(manifest["approval_decision_refs"])) != len(manifest["approval_decision_refs"]):
            errors.append("approval_decision_refs must contain unique decision IDs.")
        if not isinstance(manifest.get("approved_by"), list):
            errors.append("approved_by must be an array.")
        else:
            for approval in manifest["approved_by"]:
                if (
                    not isinstance(approval, dict)
                    or set(approval) != {"decision_id", "reviewer", "reviewed_at"}
                    or approval.get("decision_id") not in manifest.get("approval_decision_refs", [])
                    or not isinstance(approval.get("reviewer"), str)
                    or not approval.get("reviewer", "").strip()
                    or not TaxonomyReleaseManager._has_timezone(approval.get("reviewed_at"))
                ):
                    errors.append("approved_by contains invalid reviewer evidence.")
                    break
        if not TaxonomyReleaseManager._has_timezone(manifest.get("created_at")):
            errors.append("created_at must be timezone-aware.")
        if manifest.get("status") == "published":
            if not TaxonomyReleaseManager._has_timezone(manifest.get("published_at")):
                errors.append("published_at must be timezone-aware for a published release.")
            if not manifest.get("approval_decision_refs") or not manifest.get("approved_by"):
                errors.append("Published manifests require human approval evidence.")
        elif manifest.get("status") == "release_candidate":
            if manifest.get("approval_decision_refs") or manifest.get("approved_by"):
                errors.append("Release candidates must not claim publication approval.")
            if manifest.get("published_at") is not None:
                errors.append("Release candidates must not have published_at.")
        elif manifest.get("published_at") is not None:
            errors.append("Only a published manifest may have published_at.")
        return errors

    @staticmethod
    def _count_taxonomy(framework: dict[str, Any], artifacts: dict[str, str]) -> dict[str, int]:
        counts = {key: 0 for key in _COUNT_FIELDS}
        exams = framework.get("exams", [])
        counts["exams"] = len(exams) if isinstance(exams, list) else 0

        def visit(node: Any) -> None:
            if isinstance(node, dict):
                for key in ("sections", "competencies", "domains", "topics"):
                    children = node.get(key)
                    if isinstance(children, list):
                        counts[key] += len(children)
                        for child in children:
                            visit(child)
                for key, value in node.items():
                    if key not in {"sections", "competencies", "domains", "topics"}:
                        visit(value)
            elif isinstance(node, list):
                for child in node:
                    visit(child)

        visit(exams)
        skill_ids = TaxonomyReleaseManager._skill_ids_from_artifacts(artifacts)
        counts["skills"] = len(skill_ids)
        return counts

    @classmethod
    def _known_target_ids(cls, bundle: dict[str, Any]) -> set[str]:
        known: set[str] = set()

        def visit(value: Any) -> None:
            if isinstance(value, dict):
                identifier = value.get("id")
                if isinstance(identifier, str):
                    known.add(identifier)
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)

        visit(bundle.get("framework", {}))
        known.update(cls._skill_ids_from_artifacts(bundle.get("source_artifacts", {})))
        return known

    @classmethod
    def _skill_ids(cls, bundle: dict[str, Any]) -> set[str]:
        return cls._skill_ids_from_artifacts(bundle.get("source_artifacts", {}))

    @staticmethod
    def _skill_ids_from_artifacts(artifacts: dict[str, str]) -> set[str]:
        skill_ids: set[str] = set()
        for content in artifacts.values():
            try:
                rows = csv.DictReader(io.StringIO(content))
                if not rows.fieldnames:
                    continue
                identifier_field = next(
                    (field for field in ("skill_id", "candidate_skill_id") if field in rows.fieldnames),
                    None,
                )
                if identifier_field:
                    skill_ids.update(
                        row[identifier_field].strip()
                        for row in rows
                        if row.get(identifier_field, "").strip()
                    )
            except (csv.Error, UnicodeError):
                continue
        return skill_ids

    @classmethod
    def _skill_semantics(cls, bundle: dict[str, Any]) -> dict[str, dict[str, str]]:
        semantics: dict[str, dict[str, str]] = {}
        for content in bundle.get("source_artifacts", {}).values():
            rows = csv.DictReader(io.StringIO(content))
            if not rows.fieldnames:
                continue
            identifier_field = next(
                (field for field in ("skill_id", "candidate_skill_id") if field in rows.fieldnames),
                None,
            )
            if not identifier_field:
                continue
            for row in rows:
                skill_id = row.get(identifier_field, "").strip()
                if not skill_id:
                    continue
                signature = {
                    field: row.get(field, "").strip()
                    for field in _SKILL_SEMANTIC_FIELDS
                    if field in row
                }
                if not any(signature.values()):
                    continue
                prior = semantics.setdefault(skill_id, {})
                conflicts = {
                    field
                    for field in prior.keys() & signature.keys()
                    if prior[field] != signature[field]
                }
                if conflicts:
                    raise ValueError(f"Conflicting skill definitions for stable ID {skill_id}.")
                prior.update(signature)
        return semantics

    @classmethod
    def _validate_stable_skill_ids(
        cls,
        parent_bundle: dict[str, Any],
        candidate_bundle: dict[str, Any],
    ) -> None:
        parent = cls._skill_semantics(parent_bundle)
        candidate = cls._skill_semantics(candidate_bundle)
        changed = sorted(
            skill_id
            for skill_id in parent.keys() & candidate.keys()
            if any(
                parent[skill_id][field] != candidate[skill_id][field]
                for field in parent[skill_id].keys() & candidate[skill_id].keys()
            )
        )
        if changed:
            raise ValueError(
                "Stable Skill ID semantics changed; assign a new ID and migration policy: "
                + ", ".join(changed)
            )

    @staticmethod
    def _replace_candidate_manifest(path: Path, manifest: dict[str, Any]) -> None:
        lock_path = path.with_name(f".{path.name}.publish-lock")
        temporary_path = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
        lock_acquired = False
        try:
            with lock_path.open("x", encoding="utf-8"):
                lock_acquired = True
                current = json.loads(path.read_text(encoding="utf-8"))
                if current.get("status") != "release_candidate":
                    raise ValueError("Only a release candidate manifest can be promoted.")
            TaxonomyReleaseManager._write_json_exclusive(temporary_path, manifest)
            os.replace(temporary_path, path)
        finally:
            if temporary_path.exists():
                temporary_path.unlink()
            if lock_acquired and lock_path.exists():
                lock_path.unlink()

    @staticmethod
    def _write_json_exclusive(path: Path, value: dict[str, Any]) -> None:
        with path.open("x", encoding="utf-8", newline="\n") as file:
            file.write(TaxonomyReleaseManager._canonical_json(value) + "\n")
            file.flush()
            os.fsync(file.fileno())

    @staticmethod
    def _canonical_json(value: Any) -> str:
        return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))

    @staticmethod
    def _sha256(content: bytes) -> str:
        return hashlib.sha256(content).hexdigest()

    @staticmethod
    def _has_timezone(value: Any) -> bool:
        if not isinstance(value, str) or not value.strip():
            return False
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return False
        return parsed.tzinfo is not None and parsed.utcoffset() is not None

    @staticmethod
    def _now() -> str:
        return datetime.now(timezone.utc).isoformat()
