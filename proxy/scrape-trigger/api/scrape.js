/**
 * Thin proxy: TV app → this endpoint → GitHub Actions workflow_dispatch.
 * Secrets stay on Vercel (GITHUB_TOKEN, APP_KEY). Never ship PAT in the APK.
 *
 * POST /api/scrape
 * Header: X-Webunime-Key: <APP_KEY>
 *
 * Rate limit: rejects if sync-status.json state is "running",
 * or if the same IP triggered within RATE_LIMIT_MS.
 */

const WORKFLOW_DISPATCH_URL =
  "https://api.github.com/repos/gitgitmiko/scraper_webunime/actions/workflows/sync-catalog.yml/dispatches";

const STATUS_URL =
  "https://raw.githubusercontent.com/gitgitmiko/WEBUNIME/main/public/data/sync-status.json";

const RATE_LIMIT_MS = 3 * 60 * 1000;

/** @type {Map<string, number>} */
const lastTriggerByIp = new Map();

function cors(res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, X-Webunime-Key");
}

async function readSyncState() {
  const bust = Date.now();
  const res = await fetch(`${STATUS_URL}?t=${bust}`, {
    headers: {
      "Cache-Control": "no-cache",
      Pragma: "no-cache",
      "User-Agent": "WEBUNIME-Scrape-Proxy/1.0",
    },
  });
  if (!res.ok) return null;
  return res.json();
}

module.exports = async function handler(req, res) {
  cors(res);
  if (req.method === "OPTIONS") {
    return res.status(204).end();
  }
  if (req.method !== "POST") {
    return res.status(405).json({ ok: false, error: "Method not allowed" });
  }

  const appKey = process.env.APP_KEY || "";
  if (appKey) {
    const provided = req.headers["x-webunime-key"] || "";
    if (provided !== appKey) {
      return res.status(401).json({ ok: false, error: "Unauthorized" });
    }
  }

  const token = process.env.GITHUB_TOKEN;
  if (!token) {
    return res.status(500).json({ ok: false, error: "Proxy misconfigured" });
  }

  const ip =
    (req.headers["x-forwarded-for"] || "").toString().split(",")[0].trim() ||
    req.socket?.remoteAddress ||
    "unknown";
  const now = Date.now();
  const last = lastTriggerByIp.get(ip) || 0;
  if (now - last < RATE_LIMIT_MS) {
    const retryAfterSec = Math.ceil((RATE_LIMIT_MS - (now - last)) / 1000);
    res.setHeader("Retry-After", String(retryAfterSec));
    return res.status(429).json({
      ok: false,
      error: "rate_limited",
      message: "Tunggu beberapa menit sebelum memulai scrape lagi.",
      retryAfterSec,
    });
  }

  try {
    const status = await readSyncState();
    if (status && status.state === "running") {
      return res.status(409).json({
        ok: false,
        error: "already_running",
        message: "Scraping masih berjalan.",
        status,
      });
    }
  } catch (_) {
    // Jika status gagal dibaca, tetap coba dispatch.
  }

  const gh = await fetch(WORKFLOW_DISPATCH_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "Content-Type": "application/json",
      "User-Agent": "WEBUNIME-Scrape-Proxy/1.0",
    },
    body: JSON.stringify({ ref: "main" }),
  });

  if (gh.status !== 204 && gh.status !== 200) {
    const body = await gh.text();
    return res.status(502).json({
      ok: false,
      error: "github_dispatch_failed",
      message: `GitHub HTTP ${gh.status}`,
      detail: body.slice(0, 300),
    });
  }

  lastTriggerByIp.set(ip, now);
  return res.status(202).json({
    ok: true,
    message: "Scraping dimulai. Estimasi 2–5 menit.",
  });
};
