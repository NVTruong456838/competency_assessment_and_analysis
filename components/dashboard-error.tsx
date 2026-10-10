export function DashboardError() {
  return (
    <section
      role="alert"
      className="rounded-3xl border border-rose-200 bg-rose-50 p-6 text-rose-900"
    >
      <h2 className="font-semibold">Không tải được dữ liệu dashboard</h2>
      <p className="mt-2 text-sm">
        Dịch vụ hoặc truy vấn database đang gặp sự cố. Vui lòng thử tải lại trang sau.
      </p>
    </section>
  );
}
