/**
 * Reference proxy. The Groq key stays in `env.GROQ_API_KEY` (`npx wrangler secret put`).
 * The phone sends `env.JARVIS_BEARER`. This file has no secrets.
 *
 * The daily counters live in the isolate. A restart clears them. That is enough for one
 * phone; revoke a leaked bearer by replacing JARVIS_BEARER.
 */

const CHAT_MODELS = new Set([
  "openai/gpt-oss-120b",
  "qwen/qwen3.8-27b",
  "openai/gpt-oss-20b",
]);
const WHISPER_MODEL = "whisper-large-v3-turbo";
const MAX_BYTES = 25 * 1024 * 1024;
const hits = new Map();

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return json({ error: "method not allowed" }, 405);
    }
    if (!env.GROQ_API_KEY || !env.JARVIS_BEARER) {
      return json({ error: "proxy is not configured" }, 500);
    }
    if (!bearerOk(request.headers.get("authorization") || "", env.JARVIS_BEARER)) {
      return json({ error: "unauthorized" }, 401);
    }

    const kind = route(new URL(request.url).pathname);
    if (!kind) return json({ error: "not found" }, 404);

    const bytes = await request.arrayBuffer();
    if (bytes.byteLength > MAX_BYTES) return json({ error: "payload too large" }, 413);

    let path = "";
    if (kind === "chat") {
      const model = chatModel(bytes);
      if (!model) return json({ error: "bad json" }, 400);
      if (!CHAT_MODELS.has(model)) return json({ error: "model not allowed" }, 403);
      path = "/chat/completions";
    } else {
      if (multipartModel(bytes) !== WHISPER_MODEL) return json({ error: "model not allowed" }, 403);
      path = "/audio/transcriptions";
    }

    const limit = Number(kind === "chat" ? env.CHAT_DAILY_LIMIT : env.WHISPER_DAILY_LIMIT) ||
      (kind === "chat" ? 1200 : 2000);
    if (!allow(kind, limit)) {
      return json({ error: "proxy daily limit" }, 429, { "retry-after": String(secondsUntilUtcMidnight()) });
    }
    return forward(path, request.headers.get("content-type"), bytes, env);
  },
};

function route(pathname) {
  const path = pathname.replace(/\/+$/, "") || "/";
  if (path === "/chat/completions" || path === "/v1/chat/completions") return "chat";
  if (path === "/audio/transcriptions" || path === "/v1/audio/transcriptions") return "audio";
  return "";
}

function chatModel(bytes) {
  try {
    const model = JSON.parse(new TextDecoder().decode(bytes)).model;
    return typeof model === "string" ? model : "";
  } catch {
    return "";
  }
}

function multipartModel(bytes) {
  const head = new TextDecoder("utf-8", { fatal: false }).decode(bytes.slice(0, 4096));
  const match = head.match(/name="model"\r?\n\r?\n([^\r\n]+)/);
  return match ? match[1].trim() : "";
}

function allow(bucket, limit) {
  const day = new Date().toISOString().slice(0, 10);
  for (const key of [...hits.keys()]) {
    if (!key.endsWith(":" + day)) hits.delete(key);
  }
  const key = bucket + ":" + day;
  const count = (hits.get(key) || 0) + 1;
  hits.set(key, count);
  return count <= limit;
}

function secondsUntilUtcMidnight() {
  const now = new Date();
  const next = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1);
  return Math.max(1, Math.ceil((next - now.getTime()) / 1000));
}

async function forward(path, contentType, bytes, env) {
  const upstream = await fetch("https://api.groq.com/openai/v1" + path, {
    method: "POST",
    headers: {
      authorization: "Bearer " + env.GROQ_API_KEY,
      "content-type": contentType || "application/json",
    },
    body: bytes,
  });
  const headers = new Headers();
  const type = upstream.headers.get("content-type");
  if (type) headers.set("content-type", type);
  const retry = upstream.headers.get("retry-after");
  if (retry) headers.set("retry-after", retry);
  return new Response(upstream.body, { status: upstream.status, headers });
}

function bearerOk(header, expected) {
  const prefix = "Bearer ";
  if (!header.startsWith(prefix)) return false;
  const got = header.slice(prefix.length);
  if (got.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < got.length; i++) diff |= got.charCodeAt(i) ^ expected.charCodeAt(i);
  return diff === 0;
}

function json(body, status, extra) {
  const headers = { "content-type": "application/json", ...(extra || {}) };
  return new Response(JSON.stringify(body), { status, headers });
}
