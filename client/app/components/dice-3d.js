"use client";

import { useEffect, useRef } from "react";

const DICE_PIP_LAYOUTS = {
  1: [[2, 2]],
  2: [
    [1, 1],
    [3, 3],
  ],
  3: [
    [1, 1],
    [2, 2],
    [3, 3],
  ],
  4: [
    [1, 1],
    [1, 3],
    [3, 1],
    [3, 3],
  ],
  5: [
    [1, 1],
    [1, 3],
    [2, 2],
    [3, 1],
    [3, 3],
  ],
  6: [
    [1, 1],
    [1, 3],
    [2, 1],
    [2, 3],
    [3, 1],
    [3, 3],
  ],
};

export const DICE_FACE_ROTATIONS = {
  1: { rotateX: 0, rotateY: 0 },
  2: { rotateX: 0, rotateY: -90 },
  3: { rotateX: -90, rotateY: 0 },
  4: { rotateX: 90, rotateY: 0 },
  5: { rotateX: 0, rotateY: 90 },
  6: { rotateX: 0, rotateY: 180 },
};

export function rollDice(sides = 6) {
  const safeSides = Math.max(1, Math.floor(Number(sides) || 6));
  return Math.floor(Math.random() * safeSides) + 1;
}

export function clampDiceValue(value, fallback = 1) {
  const numericValue = Number(value);

  if (!Number.isFinite(numericValue)) {
    return fallback;
  }

  return Math.min(6, Math.max(1, Math.round(numericValue)));
}

function getDiceTransform(value, extraSpins = 0) {
  const rotation = DICE_FACE_ROTATIONS[value];
  const spinX = extraSpins * 360;
  const spinY = extraSpins * 360;

  return `rotateX(${rotation.rotateX + spinX}deg) rotateY(${rotation.rotateY + spinY}deg)`;
}

function DiceDots({ value }) {
  const pipSet = new Set(
    (DICE_PIP_LAYOUTS[value] ?? DICE_PIP_LAYOUTS[1]).map(([row, col]) => `${row}-${col}`),
  );

  return (
    <>
      {Array.from({ length: 9 }, (_, index) => {
        const row = Math.floor(index / 3) + 1;
        const col = (index % 3) + 1;

        return (
          <span
            key={`${row}-${col}`}
            className={pipSet.has(`${row}-${col}`) ? "dice-3d-dot" : "dice-3d-dot is-empty"}
          />
        );
      })}
    </>
  );
}

export function Dice3D({
  value = 1,
  rolling = false,
  active = false,
  className = "",
  rollDurationMs = 700,
}) {
  const cubeRef = useRef(null);
  const safeValue = clampDiceValue(value);
  const wasRollingRef = useRef(rolling);
  const pendingValueRef = useRef(safeValue);

  useEffect(() => {
    pendingValueRef.current = safeValue;
  }, [safeValue]);

  useEffect(() => {
    const cube = cubeRef.current;

    if (!cube) {
      return undefined;
    }

    cube.style.setProperty("--dice-settle-duration", `${rollDurationMs}ms`);

    if (rolling) {
      cube.classList.add("dice-3d-cube--rolling");
      cube.classList.remove("dice-3d-cube--settling");
      cube.style.transform = "";
      wasRollingRef.current = true;
      return undefined;
    }

    cube.classList.remove("dice-3d-cube--rolling");

    const settleValue = clampDiceValue(pendingValueRef.current);
    const settleFromRoll = wasRollingRef.current;
    wasRollingRef.current = false;

    if (settleFromRoll) {
      cube.classList.add("dice-3d-cube--settling");
      cube.style.transform = getDiceTransform(settleValue, 2);

      const settleTimeoutId = window.setTimeout(() => {
        cube.classList.remove("dice-3d-cube--settling");
        cube.style.transform = getDiceTransform(settleValue);
      }, rollDurationMs);

      return () => window.clearTimeout(settleTimeoutId);
    }

    cube.style.transform = getDiceTransform(settleValue);
    return undefined;
  }, [rolling, rollDurationMs, safeValue]);

  return (
    <div
      className={`dice-3d ${active ? "is-active" : ""} ${rolling ? "is-rolling" : ""} ${className}`.trim()}
    >
      <div className="dice-3d-scene">
        <div ref={cubeRef} className="dice-3d-cube">
          {[1, 2, 3, 4, 5, 6].map((faceValue) => (
            <div
              key={faceValue}
              className={`dice-3d-face dice-3d-face-${faceValue}`}
            >
              <DiceDots value={faceValue} />
            </div>
          ))}
        </div>
      </div>
      <span className="dice-3d-sr-label">{`Dice showing ${safeValue}`}</span>
    </div>
  );
}
