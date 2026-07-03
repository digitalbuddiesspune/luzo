function createGuestId() {
  return `guest_${Math.random().toString(36).slice(2, 10)}`;
}

export const mockBootState = {
  profile: {
    id: "guest_demo_01",
    displayName: "Ash Rider",
    tier: "Diamond Table",
    location: "Kolkata Arena",
    streak: 7,
    avatar: "AR",
    avatarBg: "#E53935",
  },
  wallet: {
    currency: "INR",
    availableBalance: 100000,
    reservedBalance: 0,
    totalWinnings: 0,
    winRate: 0,
    transactions: [
      {
        id: "txn_901",
        label: "Guest starting balance",
        meta: "Welcome coins",
        amount: 100000,
      },
    ],
  },
  roomTemplates: [
    {
      id: "classic_public_4p",
      title: "Classic Pot Table",
      subtitle: "4 players, full board, 22s turn timer",
      entryFee: 100,
      playerCount: 4,
      bots: "Optional",
      mode: "Public",
      chipColor: "gold",
    },
    {
      id: "duel_fast_2p",
      title: "Lightning Duel",
      subtitle: "2 players, aggressive pacing, 12s turns",
      entryFee: 100,
      playerCount: 2,
      bots: "Disabled",
      mode: "Public",
      chipColor: "mint",
    },
    {
      id: "private_party_4p",
      title: "Private Party Pot",
      subtitle: "Invite code room with controlled seats",
      entryFee: 100,
      playerCount: 4,
      bots: "Fill seats",
      mode: "Private",
      chipColor: "sunset",
    },
  ],
  openRooms: [
    {
      id: "room_saffron_71",
      title: "Saffron-71",
      code: "SAF71",
      visibility: "Private",
      entryFee: 100,
      playerCount: 4,
      occupied: 3,
      botsAllowed: true,
      stakes: "High Pot",
      livePot: 300,
      status: "Waiting for 1 seat",
    },
    {
      id: "room_rush_11",
      title: "Rush-11",
      code: "RSH11",
      visibility: "Public",
      entryFee: 100,
      playerCount: 4,
      occupied: 4,
      botsAllowed: false,
      stakes: "Classic",
      livePot: 400,
      status: "Match live",
    },
    {
      id: "room_duel_09",
      title: "Duel-09",
      code: "DUL09",
      visibility: "Public",
      entryFee: 100,
      playerCount: 2,
      occupied: 1,
      botsAllowed: false,
      stakes: "Fast 2P",
      livePot: 100,
      status: "Waiting for 1 seat",
    },
  ],
  liveFeed: [
    "Rush-11 rolled a six and opened two red tokens in 1.2s.",
    "A private room settled a 4,800 INR winner payout with idempotent ledger flow.",
    "Bot-fill created a full 4P table after two seats remained idle for 18 seconds.",
    "Redis-backed room leases reassigned an active table after a node heartbeat lapse.",
  ],
  liveMatch: {
    roomTitle: "Rush-11",
    entryFee: 100,
    pot: 400,
    currentTurn: "Ash Rider",
    dice: 6,
    mode: "Classic 4P",
    sequence: 184,
    turnTimer: 15,
    players: [
      {
        id: "guest_demo_01",
        name: "Ash Rider",
        color: "red",
        isBot: false,
        tokens: [-1, -1, -1, -1],
        avatar: "AR",
        avatarBg: "#E53935",
      },
      {
        id: "guest_reva_12",
        name: "Reva Strike",
        color: "green",
        isBot: false,
        tokens: [-1, -1, -1, -1],
        avatar: "RS",
        avatarBg: "#43A047",
      },
      {
        id: "guest_bot_7",
        name: "Meera",
        color: "yellow",
        isBot: true,
        tokens: [-1, -1, -1, -1],
        avatar: "CB",
        avatarBg: "#FDD835",
      },
      {
        id: "guest_milan_4",
        name: "Milan Ace",
        color: "blue",
        isBot: false,
        tokens: [-1, -1, -1, -1],
        avatar: "MA",
        avatarBg: "#1E88E5",
      },
    ],
    events: [
      {
        id: "evt_1",
        actor: "Ash Rider",
        detail: "rolled a 6 and opened a yard token",
      },
      {
        id: "evt_2",
        actor: "Milan Ace",
        detail: "captured Meera on a non-safe lane",
      },
      {
        id: "evt_3",
        actor: "System",
        detail: "snapshot #184 persisted and replay channel advanced",
      },
    ],
  },
  history: [
    {
      id: "hist_01",
      room: "Classic Pot Table",
      outcome: "Won",
      delta: 4800,
      when: "08 mins ago",
    },
    {
      id: "hist_02",
      room: "Lightning Duel",
      outcome: "Lost",
      delta: -300,
      when: "41 mins ago",
    },
    {
      id: "hist_03",
      room: "Private Party Pot",
      outcome: "Won",
      delta: 3900,
      when: "Today, 11:20",
    },
  ],
};

