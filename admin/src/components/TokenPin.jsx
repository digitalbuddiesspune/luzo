import { useId } from "react";

const TOKEN_GEM = {
  red: { light: "#ff5a5a", mid: "#e51414", dark: "#8f0000" },
  green: { light: "#3dff8a", mid: "#00c853", dark: "#006b22" },
  yellow: { light: "#ffe566", mid: "#ffc107", dark: "#a87a00" },
  blue: { light: "#5cbcff", mid: "#1e88e5", dark: "#004a9f" },
};

export function TokenPin({
  color = "blue",
  className = "",
  style,
  width = 100,
  height = 125,
  ...props
}) {
  const uid = useId().replace(/:/g, "");
  const gem = TOKEN_GEM[color] || TOKEN_GEM.blue;
  const bodyId = `token-body-${uid}`;
  const gemId = `token-gem-${uid}`;
  const glossId = `token-gloss-${uid}`;
  const shadowId = `token-shadow-${uid}`;

  return (
    <svg
      className={className}
      style={style}
      width={width}
      height={height}
      viewBox="0 0 100 125"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
      focusable="false"
      {...props}
    >
      <defs>
        <linearGradient id={bodyId} x1="18" y1="8" x2="88" y2="118" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#9a9d9f" />
          <stop offset="0.18" stopColor="#f7f9fa" />
          <stop offset="0.45" stopColor="#e6e9ea" />
          <stop offset="0.72" stopColor="#b8bcbe" />
          <stop offset="1" stopColor="#6f7375" />
        </linearGradient>
        <radialGradient id={gemId} cx="38%" cy="30%" r="72%">
          <stop offset="0" stopColor={gem.light} />
          <stop offset="0.55" stopColor={gem.mid} />
          <stop offset="1" stopColor={gem.dark} />
        </radialGradient>
        <linearGradient id={glossId} x1="34" y1="18" x2="62" y2="52" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#ffffff" stopOpacity="0.85" />
          <stop offset="0.55" stopColor="#ffffff" stopOpacity="0.18" />
          <stop offset="1" stopColor="#ffffff" stopOpacity="0" />
        </linearGradient>
        <filter
          id={shadowId}
          x="-35%"
          y="-20%"
          width="170%"
          height="170%"
          colorInterpolationFilters="sRGB"
        >
          <feDropShadow dx="0" dy="3" stdDeviation="2.4" floodColor="#000" floodOpacity="0.42" />
        </filter>
      </defs>
      <path
        d="M50 4C25 4 8 20 8 43C8 68 29 96 47 119C48.7 121.2 51.3 121.2 53 119C71 96 92 68 92 43C92 20 75 4 50 4Z"
        fill={`url(#${bodyId})`}
        stroke="#1a1a1a"
        strokeWidth="2.1"
        filter={`url(#${shadowId})`}
      />
      <path
        d="M50 10C30 10 15 23 15 42C15 58 28 80 42 100C46 106 54 106 58 100C72 80 85 58 85 42C85 23 70 10 50 10Z"
        fill="#ffffff"
        fillOpacity="0.14"
      />
      <circle
        cx="50"
        cy="42"
        r="24.5"
        fill={`url(#${gemId})`}
        stroke="#121212"
        strokeWidth="2"
      />
      <circle
        cx="50"
        cy="42"
        r="18.5"
        fill="none"
        stroke="#ffffff"
        strokeOpacity="0.22"
        strokeWidth="1.4"
      />
      <ellipse cx="41" cy="33" rx="9.5" ry="6.5" fill={`url(#${glossId})`} />
      <circle cx="58" cy="48" r="3.2" fill="#ffffff" fillOpacity="0.18" />
    </svg>
  );
}
