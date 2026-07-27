"use client";

/* eslint-disable @next/next/no-img-element */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import {
  PLAY_ROUTES,
  cloneMockBootState,
  createPlayRouteState,
  mockBootState,
} from "@/app/lib/mock-state";
import { IS_FRIENDS_MODE_VISIBLE } from "@/app/lib/features";
import { Dice3D } from "@/app/components/dice-3d";
import {
  createPrivateRoom as createPrivateRoomRequest,
  ensureGuestSession,
  fetchMatchSnapshot,
  fetchPrivateRoomState,
  fetchWalletOverview,
  isOperatorPlatformEnabled,
  OPERATOR_PLATFORM_ACCESS_MESSAGE,
  joinPrivateRoom as joinPrivateRoomRequest,
  joinOnlineMatch,
  leavePrivateRoom,
  leaveOnlineRoom,
  normalizeMatchSnapshot,
  normalizeWalletResponse,
  rollMatchDice,
  startPrivateRoom as startPrivateRoomRequest,
  subscribeOperatorGatewayLogs,
  submitMatchMove,
  toWebSocketUrl,
  transferPrivateRoomHost,
} from "@/app/lib/server-game";

const BOARD_PATH = [
  [6, 1],
  [6, 2],
  [6, 3],
  [6, 4],
  [6, 5],
  [5, 6],
  [4, 6],
  [3, 6],
  [2, 6],
  [1, 6],
  [0, 6],
  [0, 7],
  [0, 8],
  [1, 8],
  [2, 8],
  [3, 8],
  [4, 8],
  [5, 8],
  [6, 9],
  [6, 10],
  [6, 11],
  [6, 12],
  [6, 13],
  [6, 14],
  [7, 14],
  [8, 14],
  [8, 13],
  [8, 12],
  [8, 11],
  [8, 10],
  [8, 9],
  [9, 8],
  [10, 8],
  [11, 8],
  [12, 8],
  [13, 8],
  [14, 8],
  [14, 7],
  [14, 6],
  [13, 6],
  [12, 6],
  [11, 6],
  [10, 6],
  [9, 6],
  [8, 5],
  [8, 4],
  [8, 3],
  [8, 2],
  [8, 1],
  [8, 0],
  [7, 0],
  [6, 0],
];

const START_OFFSETS = {
  red: 0,
  green: 13,
  yellow: 26,
  blue: 39,
};

const YARD_POSITIONS = {
  red: [
    [1, 1],
    [1, 4],
    [4, 1],
    [4, 4],
  ],
  green: [
    [1, 10],
    [1, 13],
    [4, 10],
    [4, 13],
  ],
  yellow: [
    [10, 10],
    [10, 13],
    [13, 10],
    [13, 13],
  ],
  blue: [
    [10, 1],
    [10, 4],
    [13, 1],
    [13, 4],
  ],
};

const HOME_LANES = {
  red: [
    [7, 1],
    [7, 2],
    [7, 3],
    [7, 4],
    [7, 5],
  ],
  green: [
    [1, 7],
    [2, 7],
    [3, 7],
    [4, 7],
    [5, 7],
  ],
  yellow: [
    [7, 13],
    [7, 12],
    [7, 11],
    [7, 10],
    [7, 9],
  ],
  blue: [
    [13, 7],
    [12, 7],
    [11, 7],
    [10, 7],
    [9, 7],
  ],
};

const SAFE_STAR_ASSETS = {
  red: "/assets/star.png",
  green: "/assets/star.png",
  yellow: "/assets/star.png",
  blue: "/assets/star.png",
};

const TOKEN_ASSETS = {
  red: "/assets/ludo_pin_red.svg",
  green: "/assets/ludo_pin_green.svg",
  yellow: "/assets/ludo_pin_yellow.svg",
  blue: "/assets/ludo_pin_blue.svg",
};

const HOME_ASSETS = {
  red: "/assets/BoardRedHouse.png",
  green: "/assets/BoardGreenHouse.png",
  yellow: "/assets/BoardYellowHouse.png",
  blue: "/assets/BoardBlueHouse.png",
};

const BOARD_ARROWS = {
  "0-7": "/assets/BoardArrow.png",
  "7-0": "/assets/BoardArrow.png",
  "7-14": "/assets/BoardArrow.png",
  "14-7": "/assets/BoardArrow.png",
};

const START_CELLS = {
  "6-1": "red",
  "1-8": "green",
  "8-13": "yellow",
  "13-6": "blue",
};

const STAR_SAFE_CELLS = {
  "2-6": "green",
  "6-12": "yellow",
  "12-8": "blue",
  "8-2": "red",
};

const BOARD_PATH_INDEX = new Map(
  BOARD_PATH.map(([row, col], index) => [`${row}-${col}`, index]),
);

const HOME_LANE_LOOKUP = new Map(
  Object.entries(HOME_LANES).flatMap(([color, cells]) =>
    cells.map(([row, col]) => [`${row}-${col}`, color]),
  ),
);

const YARD_LOOKUP = new Map(
  Object.entries(YARD_POSITIONS).flatMap(([color, cells]) =>
    cells.map(([row, col]) => [`${row}-${col}`, color]),
  ),
);

const BOT_NAME_BY_COLOR = {
  green: "Aarav",
  yellow: "Meera",
  blue: "Kabir",
};
const TURN_ROLL_DELAY_MS = 500;
const BOT_MOVE_DELAY_MS = 850;
const TURN_ADVANCE_DELAY_MS = 750;
const DICE_PASS_DELAY_MS = 500;
const TURN_TICK_MS = 100;
const ONLINE_SOCKET_RECONNECT_DELAY_MS = 1500;
const ONLINE_LOBBY_POLL_MS = 2000;
const ONLINE_LOBBY_FAST_POLL_MS = 400;
const MATCH_SNAPSHOT_FALLBACK_POLL_MS = 5000;

function resolveOnlineLobbyPollDelayMs(room, match) {
  if (match) {
    return ONLINE_LOBBY_POLL_MS;
  }

  if (!room) {
    return ONLINE_LOBBY_POLL_MS;
  }

  const isStarting = String(room.status || "").toUpperCase() === "STARTING";
  const waitingDeadlineMs = room.waitingDeadlineAt
    ? new Date(room.waitingDeadlineAt).getTime()
    : null;
  const isTimerElapsed =
    waitingDeadlineMs == null || waitingDeadlineMs <= Date.now();

  return isStarting || isTimerElapsed
    ? ONLINE_LOBBY_FAST_POLL_MS
    : ONLINE_LOBBY_POLL_MS;
}

function isOnlineLobbyStarting(room) {
  return String(room?.status || "").toUpperCase() === "STARTING";
}
const TOKEN_STEP_ANIMATION_MS = 200;
const CAPTURE_RETURN_STEP_MS = 28;
const CAPTURE_RETURN_SAMPLES_PER_SEGMENT = 4;
const TURN_WARNING_THRESHOLD_SECONDS = 5;
const DICE_ROLL_MIN_SPIN_MS = 700;
const MAIN_PATH_LAST_PROGRESS = 50;
const HOME_LANE_START_PROGRESS = 51;
const HOME_LANE_LAST_PROGRESS = 55;
const FINISHED_PROGRESS = 56;
const SAFE_CELL_KEYS = new Set([
  ...Object.keys(START_CELLS),
  ...Object.keys(STAR_SAFE_CELLS),
]);

const soundController = {
  context: null,
  muted: false,
  diceRollAudio: null,
  matchStartAudio: null,
  tokenCaptureAudio: null,
  lobbyMusicAudio: null,
  lobbyMusicActive: false,

  setMuted(nextMuted) {
    this.muted = nextMuted;

    if (nextMuted) {
      this.pauseLobbyMusic();
      return;
    }

    if (this.lobbyMusicActive) {
      this.playLobbyMusic();
    }
  },

  ensureContext() {
    if (typeof window === "undefined") {
      return null;
    }

    const AudioContextCtor = window.AudioContext || window.webkitAudioContext;

    if (!AudioContextCtor) {
      return null;
    }

    if (!this.context) {
      this.context = new AudioContextCtor();
    }

    if (this.context.state === "suspended") {
      this.context.resume().catch(() => {});
    }

    return this.context;
  },

  pulse({
    frequency,
    startTime,
    duration = 0.08,
    gain = 0.035,
    type = "sine",
    slideTo = null,
  }) {
    if (this.muted) {
      return;
    }

    const context = this.ensureContext();

    if (!context) {
      return;
    }

    const oscillator = context.createOscillator();
    const gainNode = context.createGain();
    const safeStartTime = startTime ?? context.currentTime;

    oscillator.type = type;
    oscillator.frequency.setValueAtTime(frequency, safeStartTime);

    if (slideTo) {
      oscillator.frequency.exponentialRampToValueAtTime(
        slideTo,
        safeStartTime + duration,
      );
    }

    gainNode.gain.setValueAtTime(0.0001, safeStartTime);
    gainNode.gain.exponentialRampToValueAtTime(
      Math.max(gain, 0.0002),
      safeStartTime + 0.01,
    );
    gainNode.gain.exponentialRampToValueAtTime(
      0.0001,
      safeStartTime + duration,
    );

    oscillator.connect(gainNode);
    gainNode.connect(context.destination);
    oscillator.start(safeStartTime);
    oscillator.stop(safeStartTime + duration + 0.02);
  },

  click() {
    const context = this.ensureContext();

    if (!context) {
      return;
    }

    const start = context.currentTime;
    this.pulse({
      frequency: 760,
      slideTo: 560,
      duration: 0.05,
      gain: 0.085,
      type: "triangle",
      startTime: start,
    });
    this.pulse({
      frequency: 920,
      slideTo: 700,
      duration: 0.03,
      gain: 0.055,
      type: "sine",
      startTime: start + 0.012,
    });
  },

  tick() {
    const context = this.ensureContext();

    if (!context) {
      return;
    }

    this.pulse({
      frequency: 1180,
      slideTo: 960,
      duration: 0.05,
      gain: 0.022,
      type: "square",
      startTime: context.currentTime,
    });
  },

  turnChange() {
    const context = this.ensureContext();

    if (!context) {
      return;
    }

    const start = context.currentTime;
    [420, 560].forEach((frequency, index) => {
      this.pulse({
        frequency,
        duration: 0.08,
        gain: 0.03,
        type: "triangle",
        startTime: start + index * 0.07,
      });
    });
  },

  diceRollSpin() {
    if (this.muted || typeof window === "undefined") {
      return;
    }

    if (!this.diceRollAudio) {
      this.diceRollAudio = new window.Audio(
        "/sounds/frontend_public_sounds_DiceRolling.mp3",
      );
      this.diceRollAudio.preload = "auto";
    }

    this.diceRollAudio.currentTime = 0;
    this.diceRollAudio.play().catch(() => {});
  },

  diceRoll() {
    const context = this.ensureContext();

    if (!context || this.muted) {
      return;
    }

    const start = context.currentTime;
    this.pulse({
      frequency: 300,
      slideTo: 155,
      duration: 0.16,
      gain: 0.036,
      type: "triangle",
      startTime: start,
    });
    this.pulse({
      frequency: 540,
      slideTo: 360,
      duration: 0.11,
      gain: 0.015,
      type: "sine",
      startTime: start + 0.04,
    });
  },

  tokenStep() {
    const context = this.ensureContext();

    if (!context) {
      return;
    }

    const start = context.currentTime;
    this.pulse({
      frequency: 210,
      slideTo: 150,
      duration: 0.055,
      gain: 0.045,
      type: "square",
      startTime: start,
    });
    this.pulse({
      frequency: 480,
      slideTo: 320,
      duration: 0.04,
      gain: 0.028,
      type: "triangle",
      startTime: start + 0.018,
    });
  },

  homeArrival() {
    const context = this.ensureContext();

    if (!context) {
      return;
    }

    const start = context.currentTime;
    [620, 780, 980].forEach((frequency, index) => {
      this.pulse({
        frequency,
        duration: 0.12,
        gain: 0.026,
        type: "triangle",
        startTime: start + index * 0.075,
      });
    });
  },

  matchWin() {
    const context = this.ensureContext();

    if (!context) {
      return;
    }

    const start = context.currentTime;
    [523, 659, 784, 1046, 1318].forEach((frequency, index) => {
      this.pulse({
        frequency,
        duration: 0.2,
        gain: 0.045 - index * 0.004,
        type: index % 2 === 0 ? "triangle" : "sine",
        startTime: start + index * 0.12,
      });
    });
    this.pulse({
      frequency: 1568,
      slideTo: 2093,
      duration: 0.35,
      gain: 0.03,
      type: "sine",
      startTime: start + 0.62,
    });
  },

  matchLose() {
    const context = this.ensureContext();

    if (!context) {
      return;
    }

    const start = context.currentTime;
    [440, 330, 250].forEach((frequency, index) => {
      this.pulse({
        frequency,
        duration: 0.16,
        gain: 0.028,
        type: "sawtooth",
        startTime: start + index * 0.11,
      });
    });
  },

  matchStart() {
    if (this.muted || typeof window === "undefined") {
      return;
    }

    if (!this.matchStartAudio) {
      this.matchStartAudio = new window.Audio("/sounds/ludoking_start.mp3");
      this.matchStartAudio.preload = "auto";
    }

    this.matchStartAudio.currentTime = 0;
    this.matchStartAudio.play().catch(() => {});
  },

  tokenCapture() {
    if (this.muted || typeof window === "undefined") {
      return;
    }

    if (!this.tokenCaptureAudio) {
      this.tokenCaptureAudio = new window.Audio("/sounds/skelly.mp3");
      this.tokenCaptureAudio.preload = "auto";
    }

    this.tokenCaptureAudio.currentTime = 0;
    this.tokenCaptureAudio.play().catch(() => {});
  },

  playLobbyMusic() {
    if (this.muted || typeof window === "undefined" || !this.lobbyMusicActive) {
      return;
    }

    if (!this.lobbyMusicAudio) {
      this.lobbyMusicAudio = new window.Audio("/sounds/ludo_king.mp3");
      this.lobbyMusicAudio.preload = "auto";
      this.lobbyMusicAudio.loop = true;
      this.lobbyMusicAudio.volume = 0.45;
    }

    this.lobbyMusicAudio.play().catch(() => {});
  },

  pauseLobbyMusic() {
    if (this.lobbyMusicAudio) {
      this.lobbyMusicAudio.pause();
    }
  },

  setLobbyMusicActive(active) {
    this.lobbyMusicActive = active;

    if (active) {
      this.playLobbyMusic();
      return;
    }

    this.pauseLobbyMusic();

    if (this.lobbyMusicAudio) {
      this.lobbyMusicAudio.currentTime = 0;
    }
  },

  resumeLobbyMusicIfNeeded() {
    if (this.lobbyMusicActive) {
      this.playLobbyMusic();
    }
  },
};

function resolveTokenCell(color, progress, tokenIndex) {
  if (progress === -1) {
    return YARD_POSITIONS[color][tokenIndex];
  }

  if (progress >= 0 && progress <= MAIN_PATH_LAST_PROGRESS) {
    return BOARD_PATH[(START_OFFSETS[color] + progress) % 52];
  }

  if (
    progress >= HOME_LANE_START_PROGRESS &&
    progress <= HOME_LANE_LAST_PROGRESS
  ) {
    return HOME_LANES[color][progress - HOME_LANE_START_PROGRESS];
  }

  return [7, 7];
}

function getCellCenterPercent(row, col) {
  return {
    left: ((col + 0.5) / 15) * 100,
    top: ((row + 0.5) / 15) * 100,
  };
}

function getYardSlotCenterPercent(color, tokenIndex) {
  const houseOriginByColor = {
    red: { left: 0, top: 0 },
    green: { left: 60, top: 0 },
    yellow: { left: 60, top: 60 },
    blue: { left: 0, top: 60 },
  };
  const slotCenterByIndex = [
    { left: 31.9, top: 31.9 },
    { left: 68.1, top: 31.9 },
    { left: 31.9, top: 68.1 },
    { left: 68.1, top: 68.1 },
  ];

  const houseOrigin = houseOriginByColor[color] ?? houseOriginByColor.red;
  const slotCenter = slotCenterByIndex[tokenIndex] ?? slotCenterByIndex[0];

  return {
    left: houseOrigin.left + slotCenter.left * 0.4,
    top: houseOrigin.top + slotCenter.top * 0.4,
  };
}

function samplePathPositions(anchorPositions) {
  if (anchorPositions.length <= 1) {
    return anchorPositions;
  }

  const sampledPositions = [anchorPositions[0]];

  for (let anchorIndex = 0; anchorIndex < anchorPositions.length - 1; anchorIndex += 1) {
    const currentAnchor = anchorPositions[anchorIndex];
    const nextAnchor = anchorPositions[anchorIndex + 1];

    for (
      let sampleIndex = 1;
      sampleIndex <= CAPTURE_RETURN_SAMPLES_PER_SEGMENT;
      sampleIndex += 1
    ) {
      const progress = sampleIndex / CAPTURE_RETURN_SAMPLES_PER_SEGMENT;
      sampledPositions.push({
        left:
          currentAnchor.left + (nextAnchor.left - currentAnchor.left) * progress,
        top:
          currentAnchor.top + (nextAnchor.top - currentAnchor.top) * progress,
      });
    }
  }

  return sampledPositions;
}

function rollDiceValue(consecutiveSixCount = 0) {
  if (consecutiveSixCount >= 2) {
    return Math.floor(Math.random() * 5) + 1;
  }

  return Math.floor(Math.random() * 6) + 1;
}

function canMoveToken(progress, diceValue) {
  if (progress === -1) {
    return diceValue === 6;
  }

  if (progress >= FINISHED_PROGRESS) {
    return false;
  }

  return progress + diceValue <= FINISHED_PROGRESS;
}

function getMovableTokenIndexes(player, diceValue) {
  return player.tokens.reduce((movable, progress, tokenIndex) => {
    if (canMoveToken(progress, diceValue)) {
      movable.push(tokenIndex);
    }

    return movable;
  }, []);
}

function isTokenSelectable(selectableTokenIndexes, tokenIndex) {
  if (!selectableTokenIndexes?.length) {
    return false;
  }

  const normalizedIndex = Number(tokenIndex);
  return selectableTokenIndexes.some(
    (index) => Number(index) === normalizedIndex,
  );
}

function resolveForcedAutoMoveToken(selectableTokenIndexes) {
  // Only auto-play when there is exactly one legal move.
  // If a six can also open yard tokens, let the player choose.
  if (!selectableTokenIndexes || selectableTokenIndexes.length !== 1) {
    return null;
  }

  return Number(selectableTokenIndexes[0]);
}

function buildOptimisticTokenMove(match, tokenIndex, userId) {
  if (
    !match ||
    match.phase !== "awaiting-move" ||
    match.currentTurnUserId !== userId ||
    !isTokenSelectable(match.selectableTokenIndexes, tokenIndex)
  ) {
    return null;
  }

  const playerIndex = match.players.findIndex((player) => player.id === userId);

  if (playerIndex < 0) {
    return null;
  }

  const diceValue = Number(match.dice);
  const currentProgress = match.players[playerIndex].tokens[tokenIndex];

  if (!Number.isFinite(diceValue) || diceValue < 1 || !canMoveToken(currentProgress, diceValue)) {
    return null;
  }

  const nextProgress = currentProgress === -1 ? 0 : currentProgress + diceValue;
  const players = match.players.map((player, index) => {
    if (index !== playerIndex) {
      return {
        ...player,
        tokens: [...player.tokens],
      };
    }

    const tokens = [...player.tokens];
    tokens[tokenIndex] = nextProgress;
    return {
      ...player,
      tokens,
    };
  });

  return {
    ...match,
    players,
    selectableTokenIndexes: [],
    // Bump client-side sequence so stale equal/older snapshots cannot
    // temporarily overwrite this optimistic move and cause visual reverts.
    sequence:
      typeof match.sequence === "number" ? match.sequence + 1 : match.sequence,
  };
}

function getBoardCellKey(color, progress, tokenIndex = 0) {
  const [row, col] = resolveTokenCell(color, progress, tokenIndex);
  return `${row}-${col}`;
}