export const PLAY_ROUTES = {
  online: "/play/online/",
  online2: "/play/online/?players=2",
  online4: "/play/online/?players=4",
  friends: "/play/private-room/",
  computer: "/play/computer/",
  local: "/play/local/",
};

const PLAY_ROUTE_CONFIG = {
  online: {
    room: {
      id: "room_rush_11",
      title: "Rush-11",
      entryFee: 100,
      pot: 400,
      mode: "Online Match",
      eventDetail: "rolled a 6 and opened a live table token",
    },
  },
  "private-room": {
    room: {
      id: "room_private_cipher",
      title: "Cipher-Deck",
      code: "CYP42",
      visibility: "Private",
      entryFee: 100,
      playerCount: 4,
      occupied: 1,
      botsAllowed: true,
      stakes: "Invite Room",
      livePot: 100,
      status: "Invite code generated",
    },
    pot: 100,
    mode: "Private Room",
    eventDetail: "generated room code CYP42 and opened the host seat",
  },
    computer: {
      room: {
        id: "room_duel_09",
        title: "Duel-09",
        entryFee: 100,
        pot: 100,
        mode: "Vs Computer",
        eventDetail: "queued a duel table against bot-fill",
      },
  },
  local: {
    room: {
      id: "room_saffron_71",
      title: "Saffron-71",
      entryFee: 100,
      pot: 300,
      mode: "Local Match",
      eventDetail: "loaded a local table with shared device turns",
    },
  },
};

export function cloneMockBootState() {
  const state = JSON.parse(JSON.stringify(mockBootState));
  const guestId = createGuestId();

  state.profile.id = guestId;
  state.liveMatch.players[0].id = guestId;

  return state;
}

export function createPlayRouteState(mode) {
  const state = cloneMockBootState();
  const config = PLAY_ROUTE_CONFIG[mode];

  if (!config) {
    return state;
  }

  if (mode === "private-room") {
    const room = config.room;

    state.openRooms = [room, ...state.openRooms];
      state.liveMatch = {
        ...state.liveMatch,
        roomTitle: room.title,
        entryFee: room.entryFee,
        pot: config.pot,
        mode: config.mode,
      events: state.liveMatch.events.map((event, index) =>
        index === 0
          ? {
              ...event,
              detail: config.eventDetail,
            }
          : event,
      ),
    };

    return state;
  }

  state.liveMatch = {
    ...state.liveMatch,
    roomTitle: config.room.title,
    entryFee: config.room.entryFee ?? state.liveMatch.entryFee,
    pot: config.room.pot,
    mode: config.room.mode,
    events: state.liveMatch.events.map((event, index) =>
      index === 0
        ? {
            ...event,
            detail: config.room.eventDetail,
          }
        : event,
    ),
  };

  return state;
}
