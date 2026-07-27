export function formatAmount(amount, currency = "INR") {
  const safeAmount = Number.isFinite(amount) ? amount : 0;
  return `${safeAmount.toLocaleString("en-IN")} ${currency}`;
}

export function formatProfitLoss(amount, currency = "INR") {
  if (amount === null || amount === undefined) {
    return "—";
  }

  const prefix = amount > 0 ? "+" : "";
  return `${prefix}${formatAmount(amount, currency)}`;
}

export function formatDate(value) {
  if (!value) {
    return "—";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return "—";
  }

  return date.toLocaleString("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function formatPercentFromBasisPoints(basisPoints) {
  return `${(basisPoints / 100).toFixed(2)}%`;
}

export function profitLossClassName(amount) {
  if (amount === null || amount === undefined) {
    return "text-[var(--color-muted)]";
  }

  if (amount > 0) {
    return "text-[var(--accent)]";
  }

  if (amount < 0) {
    return "text-rose-600";
  }

  return "text-[var(--color-ink)]";
}