function prependMatchEvent(events, actor, detail) {
  return [
    {
      id: `evt_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
      actor,
      detail,
    },
    ...events,
  ].slice(0, 5);
}

function normalizeMatchPlayers(players, userPlayerId) {
  return players.map((player, index) => ({
    ...player,
    isBot: player.id !== userPlayerId || index !== 0,
    name:
      player.id === userPlayerId && index === 0
        ? player.name
        : (BOT_NAME_BY_COLOR[player.color] ?? "Guest Player"),
  }));
}

const PLAYER_CARD_COLOR_ORDER = ["red", "green", "yellow", "blue"];
const PLAYER_CARD_BOTTOM_RIGHT_COLOR = "yellow";
const TURN_COLOR_ORDER = ["red", "blue", "yellow", "green"];

function sortPlayersByTurnOrder(players) {
  return [...players].sort((left, right) => {
    const leftIndex = TURN_COLOR_ORDER.indexOf(left.color);
    const rightIndex = TURN_COLOR_ORDER.indexOf(right.color);

    return (
      (leftIndex === -1 ? TURN_COLOR_ORDER.length : leftIndex) -
      (rightIndex === -1 ? TURN_COLOR_ORDER.length : rightIndex)
    );
  });
}

function resolvePlayerCardRows(players, userPlayerId) {
  const playerByColor = new Map(
    players.map((player) => [player.color, player]),
  );
  const userPlayer = players.find((player) => player.id === userPlayerId);

  if (!userPlayer) {
    return {
      topPlayers: [
        playerByColor.get(PLAYER_CARD_COLOR_ORDER[0]) ?? null,
        playerByColor.get(PLAYER_CARD_COLOR_ORDER[1]) ?? null,
      ],
      bottomPlayers: [
        playerByColor.get(PLAYER_CARD_COLOR_ORDER[3]) ?? null,
        playerByColor.get(PLAYER_CARD_COLOR_ORDER[2]) ?? null,
      ],
    };
  }

  const userColorIndex = PLAYER_CARD_COLOR_ORDER.indexOf(userPlayer.color);
  const rotatedColors = PLAYER_CARD_COLOR_ORDER.map(
    (_, slotIndex) =>
      PLAYER_CARD_COLOR_ORDER[
        (userColorIndex +
          slotIndex -
          PLAYER_CARD_COLOR_ORDER.indexOf(PLAYER_CARD_BOTTOM_RIGHT_COLOR) +
          PLAYER_CARD_COLOR_ORDER.length) %
          PLAYER_CARD_COLOR_ORDER.length
      ],
  );

  return {
    topPlayers: [
      playerByColor.get(rotatedColors[0]) ?? null,
      playerByColor.get(rotatedColors[1]) ?? null,
    ],
    bottomPlayers: [
      playerByColor.get(rotatedColors[3]) ?? null,
      playerByColor.get(rotatedColors[2]) ?? null,
    ],
  };
}

function getBoardRotationQuarterTurns(userColor) {
  const userColorIndex = PLAYER_CARD_COLOR_ORDER.indexOf(userColor);
  const targetIndex = PLAYER_CARD_COLOR_ORDER.indexOf(
    PLAYER_CARD_BOTTOM_RIGHT_COLOR,
  );

  if (userColorIndex === -1 || targetIndex === -1) {
    return 0;
  }

  return (
    (targetIndex - userColorIndex + PLAYER_CARD_COLOR_ORDER.length) %
    PLAYER_CARD_COLOR_ORDER.length
  );
}

function waitForMinimumDuration(startedAt, minimumDurationMs) {
  const remainingMs = Math.max(0, minimumDurationMs - (Date.now() - startedAt));

  if (remainingMs === 0) {
    return Promise.resolve();
  }

  return new Promise((resolve) => {
    window.setTimeout(resolve, remainingMs);
  });
}

function scheduleMatchSnapshotSync({
  timeoutRef,
  sessionToken,
  matchId,
  minimumSequence = 0,
  setMatch,
  enabled = true,
  delays = [750, 2000],
}) {
  if (!enabled || !sessionToken || !matchId) {
    return;
  }

  if (timeoutRef.current) {
    window.clearTimeout(timeoutRef.current);
    timeoutRef.current = null;
  }

  let delayIndex = 0;

  const syncNext = () => {
    timeoutRef.current = window.setTimeout(async () => {
      try {
        const latestMatch = await fetchMatchSnapshot(sessionToken, matchId);
        const normalizedMatch = normalizeMatchSnapshot(latestMatch);

        if (normalizedMatch.sequence >= minimumSequence) {
          applyFreshMatch(setMatch, normalizedMatch);
        }
      } catch {}

      delayIndex += 1;

      if (delayIndex < delays.length) {
        syncNext();
      } else {
        timeoutRef.current = null;
      }
    }, delays[delayIndex]);
  };

  syncNext();
}

function countActiveHumanPlayers(players = []) {
  return players.filter((player) => !player.isBot && !player.isAbandoned).length;
}

function hasTwoPlayerHumanMatch(match) {
  return (match?.players ?? []).filter((player) => !player.isBot).length === 2;
}

function didOpponentAbandonMatch(match, userPlayerId) {
  if (!match || !userPlayerId) {
    return false;
  }

  return (match.players ?? []).some(
    (player) =>
      !player.isBot && player.id !== userPlayerId && player.isAbandoned,
  );
}

function getLeaveActiveMatchMessage({ isPrivate, activeHumanCount }) {
  if (activeHumanCount === 2) {
    return "If you leave now, your opponent wins the match.";
  }

  return isPrivate
    ? "If you leave now, you will abandon the game. The match may continue with the remaining players."
    : "If you leave now, you will abandon the game. The match may continue without you.";
}

function useOpponentForfeitSnapshotSync({
  match,
  userPlayerId,
  sessionToken,
  setMatch,
  timeoutRef,
}) {
  useEffect(() => {
    if (
      !sessionToken ||
      !match?.id ||
      !userPlayerId ||
      match.phase === "finished" ||
      !hasTwoPlayerHumanMatch(match) ||
      !didOpponentAbandonMatch(match, userPlayerId)
    ) {
      return undefined;
    }

    scheduleMatchSnapshotSync({
      timeoutRef,
      sessionToken,
      matchId: match.id,
      minimumSequence: match.sequence ?? 0,
      setMatch,
      enabled: true,
      delays: [200, 600, 1500, 3500],
    });

    return () => {
      if (timeoutRef.current) {
        window.clearTimeout(timeoutRef.current);
        timeoutRef.current = null;
      }
    };
  }, [
    match?.id,
    match?.phase,
    match?.players,
    match?.sequence,
    sessionToken,
    userPlayerId,
    setMatch,
  ]);
}

function applyFreshMatch(setMatch, nextMatch) {
  if (!nextMatch) {
    setMatch(null);
    return;
  }

  setMatch((currentMatch) => {
    if (
      currentMatch?.id === nextMatch.id &&
      typeof currentMatch.sequence === "number" &&
      typeof nextMatch.sequence === "number" &&
      nextMatch.sequence < currentMatch.sequence
    ) {
      return currentMatch;
    }

    return nextMatch;
  });
}

function useBotRollingSnapshotSync({
  match,
  sessionToken,
  setMatch,
  enabled = true,
}) {
  useEffect(() => {
    if (
      !enabled ||
      !sessionToken ||
      !match?.id ||
      match.phase !== "rolling" ||
      match.dice
    ) {
      return undefined;
    }

    const activePlayer = match.players.find(
      (player) => player.id === match.currentTurnUserId,
    );

    if (!activePlayer?.isBot) {
      return undefined;
    }

    let cancelled = false;
    const timeoutIds = [1800].map((delay) =>
      window.setTimeout(async () => {
        if (cancelled) {
          return;
        }

        try {
          const latestMatch = await fetchMatchSnapshot(sessionToken, match.id);

          if (!cancelled) {
            applyFreshMatch(setMatch, normalizeMatchSnapshot(latestMatch));
          }
        } catch {}
      }, delay),
    );

    return () => {
      cancelled = true;
      timeoutIds.forEach((timeoutId) => window.clearTimeout(timeoutId));
    };
  }, [
    match?.currentTurnUserId,
    match?.dice,
    match?.id,
    match?.phase,
    match?.players,
    enabled,
    sessionToken,
    setMatch,
  ]);
}

function useImmediatePress(action, disabled = false) {
  const suppressNextClickRef = useRef(false);

  return {
    onPointerDown(event) {
      if (disabled || !action) {
        return;
      }

      suppressNextClickRef.current = true;
      event.preventDefault();
      event.stopPropagation();
      action();
    },
    onClick(event) {
      if (disabled || !action) {
        return;
      }

      event.stopPropagation();

      if (suppressNextClickRef.current) {
        suppressNextClickRef.current = false;
        event.preventDefault();
        return;
      }

      action();
    },
  };
}

function initializeInteractiveMatch(liveMatch, userPlayerId) {
  const players = sortPlayersByTurnOrder(
    normalizeMatchPlayers(liveMatch.players, userPlayerId),
  );
  const turnTimer = liveMatch.turnTimer ?? 15;
  const turnEndsAt = Date.now() + turnTimer * 1000;

  return {
    ...liveMatch,
    players,
    currentPlayerIndex: 0,
    currentTurn: players[0]?.name ?? "",
    currentTurnUserId: players[0]?.id ?? "",
    consecutiveSixCount: 0,
    dice: null,
    turnTimer,
    turnEndsAt,
    phase: "rolling",
    selectableTokenIndexes: [],
    pendingNextPlayerIndex: null,
    winnerId: null,
  };
}

function startPlayerTurn(match, playerIndex) {
  const turnEndsAt = Date.now() + match.turnTimer * 1000;

  return {
    ...match,
    currentPlayerIndex: playerIndex,
    currentTurn: match.players[playerIndex].name,
    currentTurnUserId: match.players[playerIndex].id,
    consecutiveSixCount:
      playerIndex === match.currentPlayerIndex
        ? (match.consecutiveSixCount ?? 0)
        : 0,
    dice: null,
    turnEndsAt,
    phase: "rolling",
    selectableTokenIndexes: [],
    pendingNextPlayerIndex: null,
  };
}

function rollInteractiveMatch(match) {
  if (match.phase !== "rolling" || match.winnerId) {
    return match;
  }

  const activePlayer = match.players[match.currentPlayerIndex];
  const dice = rollDiceValue(match.consecutiveSixCount ?? 0);
  const selectableTokenIndexes = getMovableTokenIndexes(activePlayer, dice);
  const nextConsecutiveSixCount =
    dice === 6 ? (match.consecutiveSixCount ?? 0) + 1 : 0;
  const detail =
    selectableTokenIndexes.length > 0
      ? `rolled a ${dice}.`
      : `rolled a ${dice} but had no valid move.`;

  const rolledMatch = {
    ...match,
    dice,
    consecutiveSixCount: nextConsecutiveSixCount,
    phase: selectableTokenIndexes.length === 0
      ? "advancing"
      : activePlayer.isBot
        ? "bot-moving"
        : "awaiting-move",
    selectableTokenIndexes,
    pendingNextPlayerIndex:
      selectableTokenIndexes.length > 0
        ? null
        : dice === 6
          ? match.currentPlayerIndex
          : (match.currentPlayerIndex + 1) % match.players.length,
    turnEndsAt:
      !activePlayer.isBot && selectableTokenIndexes.length > 0
        ? Date.now() + match.turnTimer * 1000
        : match.turnEndsAt,
    events: prependMatchEvent(match.events, activePlayer.name, detail),
  };

  if (activePlayer.isBot || selectableTokenIndexes.length === 0) {
    return rolledMatch;
  }

  const forcedTokenIndex = resolveForcedAutoMoveToken(
    selectableTokenIndexes,
  );

  if (forcedTokenIndex == null) {
    return rolledMatch;
  }

  return applyTokenMove(rolledMatch, forcedTokenIndex);
}

function countAttackersOnCell(players, playerIndex, cellKey) {
  let attackers = 0;

  players.forEach((opponent, opponentIndex) => {
    if (opponentIndex === playerIndex || opponent.isAbandoned) {
      return;
    }

    const canReach = opponent.tokens.some((opponentProgress, opponentTokenIndex) => {
      if (opponentProgress < 0 || opponentProgress > MAIN_PATH_LAST_PROGRESS) {
        return false;
      }

      for (let dice = 1; dice <= 6; dice += 1) {
        if (!canMoveToken(opponentProgress, dice)) {
          continue;
        }

        if (
          getBoardCellKey(
            opponent.color,
            opponentProgress + dice,
            opponentTokenIndex,
          ) === cellKey
        ) {
          return true;
        }
      }

      return false;
    });

    if (canReach) {
      attackers += 1;
    }
  });

  return attackers;
}

function isCellThreatenedByOpponents(players, playerIndex, cellKey) {
  return countAttackersOnCell(players, playerIndex, cellKey) > 0;
}

function progressRewardMultiplier(progress) {
  const ratio = progress / FINISHED_PROGRESS;
  if (ratio < 0.25) return 1;
  if (ratio < 0.5) return 1.2;
  if (ratio < 0.75) return 1.5;
  if (ratio < 0.9) return 2;
  return 3;
}

function opponentThreatScore(players, botIndex, opponentIndex) {
  const opponent = players[opponentIndex];
  const completed = opponent.tokens.filter((p) => p === FINISHED_PROGRESS).length;
  const active = opponent.tokens.filter((p) => p >= 0);
  const avg =
    active.length === 0
      ? 0
      : active.reduce((sum, p) => sum + Math.min(p, FINISHED_PROGRESS), 0) /
        active.length;
  const nearHome = opponent.tokens.filter((p) => p >= 40).length;
  return completed * 3 + avg / 20 + nearHome * 1.5;
}

function scoreBotMove(players, playerIndex, tokenIndex, diceValue) {
  const player = players[playerIndex];
  const currentProgress = player.tokens[tokenIndex];
  const nextProgress =
    currentProgress === -1 ? 0 : currentProgress + diceValue;
  const activePlayers = players.filter((p) => !p.isAbandoned).length;
  const twoPlayerAttackMultiplier = activePlayers === 2 ? 1.6 : 1;

  const tokensAfterMove = [...player.tokens];
  tokensAfterMove[tokenIndex] = nextProgress;
  if (tokensAfterMove.every((progress) => progress === FINISHED_PROGRESS)) {
    return 10_000;
  }

  let immediate = 0;
  let progressScore = 0;
  let safety = 0;
  let attack = 0;
  let strategic = 0;
  let risk = 0;

  if (nextProgress === FINISHED_PROGRESS) {
    immediate += 1_200;
  }

  if (
    nextProgress >= HOME_LANE_START_PROGRESS &&
    nextProgress <= HOME_LANE_LAST_PROGRESS
  ) {
    immediate += 300;
  }

  const steps = currentProgress < 0 ? 1 : Math.max(0, nextProgress - currentProgress);
  progressScore +=
    steps * 10 * progressRewardMultiplier(Math.max(nextProgress, 0));

  if (currentProgress === -1 && nextProgress === 0) {
    const activeTokens = player.tokens.filter(
      (progress) => progress >= 0 && progress < FINISHED_PROGRESS,
    ).length;
    if (activeTokens === 0) immediate += 264;
    else if (activeTokens === 1) immediate += 168;
    else if (activeTokens === 2) immediate += 84;
    else immediate -= 48;
  }

  const wasThreatened =
    currentProgress >= 0 &&
    currentProgress <= MAIN_PATH_LAST_PROGRESS &&
    !SAFE_CELL_KEYS.has(getBoardCellKey(player.color, currentProgress, tokenIndex)) &&
    isCellThreatenedByOpponents(
      players,
      playerIndex,
      getBoardCellKey(player.color, currentProgress, tokenIndex),
    );

  if (nextProgress >= 0 && nextProgress <= MAIN_PATH_LAST_PROGRESS) {
    const landingKey = getBoardCellKey(player.color, nextProgress, tokenIndex);

    if (!SAFE_CELL_KEYS.has(landingKey)) {
      players.forEach((opponent, opponentIndex) => {
        if (opponentIndex === playerIndex || opponent.isAbandoned) {
          return;
        }

        opponent.tokens.forEach((opponentProgress, opponentTokenIndex) => {
          if (
            opponentProgress >= 0 &&
            opponentProgress <= MAIN_PATH_LAST_PROGRESS &&
            getBoardCellKey(
              opponent.color,
              opponentProgress,
              opponentTokenIndex,
            ) === landingKey
          ) {
            const threatBonus = opponentThreatScore(players, playerIndex, opponentIndex) * 40;
            const nearHomeBonus =
              opponentProgress >= 40 ? 180 : opponentProgress >= 25 ? 80 : 0;
            attack +=
              (2500 + opponentProgress * 4 + nearHomeBonus + threatBonus) *
              twoPlayerAttackMultiplier;
          }
        });
      });

      const attackers = countAttackersOnCell(players, playerIndex, landingKey);
      if (attackers >= 2) {
        safety -= 900;
      } else if (attackers === 1) {
        safety -= 700;
      }

      if (
        currentProgress >= 0 &&
        SAFE_CELL_KEYS.has(
          getBoardCellKey(player.color, currentProgress, tokenIndex),
        ) &&
        attackers > 0
      ) {
        safety -= 250;
      }
    } else {
      safety += 220;
      if (wasThreatened) {
        safety += 500;
      }
    }
  } else if (wasThreatened && nextProgress >= HOME_LANE_START_PROGRESS) {
    safety += 700;
  } else if (wasThreatened && nextProgress > currentProgress) {
    safety += 200;
  }

  // Stack / blockade hint
  const beforeStacks = countOwnStacks(player);
  const afterPlayer = {
    ...player,
    tokens: tokensAfterMove,
  };
  const afterStacks = countOwnStacks(afterPlayer);
  if (afterStacks > beforeStacks) {
    strategic += 400;
  } else if (afterStacks < beforeStacks && nextProgress !== FINISHED_PROGRESS) {
    strategic -= 150;
  }

  return immediate + progressScore + safety + attack + strategic + risk;
}

function countOwnStacks(player) {
  const counts = new Map();
  player.tokens.forEach((progress, tokenIndex) => {
    if (progress < 0 || progress > MAIN_PATH_LAST_PROGRESS) {
      return;
    }
    const key = getBoardCellKey(player.color, progress, tokenIndex);
    counts.set(key, (counts.get(key) || 0) + 1);
  });
  let stacks = 0;
  counts.forEach((count) => {
    if (count >= 2) stacks += 1;
  });
  return stacks;
}

function chooseBotToken(players, playerIndex, movableTokenIndexes, diceValue) {
  if (!movableTokenIndexes.length) {
    return 0;
  }

  if (movableTokenIndexes.length === 1) {
    return movableTokenIndexes[0];
  }

  const player = players[playerIndex];

  const isWinningMove = (tokenIndex) => {
    const currentProgress = player.tokens[tokenIndex];
    const nextProgress = currentProgress === -1 ? 0 : currentProgress + diceValue;
    const tokensAfterMove = [...player.tokens];
    tokensAfterMove[tokenIndex] = nextProgress;
    return tokensAfterMove.every((progress) => progress === FINISHED_PROGRESS);
  };

  const isCaptureMove = (tokenIndex) => {
    const currentProgress = player.tokens[tokenIndex];
    const nextProgress = currentProgress === -1 ? 0 : currentProgress + diceValue;
    if (nextProgress < 0 || nextProgress > MAIN_PATH_LAST_PROGRESS) {
      return false;
    }
    const landingKey = getBoardCellKey(player.color, nextProgress, tokenIndex);
    if (SAFE_CELL_KEYS.has(landingKey)) {
      return false;
    }
    return players.some((opponent, opponentIndex) => {
      if (opponentIndex === playerIndex || opponent.isAbandoned) {
        return false;
      }
      return opponent.tokens.some((opponentProgress, opponentTokenIndex) => {
        if (opponentProgress < 0 || opponentProgress > MAIN_PATH_LAST_PROGRESS) {
          return false;
        }
        return (
          getBoardCellKey(opponent.color, opponentProgress, opponentTokenIndex) ===
          landingKey
        );
      });
    });
  };

  // Hard priority: win first, then kill opponent goti, then best score.
  const winningMoves = movableTokenIndexes.filter(isWinningMove);
  const candidateIndexes =
    winningMoves.length > 0
      ? winningMoves
      : (() => {
          const capturingMoves = movableTokenIndexes.filter(isCaptureMove);
          return capturingMoves.length > 0 ? capturingMoves : movableTokenIndexes;
        })();

  return candidateIndexes.reduce((bestTokenIndex, tokenIndex) => {
    const bestScore = scoreBotMove(
      players,
      playerIndex,
      bestTokenIndex,
      diceValue,
    );
    const candidateScore = scoreBotMove(
      players,
      playerIndex,
      tokenIndex,
      diceValue,
    );

    return candidateScore > bestScore ? tokenIndex : bestTokenIndex;
  }, candidateIndexes[0]);
}

function applyTokenMove(match, tokenIndex) {
  const activePlayer = match.players[match.currentPlayerIndex];
  const diceValue = match.dice ?? 1;
  const players = match.players.map((player) => ({
    ...player,
    tokens: [...player.tokens],
  }));
  const activeTokens = players[match.currentPlayerIndex].tokens;
  const currentProgress = activeTokens[tokenIndex];
  const nextProgress = currentProgress === -1 ? 0 : currentProgress + diceValue;

  activeTokens[tokenIndex] = nextProgress;

  const capturedPlayers = [];

  if (nextProgress >= 0 && nextProgress <= MAIN_PATH_LAST_PROGRESS) {
    const landingCellKey = getBoardCellKey(
      activePlayer.color,
      nextProgress,
      tokenIndex,
    );

    if (!SAFE_CELL_KEYS.has(landingCellKey)) {
      players.forEach((player, playerIndex) => {
        if (playerIndex === match.currentPlayerIndex) {
          return;
        }

        player.tokens = player.tokens.map((progress, otherTokenIndex) => {
          if (progress < 0 || progress > MAIN_PATH_LAST_PROGRESS) {
            return progress;
          }

          if (
            getBoardCellKey(player.color, progress, otherTokenIndex) ===
            landingCellKey
          ) {
            capturedPlayers.push(player.name);
            return -1;
          }

          return progress;
        });
      });
    }
  }

  const movedOutOfYard = currentProgress === -1 && nextProgress === 0;
  const reachedHome = nextProgress === FINISHED_PROGRESS;
  const hasWon = players[match.currentPlayerIndex].tokens.every(
    (progress) => progress === FINISHED_PROGRESS,
  );

  let detail = movedOutOfYard
    ? `rolled a ${diceValue} and opened token ${tokenIndex + 1}.`
    : `rolled a ${diceValue} and moved token ${tokenIndex + 1}.`;

  if (capturedPlayers.length > 0) {
    detail = `${detail} Captured ${capturedPlayers.join(", ")}.`;
  }

  if (reachedHome) {
    detail = `${detail} Token ${tokenIndex + 1} reached home.`;
  }

  const nextPlayerIndex =
    diceValue === 6 || capturedPlayers.length > 0
      ? match.currentPlayerIndex
      : (match.currentPlayerIndex + 1) % players.length;

  return {
    ...match,
    players,
    currentTurn: activePlayer.name,
    currentTurnUserId: activePlayer.id,
    phase: hasWon ? "finished" : "advancing",
    pendingNextPlayerIndex: hasWon ? null : nextPlayerIndex,
    selectableTokenIndexes: [],
    winnerId: hasWon ? activePlayer.id : null,
    sequence: match.sequence + 1,
    events: prependMatchEvent(match.events, activePlayer.name, detail),
  };
}

function clonePlayersForBoard(players) {
  return players.map((player) => ({
    ...player,
    tokens: [...player.tokens],
  }));
}

function sameBoardTokenState(leftPlayers, rightPlayers) {
  if (leftPlayers.length !== rightPlayers.length) {
    return false;
  }

  return leftPlayers.every((player, playerIndex) => {
    const rightPlayer = rightPlayers[playerIndex];

    if (
      player.id !== rightPlayer.id ||
      player.tokens.length !== rightPlayer.tokens.length
    ) {
      return false;
    }

    return player.tokens.every(
      (tokenProgress, tokenIndex) =>
        tokenProgress === rightPlayer.tokens[tokenIndex],
    );
  });
}

function buildTokenAnimationFrames(previousPlayers, nextPlayers) {
  const differences = [];

  previousPlayers.forEach((player, playerIndex) => {
    player.tokens.forEach((progress, tokenIndex) => {
      const nextProgress = nextPlayers[playerIndex]?.tokens[tokenIndex];

      if (nextProgress !== progress) {
        differences.push({
          playerIndex,
          tokenIndex,
          from: progress,
          to: nextProgress,
        });
      }
    });
  });

  const movedToken = differences.find(
    (difference) =>
      typeof difference.to === "number" &&
      typeof difference.from === "number" &&
      difference.to > difference.from,
  );

  if (!movedToken) {
    return [clonePlayersForBoard(nextPlayers)];
  }

  const stepValues = [];

  if (movedToken.from === -1) {
    for (let progress = 0; progress <= movedToken.to; progress += 1) {
      stepValues.push(progress);
    }
  } else {
    for (
      let progress = movedToken.from + 1;
      progress <= movedToken.to;
      progress += 1
    ) {
      stepValues.push(progress);
    }
  }

  if (stepValues.length <= 1) {
    return [clonePlayersForBoard(nextPlayers)];
  }

  return stepValues.map((stepValue, frameIndex) => {
    if (frameIndex === stepValues.length - 1) {
      return clonePlayersForBoard(nextPlayers);
    }

    const framePlayers = clonePlayersForBoard(previousPlayers);
    framePlayers[movedToken.playerIndex].tokens[movedToken.tokenIndex] =
      stepValue;
    return framePlayers;
  });
}

function buildCapturedTokenAnimations(previousPlayers, nextPlayers) {
  const animations = [];

  previousPlayers.forEach((player, playerIndex) => {
    player.tokens.forEach((progress, tokenIndex) => {
      const nextProgress = nextPlayers[playerIndex]?.tokens[tokenIndex];

      if (progress < 0 || nextProgress !== -1) {
        return;
      }

      const anchorPositions = [];

      for (let currentProgress = progress; currentProgress >= 0; currentProgress -= 1) {
        const [row, col] = resolveTokenCell(player.color, currentProgress, tokenIndex);
        anchorPositions.push(getCellCenterPercent(row, col));
      }

      anchorPositions.push(getYardSlotCenterPercent(player.color, tokenIndex));

      animations.push({
        id: `${player.id}-${tokenIndex}`,
        color: player.color,
        positions: samplePathPositions(anchorPositions),
      });
    });
  });

  return animations;
}

function formatCurrency(amount) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(amount);
}

function formatCompactNumber(value) {
  return new Intl.NumberFormat("en-IN", {
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(value);
}

function formatCountdown(totalMilliseconds) {
  const safeMilliseconds = Math.max(0, totalMilliseconds);
  const totalSeconds = Math.ceil(safeMilliseconds / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

function buildRuntimeAppState(baseAppState, session, wallet) {
  return {
    ...baseAppState,
    profile: {
      ...baseAppState.profile,
      id: session?.userId ?? baseAppState.profile.id,
      displayName: session?.displayName ?? baseAppState.profile.displayName,
    },
    wallet: wallet ?? baseAppState.wallet,
    history: wallet?.history ?? baseAppState.history,
  };
}

function useSoundUnlock() {
  useEffect(() => {
    if (typeof window === "undefined") {
      return undefined;
    }

    const unlockSound = () => {
      soundController.ensureContext();
      soundController.resumeLobbyMusicIfNeeded();
    };

    window.addEventListener("pointerdown", unlockSound, { passive: true });
    window.addEventListener("keydown", unlockSound);

    return () => {
      window.removeEventListener("pointerdown", unlockSound);
      window.removeEventListener("keydown", unlockSound);
    };
  }, []);
}

function useSoundSetting() {
  const [isSoundOn, setIsSoundOn] = useState(() => {
    if (typeof window === "undefined") {
      return true;
    }

    return window.localStorage.getItem("ludo-sound-muted") !== "true";
  });

  useEffect(() => {
    soundController.setMuted(!isSoundOn);

    if (typeof window !== "undefined") {
      window.localStorage.setItem(
        "ludo-sound-muted",
        isSoundOn ? "false" : "true",
      );
    }
  }, [isSoundOn]);

  return [isSoundOn, setIsSoundOn];
}

function useLobbyBackgroundMusic(shouldPlay) {
  useEffect(() => {
    soundController.setLobbyMusicActive(shouldPlay);

    return () => {
      soundController.setLobbyMusicActive(false);
    };
  }, [shouldPlay]);
}

function useGlobalButtonClickSound() {
  useEffect(() => {
    if (typeof window === "undefined") {
      return undefined;
    }

    const handlePointerDown = (event) => {
      const target = event.target;

      if (!(target instanceof Element)) {
        return;
      }

      const button = target.closest("button");

      if (!button || button.disabled || button.dataset.soundSkip === "true") {
        return;
      }

      if (button.closest(".ludo-board-frame")) {
        return;
      }

      soundController.click();
    };

    window.addEventListener("pointerdown", handlePointerDown, true);

    return () => {
      window.removeEventListener("pointerdown", handlePointerDown, true);
    };
  }, []);
}

function snapshotPlayersForSound(players = []) {
  return players.map((player) => ({
    id: player.id,
    tokens: [...player.tokens],
  }));
}

function getActiveTurnPlayerId(match) {
  return (
    match?.currentTurnUserId ??
    match?.players?.[match.currentPlayerIndex ?? 0]?.id ??
    null
  );
}

function hasNewHomeArrival(previousPlayers = [], nextPlayers = []) {
  return nextPlayers.some((player, playerIndex) =>
    player.tokens.some((progress, tokenIndex) => {
      const previousProgress =
        previousPlayers[playerIndex]?.tokens?.[tokenIndex] ?? null;

      return (
        previousProgress !== FINISHED_PROGRESS && progress === FINISHED_PROGRESS
      );
    }),
  );
}

function useGameplayTransitionSounds(match) {
  const previousStateRef = useRef(null);

  useEffect(() => {
    if (!match?.players?.length) {
      previousStateRef.current = null;
      return;
    }

    const activePlayerId = getActiveTurnPlayerId(match);
    const nextState = {
      activePlayerId,
      phase: match.phase,
      dice: match.dice ?? null,
      players: snapshotPlayersForSound(match.players),
    };
    const previousState = previousStateRef.current;

    if (previousState) {
      if (
        activePlayerId &&
        previousState.activePlayerId &&
        activePlayerId !== previousState.activePlayerId
      ) {
        soundController.turnChange();
      }

      if (match.dice != null && previousState.dice !== match.dice) {
        const roller = match.players?.find(
          (player) => player.id === getActiveTurnPlayerId(match),
        );
        // Bot/opponent rolls already play the dice-rolling clip once.
        if (!roller?.isBot) {
          soundController.diceRoll();
        }
      }

      if (hasNewHomeArrival(previousState.players, nextState.players)) {
        soundController.homeArrival();
      }
    }

    previousStateRef.current = nextState;
  }, [
    match?.phase,
    match?.sequence,
    match?.currentPlayerIndex,
    match?.currentTurnUserId,
    match?.players,
  ]);
}

function useTurnWarningSound({ match, now, userPlayerId }) {
  const lastTickSecondRef = useRef(null);

  useEffect(() => {
    if (!match || !userPlayerId || match.phase === "finished") {
      lastTickSecondRef.current = null;
      return;
    }

    const deadlineTime = match.turnDeadlineAt
      ? new Date(match.turnDeadlineAt).getTime()
      : (match.turnEndsAt ?? null);
    const isUserTurn = getActiveTurnPlayerId(match) === userPlayerId;

    if (!deadlineTime || !isUserTurn) {
      lastTickSecondRef.current = null;
      return;
    }

    const remainingSeconds = Math.ceil((deadlineTime - now) / 1000);

    if (
      remainingSeconds >= 1 &&
      remainingSeconds <= TURN_WARNING_THRESHOLD_SECONDS
    ) {
      if (lastTickSecondRef.current !== remainingSeconds) {
        soundController.tick();
        lastTickSecondRef.current = remainingSeconds;
      }
      return;
    }

    lastTickSecondRef.current = null;
  }, [match, now, userPlayerId]);
}

function useMatchResultSound(match, userPlayerId) {
  const handledMatchIdRef = useRef(null);

  useEffect(() => {
    if (
      !match?.id ||
      match.phase !== "finished" ||
      !match.winnerId ||
      !userPlayerId
    ) {
      return undefined;
    }

    if (handledMatchIdRef.current === match.id) {
      return undefined;
    }

    handledMatchIdRef.current = match.id;

    if (match.winnerId === userPlayerId) {
      soundController.matchWin();
    } else {
      soundController.matchLose();
    }

    return undefined;
  }, [match?.id, match?.phase, match?.winnerId, userPlayerId]);
}

function normalizePrivateRoomState(room) {
  if (!room) {
    return null;
  }

  return {
    roomId: room.roomId,
    roomCode: room.roomCode,
    roomName: room.roomName,
    status: room.status?.toLowerCase?.() ?? "waiting",
    entryFee: room.entryFee,
    maxPlayers: room.maxPlayers,
    occupiedSeats: room.occupiedSeats,
    hostUserId: room.hostUserId,
    hostDisplayName: room.hostDisplayName,
    members: room.members ?? [],
    match: room.match ? normalizeMatchSnapshot(room.match) : null,
    websocketPath: room.websocketPath ?? null,
  };
}

function useHydratedGuestAppState(baseAppState) {
  const [session, setSession] = useState(null);
  const [wallet, setWallet] = useState(baseAppState.wallet);
  const [accessMessage, setAccessMessage] = useState("");

  useEffect(() => {
    let cancelled = false;

    async function hydrateGuestState() {
      try {
        const activeSession = await ensureGuestSession(
          baseAppState.profile.displayName,
        );

        if (cancelled) {
          return;
        }

        setSession(activeSession);
        setAccessMessage("");

        try {
          const walletOverview = await fetchWalletOverview(
            activeSession.sessionToken,
          );

          if (!cancelled) {
            setWallet(normalizeWalletResponse(walletOverview));
          }
        } catch {}
      } catch (error) {
        if (!cancelled && error.message === OPERATOR_PLATFORM_ACCESS_MESSAGE) {
          setAccessMessage(OPERATOR_PLATFORM_ACCESS_MESSAGE);
        }
      }
    }

    hydrateGuestState();

    return () => {
      cancelled = true;
    };
  }, [baseAppState.profile.displayName]);

  const hydratedAppState = useMemo(
    () => buildRuntimeAppState(baseAppState, session, wallet),
    [baseAppState, session, wallet],
  );

  return {
    appState: hydratedAppState,
    accessMessage,
  };
}

function pickSafeStar(color) {
  return SAFE_STAR_ASSETS[color] ?? SAFE_STAR_ASSETS.red;
}

function getZoneColor(row, col) {
  if (row <= 5 && col <= 5) return "red";
  if (row <= 5 && col >= 9) return "green";
  if (row >= 9 && col >= 9) return "yellow";
  if (row >= 9 && col <= 5) return "blue";
  return null;
}

function AppFrame({ screenClassName, backdrop, children, overlay = null }) {
  return (
    <main className="viewport-shell">
      <div className={`portrait-device ${screenClassName}`}>
        {backdrop}
        <div className="screen-content-shell">{children}</div>
        {overlay}
      </div>
    </main>
  );
}

function GameBackdrop() {
  return (
    <div
      className="screen-background-shell game-background-shell"
      aria-hidden="true"
    />
  );
}

function UtilitySheet({
  isOpen,
  onClose,
  onSelectPanel,
  onStartMode,
  hideLocalMatch = false,
}) {
  if (!isOpen) {
    return null;
  }

  return (
    <div className="utility-sheet-scrim" role="presentation" onClick={onClose}>
      <aside
        className="utility-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="Utilities"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="utility-sheet-header">
          <span>Quick Access</span>
          <button type="button" className="utility-close" onClick={onClose}>
            Close
          </button>
        </div>

        <div className="utility-grid">
          <button type="button" onClick={() => onSelectPanel("menu")}>
            Home
          </button>
          <button type="button" onClick={() => onStartMode("online2")}>
            Multiplayer 2
          </button>
          <button type="button" onClick={() => onStartMode("online4")}>
            Multiplayer 4
          </button>
          <button type="button" onClick={() => onStartMode("computer")}>
            Vs Computer
          </button>
          {!hideLocalMatch ? (
            <button type="button" onClick={() => onStartMode("local")}>
              Local Match
            </button>
          ) : null}
        </div>
      </aside>
    </div>
  );
}

function HeaderActionButton({
  iconSrc,
  label,
  onClick,
  className = "",
  width,
  height,
}) {
  return (
    <button
      type="button"
      className={`main-header-action ${className}`.trim()}
      onClick={onClick}
      aria-label={label}
      disabled={!onClick}
    >
      <img
        src={iconSrc}
        alt=""
        width={width}
        height={height}
        draggable={false}
      />
    </button>
  );
}

function MainHeader({
  profile,
  wallet,
  onOpenWallet,
  onNotifications,
  onUtilities,
}) {
  const displayName = profile?.displayName || "Guest Player";
  const profileLevel = 3;

  return (
    <header className="main-header-bar">
      <div className="main-header-avatar-shell">
        <img
          className="main-header-avatar"
          src="/assets/ProfileMain.png"
          alt={`${displayName} profile`}
          width={180}
          height={176}
          draggable={false}
        />
        <div
          className="main-header-rank-badge"
          aria-label={`Profile level ${profileLevel}`}
        >
          <img
            src="/assets/ProfileStarIcon.png"
            alt=""
            width={60}
            height={60}
            draggable={false}
          />
          <span>{profileLevel}</span>
        </div>
      </div>

      <strong className="main-header-name">{displayName}</strong>

      <button
        type="button"
        className="main-header-wallet"
        onClick={onOpenWallet}
        aria-label={`Open wallet. Balance ${formatCompactNumber(wallet.availableBalance)}`}
        disabled={!onOpenWallet}
      >
        <img
          className="main-header-wallet-bg"
          src="/assets/CoinValueBg.png"
          alt=""
          width={326}
          height={103}
          draggable={false}
        />
        <img
          className="main-header-wallet-coin"
          src="/assets/MainCoinIcon.png"
          alt=""
          width={120}
          height={121}
          draggable={false}
        />
        <span className="main-header-wallet-value">
          {formatCompactNumber(wallet.availableBalance)}
        </span>
      </button>

      <HeaderActionButton
        iconSrc="/assets/MainNotificationIcon.png"
        label="Open notifications"
        onClick={onNotifications}
        className="main-header-action-bell"
        width={120}
        height={120}
      />
      <HeaderActionButton
        iconSrc="/assets/MainSettingsIcon.png"
        label="Open settings"
        onClick={onUtilities}
        className="is-settings"
        width={30}
        height={30}
      />
    </header>
  );
}

function MenuHeader({
  profile,
  wallet,
  onOpenWallet,
  onUtilities,
  onNotifications,
}) {
  return (
    <MainHeader
      profile={profile}
      wallet={wallet}
      onOpenWallet={onOpenWallet}
      onNotifications={onNotifications}
      onUtilities={onUtilities}
    />
  );
}

function MenuActionCard({
  title,
  artwork,
  onClick,
  className = "",
}) {
  return (
    <button
      type="button"
      className={`menu-action-card ${className}`.trim()}
      onClick={onClick}
    >
      <img
        className="menu-action-image"
        src={artwork}
        alt=""
        width={485}
        height={736}
        draggable={false}
      />
    </button>
  );
}

function FriendsRoomModal({
  isOpen,
  activeTab,
  roomName,
  displayName,
  entryFee,
  roomCode,
  statusMessage = "",
  isSubmitting = false,
  onClose,
  onChangeTab,
  onRoomNameChange,
  onDisplayNameChange,
  onEntryFeeChange,
  onRoomCodeChange,
  onSubmitCreate,
  onSubmitJoin,
}) {
  const isCreate = activeTab === "create";

  useEffect(() => {
    if (!isOpen) {
      return undefined;
    }

    function handleKeyDown(event) {
      if (event.key === "Escape") {
        onClose();
      }
    }

    window.addEventListener("keydown", handleKeyDown);

    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) {
    return null;
  }

  return (
    <div
      className="home-room-modal-scrim"
      role="presentation"
      onClick={onClose}
    >
      <aside
        className="home-room-modal"
        role="dialog"
        aria-modal="true"
        aria-label="Play with friends"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="home-room-modal-header">
          <div>
            <span className="panel-label">Play With Friends</span>
            <strong>Private Table</strong>
          </div>
          <button
            type="button"
            className="home-room-modal-close"
            onClick={onClose}
            aria-label="Close"
          >
            X
          </button>
        </header>

        <div className="private-room-toggle home-room-modal-toggle">
          <button
            type="button"
            className={isCreate ? "is-active" : ""}
            onClick={() => onChangeTab("create")}
          >
            Create Room
          </button>
          <button
            type="button"
            className={!isCreate ? "is-active" : ""}
            onClick={() => onChangeTab("join")}
          >
            Join Room
          </button>
        </div>

        {statusMessage ? (
          <div className="home-room-modal-status">{statusMessage}</div>
        ) : null}

        {isCreate ? (
          <form
            className="private-room-form home-room-modal-form"
            onSubmit={(event) => {
              event.preventDefault();
              onSubmitCreate();
            }}
          >
            <label className="private-room-field">
              <span>Room Name</span>
              <input
                type="text"
                value={roomName}
                onChange={(event) => onRoomNameChange(event.target.value)}
                placeholder="Weekend Table"
                maxLength={32}
              />
            </label>

            <label className="private-room-field">
              <span>Your Name</span>
              <input
                type="text"
                value={displayName}
                onChange={(event) => onDisplayNameChange(event.target.value)}
                placeholder="Enter nickname"
                maxLength={24}
              />
            </label>

            <label className="private-room-field">
              <span>Entry Fee</span>
              <input
                type="number"
                inputMode="numeric"
                min="1"
                step="1"
                value={entryFee}
                onChange={(event) => onEntryFeeChange(event.target.value)}
                placeholder="100"
              />
            </label>

            <button
              type="submit"
              className="private-room-primary-button"
              disabled={isSubmitting}
            >
              {isSubmitting ? "Creating..." : "Create And Enter"}
            </button>
          </form>
        ) : (
          <form
            className="private-room-form home-room-modal-form"
            onSubmit={(event) => {
              event.preventDefault();
              onSubmitJoin();
            }}
          >
            <label className="private-room-field">
              <span>Room Code</span>
              <input
                type="text"
                value={roomCode}
                onChange={(event) =>
                  onRoomCodeChange(event.target.value.toUpperCase())
                }
                placeholder="Paste code"
                maxLength={6}
              />
            </label>

            <label className="private-room-field">
              <span>Your Name</span>
              <input
                type="text"
                value={displayName}
                onChange={(event) => onDisplayNameChange(event.target.value)}
                placeholder="Enter nickname"
                maxLength={24}
              />
            </label>

            <button
              type="submit"
              className="private-room-primary-button"
              disabled={isSubmitting}
            >
              {isSubmitting ? "Joining..." : "Join Room"}
            </button>
          </form>
        )}
      </aside>
    </div>
  );
}

function MenuScreen({
  appState,
  onModeSelect,
  onOpenUtilities,
  onOpenHistory,
  onOpenWallet,
  overlay = null,
}) {
  return (
    <AppFrame
      screenClassName="screen-menu"
      backdrop={<GameBackdrop />}
      overlay={overlay}
    >
      <MenuHeader
        profile={appState.profile}
        wallet={appState.wallet}
        onOpenWallet={onOpenWallet}
        onUtilities={onOpenUtilities}
        onNotifications={onOpenHistory}
      />

      <section className="menu-home-layout">
        <div className="menu-home-hero">
          <img
            className="menu-ludo-hero"
            src="/assets/LudoHome.png"
            alt=""
            width={978}
            height={717}
            draggable={false}
          />
        </div>

        <div className="menu-game-banner" aria-label="Ludo game">
          <img
            className="menu-game-banner-art"
            src="/assets/LudoGameBanner.png"
            alt=""
            width={1228}
            height={816}
            draggable={false}
          />
        </div>

        <div className="menu-action-grid">
          <MenuActionCard
            title="Multiplayer 4"
            artwork="/assets/Multiplayer4Option.png"
            onClick={() => onModeSelect("online4")}
          />
          <MenuActionCard
            title="Multiplayer 2"
            artwork="/assets/Multiplayer2Option.png"
            className="menu-action-card-friends"
            onClick={() => onModeSelect("online2")}
          />
        </div>

      </section>
    </AppFrame>
  );
}

function OperatorAccessBlockedScreen({ message }) {
  return (
    <AppFrame
      screenClassName="screen-menu screen-access-blocked"
      backdrop={<GameBackdrop />}
    >
      <section className="access-blocked-panel" role="alert">
        <strong>{message}</strong>
      </section>
    </AppFrame>
  );
}

function BoardHeader({
  profile,
  wallet,
  onUtilities,
  onOpenWallet,
  onNotifications,
}) {
  return (
    <MainHeader
      profile={profile}
      wallet={wallet}
      onOpenWallet={onOpenWallet}
      onNotifications={onNotifications}
      onUtilities={onUtilities}
    />
  );
}

function GameScreenHeader({ wallet, onMenu, onOpenWallet, onHelp }) {
  return (
    <header className="game-header-bar">
      <button
        type="button"
        className="game-header-icon-button"
        onClick={onMenu}
        aria-label="Open menu"
      >
        <img
          src="/assets/HamburgerIcon.png"
          alt=""
          width={120}
          height={120}
          draggable={false}
        />
      </button>

      <button
        type="button"
        className="game-header-wallet"
        onClick={onOpenWallet}
        aria-label={`Open wallet. Balance ${formatCompactNumber(wallet.availableBalance)}`}
        disabled={!onOpenWallet}
      >
        <img
          className="game-header-wallet-bg"
          src="/assets/CoinValueBg.png"
          alt=""
          width={326}
          height={103}
          draggable={false}
        />
        <img
          className="game-header-wallet-coin"
          src="/assets/MainCoinIcon.png"
          alt=""
          width={120}
          height={121}
          draggable={false}
        />
        <span className="game-header-wallet-value">
          {formatCompactNumber(wallet.availableBalance)}
        </span>
      </button>

      <button
        type="button"
        className="game-header-icon-button"
        onClick={onHelp}
        aria-label="Open help"
      >
        <img
          src="/assets/HelpIcon.png"
          alt=""
          width={120}
          height={120}
          draggable={false}
        />
      </button>
    </header>
  );
}

function GameSideDrawer({
  isOpen,
  profile,
  wallet,
  isSoundOn,
  onClose,
  onToggleSound,
  onOpenHistory,
  onLeave,
}) {
  useEffect(() => {
    if (!isOpen) {
      return undefined;
    }

    function handleKeyDown(event) {
      if (event.key === "Escape") {
        onClose();
      }
    }

    window.addEventListener("keydown", handleKeyDown);

    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) {
    return null;
  }

  const displayName = profile?.displayName || "Guest Player";

  return (
    <div className="game-menu-scrim" role="presentation" onClick={onClose}>
      <aside
        className="game-menu-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="Game menu"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="game-menu-topbar">
          <span>Game Menu</span>
          <button type="button" onClick={onClose} aria-label="Close menu">
            X
          </button>
        </div>

        <div className="game-menu-profile">
          <img
            src="/assets/ProfileMain.png"
            alt=""
            width={180}
            height={176}
            draggable={false}
          />
          <div>
            <strong>{displayName}</strong>
            <span>{formatCompactNumber(wallet.availableBalance)} coins</span>
          </div>
        </div>

        <div className="game-menu-options">
          <button
            type="button"
            className="game-menu-option"
            onClick={() => {
              onOpenHistory?.();
              onClose();
            }}
          >
            History
          </button>
          <button
            type="button"
            className="game-menu-option game-menu-toggle"
            onClick={onToggleSound}
            aria-pressed={isSoundOn}
          >
            <span>Sound</span>
            <span className={`game-sound-switch ${isSoundOn ? "is-on" : ""}`}>
              <span />
            </span>
          </button>
          {onLeave ? (
            <button
              type="button"
              className="game-menu-option game-menu-leave"
              onClick={() => {
                onClose();
                onLeave();
              }}
            >
              Leave Room
            </button>
          ) : null}
        </div>
      </aside>
    </div>
  );
}

function HomeSideDrawer({
  isOpen,
  profile,
  wallet,
  isSoundOn,
  onClose,
  onToggleSound,
  onOpenHistory,
  onStartMode,
}) {
  useEffect(() => {
    if (!isOpen) {
      return undefined;
    }

    function handleKeyDown(event) {
      if (event.key === "Escape") {
        onClose();
      }
    }

    window.addEventListener("keydown", handleKeyDown);

    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) {
    return null;
  }

  const displayName = profile?.displayName || "Guest Player";

  return (
    <div
      className="game-menu-scrim home-menu-scrim"
      role="presentation"
      onClick={onClose}
    >
      <aside
        className="game-menu-drawer home-menu-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="Home settings"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="game-menu-topbar">
          <span>Settings</span>
          <button type="button" onClick={onClose} aria-label="Close settings">
            X
          </button>
        </div>

        <div className="game-menu-profile">
          <img
            src="/assets/ProfileMain.png"
            alt=""
            width={180}
            height={176}
            draggable={false}
          />
          <div>
            <strong>{displayName}</strong>
            <span>{formatCompactNumber(wallet.availableBalance)} coins</span>
          </div>
        </div>

        <div className="game-menu-options">
          <button
            type="button"
            className="game-menu-option"
            onClick={() => {
              onOpenHistory?.();
              onClose();
            }}
          >
            History
          </button>
          <button
            type="button"
            className="game-menu-option game-menu-toggle"
            onClick={onToggleSound}
            aria-pressed={isSoundOn}
          >
            <span>Sound</span>
            <span className={`game-sound-switch ${isSoundOn ? "is-on" : ""}`}>
              <span />
            </span>
          </button>
          <button
            type="button"
            className="game-menu-option"
            onClick={() => {
              onStartMode?.("online2");
              onClose();
            }}
          >
            Multiplayer 2
          </button>
          <button
            type="button"
            className="game-menu-option"
            onClick={() => {
              onStartMode?.("online4");
              onClose();
            }}
          >
            Multiplayer 4
          </button>
        </div>
      </aside>
    </div>
  );
}

function HistorySideDrawer({ isOpen, history = [], onClose }) {
  useEffect(() => {
    if (!isOpen) {
      return undefined;
    }

    function handleKeyDown(event) {
      if (event.key === "Escape") {
        onClose();
      }
    }

    window.addEventListener("keydown", handleKeyDown);

    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) {
    return null;
  }

  return (
    <div
      className="game-menu-scrim home-menu-scrim"
      role="presentation"
      onClick={onClose}
    >
      <aside
        className="game-menu-drawer home-menu-drawer history-menu-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="Match history"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="game-menu-topbar">
          <span>History</span>
          <button type="button" onClick={onClose} aria-label="Close history">
            X
          </button>
        </div>

        <div className="history-drawer-list">
          {history.length ? (
            history.map((match) => (
              <article key={match.id} className="history-drawer-card">
                <div>
                  <strong>{match.room}</strong>
                  <span>{match.when}</span>
                </div>
                <div className="history-drawer-result">
                  <span>{match.outcome}</span>
                  <strong
                    className={
                      match.delta >= 0 ? "delta-positive" : "delta-negative"
                    }
                  >
                    {formatCurrency(match.delta)}
                  </strong>
                </div>
              </article>
            ))
          ) : (
            <div className="history-drawer-empty">No wallet activity yet.</div>
          )}
        </div>
      </aside>
    </div>
  );
}

function beginDiceRollSound() {
  soundController.diceRollSpin();
}

function DieFace({ value, active, showRollCue = false }) {
  return (
    <div
      className={`die-face-shell ${active ? "is-active" : ""} ${showRollCue ? "has-roll-cue" : ""}`}
    >
      <Dice3D value={value} active={active} rollDurationMs={DICE_ROLL_MIN_SPIN_MS} />
      {showRollCue ? <RollCueArrow /> : null}
    </div>
  );
}

function RollCueArrow() {
  return (
    <img
      className="dice-roll-cue-arrow"
      src="/assets/roll-cue-arrow.svg"
      alt=""
      width={72}
      height={48}
      draggable={false}
      aria-hidden="true"
    />
  );
}

function RollDiceButton({ value, onRollDice, rolling = false, showRollCue = false }) {
  const pressHandlers = useImmediatePress(onRollDice, rolling);

  return (
    <button
      type="button"
      className={`die-face-shell die-roll-button is-active ${rolling ? "is-rolling" : ""} ${showRollCue ? "has-roll-cue" : ""}`}
      {...pressHandlers}
      disabled={rolling}
      aria-label="Roll dice"
    >
      {rolling ? (
        <Dice3D
          value={value}
          rolling
          active
          rollDurationMs={DICE_ROLL_MIN_SPIN_MS}
        />
      ) : (
        <Dice3D value={value} active rollDurationMs={DICE_ROLL_MIN_SPIN_MS} />
      )}
      {showRollCue && !rolling ? <RollCueArrow /> : null}
    </button>
  );
}

function PlayerStatusCard({
  player,
  color,
  isUser,
  isCurrentTurn,
  diceValue,
  lastDiceValue = 1,
  rollTargetValue = null,
  turnProgress,
  canRollDice = false,
  isRollingDice = false,
  isWaitingToRoll = false,
  onRollDice,
}) {
  const finishedTokens = player.tokens.filter(
    (token) => token >= FINISHED_PROGRESS,
  ).length;
  const showRollCue = canRollDice || isWaitingToRoll || isRollingDice;
  const spinningValue = rollTargetValue ?? lastDiceValue;
  // Prefer rolling animation over the settled face so opponents/bots don't
  // skip the tumble when the server result arrives early.
  const diceSlot = isRollingDice ? (
    <RollDiceButton
      value={spinningValue}
      onRollDice={onRollDice}
      rolling
      showRollCue
    />
  ) : diceValue ? (
    <DieFace value={diceValue} active={isCurrentTurn} />
  ) : canRollDice ? (
    <RollDiceButton
      value={lastDiceValue}
      onRollDice={onRollDice}
      showRollCue
    />
  ) : isWaitingToRoll ? (
    <RollDiceButton
      value={lastDiceValue}
      onRollDice={onRollDice}
      rolling
      showRollCue
    />
  ) : (
    <div className="die-slot" aria-hidden="true" />
  );

  return (
    <article
      className={`board-player-card color-${color} ${isCurrentTurn ? "is-current-turn" : ""} ${isUser ? "is-user" : ""} ${player.isAbandoned ? "is-abandoned" : ""}`}
    >
      {player.isAbandoned ? (
        <div className="player-abandoned-overlay">
          <span>Abandoned</span>
        </div>
      ) : null}
      <div className="player-card-row">
        <div className="player-card-copy">
          <div className="player-card-name-row">
            <strong>{player.name}</strong>
            {isUser ? <span className="player-you-badge">You</span> : null}
          </div>
        </div>

        <div className="player-turn-column">
          {diceSlot}

          <div
            className="player-token-dots"
            aria-label={`${finishedTokens} tokens home`}
          >
            {Array.from({ length: 4 }, (_, index) => (
              <span
                key={index}
                className={
                  index < finishedTokens ? "is-complete" : "is-pending"
                }
              />
            ))}
          </div>
        </div>
      </div>

      <div
        className={`player-turn-timer ${isCurrentTurn ? "is-active" : ""}`}
        aria-hidden="true"
      >
        <span style={{ transform: `scaleX(${turnProgress})` }} />
      </div>
    </article>
  );
}

function BoardToken({
  color,
  stackIndex,
  isSelectable,
  showChoiceRing = false,
  isUserTurnToken = false,
  onSelect,
}) {
  const pressHandlers = useImmediatePress(onSelect, !isSelectable);
  const tokenImage = (
    <img
      className={`board-token ${isSelectable ? "is-selectable" : ""} ${isUserTurnToken ? "is-user-turn-token" : ""}`}
      src={TOKEN_ASSETS[color]}
      alt=""
      width={100}
      height={125}
      draggable={false}
    />
  );
  const markerContent = (
    <>
      <span className="token-base-ring" aria-hidden="true" />
      {showChoiceRing ? (
        <span className="token-choice-ring" aria-hidden="true" />
      ) : null}
      {tokenImage}
    </>
  );

  if (!isSelectable) {
    return (
      <span
        className={`board-token-marker stack-${stackIndex}${isUserTurnToken ? " is-user-turn-token" : ""}`}
      >
        {markerContent}
      </span>
    );
  }

  return (
    <button
      type="button"
      className={`board-token-button stack-${stackIndex} is-choice`}
      {...pressHandlers}
      aria-label={`Move ${color} token`}
    >
      {markerContent}
    </button>
  );
}

function HouseToken({ color, isSelectable, showChoiceRing = false, onSelect }) {
  const pressHandlers = useImmediatePress(onSelect, !isSelectable);
  const tokenImage = (
    <img
      className={`house-token ${isSelectable ? "is-selectable" : ""}`}
      src={TOKEN_ASSETS[color]}
      alt=""
      width={100}
      height={125}
      draggable={false}
    />
  );
  const markerContent = (
    <>
      <span className="token-base-ring" aria-hidden="true" />
      {showChoiceRing ? (
        <span className="token-choice-ring" aria-hidden="true" />
      ) : null}
      {tokenImage}
    </>
  );

  if (!isSelectable) {
    return <span className="house-token-marker">{markerContent}</span>;
  }

  return (
    <button
      type="button"
      className="house-token-button is-choice"
      {...pressHandlers}
      aria-label={`Move ${color} token`}
    >
      {markerContent}
    </button>
  );
}

function HouseYardSlot({
  color,
  slotIndex,
  token,
  isRollingPlayerChoosing,
  selectableTokenIndexes,
  userPlayerId,
  onSelectToken,
}) {
  const isSelectable =
    isRollingPlayerChoosing &&
    token?.playerId === userPlayerId &&
    isTokenSelectable(selectableTokenIndexes, token.tokenIndex);
  const showChoiceRing = isSelectable;
  const slotPressHandlers = useImmediatePress(
    isSelectable ? () => onSelectToken?.(token.tokenIndex) : undefined,
    !isSelectable,
  );

  return (
    <div
      className={`house-slot house-slot-${slotIndex}${isSelectable ? " is-token-choice-slot" : ""}`}
      {...(isSelectable
        ? {
            role: "button",
            tabIndex: 0,
            "aria-label": `Move ${token.color} token`,
            ...slotPressHandlers,
            onKeyDown(event) {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                onSelectToken?.(token.tokenIndex);
              }
            },
          }
        : {})}
    >
      {token ? (
        <HouseToken
          color={token.color}
          isSelectable={isSelectable}
          showChoiceRing={showChoiceRing}
          onSelect={
            isSelectable ? () => onSelectToken?.(token.tokenIndex) : undefined
          }
        />
      ) : null}
    </div>
  );
}

function BoardPathCell({
  cellKey,
  className,
  tokens,
  isRollingPlayerChoosing,
  selectableTokenIndexes,
  shouldHighlightUserBoardTokens,
  userPlayerId,
  onSelectToken,
  isSafe,
  safeStarColor,
  zoneColor,
  arrowAsset,
}) {
  const selectableTokenOnCell = tokens.find(
    (token) =>
      isRollingPlayerChoosing &&
      token.playerId === userPlayerId &&
      isTokenSelectable(selectableTokenIndexes, token.tokenIndex),
  );
  const cellPressHandlers = useImmediatePress(
    selectableTokenOnCell
      ? () => onSelectToken?.(selectableTokenOnCell.tokenIndex)
      : undefined,
    !selectableTokenOnCell,
  );

  return (
    <div
      className={`${className}${selectableTokenOnCell ? " is-token-choice-cell" : ""}`}
      {...(selectableTokenOnCell
        ? {
            role: "button",
            tabIndex: 0,
            "aria-label": `Move ${selectableTokenOnCell.color} token`,
            ...cellPressHandlers,
            onKeyDown(event) {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                onSelectToken?.(selectableTokenOnCell.tokenIndex);
              }
            },
          }
        : {})}
    >
      {isSafe && (
        <img
          className="safe-star"
          src={pickSafeStar(safeStarColor ?? zoneColor)}
          alt=""
          width={12}
          height={11}
          draggable={false}
        />
      )}
      {arrowAsset && (
        <img
          className={`board-arrow board-arrow-${cellKey}`}
          src={arrowAsset}
          alt=""
          width={15}
          height={15}
          draggable={false}
        />
      )}
      {tokens.map((token, tokenIndex) => {
        const isSelectable =
          isRollingPlayerChoosing &&
          token.playerId === userPlayerId &&
          isTokenSelectable(selectableTokenIndexes, token.tokenIndex);
        const showChoiceRing = isSelectable;
        const isUserTurnToken =
          shouldHighlightUserBoardTokens && token.playerId === userPlayerId;

        return (
          <BoardToken
            key={token.id}
            color={token.color}
            stackIndex={tokenIndex}
            isSelectable={isSelectable}
            showChoiceRing={showChoiceRing}
            isUserTurnToken={isUserTurnToken}
            onSelect={
              isSelectable
                ? () => onSelectToken?.(token.tokenIndex)
                : undefined
            }
          />
        );
      })}
    </div>
  );
}

function LudoBoard({
  match,
  selectableTokenIndexes = [],
  onSelectToken,
  onAnimationChange,
  onTokenStep,
  onTokenCapture,
  userPlayerId,
}) {
  const [displayPlayers, setDisplayPlayers] = useState(() =>
    clonePlayersForBoard(match.players),
  );
  const displayPlayersRef = useRef(displayPlayers);
  const pendingPlayersRef = useRef(null);
  const animationTimeoutsRef = useRef([]);
  const captureAnimationTimeoutsRef = useRef([]);
  const animationCompletionTimeoutRef = useRef(null);
  const isAnimatingRef = useRef(false);
  const [capturedTokenAnimations, setCapturedTokenAnimations] = useState([]);
  const userColor = useMemo(
    () => match.players.find((player) => player.id === userPlayerId)?.color,
    [match.players, userPlayerId],
  );
  const activeTurnColor = useMemo(
    () =>
      match.players.find((player) => player.id === match.currentTurnUserId)
        ?.color ?? null,
    [match.players, match.currentTurnUserId],
  );
  const boardRotation = useMemo(
    () => getBoardRotationQuarterTurns(userColor) * 90,
    [userColor],
  );

  useEffect(() => {
    displayPlayersRef.current = displayPlayers;
  }, [displayPlayers]);

  function startCapturedTokenAnimations(animations, startDelayMs) {
    captureAnimationTimeoutsRef.current.forEach((timeoutId) =>
      window.clearTimeout(timeoutId),
    );
    captureAnimationTimeoutsRef.current = [];

    if (!animations.length) {
      setCapturedTokenAnimations([]);
      return;
    }

    const initialAnimations = animations.map((animation) => ({
      id: animation.id,
      color: animation.color,
      position: animation.positions[0],
    }));

    captureAnimationTimeoutsRef.current.push(
      window.setTimeout(() => {
        onTokenCapture?.();
        setCapturedTokenAnimations(initialAnimations);
      }, startDelayMs),
    );

    animations.forEach((animation) => {
      animation.positions.slice(1).forEach((position, positionIndex) => {
        captureAnimationTimeoutsRef.current.push(
          window.setTimeout(() => {
            setCapturedTokenAnimations((currentAnimations) =>
              currentAnimations.map((currentAnimation) =>
                currentAnimation.id === animation.id
                  ? { ...currentAnimation, position }
                  : currentAnimation,
              ),
            );
          }, startDelayMs + CAPTURE_RETURN_STEP_MS * (positionIndex + 1)),
        );
      });
    });

    const maxPositions = Math.max(
      ...animations.map((animation) => animation.positions.length),
    );

    captureAnimationTimeoutsRef.current.push(
      window.setTimeout(() => {
        setCapturedTokenAnimations([]);
        captureAnimationTimeoutsRef.current = [];
      }, startDelayMs + CAPTURE_RETURN_STEP_MS * maxPositions),
    );
  }

  useEffect(() => {
    return () => {
      animationTimeoutsRef.current.forEach((timeoutId) =>
        window.clearTimeout(timeoutId),
      );
      animationTimeoutsRef.current = [];
      captureAnimationTimeoutsRef.current.forEach((timeoutId) =>
        window.clearTimeout(timeoutId),
      );
      captureAnimationTimeoutsRef.current = [];
      if (animationCompletionTimeoutRef.current) {
        window.clearTimeout(animationCompletionTimeoutRef.current);
        animationCompletionTimeoutRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    const nextPlayers = clonePlayersForBoard(match.players);
    const currentPlayers = displayPlayersRef.current;

    if (
      currentPlayers.length !== nextPlayers.length ||
      currentPlayers.some(
        (player, index) => player.id !== nextPlayers[index]?.id,
      )
    ) {
      animationTimeoutsRef.current.forEach((timeoutId) =>
        window.clearTimeout(timeoutId),
      );
      animationTimeoutsRef.current = [];
      captureAnimationTimeoutsRef.current.forEach((timeoutId) =>
        window.clearTimeout(timeoutId),
      );
      captureAnimationTimeoutsRef.current = [];
      if (animationCompletionTimeoutRef.current) {
        window.clearTimeout(animationCompletionTimeoutRef.current);
        animationCompletionTimeoutRef.current = null;
      }
      pendingPlayersRef.current = null;
      isAnimatingRef.current = false;
      onAnimationChange?.(false);
      window.queueMicrotask(() => {
        setCapturedTokenAnimations([]);
        setDisplayPlayers(nextPlayers);
      });
      return;
    }

    if (sameBoardTokenState(currentPlayers, nextPlayers)) {
      if (!isAnimatingRef.current) {
        setDisplayPlayers(nextPlayers);
      }
      return;
    }

    if (isAnimatingRef.current) {
      pendingPlayersRef.current = nextPlayers;
      return;
    }

    const frames = buildTokenAnimationFrames(currentPlayers, nextPlayers);
    const capturedAnimations = buildCapturedTokenAnimations(
      currentPlayers,
      nextPlayers,
    );
    const captureMaxPositions = capturedAnimations.length
      ? Math.max(
          ...capturedAnimations.map((animation) => animation.positions.length),
        )
      : 0;
    const moveAnimationDuration =
      frames.length > 1 ? TOKEN_STEP_ANIMATION_MS * (frames.length - 1) : 0;
    const captureAnimationDuration = capturedAnimations.length
      ? moveAnimationDuration + CAPTURE_RETURN_STEP_MS * captureMaxPositions
      : 0;
    const totalAnimationDuration = Math.max(
      moveAnimationDuration,
      captureAnimationDuration,
    );

    if (totalAnimationDuration <= 0) {
      onTokenStep?.();
      setDisplayPlayers(nextPlayers);
      startCapturedTokenAnimations(capturedAnimations, 0);
      return;
    }

    onAnimationChange?.(true);
    isAnimatingRef.current = true;
    animationTimeoutsRef.current =
      frames.length > 1
        ? frames.map((framePlayers, frameIndex) =>
            window.setTimeout(
              () => {
                onTokenStep?.();
                setDisplayPlayers(framePlayers);
              },
              TOKEN_STEP_ANIMATION_MS * frameIndex,
            ),
          )
        : [];
    startCapturedTokenAnimations(
      capturedAnimations,
      moveAnimationDuration,
    );
    if (animationCompletionTimeoutRef.current) {
      window.clearTimeout(animationCompletionTimeoutRef.current);
    }
    animationCompletionTimeoutRef.current = window.setTimeout(() => {
      isAnimatingRef.current = false;
      animationTimeoutsRef.current = [];
      animationCompletionTimeoutRef.current = null;
      onAnimationChange?.(false);
      const pendingPlayers = pendingPlayersRef.current;
      pendingPlayersRef.current = null;

      if (
        pendingPlayers &&
        !sameBoardTokenState(displayPlayersRef.current, pendingPlayers)
      ) {
        setDisplayPlayers(pendingPlayers);
      }
    }, totalAnimationDuration);
  }, [match.players, onAnimationChange, onTokenCapture, onTokenStep]);

  const { tokenMap, yardTokenMap } = useMemo(() => {
    const boardEntries = new Map();
    const yardEntries = {
      red: Array(4).fill(null),
      green: Array(4).fill(null),
      yellow: Array(4).fill(null),
      blue: Array(4).fill(null),
    };

    displayPlayers.forEach((player) => {
      player.tokens.forEach((progress, tokenIndex) => {
        const token = {
          id: `${player.id}-${tokenIndex}`,
          color: player.color,
          playerId: player.id,
          tokenIndex,
        };

        if (progress === -1) {
          yardEntries[player.color][tokenIndex] = token;
          return;
        }

        const [row, col] = resolveTokenCell(player.color, progress, tokenIndex);
        const key = `${row}-${col}`;
        const tokens = boardEntries.get(key) ?? [];
        tokens.push(token);
        boardEntries.set(key, tokens);
      });
    });

    return {
      tokenMap: boardEntries,
      yardTokenMap: yardEntries,
    };
  }, [displayPlayers]);
  const shouldHighlightUserBoardTokens =
    match.currentTurnUserId === userPlayerId &&
    (match.phase === "rolling" || match.phase === "awaiting-move");
  const isRollingPlayerChoosing =
    match.phase === "awaiting-move" &&
    match.currentTurnUserId === userPlayerId;

  return (
    <div className="ludo-board-frame">
      <div
        className="ludo-board-surface"
        style={{
          transform: `rotate(${boardRotation}deg)`,
          "--token-upright-rotation": `${-boardRotation}deg`,
        }}
      >
        {Object.entries(HOME_ASSETS).map(([color, asset]) => (
          <img
            key={color}
            className={`board-home-image house-${color}${activeTurnColor === color ? " is-turn-house" : ""}`}
            src={asset}
            alt=""
            width={137}
            height={137}
            draggable={false}
          />
        ))}

        {Object.keys(HOME_ASSETS).map((color) => (
          <div
            key={`${color}-yard`}
            className={`board-house-overlay house-${color}${activeTurnColor === color ? " is-turn-house" : ""}`}
          >
            {Array.from({ length: 4 }, (_, slotIndex) => (
              <HouseYardSlot
                key={`${color}-${slotIndex}`}
                color={color}
                slotIndex={slotIndex}
                token={yardTokenMap[color][slotIndex]}
                isRollingPlayerChoosing={isRollingPlayerChoosing}
                selectableTokenIndexes={selectableTokenIndexes}
                userPlayerId={userPlayerId}
                onSelectToken={onSelectToken}
              />
            ))}
          </div>
        ))}

        <img
          className="board-final-home"
          src="/assets/BoardFinalDest.png"
          alt=""
          width={70}
          height={70}
          draggable={false}
        />

        <div className="board-grid">
          {Array.from({ length: 15 * 15 }, (_, index) => {
            const row = Math.floor(index / 15);
            const col = index % 15;
            const key = `${row}-${col}`;
            const tokens = tokenMap.get(key) ?? [];
            const pathIndex = BOARD_PATH_INDEX.get(key);
            const zoneColor = getZoneColor(row, col);
            const homeLaneColor = HOME_LANE_LOOKUP.get(key);
            const yardColor = YARD_LOOKUP.get(key);
            const arrowAsset = BOARD_ARROWS[key];
            const startCellColor = START_CELLS[key];
            const safeStarColor = STAR_SAFE_CELLS[key];
            const isPath = pathIndex !== undefined;
            const isSafe = Boolean(safeStarColor);
            const isFinalHomeZone =
              row >= 6 && row <= 8 && col >= 6 && col <= 8;

            const classNames = ["board-cell"];

            if (isPath) classNames.push("is-path");
            if (homeLaneColor) classNames.push(`lane-${homeLaneColor}`);
            if (startCellColor) classNames.push(`start-${startCellColor}`);
            if (yardColor) classNames.push(`yard-${yardColor}`);
            if (isFinalHomeZone) classNames.push("is-final-home-zone");
            if (row === 7 && col === 7) classNames.push("is-center-cell");
            if (isSafe) classNames.push("is-safe");

            return (
              <BoardPathCell
                key={key}
                cellKey={key}
                className={classNames.join(" ")}
                tokens={tokens}
                isRollingPlayerChoosing={isRollingPlayerChoosing}
                selectableTokenIndexes={selectableTokenIndexes}
                shouldHighlightUserBoardTokens={shouldHighlightUserBoardTokens}
                userPlayerId={userPlayerId}
                onSelectToken={onSelectToken}
                isSafe={isSafe}
                safeStarColor={safeStarColor}
                zoneColor={zoneColor}
                arrowAsset={arrowAsset}
              />
            );
          })}
        </div>

        {capturedTokenAnimations.map((animation) => (
          <img
            key={animation.id}
            className="captured-token-overlay"
            src={TOKEN_ASSETS[animation.color]}
            alt=""
            width={100}
            height={125}
            draggable={false}
            style={{
              left: `${animation.position.left}%`,
              top: `${animation.position.top}%`,
            }}
          />
        ))}
      </div>
    </div>
  );
}

function BoardScreen({
  appState,
  match,
  turnProgress,
  userPlayerId,
  statusMessage = "",
  rollingDiceUserId = null,
  onSelectToken,
  onRollDice,
  onLeave,
  onOpenUtilities,
  onOpenWallet,
  onOpenHistory,
  onPotIntroComplete,
}) {
  const { topPlayers, bottomPlayers } = useMemo(
    () => resolvePlayerCardRows(match.players, userPlayerId),
    [match.players, userPlayerId],
  );
  const [isGameMenuOpen, setIsGameMenuOpen] = useState(false);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [isSoundOn, setIsSoundOn] = useSoundSetting();
  const [autoSpinExpiredTurnId, setAutoSpinExpiredTurnId] = useState(null);
  const [opponentDiceSpin, setOpponentDiceSpin] = useState(null);
  const [boardSyncMatch, setBoardSyncMatch] = useState(match);
  const [isBoardAnimating, setIsBoardAnimating] = useState(false);
  const [isPassingDice, setIsPassingDice] = useState(false);
  const [heldTurnUserId, setHeldTurnUserId] = useState(match.currentTurnUserId);
  const [heldDiceValue, setHeldDiceValue] = useState(match.dice ?? null);
  const settledTurnUserIdRef = useRef(match.currentTurnUserId);
  const settledDiceValueRef = useRef(match.dice ?? null);
  const passDiceTimeoutRef = useRef(null);
  const previousTurnUserIdRef = useRef(match.currentTurnUserId);
  const previousPhaseRef = useRef(match.phase);
  const currentTurnUserIdRef = useRef(match.currentTurnUserId);
  const previousOpponentDiceRef = useRef({
    turnUserId: match.currentTurnUserId,
    dice: match.dice ?? null,
  });
  const opponentSpinTimeoutRef = useRef(null);
  const queuedBoardMatchRef = useRef(match);
  const opponentSpinStartedAtRef = useRef(0);
  const opponentRollSoundKeyRef = useRef(null);
  const [lastDiceByPlayer, setLastDiceByPlayer] = useState(() => new Map());
  const matchPotAmount = match.pot ?? 0;
  const [potIntroPhase, setPotIntroPhase] = useState("visible");
  const potIntroMatchIdRef = useRef(match.id);
  const isPotIntroBlocking = potIntroPhase !== "done";

  useEffect(() => {
    if (potIntroMatchIdRef.current === match.id) {
      return;
    }

    potIntroMatchIdRef.current = match.id;
    setPotIntroPhase("visible");
  }, [match.id]);

  useEffect(() => {
    if (potIntroPhase !== "visible") {
      return undefined;
    }

    soundController.matchStart();

    const hideTimeoutId = window.setTimeout(() => {
      setPotIntroPhase("exiting");
    }, 3000);

    return () => window.clearTimeout(hideTimeoutId);
  }, [potIntroPhase, match.id]);

  useEffect(() => {
    if (potIntroPhase !== "exiting") {
      return undefined;
    }

    const doneTimeoutId = window.setTimeout(() => {
      setPotIntroPhase("done");
    }, 600);

    return () => window.clearTimeout(doneTimeoutId);
  }, [potIntroPhase]);

  useEffect(() => {
    if (potIntroPhase !== "done") {
      return;
    }

    onPotIntroComplete?.();
  }, [potIntroPhase, onPotIntroComplete]);

  useEffect(() => {
    setLastDiceByPlayer(new Map());
  }, [match.id]);

  useEffect(() => {
    setLastDiceByPlayer((currentDiceByPlayer) => {
      const nextDiceByPlayer = new Map(currentDiceByPlayer);

      if (match.dice && match.currentTurnUserId) {
        nextDiceByPlayer.set(match.currentTurnUserId, match.dice);
      }
      if (match.lastRollDice && match.lastRollUserId) {
        nextDiceByPlayer.set(match.lastRollUserId, match.lastRollDice);
      }

      return nextDiceByPlayer;
    });
  }, [
    match.currentTurnUserId,
    match.dice,
    match.lastRollDice,
    match.lastRollUserId,
  ]);

  useEffect(() => {
    currentTurnUserIdRef.current = match.currentTurnUserId;
  }, [match.currentTurnUserId]);

  useEffect(() => {
    return () => {
      if (passDiceTimeoutRef.current) {
        window.clearTimeout(passDiceTimeoutRef.current);
      }
    };
  }, []);

  function clearPassDiceTimeout() {
    if (passDiceTimeoutRef.current) {
      window.clearTimeout(passDiceTimeoutRef.current);
      passDiceTimeoutRef.current = null;
    }
  }

  function syncHeldTurnToMatch() {
    clearPassDiceTimeout();
    setIsPassingDice(false);
    setHeldTurnUserId(currentTurnUserIdRef.current);
    setHeldDiceValue(match.dice ?? null);
    settledTurnUserIdRef.current = currentTurnUserIdRef.current;
    settledDiceValueRef.current = match.dice ?? null;
  }

  function releaseDicePassHold() {
    clearPassDiceTimeout();
    setIsPassingDice(false);
    setHeldTurnUserId(currentTurnUserIdRef.current);
    setHeldDiceValue(null);
    settledTurnUserIdRef.current = currentTurnUserIdRef.current;
    settledDiceValueRef.current = null;
  }

  function shouldDelayDicePass(previousTurnUserId, previousPhase) {
    return (
      previousTurnUserId &&
      previousTurnUserId !== match.currentTurnUserId &&
      match.phase === "rolling" &&
      !match.dice &&
      previousPhase !== "advancing"
    );
  }

  function beginDicePassHold(previousTurnUserId) {
    clearPassDiceTimeout();
    setHeldTurnUserId(previousTurnUserId);
    setHeldDiceValue(
      settledDiceValueRef.current ??
        lastDiceByPlayer.get(previousTurnUserId) ??
        match.lastRollDice ??
        null,
    );
    setIsPassingDice(true);
    passDiceTimeoutRef.current = window.setTimeout(() => {
      passDiceTimeoutRef.current = null;
      releaseDicePassHold();
    }, DICE_PASS_DELAY_MS);
  }

  useEffect(() => {
    if (isBoardAnimating || isPassingDice) {
      previousTurnUserIdRef.current = match.currentTurnUserId;
      previousPhaseRef.current = match.phase;
      return;
    }

    const previousTurnUserId = previousTurnUserIdRef.current;
    const previousPhase = previousPhaseRef.current;

    if (shouldDelayDicePass(previousTurnUserId, previousPhase)) {
      beginDicePassHold(previousTurnUserId);
    } else {
      setHeldTurnUserId(match.currentTurnUserId);
      setHeldDiceValue(match.dice ?? null);
      settledTurnUserIdRef.current = match.currentTurnUserId;
      settledDiceValueRef.current = match.dice ?? null;
    }

    previousTurnUserIdRef.current = match.currentTurnUserId;
    previousPhaseRef.current = match.phase;
  }, [
    isBoardAnimating,
    isPassingDice,
    match.currentTurnUserId,
    match.dice,
    match.lastRollDice,
    match.phase,
  ]);

  function handleBoardAnimationChange(isAnimating) {
    if (isAnimating) {
      setHeldTurnUserId(settledTurnUserIdRef.current ?? match.currentTurnUserId);
      setHeldDiceValue(settledDiceValueRef.current);
      setIsBoardAnimating(true);
      return;
    }

    setIsBoardAnimating(false);
    const heldUserId = settledTurnUserIdRef.current ?? heldTurnUserId;

    if (
      heldUserId &&
      heldUserId !== match.currentTurnUserId &&
      match.phase === "rolling" &&
      !match.dice
    ) {
      beginDicePassHold(heldUserId);
      return;
    }

    syncHeldTurnToMatch();
  }

  function getLastDiceValue(playerId) {
    return lastDiceByPlayer.get(playerId) ?? match.dice ?? 1;
  }

  function getRollTargetValue(playerId) {
    if (opponentDiceSpin?.userId === playerId) {
      return opponentDiceSpin.value;
    }

    if (rollingDiceUserId === playerId && match.dice) {
      return match.dice;
    }

    return null;
  }

  function getVisibleDiceValue(playerId) {
    if (opponentDiceSpin?.userId === playerId) {
      return opponentDiceSpin.revealing ? opponentDiceSpin.value : null;
    }

    if (rollingDiceUserId === playerId) {
      return null;
    }

    if (
      (isBoardAnimating || isPassingDice) &&
      heldTurnUserId === playerId &&
      heldDiceValue
    ) {
      return heldDiceValue;
    }

    if (
      isPassingDice &&
      heldTurnUserId === playerId &&
      lastDiceByPlayer.get(playerId)
    ) {
      return lastDiceByPlayer.get(playerId);
    }

    const isCurrentTurnPlayer = match.currentTurnUserId === playerId;

    if (isCurrentTurnPlayer && match.dice) {
      return match.dice;
    }

    // Current player has not rolled yet this turn — leave dice slot to roll UI.
    if (isCurrentTurnPlayer) {
      return null;
    }

    return (
      lastDiceByPlayer.get(playerId) ??
      (match.lastRollUserId === playerId ? match.lastRollDice : null) ??
      null
    );
  }

  const isHoldingTurnDisplay =
    isBoardAnimating || isPassingDice || Boolean(opponentDiceSpin);
  const canRollDice =
    !isPotIntroBlocking &&
    !isHoldingTurnDisplay &&
    match.phase === "rolling" &&
    match.currentTurnUserId === userPlayerId &&
    !match.dice &&
    Boolean(onRollDice);
  const visibleTurnUserId = isHoldingTurnDisplay
    ? heldTurnUserId ?? opponentDiceSpin?.userId ?? match.currentTurnUserId
    : match.currentTurnUserId;
  const nextTurnPlayer = match.players.find(
    (player) => player.id === match.currentTurnUserId,
  );
  // Bot/opponent dice only start after the previous play + pass wait finishes.
  const autoRollingDiceUserId =
    !isPotIntroBlocking &&
    !isBoardAnimating &&
    !isPassingDice &&
    !opponentDiceSpin &&
    match.phase === "rolling" &&
    !match.dice &&
    nextTurnPlayer?.isBot
      ? match.currentTurnUserId
      : null;
  const waitingToRollUserId =
    !isPotIntroBlocking &&
    !isBoardAnimating &&
    !isPassingDice &&
    !opponentDiceSpin &&
    match.phase === "rolling" &&
    !match.dice &&
    match.currentTurnUserId !== userPlayerId &&
    !nextTurnPlayer?.isBot
      ? match.currentTurnUserId
      : null;

  useEffect(() => {
    setBoardSyncMatch(match);
    queuedBoardMatchRef.current = match;
    setOpponentDiceSpin(null);
    opponentSpinStartedAtRef.current = 0;
    opponentRollSoundKeyRef.current = null;
    if (opponentSpinTimeoutRef.current) {
      window.clearTimeout(opponentSpinTimeoutRef.current);
      opponentSpinTimeoutRef.current = null;
    }
    previousOpponentDiceRef.current = {
      turnUserId: match.currentTurnUserId,
      dice: match.dice ?? null,
    };
  }, [match.id]);

  useEffect(() => {
    queuedBoardMatchRef.current = match;

    if (isPotIntroBlocking) {
      if (!opponentDiceSpin) {
        setBoardSyncMatch(match);
      }
      return undefined;
    }

    // Wait until the previous player/opponent finish animating and the half-second
    // dice pass delay completes before starting the next opponent dice.
    if (isBoardAnimating || isPassingDice) {
      return undefined;
    }

    const previousDice = previousOpponentDiceRef.current;
    const turnUserId = match.currentTurnUserId;
    const dice = match.dice ?? null;
    const diceJustAppeared =
      dice != null &&
      turnUserId &&
      turnUserId !== userPlayerId &&
      (previousDice.dice == null ||
        previousDice.turnUserId !== turnUserId ||
        previousDice.dice !== dice);

    if (diceJustAppeared) {
      // Already handling this exact roll — don't restart timers/sound.
      if (
        opponentDiceSpin?.userId === turnUserId &&
        opponentDiceSpin.value === dice &&
        !opponentDiceSpin.revealing
      ) {
        previousOpponentDiceRef.current = { turnUserId, dice };
        return undefined;
      }

      previousOpponentDiceRef.current = {
        turnUserId,
        dice,
      };

      if (opponentSpinTimeoutRef.current) {
        window.clearTimeout(opponentSpinTimeoutRef.current);
      }

      const spinAlreadyStarted = opponentSpinStartedAtRef.current > 0;
      if (!spinAlreadyStarted) {
        opponentSpinStartedAtRef.current = Date.now();
      }

      const soundKey = `${turnUserId}:${dice}`;
      if (opponentRollSoundKeyRef.current !== soundKey) {
        // Only play if auto-roll hasn't already started the sound for this turn.
        const autoSoundKey = `auto:${turnUserId}`;
        if (opponentRollSoundKeyRef.current !== autoSoundKey) {
          beginDiceRollSound();
        }
        opponentRollSoundKeyRef.current = soundKey;
      }

      const elapsedMs = Date.now() - opponentSpinStartedAtRef.current;
      const remainingSpinMs = spinAlreadyStarted
        ? Math.max(180, DICE_ROLL_MIN_SPIN_MS - elapsedMs)
        : DICE_ROLL_MIN_SPIN_MS;

      // Keep the current board snapshot (pre-move). Do NOT apply match.players yet.
      setHeldTurnUserId(turnUserId);
      setHeldDiceValue(dice);
      settledTurnUserIdRef.current = turnUserId;
      settledDiceValueRef.current = dice;
      setLastDiceByPlayer((currentDiceByPlayer) => {
        const nextDiceByPlayer = new Map(currentDiceByPlayer);
        nextDiceByPlayer.set(turnUserId, dice);
        return nextDiceByPlayer;
      });
      setOpponentDiceSpin({
        userId: turnUserId,
        value: dice,
        revealing: false,
      });
      setAutoSpinExpiredTurnId(null);

      opponentSpinTimeoutRef.current = window.setTimeout(() => {
        // 1) Show the final dice face first
        setOpponentDiceSpin((current) =>
          current?.userId === turnUserId
            ? { ...current, revealing: true }
            : current,
        );

        // 2) Then release the queued goti move after the face is visible
        opponentSpinTimeoutRef.current = window.setTimeout(() => {
          setBoardSyncMatch(queuedBoardMatchRef.current);
          opponentSpinTimeoutRef.current = window.setTimeout(() => {
            opponentSpinTimeoutRef.current = null;
            opponentSpinStartedAtRef.current = 0;
            opponentRollSoundKeyRef.current = null;
            setOpponentDiceSpin((current) =>
              current?.userId === turnUserId ? null : current,
            );
          }, 160);
        }, 220);
      }, remainingSpinMs);

      return undefined;
    }

    previousOpponentDiceRef.current = {
      turnUserId,
      dice,
    };

    // While opponent/bot dice is spinning or revealing, hold the board.
    if (opponentDiceSpin) {
      return undefined;
    }

    setBoardSyncMatch(match);
    return undefined;
  }, [
    isPotIntroBlocking,
    isBoardAnimating,
    isPassingDice,
    match,
    opponentDiceSpin,
    userPlayerId,
  ]);

  useEffect(() => {
    return () => {
      if (opponentSpinTimeoutRef.current) {
        window.clearTimeout(opponentSpinTimeoutRef.current);
        opponentSpinTimeoutRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    if (!autoRollingDiceUserId) {
      setAutoSpinExpiredTurnId(null);
      return undefined;
    }

    setAutoSpinExpiredTurnId(null);

    if (!opponentSpinStartedAtRef.current) {
      opponentSpinStartedAtRef.current = Date.now();
    }

    const autoSoundKey = `auto:${autoRollingDiceUserId}`;
    if (opponentRollSoundKeyRef.current !== autoSoundKey) {
      opponentRollSoundKeyRef.current = autoSoundKey;
      beginDiceRollSound();
    }

    return undefined;
  }, [autoRollingDiceUserId]);

  const isRollingPlayerChoosing =
    !isPotIntroBlocking &&
    !isHoldingTurnDisplay &&
    match.phase === "awaiting-move" &&
    match.currentTurnUserId === userPlayerId;

  return (
    <AppFrame
      screenClassName="screen-board screen-board-match screen-board-scrollable"
      backdrop={<GameBackdrop />}
      overlay={
        <>
          <GameSideDrawer
            isOpen={isGameMenuOpen}
            profile={appState.profile}
            wallet={appState.wallet}
            isSoundOn={isSoundOn}
            onClose={() => setIsGameMenuOpen(false)}
            onToggleSound={() => setIsSoundOn((current) => !current)}
            onOpenHistory={() => setIsHistoryOpen(true)}
            onLeave={onLeave}
          />
          <HistorySideDrawer
            isOpen={isHistoryOpen}
            history={appState.history}
            onClose={() => setIsHistoryOpen(false)}
          />
        </>
      }
    >
      <GameScreenHeader
        wallet={appState.wallet}
        onMenu={() => setIsGameMenuOpen(true)}
        onOpenWallet={undefined}
        onHelp={() => setIsHistoryOpen(true)}
      />

      {statusMessage ? (
        <div className="board-status-banner">{statusMessage}</div>
      ) : null}

      <MatchPotIntroOverlay
        potAmount={matchPotAmount}
        phase={potIntroPhase}
      />

      <section className="board-screen-mobile">
        <div className="board-player-row board-player-row-top">
          {topPlayers.map((player, index) =>
            player ? (
              <PlayerStatusCard
                key={player.id}
                player={player}
                color={player.color}
                isUser={player.id === userPlayerId}
                isCurrentTurn={visibleTurnUserId === player.id}
                diceValue={getVisibleDiceValue(player.id)}
                lastDiceValue={getLastDiceValue(player.id)}
                rollTargetValue={getRollTargetValue(player.id)}
                canRollDice={canRollDice && visibleTurnUserId === player.id}
                isRollingDice={
                  rollingDiceUserId === player.id ||
                  (opponentDiceSpin?.userId === player.id &&
                    !opponentDiceSpin.revealing) ||
                  (autoRollingDiceUserId === player.id &&
                    autoSpinExpiredTurnId !== player.id)
                }
                isWaitingToRoll={
                  waitingToRollUserId === player.id ||
                  autoSpinExpiredTurnId === player.id
                }
                onRollDice={onRollDice}
                turnProgress={
                  isPotIntroBlocking
                    ? 0
                    : visibleTurnUserId === player.id
                      ? turnProgress
                      : 0
                }
              />
            ) : (
              <div
                key={`top-empty-${index}`}
                className="board-player-card-spacer"
                aria-hidden="true"
              />
            ),
          )}
        </div>

        <div className="board-stage-shell">
          <div className="board-stage-rail" aria-hidden="true" />
          <LudoBoard
            match={boardSyncMatch}
            selectableTokenIndexes={
              isRollingPlayerChoosing ? match.selectableTokenIndexes : []
            }
            onSelectToken={
              isRollingPlayerChoosing ? onSelectToken : undefined
            }
            onAnimationChange={handleBoardAnimationChange}
            onTokenStep={() => soundController.tokenStep()}
            onTokenCapture={() => soundController.tokenCapture()}
            userPlayerId={userPlayerId}
          />
        </div>

        <div className="board-player-row board-player-row-bottom">
          {bottomPlayers.map((player, index) =>
            player ? (
              <PlayerStatusCard
                key={player.id}
                player={player}
                color={player.color}
                isUser={player.id === userPlayerId}
                isCurrentTurn={visibleTurnUserId === player.id}
                diceValue={getVisibleDiceValue(player.id)}
                lastDiceValue={getLastDiceValue(player.id)}
                rollTargetValue={getRollTargetValue(player.id)}
                canRollDice={canRollDice && visibleTurnUserId === player.id}
                isRollingDice={
                  rollingDiceUserId === player.id ||
                  (opponentDiceSpin?.userId === player.id &&
                    !opponentDiceSpin.revealing) ||
                  (autoRollingDiceUserId === player.id &&
                    autoSpinExpiredTurnId !== player.id)
                }
                isWaitingToRoll={
                  waitingToRollUserId === player.id ||
                  autoSpinExpiredTurnId === player.id
                }
                onRollDice={onRollDice}
                turnProgress={
                  isPotIntroBlocking
                    ? 0
                    : visibleTurnUserId === player.id
                      ? turnProgress
                      : 0
                }
              />
            ) : (
              <div
                key={`bottom-empty-${index}`}
                className="board-player-card-spacer"
                aria-hidden="true"
              />
            ),
          )}
        </div>
      </section>
    </AppFrame>
  );
}

function WaitingLobbyScreen({
  appState,
  room,
  countdownMs,
  statusMessage = "",
  onLeave,
  onOpenUtilities,
  onOpenWallet,
  onOpenHistory,
}) {
  const isStarting = isOnlineLobbyStarting(room);
  const hasTimedOut = countdownMs <= 0 || isStarting;

  return (
    <AppFrame
      screenClassName="screen-board screen-board-match screen-online-waiting"
      backdrop={<GameBackdrop />}
    >
      <BoardHeader
        profile={appState.profile}
        wallet={appState.wallet}
        onUtilities={onOpenUtilities}
        onOpenWallet={onOpenWallet}
        onNotifications={onOpenHistory}
      />

      {statusMessage ? (
        <div className="board-status-banner">{statusMessage}</div>
      ) : null}

      <section className="board-room-strip online-room-strip">
        <div>
          <span className="board-room-label">Room</span>
          <strong>{room.roomCode}</strong>
        </div>
        <div>
          <span className="board-room-label">Boot Amount</span>
          <strong>{formatCurrency(room.entryFee ?? 0)}</strong>
        </div>
        <div>
          <span className="board-room-label">Players</span>
          <strong>
            {room.realPlayerCount}/{room.maxPlayers}
          </strong>
        </div>
      </section>

      <section className="waiting-lobby-panel online-waiting-panel">
        <div className="waiting-lobby-kicker-row">
          <span className="panel-label">Online Lobby</span>
          <span className="waiting-lobby-badge">
            {room.realPlayerCount}/{room.maxPlayers} Seats
          </span>
        </div>

        <div className="online-seat-row" aria-label="Online seats">
          {Array.from({ length: room.maxPlayers }, (_, index) => {
            const isFilled = index < room.realPlayerCount;

            return (
              <span
                key={index}
                className={isFilled ? "is-filled" : ""}
                aria-label={isFilled ? "Filled seat" : "Open seat"}
              />
            );
          })}
        </div>

        <div className="waiting-timer-hero">
          <span className="waiting-timer-label">Lobby Timer</span>
          <strong className="waiting-timer-value">
            {hasTimedOut ? "00:00" : formatCountdown(countdownMs)}
          </strong>
          <span className="waiting-timer-meta">
            {isStarting
              ? "Starting match..."
              : hasTimedOut
                ? "Preparing table"
                : "Table starts when ready"}
          </span>
        </div>

        <p className="waiting-lobby-copy">
          Your online table is being prepared. The match starts automatically
          when the lobby is ready.
        </p>

        <button
          type="button"
          className="waiting-leave-button"
          onClick={onLeave}
        >
          Leave Lobby
        </button>
      </section>
    </AppFrame>
  );
}

function BoardTransitionScreen({
  appState,
  title = "Connecting",
  statusMessage = "",
}) {
  return (
    <AppFrame
      screenClassName="screen-board screen-board-match"
      backdrop={<GameBackdrop />}
    >
      <BoardHeader
        profile={appState.profile}
        wallet={appState.wallet}
        onUtilities={() => {}}
        onOpenWallet={() => {}}
        onNotifications={() => {}}
      />

      {statusMessage ? (
        <div className="board-status-banner">{statusMessage}</div>
      ) : null}

      <section className="board-transition-panel utility-panel">
        <span className="panel-label">Match Status</span>
        <strong>{title}</strong>
        <span className="panel-secondary">
          {statusMessage || "Preparing your table..."}
        </span>
      </section>
    </AppFrame>
  );
}

function OnlineLoadingScreen({
  appState,
  title = "Finding Table",
  statusMessage = "",
}) {
  return (
    <AppFrame
      screenClassName="screen-board screen-board-match screen-online-loading"
      backdrop={<GameBackdrop />}
    >
      <BoardHeader
        profile={appState.profile}
        wallet={appState.wallet}
        onUtilities={() => {}}
        onOpenWallet={() => {}}
        onNotifications={() => {}}
      />

      <section className="online-loading-panel">
        <div className="online-loading-emblem" aria-hidden="true">
          <span />
          <span />
          <span />
          <span />
        </div>

        <div className="online-loading-copy">
          <span className="panel-label">Play Online</span>
          <strong>{title}</strong>
          <p>{statusMessage || "Preparing your online lobby."}</p>
        </div>

        <div className="online-loading-progress" aria-hidden="true">
          <span />
        </div>
      </section>
    </AppFrame>
  );
}

function ConfirmDialog({
  isOpen,
  title,
  message,
  confirmLabel = "Yes",
  cancelLabel = "No",
  onConfirm,
  onCancel,
}) {
  if (!isOpen) {
    return null;
  }

  return (
    <div className="dialog-scrim" role="presentation" onClick={onCancel}>
      <aside
        className="dialog-card"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(event) => event.stopPropagation()}
      >
        <strong>{title}</strong>
        <p>{message}</p>
        <div className="dialog-actions">
          <button
            type="button"
            className="dialog-button dialog-button-secondary"
            onClick={onCancel}
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            className="dialog-button dialog-button-primary"
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
        </div>
      </aside>
    </div>
  );
}

function formatMatchDuration(totalSeconds) {
  const safeSeconds = Math.max(0, Math.floor(totalSeconds || 0));
  const minutes = Math.floor(safeSeconds / 60);
  const seconds = safeSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

function computeMatchResultStats(match, winnerId) {
  const winner = match.players.find((player) => player.id === winnerId);
  const winnerName = winner?.name || match.winnerDisplayName || "";
  const winnerEvents = (match.events || []).filter(
    (event) => event.actor === winnerName,
  );
  const totalMoves = winnerEvents.filter((event) =>
    /opened token|moved token/i.test(event.detail || ""),
  ).length;
  const productiveMoves = winnerEvents.filter((event) =>
    /opened token|moved token|Captured|reached home/i.test(event.detail || ""),
  ).length;
  const finishedTokens =
    winner?.tokens?.filter((progress) => progress >= FINISHED_PROGRESS).length ??
    0;
  const isForfeitWin = (match.events || []).some((event) =>
    /opponent left the 2-player match|won because the opponent left/i.test(
      event.detail || "",
    ),
  );
  const accuracy =
    isForfeitWin && totalMoves <= 0
      ? 100
      : totalMoves <= 0
        ? Math.min(100, Math.round((finishedTokens / 4) * 100) || 100)
        : Math.min(
            100,
            Math.max(
              40,
              Math.round(
                ((productiveMoves + finishedTokens * 2) /
                  Math.max(totalMoves + finishedTokens, 1)) *
                  50,
              ),
            ),
          );
  const startedAt = match.createdAt
    ? new Date(match.createdAt).getTime()
    : null;
  const endedAt = match.updatedAt
    ? new Date(match.updatedAt).getTime()
    : Date.now();
  const durationSeconds =
    startedAt && Number.isFinite(startedAt)
      ? Math.max(1, Math.round((endedAt - startedAt) / 1000))
      : Math.max(1, (match.events?.length || 1) * 8);
  const playerPosition =
    Math.max(
      1,
      match.players.findIndex((player) => player.id === winnerId) + 1,
    ) || 1;

  return {
    winner,
    playerPosition,
    totalMoves,
    accuracy,
    durationLabel: formatMatchDuration(durationSeconds),
    potAmount: match.pot ?? 0,
    isForfeitWin,
  };
}

function MatchPotIntroOverlay({ potAmount, phase }) {
  if (phase !== "visible" && phase !== "exiting") {
    return null;
  }

  return (
    <div
      className={`match-pot-intro-scrim ${phase === "exiting" ? "is-exiting" : "is-visible"}`}
      aria-live="polite"
    >
      <div className="match-pot-intro-card">
        <img src="/assets/MainCoinIcon.png" alt="" draggable={false} />
        <span>Pot Amount</span>
        <strong>{formatCurrency(potAmount)}</strong>
      </div>
    </div>
  );
}

function MatchResultDialog({
  match,
  userPlayerId,
  onGoHome,
  onStartNewGame,
  newGameLabel = "Play Again",
}) {
  if (!match || match.phase !== "finished" || !match.winnerId) {
    return null;
  }

  const didWin = match.winnerId === userPlayerId;
  const stats = computeMatchResultStats(match, match.winnerId);
  const winnerColor = stats.winner?.color || "blue";
  const winnerName =
    stats.winner?.name || match.winnerDisplayName || `Player ${stats.playerPosition}`;

  return (
    <div
      className={`dialog-scrim match-result-scrim ${didWin ? "is-win" : "is-loss"}`}
      role="presentation"
    >
      <aside
        className={`match-result-panel ${didWin ? "is-win" : "is-loss"}`}
        role="dialog"
        aria-modal="true"
        aria-label={didWin ? "You won the match" : "Match finished"}
      >
        <img
          className="match-result-empty-art"
          src="/assets/empty.png"
          alt=""
          draggable={false}
        />

        <div className="match-result-confetti" aria-hidden="true">
          {Array.from({ length: 18 }, (_, index) => (
            <span key={index} className={`match-result-confetti-bit bit-${index % 6}`} />
          ))}
        </div>

        <div className="match-result-hero">
          <div className="match-result-crown" aria-hidden="true">
            ♛
          </div>
          <div className="match-result-ribbon">
            <strong>{didWin ? "YOU WON!" : "GAME OVER"}</strong>
          </div>
          <p className="match-result-congrats">
            {didWin ? "Congratulations!" : "Better luck next time"}
          </p>
          <p className="match-result-subtitle">
            {didWin
              ? stats.isForfeitWin
                ? "Opponent left — you are the winner!"
                : "You are the Ludo King!"
              : `${winnerName} won the match`}
          </p>
        </div>

        <div className="match-result-winner-card">
          <div className="match-result-goti-wrap">
            <img
              className="match-result-goti"
              src={TOKEN_ASSETS[winnerColor] || TOKEN_ASSETS.blue}
              alt=""
              draggable={false}
            />
            <span className="match-result-winner-badge">Winner</span>
          </div>
          <div className="match-result-winner-copy">
            <span className="match-result-player-label">Player Position</span>
            <strong>Player {stats.playerPosition}</strong>
            <span className="match-result-player-name">{winnerName}</span>
            <div className="match-result-pot-chip">
              <img src="/assets/MainCoinIcon.png" alt="" draggable={false} />
              <span>Pot {formatCurrency(stats.potAmount)}</span>
            </div>
          </div>
        </div>

        <div className="match-result-stats-grid">
          <div className="match-result-stat">
            <span className="match-result-stat-icon" aria-hidden="true">
              🏆
            </span>
            <span className="match-result-stat-label">Rank</span>
            <strong>{didWin ? "#1" : "#2"}</strong>
          </div>
          <div className="match-result-stat">
            <span className="match-result-stat-icon" aria-hidden="true">
              🎲
            </span>
            <span className="match-result-stat-label">Total Moves</span>
            <strong>{stats.totalMoves}</strong>
          </div>
          <div className="match-result-stat">
            <span className="match-result-stat-icon" aria-hidden="true">
              ◎
            </span>
            <span className="match-result-stat-label">Accuracy</span>
            <strong>{stats.accuracy}%</strong>
          </div>
          <div className="match-result-stat">
            <span className="match-result-stat-icon" aria-hidden="true">
              ⏱
            </span>
            <span className="match-result-stat-label">Game Time</span>
            <strong>{stats.durationLabel}</strong>
          </div>
        </div>

        <div className="match-result-actions">
          <button
            type="button"
            className="match-result-button match-result-button-primary"
            onClick={onStartNewGame}
          >
            {newGameLabel}
          </button>
          <button
            type="button"
            className="match-result-button match-result-button-secondary"
            onClick={onGoHome}
          >
            Home
          </button>
        </div>
      </aside>
    </div>
  );
}

function PrivateRoomSetupScreen({
  appState,
  activeTab,
  roomName,
  displayName,
  entryFee,
  roomCode,
  statusMessage = "",
  onBack,
  onChangeTab,
  onRoomNameChange,
  onDisplayNameChange,
  onEntryFeeChange,
  onRoomCodeChange,
  onSubmitCreate,
  onSubmitJoin,
  onOpenUtilities,
  onOpenWallet,
  onOpenHistory,
}) {
  const isCreate = activeTab === "create";

  return (
    <AppFrame
      screenClassName="screen-board screen-board-match"
      backdrop={<GameBackdrop />}
    >
      <BoardHeader
        profile={appState.profile}
        wallet={appState.wallet}
        onUtilities={onOpenUtilities}
        onOpenWallet={onOpenWallet}
        onNotifications={onOpenHistory}
      />

      <section className="private-room-stage">
        <button
          type="button"
          className="private-room-back-button"
          onClick={onBack}
          aria-label="Go back"
        >
          <span aria-hidden="true">‹</span>
        </button>

        <div className="private-room-heading">
          <span className="panel-label">Play With Friends</span>
          <h1>Private Table</h1>
          <p>
            Create a room for your group or join with a code and sit down with
            your own people.
          </p>
        </div>

        {statusMessage ? (
          <div className="board-status-banner private-room-status-banner">
            {statusMessage}
          </div>
        ) : null}

        <section className="private-room-card">
          <div className="private-room-toggle">
            <button
              type="button"
              className={isCreate ? "is-active" : ""}
              onClick={() => onChangeTab("create")}
            >
              Create Room
            </button>
            <button
              type="button"
              className={!isCreate ? "is-active" : ""}
              onClick={() => onChangeTab("join")}
            >
              Join Room
            </button>
          </div>

          {isCreate ? (
            <form
              className="private-room-form"
              onSubmit={(event) => {
                event.preventDefault();
                onSubmitCreate();
              }}
            >
              <label className="private-room-field">
                <span>Room Name</span>
                <input
                  type="text"
                  value={roomName}
                  onChange={(event) => onRoomNameChange(event.target.value)}
                  placeholder="Weekend Table"
                  maxLength={32}
                />
              </label>

              <label className="private-room-field">
                <span>Your Name</span>
                <input
                  type="text"
                  value={displayName}
                  onChange={(event) => onDisplayNameChange(event.target.value)}
                  placeholder="Enter nickname"
                  maxLength={24}
                />
              </label>

              <div className="private-room-field-grid private-room-field-grid-single">
                <label className="private-room-field">
                  <span>Entry Fee</span>
                  <input
                    type="number"
                    inputMode="numeric"
                    min="1"
                    step="1"
                    value={entryFee}
                    onChange={(event) => onEntryFeeChange(event.target.value)}
                    placeholder="100"
                  />
                </label>
              </div>

              <button type="submit" className="private-room-primary-button">
                Create And Enter
              </button>
            </form>
          ) : (
            <form
              className="private-room-form"
              onSubmit={(event) => {
                event.preventDefault();
                onSubmitJoin();
              }}
            >
              <label className="private-room-field">
                <span>Room Code</span>
                <input
                  type="text"
                  value={roomCode}
                  onChange={(event) =>
                    onRoomCodeChange(event.target.value.toUpperCase())
                  }
                  placeholder="Paste code"
                  maxLength={6}
                />
              </label>

              <label className="private-room-field">
                <span>Your Name</span>
                <input
                  type="text"
                  value={displayName}
                  onChange={(event) => onDisplayNameChange(event.target.value)}
                  placeholder="Enter nickname"
                  maxLength={24}
                />
              </label>
              <button type="submit" className="private-room-primary-button">
                Join Room
              </button>
            </form>
          )}
        </section>
      </section>
    </AppFrame>
  );
}

function PrivateRoomLobbyScreen({
  appState,
  room,
  session,
  statusMessage = "",
  copiedCode = false,
  onBack,
  onCopyCode,
  onStart,
  onTransferHost,
  onLeave,
  onOpenUtilities,
  onOpenWallet,
  onOpenHistory,
}) {
  const isHost = session?.userId === room.hostUserId;

  return (
    <AppFrame
      screenClassName="screen-board screen-board-match"
      backdrop={<GameBackdrop />}
    >
      <BoardHeader
        profile={appState.profile}
        wallet={appState.wallet}
        onUtilities={onOpenUtilities}
        onOpenWallet={onOpenWallet}
        onNotifications={onOpenHistory}
      />

      {statusMessage ? (
        <div className="board-status-banner">{statusMessage}</div>
      ) : null}

      <section className="board-room-strip">
        <div>
          <span className="board-room-label">Room</span>
          <strong>{room.roomName}</strong>
        </div>
        <div>
          <span className="board-room-label">Entry Fee</span>
          <strong>{formatCurrency(room.entryFee ?? 0)}</strong>
        </div>
        <div>
          <span className="board-room-label">Players</span>
          <strong>
            {room.occupiedSeats}/{room.maxPlayers}
          </strong>
        </div>
      </section>

      <section className="private-room-lobby-panel utility-panel">
        <div className="private-room-lobby-toprow">
          <button
            type="button"
            className="private-room-back-button"
            onClick={onBack}
            aria-label="Go back"
          >
            <span aria-hidden="true">‹</span>
          </button>
          <span className="waiting-lobby-badge">
            {isHost
              ? "You are host"
              : `${room.hostDisplayName ?? "Host"} is host`}
          </span>
        </div>

        <div className="private-room-code-card">
          <span className="waiting-timer-label">Room Code</span>
          <strong>{room.roomCode}</strong>
          <button
            type="button"
            className="private-room-copy-button"
            onClick={onCopyCode}
          >
            {copiedCode ? "Copied" : "Copy Code"}
          </button>
        </div>

        <p className="waiting-lobby-copy">
          Share this code with your friends. The host can start when the room
          has at least 2 players.
        </p>

        <div className="private-room-member-list">
          {room.members.map((member) => {
            const canTransfer = isHost && member.userId !== session?.userId;

            return (
              <article key={member.userId} className="private-room-member-card">
                <div>
                  <strong>{member.displayName}</strong>
                  <span>{member.isHost ? "Host" : `Seat ${member.color}`}</span>
                </div>

                {canTransfer ? (
                  <button
                    type="button"
                    className="private-room-host-button"
                    onClick={() => onTransferHost(member.userId)}
                  >
                    Make Host
                  </button>
                ) : (
                  <span className="private-room-member-badge">
                    {member.userId === session?.userId ? "You" : member.color}
                  </span>
                )}
              </article>
            );
          })}
        </div>

        {isHost ? (
          <button
            type="button"
            className="private-room-primary-button"
            onClick={onStart}
            disabled={room.members.length < 2}
          >
            Start Game
          </button>
        ) : null}

        <button
          type="button"
          className="waiting-leave-button"
          onClick={onLeave}
        >
          Leave Room
        </button>
      </section>
    </AppFrame>
  );
}

const PANEL_ROUTES = {
  menu: "/",
};

function withOperatorLaunchParams(href) {
  if (
    !isOperatorPlatformEnabled() ||
    typeof window === "undefined" ||
    !window.location.search
  ) {
    return href;
  }

  const currentParams = new URLSearchParams(window.location.search);
  const launchId = currentParams.get("id")?.trim();
  const gameId = currentParams.get("game_id") ?? currentParams.get("gameId");

  if (!launchId || !gameId) {
    return href;
  }

  const [pathname, hash = ""] = href.split("#");
  const [path, query = ""] = pathname.split("?");
  const nextParams = new URLSearchParams(query);
  nextParams.set("id", launchId);
  nextParams.set("game_id", gameId);

  return `${path}?${nextParams.toString()}${hash ? `#${hash}` : ""}`;
}

function navigateToHref(router, href, { replace = false } = {}) {
  const nextHref = withOperatorLaunchParams(href);

  if (typeof window !== "undefined") {
    if (replace) {
      window.location.replace(nextHref);
    } else {
      window.location.assign(nextHref);
    }
    return;
  }

  if (replace) {
    router.replace(nextHref);
  } else {
    router.push(nextHref);
  }
}

function navigateToPanel(router, panel, onAfterNavigate) {
  const href = PANEL_ROUTES[panel] ?? PANEL_ROUTES.menu;

  onAfterNavigate?.();
  navigateToHref(router, href);
}

function navigateToMode(router, mode, onAfterNavigate) {
  const href = PLAY_ROUTES[mode];

  if (!href) {
    return;
  }

  onAfterNavigate?.();
  navigateToHref(router, href);
}

function useOperatorGatewayConsoleLogs(sessionToken) {
  useEffect(() => {
    return subscribeOperatorGatewayLogs(sessionToken, (event) => {
      console.info(`[Ludo operator gateway] ${event.action ?? event.eventType}`, {
        type: event.eventType,
        gameUserId: event.gameUserId,
        userId: event.userId,
        operatorId: event.operatorId,
        txnId: event.txnId,
        txnRefId: event.txnRefId,
        amount: event.amount,
        description: event.description,
        target: event.target,
        ip: event.ip,
        gameId: event.gameId,
        exchange: event.exchange,
        routingKey: event.routingKey,
        createdAt: event.createdAt,
      });
    });
  }, [sessionToken]);
}

export function MenuPageShell({ appState = mockBootState }) {
  const router = useRouter();
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [isFriendsRoomOpen, setIsFriendsRoomOpen] = useState(false);
  const [friendsActiveTab, setFriendsActiveTab] = useState("create");
  const [friendsRoomName, setFriendsRoomName] = useState("Weekend Table");
  const [friendsDisplayName, setFriendsDisplayName] = useState(
    appState.profile.displayName,
  );
  const [friendsEntryFee, setFriendsEntryFee] = useState("100");
  const [friendsRoomCode, setFriendsRoomCode] = useState("");
  const [friendsStatusMessage, setFriendsStatusMessage] = useState("");
  const [isFriendsSubmitting, setIsFriendsSubmitting] = useState(false);
  const { appState: hydratedAppState, accessMessage } =
    useHydratedGuestAppState(appState);
  const [isSoundOn, setIsSoundOn] = useSoundSetting();
  useSoundUnlock();
  useGlobalButtonClickSound();
  useLobbyBackgroundMusic(true);

  if (accessMessage) {
    return <OperatorAccessBlockedScreen message={accessMessage} />;
  }

  function handleModeSelect(modeKey) {
    if (modeKey === "friends") {
      setIsSettingsOpen(false);
      setFriendsStatusMessage("");
      setIsFriendsRoomOpen(true);
      return;
    }

    navigateToMode(router, modeKey, () => setIsSettingsOpen(false));
  }

  function handleCloseFriendsRoom() {
    if (isFriendsSubmitting) {
      return;
    }

    setFriendsStatusMessage("");
    setIsFriendsRoomOpen(false);
  }

  async function handleCreateFriendsRoom() {
    try {
      const parsedEntryFee = Number.parseInt(friendsEntryFee, 10);

      if (!Number.isFinite(parsedEntryFee) || parsedEntryFee <= 0) {
        setFriendsStatusMessage("Enter a valid entry fee greater than 0.");
        return;
      }

      setIsFriendsSubmitting(true);
      setFriendsStatusMessage("Creating private room...");
      const activeSession = await ensureGuestSession(
        friendsDisplayName || hydratedAppState.profile.displayName,
      );

      await createPrivateRoomRequest(activeSession.sessionToken, {
        roomName: friendsRoomName,
        displayName: friendsDisplayName,
        entryFee: parsedEntryFee,
      });

      navigateToHref(router, PLAY_ROUTES.friends);
    } catch (error) {
      setFriendsStatusMessage(
        error.message || "Unable to create the private room.",
      );
      setIsFriendsSubmitting(false);
    }
  }

  async function handleJoinFriendsRoom() {
    try {
      if (!friendsRoomCode.trim()) {
        setFriendsStatusMessage("Enter a room code to join.");
        return;
      }

      setIsFriendsSubmitting(true);
      setFriendsStatusMessage("Joining private room...");
      const activeSession = await ensureGuestSession(
        friendsDisplayName || hydratedAppState.profile.displayName,
      );

      await joinPrivateRoomRequest(activeSession.sessionToken, {
        roomCode: friendsRoomCode.trim(),
        displayName: friendsDisplayName,
      });

      navigateToHref(router, PLAY_ROUTES.friends);
    } catch (error) {
      setFriendsStatusMessage(error.message || "Unable to join the room.");
      setIsFriendsSubmitting(false);
    }
  }

  return (
    <>
      <MenuScreen
        appState={hydratedAppState}
        onModeSelect={handleModeSelect}
        onOpenUtilities={() => setIsSettingsOpen(true)}
        onOpenHistory={() => setIsHistoryOpen(true)}
        onOpenWallet={undefined}
        overlay={
          <>
            {IS_FRIENDS_MODE_VISIBLE ? (
              <FriendsRoomModal
                isOpen={isFriendsRoomOpen}
                activeTab={friendsActiveTab}
                roomName={friendsRoomName}
                displayName={friendsDisplayName}
                entryFee={friendsEntryFee}
                roomCode={friendsRoomCode}
                statusMessage={friendsStatusMessage}
                isSubmitting={isFriendsSubmitting}
                onClose={handleCloseFriendsRoom}
                onChangeTab={(tab) => {
                  setFriendsStatusMessage("");
                  setFriendsActiveTab(tab);
                }}
                onRoomNameChange={setFriendsRoomName}
                onDisplayNameChange={setFriendsDisplayName}
                onEntryFeeChange={setFriendsEntryFee}
                onRoomCodeChange={setFriendsRoomCode}
                onSubmitCreate={handleCreateFriendsRoom}
                onSubmitJoin={handleJoinFriendsRoom}
              />
            ) : null}
            <HomeSideDrawer
              isOpen={isSettingsOpen}
              profile={hydratedAppState.profile}
              wallet={hydratedAppState.wallet}
              isSoundOn={isSoundOn}
              onClose={() => setIsSettingsOpen(false)}
              onToggleSound={() => setIsSoundOn((current) => !current)}
              onOpenHistory={() => setIsHistoryOpen(true)}
              onStartMode={handleModeSelect}
            />
            <HistorySideDrawer
              isOpen={isHistoryOpen}
              history={hydratedAppState.history}
              onClose={() => setIsHistoryOpen(false)}
            />
          </>
        }
      />
    </>
  );
}

function PrivateRoomPageShell({ appState }) {
  const router = useRouter();
  const [isUtilityOpen, setIsUtilityOpen] = useState(false);
  const [isLeaveConfirmOpen, setIsLeaveConfirmOpen] = useState(false);
  const [isLeavingRoom, setIsLeavingRoom] = useState(false);
  const [activeTab, setActiveTab] = useState("create");
  const [roomName, setRoomName] = useState("Weekend Table");
  const [displayName, setDisplayName] = useState(appState.profile.displayName);
  const [entryFee, setEntryFee] = useState("100");
  const [roomCode, setRoomCode] = useState("");
  const [statusMessage, setStatusMessage] = useState("");
  const [session, setSession] = useState(null);
  const [wallet, setWallet] = useState(appState.wallet);
  const [privateRoom, setPrivateRoom] = useState(null);
  const [match, setMatch] = useState(null);
  const [copiedCode, setCopiedCode] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [isRealtimeConnected, setIsRealtimeConnected] = useState(false);
  const socketRef = useRef(null);
  const reconnectTimeoutRef = useRef(null);
  const pollTimeoutRef = useRef(null);
  const moveSyncTimeoutRef = useRef(null);
  const snapshotPollTimeoutRef = useRef(null);
  const forfeitSyncTimeoutRef = useRef(null);
  const latestSequenceRef = useRef(0);
  const settledMatchIdRef = useRef(null);
  const isLeavingRoomRef = useRef(false);
  const [isSubmittingMove, setIsSubmittingMove] = useState(false);
  const [rollingDiceUserId, setRollingDiceUserId] = useState(null);
  const [isMatchIntroComplete, setIsMatchIntroComplete] = useState(false);
  const privateMatchUserId = session?.userId ?? appState.profile.id;

  useLobbyBackgroundMusic(!privateRoom && !match);

  useEffect(() => {
    setIsMatchIntroComplete(false);
  }, [match?.id]);

  const handlePotIntroComplete = useCallback(() => {
    setIsMatchIntroComplete(true);
  }, []);

  useOperatorGatewayConsoleLogs(session?.sessionToken);
  useGameplayTransitionSounds(match);
  useTurnWarningSound({
    match,
    now,
    userPlayerId: privateMatchUserId,
  });
  useBotRollingSnapshotSync({
    match,
    sessionToken: session?.sessionToken,
    setMatch,
    enabled: !isRealtimeConnected,
  });
  useOpponentForfeitSnapshotSync({
    match,
    userPlayerId: privateMatchUserId,
    sessionToken: session?.sessionToken,
    setMatch,
    timeoutRef: forfeitSyncTimeoutRef,
  });
  const returnToMenu = useCallback(() => {
    navigateToHref(router, PANEL_ROUTES.menu);
  }, [router]);
  const startNewPrivateRoom = useCallback(() => {
    setPrivateRoom(null);
    setMatch(null);
    setStatusMessage("");
    setCopiedCode(false);
    setActiveTab("create");
  }, []);
  useMatchResultSound(match, privateMatchUserId);

  useEffect(() => {
    latestSequenceRef.current = match?.sequence ?? 0;
  }, [match?.sequence]);

  useEffect(() => {
    const intervalId = window.setInterval(() => {
      setNow(Date.now());
    }, TURN_TICK_MS);

    return () => window.clearInterval(intervalId);
  }, []);

  useEffect(() => {
    let cancelled = false;

    async function refreshWallet(sessionToken) {
      try {
        const walletOverview = await fetchWalletOverview(sessionToken);
        if (!cancelled) {
          setWallet(normalizeWalletResponse(walletOverview));
        }
      } catch {}
    }

    async function bootstrapPrivateRoom() {
      try {
        const activeSession = await ensureGuestSession(
          displayName || appState.profile.displayName,
        );

        if (cancelled) {
          return;
        }

        setSession(activeSession);
        setDisplayName(
          (currentName) => currentName || activeSession.displayName,
        );
        await refreshWallet(activeSession.sessionToken);

        const currentRoom = await fetchPrivateRoomState(
          activeSession.sessionToken,
        );
        if (cancelled || !currentRoom) {
          return;
        }

        const normalizedRoom = normalizePrivateRoomState(currentRoom);
        setPrivateRoom(normalizedRoom);
        setCopiedCode(false);

        if (normalizedRoom.match) {
          setMatch(normalizedRoom.match);
          setStatusMessage("Syncing private table...");
        }
      } catch (error) {
        if (!cancelled) {
          setStatusMessage(
            error.message || "Unable to connect to the private table.",
          );
        }
      }
    }

    bootstrapPrivateRoom();

    return () => {
      cancelled = true;
    };
  }, [appState.profile.displayName]);

  useEffect(() => {
    if (!session?.sessionToken || !privateRoom?.match?.id) {
      return undefined;
    }

    let cancelled = false;

    function clearRealtimeConnection() {
      if (socketRef.current) {
        socketRef.current.close();
        socketRef.current = null;
      }
      setIsRealtimeConnected(false);

      if (reconnectTimeoutRef.current) {
        window.clearTimeout(reconnectTimeoutRef.current);
        reconnectTimeoutRef.current = null;
      }
    }

    function connectSocket(websocketPath) {
      clearRealtimeConnection();

      const socket = new window.WebSocket(toWebSocketUrl(websocketPath));
      socketRef.current = socket;

      socket.onopen = () => {
        if (!cancelled) {
          setIsRealtimeConnected(true);
          setStatusMessage("");
        }
      };

      socket.onmessage = (event) => {
        try {
          const envelope = JSON.parse(event.data);

          if (envelope.type === "match_snapshot" && envelope.match) {
            applyFreshMatch(setMatch, normalizeMatchSnapshot(envelope.match));
            setIsSubmittingMove(false);
            setStatusMessage("");
          }
        } catch {}
      };

      socket.onclose = () => {
        if (cancelled) {
          return;
        }

        setIsRealtimeConnected(false);
        reconnectTimeoutRef.current = window.setTimeout(() => {
          connectSocket(websocketPath);
        }, ONLINE_SOCKET_RECONNECT_DELAY_MS);
      };

      socket.onerror = () => {
        if (!cancelled) {
          setIsRealtimeConnected(false);
        }
      };
    }

    async function syncMatchSnapshotIfSocketClosed() {
      const socket = socketRef.current;
      const isSocketOpen =
        socket && socket.readyState === window.WebSocket.OPEN;

      if (!isSocketOpen) {
        try {
          const latestMatch = await fetchMatchSnapshot(
            session.sessionToken,
            privateRoom.match.id,
          );

          if (!cancelled) {
            applyFreshMatch(setMatch, normalizeMatchSnapshot(latestMatch));
          }
        } catch {}
      }
    }

    connectSocket(privateRoom.websocketPath);
    snapshotPollTimeoutRef.current = window.setTimeout(
      syncMatchSnapshotIfSocketClosed,
      MATCH_SNAPSHOT_FALLBACK_POLL_MS,
    );

    return () => {
      cancelled = true;
      clearRealtimeConnection();

      if (snapshotPollTimeoutRef.current) {
        window.clearTimeout(snapshotPollTimeoutRef.current);
        snapshotPollTimeoutRef.current = null;
      }
    };
  }, [
    privateRoom?.match?.id,
    privateRoom?.websocketPath,
    session?.sessionToken,
  ]);

  useEffect(() => {
    if (!session?.sessionToken || !privateRoom || privateRoom.match) {
      return undefined;
    }

    let cancelled = false;

    async function refreshWallet(sessionToken) {
      try {
        const walletOverview = await fetchWalletOverview(sessionToken);
        if (!cancelled) {
          setWallet(normalizeWalletResponse(walletOverview));
        }
      } catch {}
    }

    async function pollRoom() {
      try {
        const currentRoom = await fetchPrivateRoomState(session.sessionToken);

        if (cancelled) {
          return;
        }

        if (!currentRoom) {
          setPrivateRoom(null);
          setMatch(null);
          setStatusMessage("");
          return;
        }

        const normalizedRoom = normalizePrivateRoomState(currentRoom);
        setPrivateRoom(normalizedRoom);

        if (normalizedRoom.match) {
          setMatch(normalizedRoom.match);
          setStatusMessage("Syncing private table...");
          await refreshWallet(session.sessionToken);
          return;
        }

        pollTimeoutRef.current = window.setTimeout(pollRoom, 2000);
      } catch (error) {
        if (!cancelled) {
          setStatusMessage(
            error.message || "Unable to refresh the private table.",
          );
        }
      }
    }

    pollTimeoutRef.current = window.setTimeout(pollRoom, 2000);

    return () => {
      cancelled = true;
      if (pollTimeoutRef.current) {
        window.clearTimeout(pollTimeoutRef.current);
        pollTimeoutRef.current = null;
      }
    };
  }, [privateRoom?.roomId, privateRoom?.match, session?.sessionToken]);

  useEffect(() => {
    if (!session?.sessionToken || !match?.id || match.phase !== "finished") {
      return undefined;
    }

    if (settledMatchIdRef.current === match.id) {
      return undefined;
    }

    settledMatchIdRef.current = match.id;
    let cancelled = false;

    async function refreshWalletAfterSettlement() {
      try {
        const walletOverview = await fetchWalletOverview(session.sessionToken);

        if (!cancelled) {
          setWallet(normalizeWalletResponse(walletOverview));
        }
      } catch {}
    }

    refreshWalletAfterSettlement();

    return () => {
      cancelled = true;
    };
  }, [match?.id, match?.phase, session?.sessionToken]);

  useEffect(() => {
    if (!privateRoom || match) {
      setIsLeaveConfirmOpen(false);
      return undefined;
    }

    const pushGuardState = () => {
      window.history.pushState(
        {
          ...(window.history.state ?? {}),
          privateRoomGuard: true,
          stamp: Date.now(),
        },
        "",
        window.location.href,
      );
    };

    const handlePopState = () => {
      if (isLeavingRoomRef.current) {
        return;
      }

      setIsLeaveConfirmOpen(true);
      pushGuardState();
    };

    pushGuardState();
    window.addEventListener("popstate", handlePopState);

    return () => {
      window.removeEventListener("popstate", handlePopState);
    };
  }, [Boolean(privateRoom), Boolean(match)]);

  async function refreshWallet(sessionToken) {
    try {
      const walletOverview = await fetchWalletOverview(sessionToken);
      setWallet(normalizeWalletResponse(walletOverview));
    } catch {}
  }

  async function ensureActiveSession(preferredName) {
    const activeSession =
      session ??
      (await ensureGuestSession(preferredName || appState.profile.displayName));

    setSession(activeSession);
    return activeSession;
  }

  async function handleCreateRoom() {
    try {
      const parsedEntryFee = Number.parseInt(entryFee, 10);
      if (!Number.isFinite(parsedEntryFee) || parsedEntryFee <= 0) {
        setStatusMessage("Enter a valid entry fee greater than 0.");
        return;
      }

      setStatusMessage("Creating private room...");
      const activeSession = await ensureActiveSession(displayName);
      const createdRoom = await createPrivateRoomRequest(
        activeSession.sessionToken,
        {
          roomName,
          displayName,
          entryFee: parsedEntryFee,
        },
      );
      const normalizedRoom = normalizePrivateRoomState(createdRoom);
      setPrivateRoom(normalizedRoom);
      setMatch(normalizedRoom.match);
      setCopiedCode(false);
      setStatusMessage("");
      await refreshWallet(activeSession.sessionToken);
    } catch (error) {
      setStatusMessage(error.message || "Unable to create the private room.");
    }
  }

  async function handleJoinRoom() {
    try {
      setStatusMessage("Joining private room...");
      const activeSession = await ensureActiveSession(displayName);
      const joinedRoom = await joinPrivateRoomRequest(
        activeSession.sessionToken,
        {
          roomCode,
          displayName,
        },
      );
      const normalizedRoom = normalizePrivateRoomState(joinedRoom);
      setPrivateRoom(normalizedRoom);
      setMatch(normalizedRoom.match);
      setCopiedCode(false);
      setStatusMessage("");
      await refreshWallet(activeSession.sessionToken);
    } catch (error) {
      setStatusMessage(error.message || "Unable to join the private room.");
    }
  }

  async function handleCopyCode() {
    if (!privateRoom?.roomCode) {
      return;
    }

    try {
      await navigator.clipboard.writeText(privateRoom.roomCode);
      setCopiedCode(true);
      window.setTimeout(() => setCopiedCode(false), 1800);
    } catch {
      setStatusMessage("Unable to copy the room code on this device.");
    }
  }

  async function handleStartRoom() {
    if (!session?.sessionToken) {
      return;
    }

    try {
      setStatusMessage("Starting private table...");
      const startedRoom = await startPrivateRoomRequest(session.sessionToken);
      const normalizedRoom = normalizePrivateRoomState(startedRoom);
      setPrivateRoom(normalizedRoom);
      setMatch(normalizedRoom.match);
      await refreshWallet(session.sessionToken);
      setStatusMessage(normalizedRoom.match ? "Syncing private table..." : "");
    } catch (error) {
      setStatusMessage(error.message || "Unable to start the private table.");
    }
  }

  async function handleTransferHost(targetUserId) {
    if (!session?.sessionToken) {
      return;
    }

    try {
      setStatusMessage("Changing room host...");
      const updatedRoom = await transferPrivateRoomHost(
        session.sessionToken,
        targetUserId,
      );
      setPrivateRoom(normalizePrivateRoomState(updatedRoom));
      setStatusMessage("");
    } catch (error) {
      setStatusMessage(error.message || "Unable to change the host.");
    }
  }

  async function handleConfirmLeaveRoom() {
    if (!session?.sessionToken) {
      return;
    }

    try {
      setIsLeavingRoom(true);
      setIsUtilityOpen(false);
      isLeavingRoomRef.current = true;
      setStatusMessage("Leaving room...");
      await leavePrivateRoom(session.sessionToken);
      setIsLeaveConfirmOpen(false);
      navigateToHref(router, PANEL_ROUTES.menu);
    } catch (error) {
      setIsLeavingRoom(false);
      isLeavingRoomRef.current = false;
      setStatusMessage(error.message || "Unable to leave the room.");
      setIsLeaveConfirmOpen(false);
    }
  }

  async function handleSelectToken(tokenIndex) {
    if (!session?.sessionToken || !match?.id || isSubmittingMove) {
      return;
    }

    if (match.phase !== "awaiting-move" || match.currentTurnUserId !== session.userId) {
      return;
    }

    try {
      setIsSubmittingMove(true);
      const optimisticMatch = buildOptimisticTokenMove(
        match,
        tokenIndex,
        session.userId,
      );
      if (optimisticMatch) {
        applyFreshMatch(setMatch, optimisticMatch);
      }
      const updatedMatch = await submitMatchMove(
        session.sessionToken,
        match.id,
        tokenIndex,
      );
      const normalizedMatch = normalizeMatchSnapshot(updatedMatch);
      applyFreshMatch(setMatch, normalizedMatch);
      setStatusMessage("");
      setIsSubmittingMove(false);

      scheduleMatchSnapshotSync({
        timeoutRef: moveSyncTimeoutRef,
        sessionToken: session.sessionToken,
        matchId: match.id,
        minimumSequence: normalizedMatch.sequence,
        setMatch,
        enabled: !isRealtimeConnected,
      });
    } catch (error) {
      try {
        const latestMatch = await fetchMatchSnapshot(
          session.sessionToken,
          match.id,
        );
        applyFreshMatch(setMatch, normalizeMatchSnapshot(latestMatch));
      } catch {}
      setIsSubmittingMove(false);
      setStatusMessage(error.message || "Unable to move the selected token.");
    }
  }

  useEffect(() => {
    if (
      !isMatchIntroComplete ||
      !session?.sessionToken ||
      !match?.id ||
      isSubmittingMove ||
      match.phase !== "awaiting-move" ||
      match.currentTurnUserId !== session.userId
    ) {
      return undefined;
    }

    const forcedTokenIndex = resolveForcedAutoMoveToken(
      match.selectableTokenIndexes,
    );

    if (forcedTokenIndex == null) {
      return undefined;
    }

    const timeoutId = window.setTimeout(() => {
      handleSelectToken(forcedTokenIndex);
    }, 0);

    return () => window.clearTimeout(timeoutId);
  }, [
    isMatchIntroComplete,
    isSubmittingMove,
    match?.currentTurnUserId,
    match?.id,
    match?.phase,
    match?.selectableTokenIndexes,
    match?.sequence,
    session?.sessionToken,
    session?.userId,
  ]);

  async function handleRollDice() {
    if (
      !isMatchIntroComplete ||
      !session?.sessionToken ||
      !match?.id ||
      match.phase !== "rolling" ||
      match.currentTurnUserId !== session.userId ||
      isSubmittingMove
    ) {
      return;
    }

    try {
      const rollStartedAt = Date.now();
      setRollingDiceUserId(session.userId);
      beginDiceRollSound();
      setIsSubmittingMove(true);
      const updatedMatch = await rollMatchDice(session.sessionToken, match.id);
      let normalizedMatch = normalizeMatchSnapshot(updatedMatch);
      applyFreshMatch(setMatch, normalizedMatch);
      setStatusMessage("");
      setIsSubmittingMove(false);

      const forcedTokenIndex = resolveForcedAutoMoveToken(
        normalizedMatch.selectableTokenIndexes,
      );
      if (
        normalizedMatch.phase === "awaiting-move" &&
        forcedTokenIndex != null &&
        normalizedMatch.currentTurnUserId === session.userId
      ) {
        setIsSubmittingMove(true);
        const movedMatch = await submitMatchMove(
          session.sessionToken,
          match.id,
          forcedTokenIndex,
        );
        normalizedMatch = normalizeMatchSnapshot(movedMatch);
        applyFreshMatch(setMatch, normalizedMatch);
        setIsSubmittingMove(false);
      }

      await waitForMinimumDuration(rollStartedAt, DICE_ROLL_MIN_SPIN_MS);

      scheduleMatchSnapshotSync({
        timeoutRef: moveSyncTimeoutRef,
        sessionToken: session.sessionToken,
        matchId: match.id,
        minimumSequence: normalizedMatch.sequence,
        setMatch,
        enabled: !isRealtimeConnected,
      });
    } catch (error) {
      setStatusMessage(error.message || "Unable to roll the dice.");
    } finally {
      setRollingDiceUserId(null);
      setIsSubmittingMove(false);
    }
  }

  const composedAppState = useMemo(
    () => ({
      ...buildRuntimeAppState(appState, session, wallet),
      liveMatch: match ?? appState.liveMatch,
    }),
    [appState, match, session, wallet],
  );
  const turnProgress = match?.turnDeadlineAt
    ? Math.max(
        0,
        Math.min(
          1,
          (new Date(match.turnDeadlineAt).getTime() - now) /
            ((match.turnTimeoutSeconds ?? 30) * 1000),
        ),
      )
    : 0;

  function handleSelectPanel(panel) {
    navigateToPanel(router, panel, () => setIsUtilityOpen(false));
  }

  function handleModeSelect(modeKey) {
    navigateToMode(router, modeKey, () => setIsUtilityOpen(false));
  }

  return (
    <>
      {isLeavingRoom ? (
        <BoardTransitionScreen
          appState={composedAppState}
          title="Leaving Room"
          statusMessage={statusMessage || "Leaving room..."}
        />
      ) : match ? (
        <BoardScreen
          appState={composedAppState}
          match={match}
          turnProgress={turnProgress}
          userPlayerId={session?.userId ?? composedAppState.profile.id}
          statusMessage={statusMessage}
          rollingDiceUserId={rollingDiceUserId}
          onSelectToken={handleSelectToken}
          onRollDice={handleRollDice}
          onLeave={() => setIsLeaveConfirmOpen(true)}
          onOpenUtilities={() => setIsUtilityOpen(true)}
          onOpenWallet={undefined}
          onOpenHistory={() => {}}
          onPotIntroComplete={handlePotIntroComplete}
        />
      ) : privateRoom ? (
        <PrivateRoomLobbyScreen
          appState={composedAppState}
          room={privateRoom}
          session={session}
          statusMessage={statusMessage}
          copiedCode={copiedCode}
          onBack={() => setIsLeaveConfirmOpen(true)}
          onCopyCode={handleCopyCode}
          onStart={handleStartRoom}
          onTransferHost={handleTransferHost}
          onLeave={() => setIsLeaveConfirmOpen(true)}
          onOpenUtilities={() => setIsUtilityOpen(true)}
          onOpenWallet={undefined}
          onOpenHistory={() => {}}
        />
      ) : (
        <PrivateRoomSetupScreen
          appState={composedAppState}
          activeTab={activeTab}
          roomName={roomName}
          displayName={displayName}
          entryFee={entryFee}
          roomCode={roomCode}
          statusMessage={statusMessage}
          onBack={() =>
            navigateToHref(router, PANEL_ROUTES.menu)
          }
          onChangeTab={setActiveTab}
          onRoomNameChange={setRoomName}
          onDisplayNameChange={setDisplayName}
          onEntryFeeChange={setEntryFee}
          onRoomCodeChange={setRoomCode}
          onSubmitCreate={handleCreateRoom}
          onSubmitJoin={handleJoinRoom}
          onOpenUtilities={() => setIsUtilityOpen(true)}
          onOpenWallet={undefined}
          onOpenHistory={() => {}}
        />
      )}

      <UtilitySheet
        isOpen={isUtilityOpen}
        onClose={() => setIsUtilityOpen(false)}
        onSelectPanel={handleSelectPanel}
        onStartMode={handleModeSelect}
      />

      <ConfirmDialog
        isOpen={isLeaveConfirmOpen}
        title="Leave Private Room?"
        message={
          match
            ? getLeaveActiveMatchMessage({
                isPrivate: true,
                activeHumanCount: countActiveHumanPlayers(match.players),
              })
            : "If you leave now, you will exit the private room and no entry fee will be deducted."
        }
        onConfirm={handleConfirmLeaveRoom}
        onCancel={() => setIsLeaveConfirmOpen(false)}
      />
      <MatchResultDialog
        match={match}
        userPlayerId={privateMatchUserId}
        onGoHome={returnToMenu}
        onStartNewGame={startNewPrivateRoom}
        newGameLabel="New Room"
      />
    </>
  );
}

function OnlineBoardPageShell({ appState, configuredMaxPlayers }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const maxPlayers =
    configuredMaxPlayers === 2 || searchParams.get("players") === "2" ? 2 : 4;
  const [isUtilityOpen, setIsUtilityOpen] = useState(false);
  const [isLeaveConfirmOpen, setIsLeaveConfirmOpen] = useState(false);
  const [isOnlineBootstrapping, setIsOnlineBootstrapping] = useState(true);
  const [isLeavingOnlineRoom, setIsLeavingOnlineRoom] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [statusMessage, setStatusMessage] = useState(
    "Preparing online match...",
  );
  const [session, setSession] = useState(null);
  const [wallet, setWallet] = useState(appState.wallet);
  const [lobbyRoom, setLobbyRoom] = useState(null);
  const [match, setMatch] = useState(null);
  const [onlineRestartKey, setOnlineRestartKey] = useState(0);
  const [isSubmittingMove, setIsSubmittingMove] = useState(false);
  const [rollingDiceUserId, setRollingDiceUserId] = useState(null);
  const [isMatchIntroComplete, setIsMatchIntroComplete] = useState(false);
  const [isRealtimeConnected, setIsRealtimeConnected] = useState(false);
  const socketRef = useRef(null);
  const reconnectTimeoutRef = useRef(null);
  const moveSyncTimeoutRef = useRef(null);
  const lobbyPollTimeoutRef = useRef(null);
  const snapshotPollTimeoutRef = useRef(null);
  const forfeitSyncTimeoutRef = useRef(null);
  const latestSequenceRef = useRef(match?.sequence ?? 0);
  const settledMatchIdRef = useRef(null);
  const isLeavingOnlineRoomRef = useRef(false);
  const hasActiveOnlineSession = Boolean(
    lobbyRoom || (match?.id && match.phase !== "finished"),
  );
  const onlineUserPlayerId = session?.userId ?? appState.profile.id;

  useLobbyBackgroundMusic(
    (isOnlineBootstrapping || (!lobbyRoom && !match)) && !isLeavingOnlineRoom,
  );

  useEffect(() => {
    setIsMatchIntroComplete(false);
  }, [match?.id]);

  const handlePotIntroComplete = useCallback(() => {
    setIsMatchIntroComplete(true);
  }, []);

  useOperatorGatewayConsoleLogs(session?.sessionToken);
  useGameplayTransitionSounds(match);
  useTurnWarningSound({
    match,
    now,
    userPlayerId: onlineUserPlayerId,
  });
  useBotRollingSnapshotSync({
    match,
    sessionToken: session?.sessionToken,
    setMatch,
    enabled: !isRealtimeConnected,
  });
  useOpponentForfeitSnapshotSync({
    match,
    userPlayerId: onlineUserPlayerId,
    sessionToken: session?.sessionToken,
    setMatch,
    timeoutRef: forfeitSyncTimeoutRef,
  });
  const returnToMenu = useCallback(() => {
    navigateToHref(router, PANEL_ROUTES.menu);
  }, [router]);
  const startNewOnlineGame = useCallback(() => {
    setLobbyRoom(null);
    setMatch(null);
    setStatusMessage("Preparing online match...");
    setIsOnlineBootstrapping(true);
    settledMatchIdRef.current = null;
    setOnlineRestartKey((currentKey) => currentKey + 1);
    navigateToHref(
      router,
      maxPlayers === 2 ? PLAY_ROUTES.online2 : PLAY_ROUTES.online4,
      { replace: true },
    );
  }, [maxPlayers, router]);
  useMatchResultSound(match, onlineUserPlayerId);

  useEffect(() => {
    latestSequenceRef.current = match?.sequence ?? 0;
  }, [match?.sequence]);

  useEffect(() => {
    const intervalId = window.setInterval(() => {
      setNow(Date.now());
    }, TURN_TICK_MS);

    return () => window.clearInterval(intervalId);
  }, []);

  useEffect(() => {
    let cancelled = false;

    function clearRealtimeConnection() {
      if (socketRef.current) {
        socketRef.current.close();
        socketRef.current = null;
      }
      setIsRealtimeConnected(false);

      if (moveSyncTimeoutRef.current) {
        window.clearTimeout(moveSyncTimeoutRef.current);
        moveSyncTimeoutRef.current = null;
      }

      if (reconnectTimeoutRef.current) {
        window.clearTimeout(reconnectTimeoutRef.current);
        reconnectTimeoutRef.current = null;
      }

      if (lobbyPollTimeoutRef.current) {
        window.clearTimeout(lobbyPollTimeoutRef.current);
        lobbyPollTimeoutRef.current = null;
      }
    }

    function connectSocket(websocketPath) {
      clearRealtimeConnection();

      const socket = new window.WebSocket(toWebSocketUrl(websocketPath));
      socketRef.current = socket;

      socket.onopen = () => {
        if (!cancelled) {
          setIsRealtimeConnected(true);
          setStatusMessage("");
        }
      };

      socket.onmessage = (event) => {
        try {
          const envelope = JSON.parse(event.data);

          if (envelope.type === "match_snapshot" && envelope.match) {
            applyFreshMatch(setMatch, normalizeMatchSnapshot(envelope.match));
            setIsSubmittingMove(false);
            setStatusMessage("");
          }
        } catch {}
      };

      socket.onclose = () => {
        if (cancelled) {
          return;
        }

        setIsRealtimeConnected(false);
        reconnectTimeoutRef.current = window.setTimeout(() => {
          connectSocket(websocketPath);
        }, ONLINE_SOCKET_RECONNECT_DELAY_MS);
      };

      socket.onerror = () => {
        if (!cancelled) {
          setIsRealtimeConnected(false);
        }
      };
    }

    async function refreshWallet(sessionToken) {
      try {
        const walletOverview = await fetchWalletOverview(sessionToken);
        if (!cancelled) {
          setWallet(normalizeWalletResponse(walletOverview));
        }
      } catch {}
    }

    async function pollLobby(sessionToken) {
      try {
        const response = await joinOnlineMatch(sessionToken, maxPlayers);

        if (cancelled) {
          return;
        }

        if (response.match && response.websocketPath) {
          setLobbyRoom(null);
          applyFreshMatch(setMatch, normalizeMatchSnapshot(response.match));
          setIsOnlineBootstrapping(false);
          settledMatchIdRef.current = null;
          await refreshWallet(sessionToken);
          if (!cancelled) {
            setStatusMessage("Syncing live match...");
            connectSocket(response.websocketPath);
          }
          return;
        }

        setLobbyRoom(response.room);
        setMatch(null);
        setIsOnlineBootstrapping(false);
        setStatusMessage(
          isOnlineLobbyStarting(response.room) ? "Starting match..." : "",
        );
        lobbyPollTimeoutRef.current = window.setTimeout(() => {
          pollLobby(sessionToken);
        }, resolveOnlineLobbyPollDelayMs(response.room, response.match));
      } catch (error) {
        if (!cancelled) {
          console.error("[Ludo online lobby] Failed to poll or start online match", {
            message: error.message,
            details: error.details,
          });
          setIsOnlineBootstrapping(false);
          setStatusMessage(
            error.message || "Unable to connect to the game server.",
          );
          lobbyPollTimeoutRef.current = window.setTimeout(() => {
            pollLobby(sessionToken);
          }, ONLINE_LOBBY_POLL_MS);
        }
      }
    }

    async function bootstrapOnlineMatch() {
      try {
        const activeSession = await ensureGuestSession(
          appState.profile.displayName,
        );

        if (cancelled) {
          return;
        }

        setSession(activeSession);
        await refreshWallet(activeSession.sessionToken);
        await pollLobby(activeSession.sessionToken);
        await refreshWallet(activeSession.sessionToken);
      } catch (error) {
        if (!cancelled) {
          setIsOnlineBootstrapping(false);
          setStatusMessage(
            error.message || "Unable to connect to the game server.",
          );
        }
      }
    }

    bootstrapOnlineMatch();

    return () => {
      cancelled = true;
      clearRealtimeConnection();

      if (snapshotPollTimeoutRef.current) {
        window.clearTimeout(snapshotPollTimeoutRef.current);
        snapshotPollTimeoutRef.current = null;
      }
    };
  }, [appState.profile.displayName, maxPlayers, onlineRestartKey]);

  useEffect(() => {
    if (!session?.sessionToken || !match?.id || lobbyRoom) {
      return undefined;
    }

    let cancelled = false;

    async function syncSnapshotIfSocketClosed() {
      const socket = socketRef.current;
      const isSocketOpen =
        socket && socket.readyState === window.WebSocket.OPEN;

      if (!isSocketOpen) {
        try {
          const latestMatch = await fetchMatchSnapshot(
            session.sessionToken,
            match.id,
          );

          if (!cancelled) {
            applyFreshMatch(setMatch, normalizeMatchSnapshot(latestMatch));
          }
        } catch {}
      }
    }

    snapshotPollTimeoutRef.current = window.setTimeout(
      syncSnapshotIfSocketClosed,
      MATCH_SNAPSHOT_FALLBACK_POLL_MS,
    );

    return () => {
      cancelled = true;

      if (snapshotPollTimeoutRef.current) {
        window.clearTimeout(snapshotPollTimeoutRef.current);
        snapshotPollTimeoutRef.current = null;
      }
    };
  }, [lobbyRoom, match?.id, session?.sessionToken]);

  useEffect(() => {
    if (!session?.sessionToken || !match?.id || match.phase !== "finished") {
      return;
    }

    if (settledMatchIdRef.current === match.id) {
      return;
    }

    settledMatchIdRef.current = match.id;

    let cancelled = false;

    async function refreshWalletAfterSettlement() {
      try {
        const walletOverview = await fetchWalletOverview(session.sessionToken);

        if (!cancelled) {
          setWallet(normalizeWalletResponse(walletOverview));
        }
      } catch {}
    }

    refreshWalletAfterSettlement();

    return () => {
      cancelled = true;
    };
  }, [match?.id, match?.phase, session?.sessionToken]);

  useEffect(() => {
    if (!hasActiveOnlineSession) {
      setIsLeaveConfirmOpen(false);
      return undefined;
    }

    if (typeof window === "undefined") {
      return undefined;
    }

    const pushGuardState = () => {
      window.history.pushState(
        {
          ...(window.history.state ?? {}),
          onlineRoomGuard: true,
          stamp: Date.now(),
        },
        "",
        window.location.href,
      );
    };

    const handlePopState = () => {
      if (isLeavingOnlineRoomRef.current) {
        return;
      }

      setIsLeaveConfirmOpen(true);
      pushGuardState();
    };

    pushGuardState();
    window.addEventListener("popstate", handlePopState);

    return () => {
      window.removeEventListener("popstate", handlePopState);
    };
  }, [hasActiveOnlineSession]);

  useEffect(() => {
    if (!hasActiveOnlineSession || typeof window === "undefined") {
      return undefined;
    }

    const handleBeforeUnload = (event) => {
      if (isLeavingOnlineRoomRef.current) {
        return undefined;
      }

      event.preventDefault();
      event.returnValue = "";
      return "";
    };

    window.addEventListener("beforeunload", handleBeforeUnload);

    return () => {
      window.removeEventListener("beforeunload", handleBeforeUnload);
    };
  }, [hasActiveOnlineSession]);

  async function handleSelectToken(tokenIndex) {
    if (!session?.sessionToken || !match?.id || isSubmittingMove) {
      return;
    }

    if (match.phase !== "awaiting-move" || match.currentTurnUserId !== session.userId) {
      return;
    }

    try {
      setIsSubmittingMove(true);
      const optimisticMatch = buildOptimisticTokenMove(
        match,
        tokenIndex,
        session.userId,
      );
      if (optimisticMatch) {
        applyFreshMatch(setMatch, optimisticMatch);
      }
      const updatedMatch = await submitMatchMove(
        session.sessionToken,
        match.id,
        tokenIndex,
      );
      const normalizedMatch = normalizeMatchSnapshot(updatedMatch);
      applyFreshMatch(setMatch, normalizedMatch);
      setStatusMessage("");
      setIsSubmittingMove(false);

      scheduleMatchSnapshotSync({
        timeoutRef: moveSyncTimeoutRef,
        sessionToken: session.sessionToken,
        matchId: match.id,
        minimumSequence: normalizedMatch.sequence,
        setMatch,
        enabled: !isRealtimeConnected,
      });
    } catch (error) {
      try {
        const latestMatch = await fetchMatchSnapshot(
          session.sessionToken,
          match.id,
        );
        applyFreshMatch(setMatch, normalizeMatchSnapshot(latestMatch));
      } catch {}
      setIsSubmittingMove(false);
      setStatusMessage(error.message || "Unable to move the selected token.");
    }
  }

  useEffect(() => {
    if (
      !isMatchIntroComplete ||
      !session?.sessionToken ||
      !match?.id ||
      isSubmittingMove ||
      match.phase !== "awaiting-move" ||
      match.currentTurnUserId !== session.userId
    ) {
      return undefined;
    }

    const forcedTokenIndex = resolveForcedAutoMoveToken(
      match.selectableTokenIndexes,
    );

    if (forcedTokenIndex == null) {
      return undefined;
    }

    const timeoutId = window.setTimeout(() => {
      handleSelectToken(forcedTokenIndex);
    }, 0);

    return () => window.clearTimeout(timeoutId);
  }, [
    isMatchIntroComplete,
    isSubmittingMove,
    match?.currentTurnUserId,
    match?.id,
    match?.phase,
    match?.selectableTokenIndexes,
    match?.sequence,
    session?.sessionToken,
    session?.userId,
  ]);

  async function handleRollDice() {
    if (
      !isMatchIntroComplete ||
      !session?.sessionToken ||
      !match?.id ||
      match.phase !== "rolling" ||
      match.currentTurnUserId !== session.userId ||
      isSubmittingMove
    ) {
      return;
    }

    try {
      const rollStartedAt = Date.now();
      setRollingDiceUserId(session.userId);
      beginDiceRollSound();
      setIsSubmittingMove(true);
      const updatedMatch = await rollMatchDice(session.sessionToken, match.id);
      let normalizedMatch = normalizeMatchSnapshot(updatedMatch);
      applyFreshMatch(setMatch, normalizedMatch);
      setStatusMessage("");
      setIsSubmittingMove(false);

      const forcedTokenIndex = resolveForcedAutoMoveToken(
        normalizedMatch.selectableTokenIndexes,
      );
      if (
        normalizedMatch.phase === "awaiting-move" &&
        forcedTokenIndex != null &&
        normalizedMatch.currentTurnUserId === session.userId
      ) {
        setIsSubmittingMove(true);
        const movedMatch = await submitMatchMove(
          session.sessionToken,
          match.id,
          forcedTokenIndex,
        );
        normalizedMatch = normalizeMatchSnapshot(movedMatch);
        applyFreshMatch(setMatch, normalizedMatch);
        setIsSubmittingMove(false);
      }

      await waitForMinimumDuration(rollStartedAt, DICE_ROLL_MIN_SPIN_MS);

      scheduleMatchSnapshotSync({
        timeoutRef: moveSyncTimeoutRef,
        sessionToken: session.sessionToken,
        matchId: match.id,
        minimumSequence: normalizedMatch.sequence,
        setMatch,
        enabled: !isRealtimeConnected,
      });
    } catch (error) {
      setStatusMessage(error.message || "Unable to roll the dice.");
    } finally {
      setRollingDiceUserId(null);
      setIsSubmittingMove(false);
    }
  }

  const composedAppState = useMemo(
    () => ({
      ...buildRuntimeAppState(appState, session, wallet),
      liveMatch: match,
    }),
    [appState, match, session, wallet],
  );
  const turnProgress = match?.turnDeadlineAt
    ? Math.max(
        0,
        Math.min(
          1,
          (new Date(match.turnDeadlineAt).getTime() - now) /
            ((match.turnTimeoutSeconds ?? 30) * 1000),
        ),
      )
    : 0;
  const lobbyCountdownMs = lobbyRoom?.waitingDeadlineAt
    ? new Date(lobbyRoom.waitingDeadlineAt).getTime() - now
    : 0;

  function handleSelectPanel(panel) {
    navigateToPanel(router, panel, () => setIsUtilityOpen(false));
  }

  function handleModeSelect(modeKey) {
    navigateToMode(router, modeKey, () => setIsUtilityOpen(false));
  }

  function handleRequestLeaveOnlineRoom() {
    setIsUtilityOpen(false);
    setIsLeaveConfirmOpen(true);
  }

  async function handleConfirmLeaveOnlineRoom() {
    if (!session?.sessionToken) {
      return;
    }

    try {
      setIsLeavingOnlineRoom(true);
      setIsUtilityOpen(false);
      isLeavingOnlineRoomRef.current = true;
      setStatusMessage(lobbyRoom ? "Leaving lobby..." : "Leaving room...");
      await leaveOnlineRoom(session.sessionToken);
      setIsLeaveConfirmOpen(false);
      navigateToHref(router, PANEL_ROUTES.menu);
    } catch (error) {
      if (error.message?.toLowerCase().includes("not found for user")) {
        setIsLeaveConfirmOpen(false);
        navigateToHref(router, PANEL_ROUTES.menu);
        return;
      }

      setIsLeavingOnlineRoom(false);
      isLeavingOnlineRoomRef.current = false;
      setStatusMessage(
        error.message ||
          (lobbyRoom
            ? "Unable to leave the lobby."
            : "Unable to leave the room."),
      );
      setIsLeaveConfirmOpen(false);
    }
  }

  return (
    <>
      {isLeavingOnlineRoom ? (
        <OnlineLoadingScreen
          appState={composedAppState}
          title="Returning Home"
          statusMessage={
            statusMessage ||
            (lobbyRoom ? "Leaving lobby..." : "Leaving room...")
          }
        />
      ) : isOnlineBootstrapping || (!lobbyRoom && !match) ? (
        <OnlineLoadingScreen
          appState={composedAppState}
          statusMessage={statusMessage}
        />
      ) : lobbyRoom ? (
        <WaitingLobbyScreen
          appState={composedAppState}
          room={lobbyRoom}
          countdownMs={lobbyCountdownMs}
          statusMessage={statusMessage}
          onLeave={handleRequestLeaveOnlineRoom}
          onOpenUtilities={() => setIsUtilityOpen(true)}
          onOpenWallet={undefined}
          onOpenHistory={() => {}}
        />
      ) : (
        <BoardScreen
          appState={composedAppState}
          match={match}
          turnProgress={turnProgress}
          userPlayerId={session?.userId ?? composedAppState.profile.id}
          statusMessage={statusMessage}
          rollingDiceUserId={rollingDiceUserId}
          onSelectToken={handleSelectToken}
          onRollDice={handleRollDice}
          onLeave={handleRequestLeaveOnlineRoom}
          onOpenUtilities={() => setIsUtilityOpen(true)}
          onOpenWallet={undefined}
          onOpenHistory={() => {}}
          onPotIntroComplete={handlePotIntroComplete}
        />
      )}

      <UtilitySheet
        isOpen={isUtilityOpen}
        onClose={() => setIsUtilityOpen(false)}
        onSelectPanel={handleSelectPanel}
        onStartMode={handleModeSelect}
      />

      <ConfirmDialog
        isOpen={isLeaveConfirmOpen}
        title={lobbyRoom ? "Leave Waiting Lobby?" : "Leave Online Room?"}
        message={
          lobbyRoom
            ? "If you leave now, your seat will be removed from the waiting lobby and no coins will be deducted."
            : getLeaveActiveMatchMessage({
                isPrivate: false,
                activeHumanCount: countActiveHumanPlayers(match?.players),
              })
        }
        onConfirm={handleConfirmLeaveOnlineRoom}
        onCancel={() => setIsLeaveConfirmOpen(false)}
      />
      <MatchResultDialog
        match={match}
        userPlayerId={onlineUserPlayerId}
        onGoHome={returnToMenu}
        onStartNewGame={startNewOnlineGame}
        newGameLabel="New Game"
      />
    </>
  );
}

export function BoardPageShell({ mode, appState, onlineMaxPlayers }) {
  useSoundUnlock();
  useGlobalButtonClickSound();

  if (mode === "online") {
    return (
      <OnlineBoardPageShell
        appState={appState ?? createPlayRouteState(mode)}
        configuredMaxPlayers={onlineMaxPlayers}
      />
    );
  }

  if (mode === "private-room") {
    return (
      <PrivateRoomPageShell appState={appState ?? createPlayRouteState(mode)} />
    );
  }

  return <LocalBoardPageShell mode={mode} appState={appState} />;
}

function LocalBoardPageShell({ mode, appState }) {
  const router = useRouter();
  const [isUtilityOpen, setIsUtilityOpen] = useState(false);
  const baseState = useMemo(
    () => appState ?? createPlayRouteState(mode),
    [appState, mode],
  );
  const [match, setMatch] = useState(() =>
    initializeInteractiveMatch(baseState.liveMatch, baseState.profile.id),
  );
  const [now, setNow] = useState(() => Date.now());
  const [rollingDiceUserId, setRollingDiceUserId] = useState(null);
  const [isMatchIntroComplete, setIsMatchIntroComplete] = useState(false);

  useEffect(() => {
    setIsMatchIntroComplete(false);
  }, [match?.id]);

  const handlePotIntroComplete = useCallback(() => {
    setIsMatchIntroComplete(true);
    setMatch((currentMatch) => {
      if (!currentMatch || currentMatch.phase === "finished") {
        return currentMatch;
      }

      return {
        ...currentMatch,
        turnEndsAt:
          Date.now() + (currentMatch.turnTimer ?? 30) * 1000,
      };
    });
  }, []);

  useGameplayTransitionSounds(match);
  useTurnWarningSound({
    match,
    now,
    userPlayerId: baseState.profile.id,
  });

  useEffect(() => {
    setMatch(
      initializeInteractiveMatch(baseState.liveMatch, baseState.profile.id),
    );
  }, [baseState]);

  useEffect(() => {
    const intervalId = window.setInterval(() => {
      setNow(Date.now());
    }, TURN_TICK_MS);

    return () => window.clearInterval(intervalId);
  }, []);

  useEffect(() => {
    if (!isMatchIntroComplete) {
      return undefined;
    }

    if (match.phase !== "rolling" || match.winnerId) {
      return undefined;
    }

    const activePlayer = match.players[match.currentPlayerIndex];
    if (!activePlayer?.isBot) {
      return undefined;
    }

    const timeoutId = window.setTimeout(() => {
      setMatch((currentMatch) => {
        if (currentMatch.phase !== "rolling" || currentMatch.winnerId) {
          return currentMatch;
        }

        return rollInteractiveMatch(currentMatch);
      });
    }, TURN_ROLL_DELAY_MS);

    return () => window.clearTimeout(timeoutId);
  }, [
    isMatchIntroComplete,
    match.phase,
    match.currentPlayerIndex,
    match.players,
    match.winnerId,
  ]);

  useEffect(() => {
    if (!isMatchIntroComplete) {
      return undefined;
    }

    if (match.phase !== "bot-moving" || match.winnerId) {
      return undefined;
    }

    const timeoutId = window.setTimeout(() => {
      setMatch((currentMatch) => {
        if (currentMatch.phase !== "bot-moving" || currentMatch.winnerId) {
          return currentMatch;
        }

        const tokenIndex = chooseBotToken(
          currentMatch.players,
          currentMatch.currentPlayerIndex,
          currentMatch.selectableTokenIndexes,
          currentMatch.dice ?? 1,
        );

        return applyTokenMove(currentMatch, tokenIndex);
      });
    }, BOT_MOVE_DELAY_MS);

    return () => window.clearTimeout(timeoutId);
  }, [
    isMatchIntroComplete,
    match.phase,
    match.currentPlayerIndex,
    match.winnerId,
  ]);

  useEffect(() => {
    if (!isMatchIntroComplete) {
      return undefined;
    }

    if (match.phase !== "advancing" || match.winnerId) {
      return undefined;
    }

    const timeoutId = window.setTimeout(() => {
      setMatch((currentMatch) => {
        if (currentMatch.phase !== "advancing" || currentMatch.winnerId) {
          return currentMatch;
        }

        return startPlayerTurn(
          currentMatch,
          currentMatch.pendingNextPlayerIndex ??
            currentMatch.currentPlayerIndex,
        );
      });
    }, TURN_ADVANCE_DELAY_MS);

    return () => window.clearTimeout(timeoutId);
  }, [
    isMatchIntroComplete,
    match.phase,
    match.pendingNextPlayerIndex,
    match.winnerId,
  ]);

  useEffect(() => {
    if (!isMatchIntroComplete) {
      return;
    }

    if (
      match.winnerId ||
      (match.phase !== "rolling" && match.phase !== "awaiting-move") ||
      now < match.turnEndsAt
    ) {
      return;
    }

    setMatch((currentMatch) => {
      if (
        currentMatch.winnerId ||
        (currentMatch.phase !== "rolling" &&
          currentMatch.phase !== "awaiting-move") ||
        Date.now() < currentMatch.turnEndsAt
      ) {
        return currentMatch;
      }

      const activePlayer =
        currentMatch.players[currentMatch.currentPlayerIndex];

      if (
        !activePlayer.isBot &&
        currentMatch.phase === "awaiting-move"
      ) {
        const forcedTokenIndex = resolveForcedAutoMoveToken(
          currentMatch.selectableTokenIndexes,
        );

        if (forcedTokenIndex != null) {
          return applyTokenMove(currentMatch, forcedTokenIndex);
        }

        // Multiple choices left unused: skip the turn instead of forcing a goti.
        return startPlayerTurn(
          {
            ...currentMatch,
            dice: null,
            selectableTokenIndexes: [],
            events: prependMatchEvent(
              currentMatch.events,
              activePlayer.name,
              "ran out of time.",
            ),
          },
          (currentMatch.currentPlayerIndex + 1) % currentMatch.players.length,
        );
      }

      return startPlayerTurn(
        {
          ...currentMatch,
          events: prependMatchEvent(
            currentMatch.events,
            activePlayer.name,
            "ran out of time.",
          ),
        },
        (currentMatch.currentPlayerIndex + 1) % currentMatch.players.length,
      );
    });
  }, [
    isMatchIntroComplete,
    match.phase,
    match.turnEndsAt,
    match.winnerId,
    now,
  ]);

  function handleSelectToken(tokenIndex) {
    setMatch((currentMatch) => {
      const activePlayer =
        currentMatch.players[currentMatch.currentPlayerIndex];

      if (
        currentMatch.phase !== "awaiting-move" ||
        activePlayer.isBot ||
        !isTokenSelectable(currentMatch.selectableTokenIndexes, tokenIndex)
      ) {
        return currentMatch;
      }

      return applyTokenMove(currentMatch, tokenIndex);
    });
  }

  function handleRollDice() {
    const activePlayer = match.players[match.currentPlayerIndex];

    if (
      !isMatchIntroComplete ||
      match.phase !== "rolling" ||
      match.winnerId ||
      activePlayer.isBot ||
      rollingDiceUserId
    ) {
      return;
    }

    setRollingDiceUserId(activePlayer.id);
    beginDiceRollSound();
    setMatch((currentMatch) => {
      const currentActivePlayer =
        currentMatch.players[currentMatch.currentPlayerIndex];

      if (
        currentMatch.phase !== "rolling" ||
        currentMatch.winnerId ||
        currentActivePlayer.isBot
      ) {
        return currentMatch;
      }

      return rollInteractiveMatch(currentMatch);
    });
    window.setTimeout(() => {
      setRollingDiceUserId(null);
    }, DICE_ROLL_MIN_SPIN_MS);
  }

  const state = useMemo(
    () => ({
      ...baseState,
      liveMatch: match,
    }),
    [baseState, match],
  );
  const turnProgress =
    match.phase === "finished"
      ? 1
      : Math.max(
          0,
          Math.min(1, (match.turnEndsAt - now) / (match.turnTimer * 1000)),
        );

  function handleSelectPanel(panel) {
    navigateToPanel(router, panel, () => setIsUtilityOpen(false));
  }

  function handleModeSelect(modeKey) {
    navigateToMode(router, modeKey, () => setIsUtilityOpen(false));
  }

  return (
    <>
      <BoardScreen
        appState={state}
        match={match}
        turnProgress={turnProgress}
        userPlayerId={state.profile.id}
        rollingDiceUserId={rollingDiceUserId}
        onSelectToken={handleSelectToken}
        onRollDice={handleRollDice}
        onOpenUtilities={() => setIsUtilityOpen(true)}
        onOpenWallet={undefined}
        onOpenHistory={() => {}}
        onPotIntroComplete={handlePotIntroComplete}
      />

      <UtilitySheet
        isOpen={isUtilityOpen}
        onClose={() => setIsUtilityOpen(false)}
        onSelectPanel={handleSelectPanel}
        onStartMode={handleModeSelect}
        hideLocalMatch={mode === "computer"}
      />
    </>
  );
}

export default function LudoShell() {
  return <MenuPageShell appState={cloneMockBootState()} />;
}
