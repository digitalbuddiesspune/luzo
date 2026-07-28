export function formatLocalDateISO(date = new Date()) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function getDaysAgoLocalISO(daysAgo) {
  const date = new Date();
  date.setDate(date.getDate() - daysAgo);
  return formatLocalDateISO(date);
}

export const DATE_PRESETS = [
  { id: "all", label: "All Time", from: "", to: "" },
  {
    id: "today",
    label: "Today",
    getRange: () => {
      const today = formatLocalDateISO();
      return { from: today, to: today };
    },
  },
  {
    id: "yesterday",
    label: "Yesterday",
    getRange: () => {
      const yesterday = getDaysAgoLocalISO(1);
      return { from: yesterday, to: yesterday };
    },
  },
  {
    id: "7d",
    label: "Last 7 Days",
    getRange: () => ({
      from: getDaysAgoLocalISO(6),
      to: formatLocalDateISO(),
    }),
  },
  {
    id: "30d",
    label: "Last 30 Days",
    getRange: () => ({
      from: getDaysAgoLocalISO(29),
      to: formatLocalDateISO(),
    }),
  },
];

export function resolveDatePresetRange(presetId) {
  const preset = DATE_PRESETS.find((item) => item.id === presetId);
  if (!preset) {
    return { from: "", to: "" };
  }

  if (preset.getRange) {
    return preset.getRange();
  }

  return { from: preset.from, to: preset.to };
}

export function detectActiveDatePreset(dateFrom, dateTo) {
  if (!dateFrom && !dateTo) {
    return "all";
  }

  for (const preset of DATE_PRESETS) {
    if (preset.id === "all") {
      continue;
    }

    const range = resolveDatePresetRange(preset.id);
    if (range.from === dateFrom && range.to === dateTo) {
      return preset.id;
    }
  }

  return "custom";
}
