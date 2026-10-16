'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

type ExamQuestion = {
  testItemId: number;
  order: number;
  questionType: string;
  responseMode: string;
  stem: string;
  sectionName: string;
  passageTitle: string | null;
  passageContent: string | null;
  options: { key: string; text: string }[];
  visualAssets: {
    type: string;
    path: string;
    caption: string | null;
    altText: string | null;
  }[];
  selectedOptionKeys: string[];
  answerText: string | null;
  revision: number | null;
  markedForReview: boolean;
  groupStimulus: {
    groupVersionId: number;
    title: string;
    stimulusType: string;
    stimulus: {
      title?: string;
      content?: string;
      path?: string;
      altText?: string;
      caption?: string;
      columns?: { key: string; label: string }[];
      rows?: (string | number | boolean | null)[][];
      labels?: string[];
      series?: { name: string; values: number[] }[];
    };
    itemOrder: number;
    itemCount: number;
  } | null;
};

type ExamSession = {
  attempt: {
    attemptId: number;
    status: string;
    endsAt: string;
    remainingSeconds: number;
    totalQuestions: number;
    answeredQuestions: number;
    markedForReview: number;
  };
  title: string;
  exam: string;
  questions: ExamQuestion[];
};

type ScoringPreview = {
  run: {
    rawScore: number;
    maxScore: number;
    accuracy: number | null;
    correctQuestions: number;
    totalQuestions: number;
  };
};

type AnswerState = {
  selectedOptionKeys: string[];
  answerText: string;
  markedForReview: boolean;
  revision: number;
  saved: boolean;
};

type PendingAnswer = {
  fingerprint: string;
  payload: {
    selectedOptionKeys: string[];
    answerText: string | null;
    expectedRevision: number;
    requestId: string;
  };
};

type Props = { attemptId: number };

