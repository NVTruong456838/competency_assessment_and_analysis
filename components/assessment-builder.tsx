'use client';

import { useEffect, useMemo, useState } from 'react';

type ExamSection = {
  exam_version_id: number;
  exam: string;
  exam_name: string;
  version_code: string;
  version_name: string;
  status: string;
  section_id: number;
  section_code: string;
  section_name: string;
  order_no: number;
  optional_section_group_id: number | null;
  optional_group_code: string | null;
  optional_group_name: string | null;
  optional_branch_code: string | null;
  optional_branch_name: string | null;
};

type BlueprintVersion = {
  blueprintId: number;
  blueprintVersionId: number;
  blueprintCode: string;
  title: string;
  exam: string;
  examVersion: string;
  versionNumber: number;
  testType: string;
  durationMinutes: number;
  totalQuestions: number;
  status: string;
};

type BuildResult = {
  testId: number | null;
  testVersionId: number | null;
  status: string;
  requestedQuestions: number;
  selectedQuestions: number;
  contentSha256: string | null;
  missingCoverage: {
    sectionId: number;
    requested: number;
    selected: number;
    eligibleAvailable: number;
    constraints: string[];
  }[];
};

export function AssessmentBuilder() {
  const [sections, setSections] = useState<ExamSection[]>([]);
  const [blueprints, setBlueprints] = useState<BlueprintVersion[]>([]);
  const [examVersionId, setExamVersionId] = useState('');
  const [sectionIds, setSectionIds] = useState<string[]>([]);
  const [optionalBranchSelections, setOptionalBranchSelections] = useState<Record<number, string>>({});
  const [title, setTitle] = useState('');
  const [testType, setTestType] = useState('DIAGNOSTIC');
  const [duration, setDuration] = useState(60);
  const [questionCount, setQuestionCount] = useState(10);
  const [skillIds, setSkillIds] = useState('');
  const [cognitiveDistribution, setCognitiveDistribution] = useState('{}');
  const [difficultyDistribution, setDifficultyDistribution] = useState('{}');
  const [questionTypeDistribution, setQuestionTypeDistribution] = useState('{}');
  const [releaseId, setReleaseId] = useState('');
  const [blueprintVersionId, setBlueprintVersionId] = useState('');
  const [result, setResult] = useState<BuildResult | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState(false);

  const selectedSections = useMemo(
    () => sections.filter((section) => String(section.exam_version_id) === examVersionId),
    [sections, examVersionId],
  );

  const optionalGroups = useMemo(() => {
    const groups = new Map<number, {
      id: number;
      code: string;
      name: string;
      branches: Map<string, string>;
    }>();
    for (const section of selectedSections) {
      if (section.optional_section_group_id === null || !section.optional_branch_code) continue;
      const groupId = section.optional_section_group_id;
      const group = groups.get(groupId) ?? {
        id: groupId,
        code: section.optional_group_code ?? `GROUP-${groupId}`,
        name: section.optional_group_name ?? 'Phần thi tùy chọn',
        branches: new Map<string, string>(),
      };
      group.branches.set(
        section.optional_branch_code,
        section.optional_branch_name ?? section.optional_branch_code,
      );
      groups.set(groupId, group);
    }
    return Array.from(groups.values());
  }, [selectedSections]);

  const visibleSections = useMemo(
    () => selectedSections.filter((section) => (
      section.optional_section_group_id === null
      || optionalBranchSelections[section.optional_section_group_id] === section.optional_branch_code
    )),
    [optionalBranchSelections, selectedSections],
  );

  useEffect(() => {
    Promise.all([
      fetch('/api/assessments/teacher/assessments/exam-versions', { cache: 'no-store' }),
      fetch('/api/assessments/teacher/assessments/blueprints', { cache: 'no-store' }),
    ])
      .then(async ([sectionResponse, blueprintResponse]) => {
        const sectionPayload: unknown = await sectionResponse.json();
        const blueprintPayload: unknown = await blueprintResponse.json();
        if (!sectionResponse.ok) throw new Error(errorMessage(sectionPayload));
        if (!blueprintResponse.ok) throw new Error(errorMessage(blueprintPayload));
        setSections(sectionPayload as ExamSection[]);
        setBlueprints(blueprintPayload as BlueprintVersion[]);
      })
      .catch((cause: unknown) => setError(
        cause instanceof Error ? cause.message : 'Không thể tải cấu hình bài thi.',
      ))
      .finally(() => setLoading(false));
  }, []);

  async function createBlueprint(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setWorking(true);
    setError('');
    setResult(null);
    try {
      const response = await fetch('/api/assessments/teacher/assessments/blueprints', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title,
          examVersionId: Number(examVersionId),
          testType,
          durationMinutes: duration,
          sections: sectionIds.map((sectionId) => ({
            sectionId: Number(sectionId),
            questionCount,
            durationMinutes: null,
          })),
          selectionRules: {
            skillIds: skillIds.split(',').map((value) => value.trim()).filter(Boolean).map(Number),
            cognitiveLevelDistribution: JSON.parse(cognitiveDistribution),
            difficultyDistribution: JSON.parse(difficultyDistribution),
            questionTypeDistribution: JSON.parse(questionTypeDistribution),
          },
          optionalBranchSelections: Object.entries(optionalBranchSelections).map(([groupId, branchCode]) => ({
            optionalSectionGroupId: Number(groupId),
            branchCode,
          })),
        }),
      });
      const payload: unknown = await response.json();
      if (!response.ok) throw new Error(errorMessage(payload));
      const blueprint = payload as BlueprintVersion;
      setBlueprints((current) => [blueprint, ...current]);
      setBlueprintVersionId(String(blueprint.blueprintVersionId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Không thể tạo Blueprint.');
    } finally {
      setWorking(false);
    }
  }

  async function buildTest(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setWorking(true);
    setError('');
    setResult(null);
    try {
      const response = await fetch('/api/assessments/teacher/assessments/tests/build', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          blueprintVersionId: Number(blueprintVersionId),
          taxonomyReleaseId: Number(releaseId),
          seed: crypto.getRandomValues(new Uint32Array(1))[0],
          title,
        }),
      });
      const payload: unknown = await response.json();
      if (!response.ok) throw new Error(errorMessage(payload));
      setResult(payload as BuildResult);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Không thể tạo Test Version.');
    } finally {
      setWorking(false);
    }
  }

  return (
    <section className="space-y-6">
      <div className="rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm leading-6 text-amber-950">
        Test Builder chỉ chọn Question Version đã được duyệt, Mapping Set và Taxonomy Release đã
        phát hành, kèm quyền sử dụng đã xác minh. Dữ liệu crawler chưa duyệt không được dùng.
      </div>
      {error && <p role="alert" className="rounded-xl bg-red-50 p-4 text-sm text-red-800">{error}</p>}
      {loading ? (
        <p className="text-sm text-slate-600">Đang tải cấu hình...</p>
      ) : (
        <>
          <form onSubmit={createBlueprint} className="space-y-4 rounded-2xl border border-blue-100 bg-white p-6">
            <h2 className="text-lg font-semibold text-blue-950">Tạo Blueprint phiên bản mới</h2>
            <label className="block text-sm font-medium text-slate-700">
              Tên bài đánh giá
              <input required value={title} onChange={(event) => setTitle(event.target.value)} className="mt-1 w-full rounded-lg border border-slate-300 p-2" />
            </label>
            <div className="grid gap-4 md:grid-cols-2">
              <label className="text-sm font-medium text-slate-700">
                Exam Version
                <select
                  required
                  value={examVersionId}
                  onChange={(event) => {
                    setExamVersionId(event.target.value);
                    setSectionIds([]);
                    setOptionalBranchSelections({});
                  }}
                  className="mt-1 w-full rounded-lg border border-slate-300 p-2"
                >
                  <option value="">Chọn Exam Version</option>
                  {Array.from(new Map(sections.map((section) => [
                    section.exam_version_id,
                    `${section.exam} · ${section.version_code} — ${section.version_name}`,
                  ])).entries()).map(([id, label]) => (
                    <option key={id} value={id}>{label}</option>
                  ))}
                </select>
              </label>
              <label className="text-sm font-medium text-slate-700">
                Section chứa câu hỏi
                <select
                  required
                  multiple
                  value={sectionIds}
                  onChange={(event) => setSectionIds(
                    Array.from(event.target.selectedOptions, (option) => option.value),
                  )}
                  className="mt-1 min-h-32 w-full rounded-lg border border-slate-300 p-2"
                >
                  {visibleSections.map((section) => (
                    <option key={section.section_id} value={section.section_id}>
                      {section.optional_branch_name ? `${section.optional_branch_name} · ` : ''}
                      {section.section_code} — {section.section_name}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            {optionalGroups.length > 0 && (
              <div className="rounded-xl border border-amber-200 bg-amber-50 p-4">
                <p className="text-sm font-semibold text-amber-950">
                  Chọn một nhánh cho từng phần thi tùy chọn trước khi chọn Section chứa câu hỏi.
                </p>
                <div className="mt-3 grid gap-4 md:grid-cols-2">
                  {optionalGroups.map((group) => (
                    <label key={group.id} className="text-sm font-medium text-slate-700">
                      {group.name} ({group.code})
                      <select
                        value={optionalBranchSelections[group.id] ?? ''}
                        onChange={(event) => {
                          const branchCode = event.target.value;
                          setOptionalBranchSelections((current) => {
                            const next = { ...current };
                            if (branchCode) next[group.id] = branchCode;
                            else delete next[group.id];
                            return next;
                          });
                          setSectionIds((current) => current.filter((sectionId) => {
                            const section = selectedSections.find(
                              (item) => String(item.section_id) === sectionId,
                            );
                            return section?.optional_section_group_id !== group.id
                              || section.optional_branch_code === branchCode;
                          }));
                        }}
                        className="mt-1 w-full rounded-lg border border-slate-300 p-2"
                      >
                        <option value="">Chọn nhánh</option>
                        {Array.from(group.branches.entries()).map(([code, name]) => (
                          <option key={code} value={code}>{name}</option>
                        ))}
                      </select>
                    </label>
                  ))}
                </div>
              </div>
            )}
            <div className="grid gap-4 sm:grid-cols-3">
              <label className="text-sm font-medium text-slate-700">
                Loại bài
                <select value={testType} onChange={(event) => setTestType(event.target.value)} className="mt-1 w-full rounded-lg border border-slate-300 p-2">
                  <option value="DIAGNOSTIC">Diagnostic</option>
                  <option value="MINI_MOCK">Mini Test</option>
                  <option value="FULL_MOCK">Mock Test</option>
                  <option value="PRACTICE">Practice</option>
                </select>
              </label>
              <label className="text-sm font-medium text-slate-700">
                Thời lượng (phút)
                <input type="number" min={1} max={600} required value={duration} onChange={(event) => setDuration(Number(event.target.value))} className="mt-1 w-full rounded-lg border border-slate-300 p-2" />
              </label>
              <label className="text-sm font-medium text-slate-700">
                Số câu
                <input type="number" min={1} max={200} required value={questionCount} onChange={(event) => setQuestionCount(Number(event.target.value))} className="mt-1 w-full rounded-lg border border-slate-300 p-2" />
              </label>
            </div>
            <label className="block text-sm font-medium text-slate-700">
              Skill IDs (phân tách bằng dấu phẩy)
              <input value={skillIds} onChange={(event) => setSkillIds(event.target.value)} className="mt-1 w-full rounded-lg border border-slate-300 p-2" placeholder="101, 102" />
            </label>
            <div className="grid gap-4 md:grid-cols-3">
              <DistributionInput label="Cognitive Level (%)" value={cognitiveDistribution} onChange={setCognitiveDistribution} placeholder='{"M1":30,"M2":50,"M3":20}' />
              <DistributionInput label="Difficulty (%)" value={difficultyDistribution} onChange={setDifficultyDistribution} placeholder='{"EASY":30,"MEDIUM":50,"HARD":20}' />
              <DistributionInput label="Question Type (%)" value={questionTypeDistribution} onChange={setQuestionTypeDistribution} placeholder='{"SINGLE_CHOICE":100}' />
            </div>
            <button disabled={working || sections.length === 0} className="rounded-xl bg-blue-700 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">
              {working ? 'Đang lưu...' : 'Tạo Blueprint'}
            </button>
          </form>

          <form onSubmit={buildTest} className="space-y-4 rounded-2xl border border-blue-100 bg-white p-6">
            <h2 className="text-lg font-semibold text-blue-950">Build Test Version</h2>
            <div className="grid gap-4 md:grid-cols-2">
              <label className="text-sm font-medium text-slate-700">
                Blueprint Version
                <select required value={blueprintVersionId} onChange={(event) => setBlueprintVersionId(event.target.value)} className="mt-1 w-full rounded-lg border border-slate-300 p-2">
                  <option value="">Chọn Blueprint</option>
                  {blueprints.map((blueprint) => (
                    <option key={blueprint.blueprintVersionId} value={blueprint.blueprintVersionId}>
                      {blueprint.title} · v{blueprint.versionNumber} ({blueprint.exam} {blueprint.examVersion})
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm font-medium text-slate-700">
                Published Taxonomy Release ID
                <input type="number" min={1} required value={releaseId} onChange={(event) => setReleaseId(event.target.value)} className="mt-1 w-full rounded-lg border border-slate-300 p-2" />
              </label>
            </div>
            <button disabled={working || !blueprintVersionId} className="rounded-xl bg-blue-700 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">
              {working ? 'Đang kiểm tra coverage...' : 'Tạo Test Version nháp'}
            </button>
          </form>
        </>
      )}
      {result && (
        <section role="status" className="rounded-2xl border border-slate-200 bg-white p-6">
          <h2 className="font-semibold text-slate-900">
            {result.status === 'INSUFFICIENT_QUESTIONS' ? 'Thiếu câu hỏi đủ điều kiện' : 'Test Version nháp đã tạo'}
          </h2>
          <p className="mt-2 text-sm text-slate-700">
            Đã chọn {result.selectedQuestions}/{result.requestedQuestions} câu.
            {result.contentSha256 && ` Snapshot SHA-256: ${result.contentSha256}`}
          </p>
          {result.missingCoverage.length > 0 && (
            <ul className="mt-3 space-y-2 text-sm text-amber-900">
              {result.missingCoverage.map((missing) => (
                <li key={missing.sectionId}>
                  Section {missing.sectionId}: cần {missing.requested}, tìm thấy {missing.eligibleAvailable},
                  đã chọn {missing.selected}.
                </li>
              ))}
            </ul>
          )}
          {result.testVersionId && (
            <p className="mt-3 text-sm text-slate-600">
              Test Version #{result.testVersionId} đang ở trạng thái nháp; chỉ ADMIN được thực hiện publication gate.
            </p>
          )}
        </section>
      )}
    </section>
  );
}

function DistributionInput({
  label,
  value,
  onChange,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
}) {
  return (
    <label className="text-sm font-medium text-slate-700">
      {label}
      <input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="mt-1 w-full rounded-lg border border-slate-300 p-2 font-mono text-xs"
        placeholder={placeholder}
      />
    </label>
  );
}

function errorMessage(value: unknown): string {
  if (value && typeof value === 'object' && 'message' in value && typeof value.message === 'string') {
    return value.message;
  }
  if (value && typeof value === 'object' && 'error' in value && typeof value.error === 'string') {
    return value.error;
  }
  return 'Yêu cầu không thành công.';
}
