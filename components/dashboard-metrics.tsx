export type DashboardMetric = {
  label: string;
  value: string | number;
  description: string;
};

export function DashboardMetrics({ metrics }: { metrics: DashboardMetric[] }) {
  return (
    <section aria-label="Thống kê tổng quan" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      {metrics.map((metric) => (
        <article
          key={metric.label}
          className="rounded-3xl border border-blue-100 bg-white p-6 shadow-sm shadow-blue-900/5"
        >
          <p className="text-sm font-medium text-slate-500">{metric.label}</p>
          <p className="mt-3 text-3xl font-bold tracking-tight text-blue-950">{metric.value}</p>
          <p className="mt-2 text-sm text-slate-500">{metric.description}</p>
        </article>
      ))}
    </section>
  );
}