export function ExamPlayer({ attemptId }: Props) {
  const [session, setSession] = useState<ExamSession | null>(null);
  const [answers, setAnswers] = useState<Record<number, AnswerState>>({});
  const [currentIndex, setCurrentIndex] = useState(0);
  const [seconds, setSeconds] = useState(0);
  const secondsRef = useRef(0);
  const deadlineRef = useRef<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [finished, setFinished] = useState(false);
  const [submitSummary, setSubmitSummary] = useState('');
  const [scoringPreview, setScoringPreview] = useState<ScoringPreview | null>(null);
  const [scoringNotice, setScoringNotice] = useState('');
  const scoringRequestId = useRef<string | null>(null);
  const [online, setOnline] = useState(() =>
    typeof navigator === 'undefined' ? true : navigator.onLine,
  );
  const answersRef = useRef(answers);
  const inFlight = useRef(new Map<number, Promise<boolean>>());
  const queued = useRef(new Set<number>());
  const pendingAnswers = useRef(new Map<number, PendingAnswer>());
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());
  const sessionRef = useRef<ExamSession | null>(null);
  const submitOnce = useRef(false);
  const [reviewingId, setReviewingId] = useState<number | null>(null);
  const sendAnswerRef = useRef<(itemId: number) => Promise<boolean>>(async () => false);

  const loadOrCreateScoring = useCallback(async (createIfMissing: boolean) => {
    let response = await fetch(
      `/api/assessments/student/assessments/attempts/${attemptId}/scoring`,
      { cache: 'no-store' },
    );
    if (response.status === 404 && createIfMissing) {
      scoringRequestId.current ??= crypto.randomUUID();
      response = await fetch(
        `/api/assessments/student/assessments/attempts/${attemptId}/scoring-runs`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ requestId: scoringRequestId.current }),
        },
      );
    }
    const payload: unknown = await response.json();
    if (!response.ok) throw new Error(errorMessage(payload));
    setScoringPreview(payload as ScoringPreview);
    setScoringNotice('');
  }, [attemptId]);

  const updateAnswers = useCallback((
    update: (current: Record<number, AnswerState>) => Record<number, AnswerState>,
  ) => {
    const next = update(answersRef.current);
    answersRef.current = next;
    setAnswers(next);
  }, []);

  useEffect(() => {
    answersRef.current = answers;
  }, [answers]);

  useEffect(() => {
    let active = true;
    const pendingTimers = timers.current;
    fetch(`/api/assessments/student/assessments/attempts/${attemptId}`, {
      cache: 'no-store',
    })
      .then(async (response) => {
        const payload: unknown = await response.json();
        if (!response.ok) throw new Error(errorMessage(payload));
        if (active) {
          const loaded = payload as ExamSession;
          setSession(loaded);
          sessionRef.current = loaded;
          const initialSeconds = loaded.attempt.remainingSeconds + 1;
          setSeconds(initialSeconds);
          secondsRef.current = initialSeconds;
          deadlineRef.current = performance.now() + initialSeconds * 1000;
          updateAnswers(() => Object.fromEntries(loaded.questions.map((question) => [
            question.testItemId,
            {
              selectedOptionKeys: question.selectedOptionKeys ?? [],
              answerText: question.answerText ?? '',
              markedForReview: question.markedForReview,
              revision: question.revision ?? 0,
              saved: true,
            },
          ])) as Record<number, AnswerState>);
          setFinished(loaded.attempt.status !== 'IN_PROGRESS');
          if (loaded.attempt.status !== 'IN_PROGRESS') {
            void loadOrCreateScoring(true).catch(() => {
              setScoringNotice('Chưa tải được kết quả chấm. Bạn có thể thử tải lại trang.');
            });
          }
        }
      })
      .catch((cause: unknown) => {
        if (active) setError(cause instanceof Error ? cause.message : 'Không thể tải bài làm.');
      });
    return () => {
      active = false;
      for (const timer of pendingTimers.values()) clearTimeout(timer);
    };
  }, [attemptId, loadOrCreateScoring, updateAnswers]);

  const sendAnswer = useCallback(async (itemId: number) => {
    const activeRequest = inFlight.current.get(itemId);
    if (activeRequest) {
      queued.current.add(itemId);
      const saved = await activeRequest;
      if (queued.current.delete(itemId)) return sendAnswerRef.current(itemId);
      return saved;
    }
    if (finished) return false;
    const answer = answersRef.current[itemId];
    if (!answer) return false;
    const fingerprint = JSON.stringify([answer.selectedOptionKeys, answer.answerText]);
    const pending = pendingAnswers.current.get(itemId) ?? {
      fingerprint,
      payload: {
        selectedOptionKeys: answer.selectedOptionKeys,
        answerText: answer.answerText || null,
        expectedRevision: answer.revision,
        requestId: crypto.randomUUID(),
      },
    };
    pendingAnswers.current.set(itemId, pending);
    const request = (async () => {
      try {
        let response: Response | null = null;
        for (let attempt = 0; attempt < 2; attempt++) {
          try {
            response = await fetch(
              `/api/assessments/student/assessments/attempts/${attemptId}/items/${itemId}/answer`,
              {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(pending.payload),
              },
            );
            if (response.status < 500) break;
          } catch (cause) {
            if (attempt === 1) throw cause;
          }
        }
        const result: unknown = response ? await response.json() : null;
        if (!response?.ok) throw new Error(errorMessage(result));
        const revision = (result as { revision: number }).revision;
        pendingAnswers.current.delete(itemId);
        const latestAnswer = answersRef.current[itemId];
        const unchanged = JSON.stringify([
          latestAnswer.selectedOptionKeys,
          latestAnswer.answerText,
        ]) === pending.fingerprint;
        updateAnswers((current) => {
          const latest = current[itemId];
          return { ...current, [itemId]: { ...latest, revision, saved: unchanged } };
        });
        setError('');
        if (JSON.stringify([
          answersRef.current[itemId].selectedOptionKeys,
          answersRef.current[itemId].answerText,
        ]) !== pending.fingerprint) queued.current.add(itemId);
        return unchanged;
      } catch (cause) {
        updateAnswers((current) => {
          return { ...current, [itemId]: { ...current[itemId], saved: false } };
        });
        setError(cause instanceof Error ? cause.message : 'Đáp án chưa được lưu.');
        return false;
      }
    })();
    inFlight.current.set(itemId, request);
    const saved = await request;
    if (inFlight.current.get(itemId) === request) inFlight.current.delete(itemId);
    if (queued.current.delete(itemId)) return sendAnswerRef.current(itemId);
    return saved;
  }, [attemptId, finished, updateAnswers]);
  useEffect(() => {
    sendAnswerRef.current = sendAnswer;
  }, [sendAnswer]);

  const scheduleSave = useCallback((itemId: number) => {
    const previous = timers.current.get(itemId);
    if (previous) clearTimeout(previous);
    timers.current.set(itemId, setTimeout(() => {
      timers.current.delete(itemId);
      void sendAnswer(itemId);
    }, 600));
  }, [sendAnswer]);

  const submit = useCallback(async (confirm = true, autoSubmit = false) => {
    if (submitOnce.current || !sessionRef.current) return;
    if (confirm && !window.confirm('Bạn chắc chắn muốn nộp bài?')) return;
    submitOnce.current = true;
    setBusy(true);
    setError('');
    try {
      if (!autoSubmit) {
        const savesCompleted = await Promise.all(
          Object.entries(answersRef.current)
            .filter(([, answer]) => !answer.saved)
            .map(([itemId]) => sendAnswer(Number(itemId))),
        );
        if (savesCompleted.some((saved) => !saved)
            || Object.values(answersRef.current).some((answer) => !answer.saved)) {
          throw new Error('Có câu trả lời chưa lưu được. Vui lòng kết nối lại rồi thử nộp bài.');
        }
      }
      const response = await fetch(
        `/api/assessments/student/assessments/attempts/${attemptId}/submit`,
        { method: 'POST' },
      );
      const payload: unknown = await response.json();
      if (!response.ok) throw new Error(errorMessage(payload));
      const result = payload as { status: string; answeredQuestions: number; totalQuestions: number };
      setSubmitSummary(
        `${result.answeredQuestions}/${result.totalQuestions} câu đã có phản hồi · ${result.status}`,
      );
      setFinished(true);
      try {
        await loadOrCreateScoring(true);
      } catch {
        setScoringNotice('Bài đã nộp nhưng chưa tải được kết quả chấm. Bạn có thể tải lại trang để thử lại.');
      }
    } catch (cause) {
      submitOnce.current = false;
      setError(cause instanceof Error ? cause.message : 'Không thể nộp bài.');
    } finally {
      setBusy(false);
    }
  }, [attemptId, loadOrCreateScoring, sendAnswer]);

  useEffect(() => {
    if (!session || finished) return;
    const interval = setInterval(() => {
      const remaining = Math.max(0, Math.ceil(
        ((deadlineRef.current ?? performance.now()) - performance.now()) / 1000,
      ));
      secondsRef.current = remaining;
      setSeconds(remaining);
      if (remaining === 0) void submit(false, true);
    }, 1000);
    return () => clearInterval(interval);
  }, [session, finished, submit]);

  useEffect(() => {
    const retryPending = () => {
      setOnline(navigator.onLine);
      for (const [itemId, answer] of Object.entries(answersRef.current)) {
        if (!answer.saved) void sendAnswer(Number(itemId));
      }
    };
    window.addEventListener('online', retryPending);
    window.addEventListener('offline', retryPending);
    return () => {
      window.removeEventListener('online', retryPending);
      window.removeEventListener('offline', retryPending);
    };
  }, [sendAnswer]);

  if (error && !session) {
    return <p role="alert" className="rounded-xl bg-red-50 p-4 text-red-800">{error}</p>;
  }
  if (!session) return <p className="text-sm text-slate-600">Đang tải bài làm...</p>;
  if (finished) {
    return (
      <section className="rounded-2xl border border-blue-100 bg-white p-8">
        <h2 className="text-xl font-semibold text-blue-950">Bài làm đã kết thúc</h2>
        <p className="mt-3 text-slate-700">{submitSummary || 'Trạng thái: ' + session.attempt.status}</p>
        <p className="mt-2 text-sm text-slate-600">
          Hệ thống đã lưu phản hồi. Đáp án và lời giải không được hiển thị trong màn hình làm bài.
        </p>
        {scoringPreview && (
          <div className="mt-5 rounded-xl bg-blue-50 p-4 text-sm text-blue-950">
            <p className="font-semibold">
              Kết quả nội bộ: {scoringPreview.run.rawScore}/{scoringPreview.run.maxScore} điểm
              {scoringPreview.run.accuracy !== null
                && ` · ${Math.round(scoringPreview.run.accuracy * 100)}% chính xác`}
            </p>
            <p className="mt-1">
              Đúng {scoringPreview.run.correctQuestions}/{scoringPreview.run.totalQuestions} câu.
              Đây không phải điểm TSA/HSA chính thức.
            </p>
          </div>
        )}
        {scoringNotice && <p role="status" className="mt-3 text-sm text-amber-800">{scoringNotice}</p>}
      </section>
    );
  }

  const question = session.questions[currentIndex];
  const answer = answers[question.testItemId];
  const answered = (item: ExamQuestion) => {
    const value = answers[item.testItemId];
    return !!value && (value.selectedOptionKeys.length > 0 || value.answerText.trim().length > 0);
  };

  function updateAnswer(patch: Partial<AnswerState>) {
    updateAnswers((current) => {
      return {
        ...current,
        [question.testItemId]: { ...current[question.testItemId], ...patch, saved: false },
      };
    });
    scheduleSave(question.testItemId);
  }

  function toggleOption(key: string) {
    const selected = answer.selectedOptionKeys.includes(key)
      ? answer.selectedOptionKeys.filter((value) => value !== key)
      : question.responseMode === 'SINGLE_OPTION'
        ? [key]
        : [...answer.selectedOptionKeys, key];
    updateAnswer({ selectedOptionKeys: selected });
  }

  async function toggleReview() {
    const newValue = !answer.markedForReview;
    const itemId = question.testItemId;
    const pendingTimer = timers.current.get(itemId);
    if (pendingTimer) clearTimeout(pendingTimer);
    timers.current.delete(itemId);
    setReviewingId(itemId);
    updateAnswers((current) => {
      return { ...current, [itemId]: {
        ...current[itemId], markedForReview: newValue, saved: false,
      } };
    });
    try {
      if (!await sendAnswer(itemId)) {
        throw new Error('Câu trả lời chưa được lưu; vui lòng thử lại trước khi đánh dấu.');
      }
      const latest = answersRef.current[itemId];
      const response = await fetch(
        `/api/assessments/student/assessments/attempts/${attemptId}/items/${itemId}/review`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            markedForReview: newValue,
            expectedRevision: latest.revision,
            requestId: crypto.randomUUID(),
          }),
        },
      );
      const payload: unknown = await response.json();
      if (!response.ok) throw new Error(errorMessage(payload));
      const result = payload as { revision: number };
      updateAnswers((current) => {
        return { ...current, [itemId]: {
          ...current[itemId], revision: result.revision, saved: true,
        } };
      });
      setError('');
    } catch (cause) {
      updateAnswers((current) => {
        return { ...current, [itemId]: {
          ...current[itemId], markedForReview: answer.markedForReview,
        } };
      });
      setError(cause instanceof Error ? cause.message : 'Không thể cập nhật đánh dấu.');
    } finally {
      setReviewingId(null);
    }
  }

  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_18rem]">
      <section className="min-w-0 rounded-2xl border border-blue-100 bg-white p-5 shadow-sm md:p-8">
        <header className="flex flex-wrap items-start justify-between gap-3 border-b border-slate-100 pb-5">
          <div>
            <p className="text-sm font-medium text-blue-700">{session.exam} · {question.sectionName}</p>
            <h2 className="mt-1 text-lg font-semibold text-blue-950">{session.title}</h2>
          </div>
          <div aria-live="polite" className="rounded-xl bg-blue-50 px-4 py-2 text-sm font-semibold text-blue-900">
            Còn lại: {formatTime(seconds)}
          </div>
        </header>

        {error && (
          <p role="alert" className="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
            {error}
          </p>
        )}

        {question.passageContent && (
          <aside className="my-6 rounded-xl bg-slate-50 p-4">
            {question.passageTitle && <h3 className="font-semibold text-slate-900">{question.passageTitle}</h3>}
            <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-slate-700">{question.passageContent}</p>
          </aside>
        )}
        {question.groupStimulus && (
          <QuestionGroupStimulusView group={question.groupStimulus} />
        )}
        <div className="prose prose-slate mt-6 max-w-none whitespace-pre-wrap">{question.stem}</div>
        {question.visualAssets.map((asset, index) => (
          <figure key={`${asset.path}-${index}`} data-asset-type={asset.type} className="my-5">
            {asset.path && <img src={asset.path} alt={asset.altText ?? ''} className="max-h-96 max-w-full rounded-xl object-contain" />}
            {asset.caption && <figcaption className="mt-2 text-sm text-slate-600">{asset.caption}</figcaption>}
          </figure>
        ))}
        <div className="mt-6 space-y-3">
          {question.options.map((option) => (
            <label key={option.key} className="flex cursor-pointer items-start gap-3 rounded-xl border border-slate-200 p-4 hover:border-blue-300">
              <input
                type={question.responseMode === 'SINGLE_OPTION' ? 'radio' : 'checkbox'}
                name={`item-${question.testItemId}`}
                checked={answer.selectedOptionKeys.includes(option.key)}
                onChange={() => toggleOption(option.key)}
              />
              <span className="text-sm leading-6 text-slate-800">{option.text}</span>
            </label>
          ))}
          {question.options.length === 0 && (
            <label className="block">
              <span className="sr-only">Câu trả lời</span>
              <textarea
                value={answer.answerText}
                onChange={(event) => updateAnswer({ answerText: event.target.value })}
                rows={4}
                className="w-full rounded-xl border border-slate-300 p-3 text-sm"
                placeholder="Nhập câu trả lời..."
              />
            </label>
          )}
        </div>

        <footer className="mt-8 flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-5">
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              disabled={busy || !answered(question)}
              onClick={() => updateAnswer({ selectedOptionKeys: [], answerText: '' })}
              className="rounded-xl border border-slate-300 px-4 py-2 text-sm font-medium disabled:opacity-50"
            >
              Xóa câu trả lời
            </button>
            <button type="button" disabled={reviewingId === question.testItemId} onClick={() => void toggleReview()} className="rounded-xl border border-slate-300 px-4 py-2 text-sm font-medium disabled:opacity-50">
              {reviewingId === question.testItemId ? 'Đang lưu...' : answer.markedForReview ? 'Bỏ đánh dấu xem lại' : 'Đánh dấu xem lại'}
            </button>
          </div>
          <p role="status" className="text-sm text-slate-600">
            {answer.saved ? 'Đã lưu' : 'Đang chờ lưu'}{!online ? ' · Mất kết nối' : ''}
          </p>
          <div className="flex gap-2">
            <button type="button" disabled={currentIndex === 0} onClick={() => setCurrentIndex((index) => index - 1)} className="rounded-xl border border-slate-300 px-4 py-2 text-sm disabled:opacity-40">
              Trước
            </button>
            {currentIndex < session.questions.length - 1 ? (
              <button type="button" onClick={() => setCurrentIndex((index) => index + 1)} className="rounded-xl bg-blue-700 px-4 py-2 text-sm font-semibold text-white">
                Tiếp theo
              </button>
            ) : (
              <button type="button" disabled={busy} onClick={() => void submit()} className="rounded-xl bg-blue-700 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">
                {busy ? 'Đang nộp...' : 'Nộp bài'}
              </button>
            )}
          </div>
        </footer>
      </section>

      <aside className="h-fit rounded-2xl border border-blue-100 bg-white p-5">
        <h3 className="font-semibold text-blue-950">Danh sách câu hỏi</h3>
        <p className="mt-1 text-sm text-slate-600">
          {session.attempt.answeredQuestions} đã trả lời · {session.attempt.totalQuestions} câu
        </p>
        <div className="mt-4 grid grid-cols-5 gap-2">
          {session.questions.map((item, index) => (
            <button
              key={item.testItemId}
              type="button"
              aria-label={`Câu ${index + 1}${answered(item) ? ', đã trả lời' : ', chưa trả lời'}`}
              onClick={() => setCurrentIndex(index)}
              className={`rounded-lg border px-2 py-2 text-sm ${
                index === currentIndex ? 'border-blue-700 bg-blue-700 text-white'
                  : item.markedForReview ? 'border-amber-300 bg-amber-50'
                    : answered(item) ? 'border-emerald-300 bg-emerald-50'
                      : 'border-slate-200 bg-white'
              }`}
            >
              {index + 1}
            </button>
          ))}
        </div>
      </aside>
    </div>
  );
}

