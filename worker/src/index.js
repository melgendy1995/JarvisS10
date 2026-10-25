/**
 * Reference proxy. The Groq key stays in `env.GROQ_API_KEY` (`npx wrangler secret put`).
 * The phone sends `env.JARVIS_BEARER`. This file has no secrets.
 *
 * Optional search providers, used only when the secret is set:
 *   npx wrangler secret put TAVILY_API_KEY
 *   npx wrangler secret put BRAVE_API_KEY
 *
 * The daily counters live in the isolate. A restart clears them. That is enough for one
 * phone; revoke a leaked bearer by replacing JARVIS_BEARER.
 */

import { allow, prepareChat } from "./policy.js";
import { gather } from "./search.js";

const WHISPER_MODEL = "whisper-large-v3-turbo";
const MAX_BYTES = 25 * 1024 * 1024;
const hits = new Map();

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return json({ error: "method not allowed" }, 405);
    }
    if (!env.JARVIS_BEARER) {
      return json({ error: "proxy is not configured" }, 500);
    }
    if (!bearerOk(request.headers.get("authorization") || "", env.JARVIS_BEARER)) {
      return json({ error: "unauthorized" }, 401);
    }

    const kind = route(new URL(request.url).pathname);
    if (!kind) return json({ error: "not found" }, 404);

    const bytes = await request.arrayBuffer();
    if (bytes.byteLength > MAX_BYTES) return json({ error: "payload too large" }, 413);

    if (kind === "search") return handleSearch(bytes, env);
    if (!env.GROQ_API_KEY) return json({ error: "proxy is not configured" }, 500);
    if (kind === "chat") return handleChat(bytes, request, env);

    if (multipartModel(bytes) !== WHISPER_MODEL) return json({ error: "model not allowed" }, 403);
    const whisperLimit = Number(env.WHISPER_DAILY_LIMIT) || 2000;
    if (!allow(hits, "audio", whisperLimit)) {
      return json({ error: "proxy daily limit" }, 429, { "retry-after": String(secondsUntilUtcMidnight()) });
    }
    return forward("/audio/transcriptions", request.headers.get("content-type"), bytes, env);
  },
};

function route(pathname) {
  const path = pathname.replace(/\/+$/, "") || "/";
  if (path === "/chat/completions" || path === "/v1/chat/completions") return "chat";
  if (path === "/audio/transcriptions" || path === "/v1/audio/transcriptions") return "audio";
  if (path === "/search") return "search";
  return "";
}

function handleChat(bytes, request, env) {
  let parsed;
  try {
    parsed = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return json({ error: "bad json" }, 400);
  }
  const prepared = prepareChat(parsed);
  if (!prepared.ok) return json({ error: prepared.error }, prepared.status);
  const browser = prepared.kind === "browser";
  const bucket = browser ? "browser" : "chat";
  const limit = browser
    ? (Number(env.BROWSER_DAILY_LIMIT) || 100)
    : (Number(env.CHAT_DAILY_LIMIT) || 1200);
  if (!allow(hits, bucket, limit)) {
    const error = browser ? "browser daily limit" : "proxy daily limit";
    return json({ error }, 429, { "retry-after": String(secondsUntilUtcMidnight()) });
  }
  const body = browser ? new TextEncoder().encode(JSON.stringify(prepared.body)) : bytes;
  const type = browser ? "application/json" : request.headers.get("content-type");
  return forward("/chat/completions", type, body, env);
}

async function handleSearch(bytes, env) {
  let parsed;
  try {
    parsed = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return json({ error: "bad json" }, 400);
  }
  const query = String((parsed && (parsed.q || parsed.query)) || "").trim();
  if (!query) return json({ error: "bad json" }, 400);
  const limit = Number(env.SEARCH_DAILY_LIMIT) || 300;
  if (!allow(hits, "search", limit)) {
    return json({ error: "search daily limit" }, 429, { "retry-after": String(secondsUntilUtcMidnight()) });
  }
  const items = await gather(query, env, fetch);
  return json({ items });
}

function multipartModel(bytes) {
  const head = new TextDecoder("utf-8", { fatal: false }).decode(bytes.slice(0, 4096));
  const match = head.match(/name="model"\r?\n(?:[^\r\n]+\r?\n)*?\r?\n([^\r\n]+)/);
  return match ? match[1].trim() : "";
}

export { multipartModel };

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
