const crypto = require("node:crypto");
const { HttpError } = require("../errors/httpError");

const ADMIN_ACCOUNTS_COLLECTION = "admin_accounts";
const ADMIN_SESSIONS_COLLECTION = "admin_sessions";
const SESSION_TTL_MS = 1000 * 60 * 60 * 24 * 7; // 7 days

const DEFAULT_ADMIN = {
  email: "admin@gmail.com",
  password: "123456",
  displayName: "Admin",
};

function normalizeEmail(email) {
  return String(email || "").trim().toLowerCase();
}

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString("hex");
  const hash = crypto.scryptSync(String(password), salt, 64).toString("hex");
  return `${salt}:${hash}`;
}

function verifyPassword(password, storedHash) {
  if (!storedHash || !storedHash.includes(":")) {
    return false;
  }

  const [salt, hash] = storedHash.split(":");
  const candidate = crypto.scryptSync(String(password), salt, 64);
  const expected = Buffer.from(hash, "hex");

  if (candidate.length !== expected.length) {
    return false;
  }

  return crypto.timingSafeEqual(candidate, expected);
}

function createSessionToken() {
  return crypto.randomBytes(32).toString("hex");
}

function publicAdmin(admin) {
  return {
    id: String(admin._id),
    email: admin.email,
    displayName: admin.displayName || "Admin",
  };
}

class AdminAuthService {
  constructor(database) {
    this.accounts = database.collection(ADMIN_ACCOUNTS_COLLECTION);
    this.sessions = database.collection(ADMIN_SESSIONS_COLLECTION);
  }

  async ensureIndexes() {
    await Promise.all([
      this.accounts.createIndex({ email: 1 }, { unique: true }),
      this.sessions.createIndex({ token: 1 }, { unique: true }),
      this.sessions.createIndex({ expiresAt: 1 }, { expireAfterSeconds: 0 }),
    ]);
  }

  async seedDefaultAdmin() {
    const email = normalizeEmail(DEFAULT_ADMIN.email);
    const existing = await this.accounts.findOne({ email });

    if (existing) {
      return {
        seeded: false,
        email,
      };
    }

    const now = new Date();
    await this.accounts.insertOne({
      email,
      passwordHash: hashPassword(DEFAULT_ADMIN.password),
      displayName: DEFAULT_ADMIN.displayName,
      createdAt: now,
      updatedAt: now,
    });

    return {
      seeded: true,
      email,
    };
  }

  async login({ email, password }) {
    const normalizedEmail = normalizeEmail(email);
    if (!normalizedEmail || !password) {
      throw new HttpError(400, "INVALID_CREDENTIALS", "Email and password are required.");
    }

    const admin = await this.accounts.findOne({ email: normalizedEmail });
    if (!admin || !verifyPassword(password, admin.passwordHash)) {
      throw new HttpError(401, "INVALID_CREDENTIALS", "Invalid email or password.");
    }

    const now = new Date();
    const token = createSessionToken();
    const expiresAt = new Date(now.getTime() + SESSION_TTL_MS);

    await this.sessions.insertOne({
      token,
      adminId: admin._id,
      email: admin.email,
      createdAt: now,
      expiresAt,
    });

    return {
      token,
      expiresAt,
      admin: publicAdmin(admin),
    };
  }

  async logout(token) {
    if (!token) {
      return { ok: true };
    }

    await this.sessions.deleteOne({ token });
    return { ok: true };
  }

  async resolveSession(token) {
    if (!token) {
      return null;
    }

    const session = await this.sessions.findOne({
      token,
      expiresAt: { $gt: new Date() },
    });

    if (!session) {
      return null;
    }

    const admin = await this.accounts.findOne({ _id: session.adminId });
    if (!admin) {
      await this.sessions.deleteOne({ token });
      return null;
    }

    return {
      token,
      expiresAt: session.expiresAt,
      admin: publicAdmin(admin),
    };
  }

  async requireSession(token) {
    const session = await this.resolveSession(token);
    if (!session) {
      throw new HttpError(401, "UNAUTHORIZED", "Admin login required.");
    }
    return session;
  }
}

module.exports = {
  AdminAuthService,
  ADMIN_ACCOUNTS_COLLECTION,
  ADMIN_SESSIONS_COLLECTION,
  DEFAULT_ADMIN,
  hashPassword,
  verifyPassword,
};