function QuestionGroupStimulusView({ group }: {
  group: NonNullable<ExamQuestion['groupStimulus']>;
}) {
  const stimulus = group.stimulus;
  return (
    <aside
      className="my-6 rounded-xl border border-blue-100 bg-blue-50/50 p-4"
      data-question-group-version={group.groupVersionId}
      aria-label={`Ngữ liệu chung: ${group.title}`}
    >
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-semibold text-slate-900">{group.title}</h3>
        <span className="text-xs text-slate-600">
          Câu {group.itemOrder}/{group.itemCount} trong nhóm
        </span>
      </div>
      {group.stimulusType === 'PASSAGE' && (
        <>
          {stimulus.title && <h4 className="font-medium text-slate-800">{stimulus.title}</h4>}
          {stimulus.content && <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-slate-700">{stimulus.content}</p>}
        </>
      )}
      {group.stimulusType === 'IMAGE' && stimulus.path && (
        <figure>
          <img src={stimulus.path} alt={stimulus.altText ?? ''} className="max-h-96 max-w-full rounded-xl object-contain" />
          {stimulus.caption && <figcaption className="mt-2 text-sm text-slate-600">{stimulus.caption}</figcaption>}
        </figure>
      )}
      {group.stimulusType === 'TABLE' && stimulus.columns && stimulus.rows && (
        <div className="overflow-x-auto">
          <table className="min-w-full border-collapse text-left text-sm">
            <thead>
              <tr>{stimulus.columns.map((column) => (
                <th key={column.key} scope="col" className="border-b border-slate-300 px-3 py-2 font-semibold">
                  {column.label}
                </th>
              ))}</tr>
            </thead>
            <tbody>
              {stimulus.rows.map((row, rowIndex) => (
                <tr key={rowIndex}>
                  {row.map((cell, cellIndex) => (
                    <td key={`${rowIndex}-${cellIndex}`} className="border-b border-slate-200 px-3 py-2">
                      {cell === null ? '' : String(cell)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {group.stimulusType === 'CHART' && stimulus.labels && stimulus.series && (
        <div className="overflow-x-auto">
          <table className="min-w-full border-collapse text-left text-sm">
            <caption className="sr-only">{group.title} — dữ liệu biểu đồ</caption>
            <thead><tr>
              <th scope="col" className="border-b border-slate-300 px-3 py-2">Danh mục</th>
              {stimulus.series.map((series) => (
                <th key={series.name} scope="col" className="border-b border-slate-300 px-3 py-2">{series.name}</th>
              ))}
            </tr></thead>
            <tbody>
              {stimulus.labels.map((label, index) => (
                <tr key={`${label}-${index}`}>
                  <th scope="row" className="border-b border-slate-200 px-3 py-2 font-medium">{label}</th>
                  {stimulus.series?.map((series) => (
                    <td key={series.name} className="border-b border-slate-200 px-3 py-2">
                      {series.values[index]}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </aside>
  );
}

function formatTime(seconds: number) {
  return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`;
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
