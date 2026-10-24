/** Chat allowlist and the browser_search request shape. No secrets. */

export const CHAT_MODELS = new Set([
  "openai/gpt-oss-120b",
  "qwen/qwen3.8-27b",
  "openai/gpt-oss-20b",
]);

export const GPT_OSS = new Set([
  "openai/gpt-oss-120b",
  "openai/gpt-oss-20b",
]);

/**
 * Normal chat is unchanged. browser_search is only gpt-oss, with no other tools
 * and no structured output. The forwarded body uses low reasoning and 1024 tokens.
 */
export function prepareChat(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return { ok: false, status: 400, error: "bad json" };
  }
  const model = typeof body.model === "string" ? body.model : "";
  if (!model) return { ok: false, status: 400, error: "bad json" };
  const tools = Array.isArray(body.tools) ? body.tools : [];
  const browser = tools.some((tool) => tool && tool.type === "browser_search");
  if (!browser) {
    if (!CHAT_MODELS.has(model)) return { ok: false, status: 403, error: "model not allowed" };
    return { ok: true, kind: "chat", body };
  }
  const onlyBrowser = tools.length === 1 &&
    tools[0] &&
    tools[0].type === "browser_search" &&
    Object.keys(tools[0]).every((key) => key === "type");
  if (!GPT_OSS.has(model) || !onlyBrowser || body.response_format != null) {
    return { ok: false, status: 403, error: "browser_search not allowed" };
  }
  const next = { ...body, tools: [{ type: "browser_search" }] };
  next.tool_choice = "required";
  next.reasoning_effort = "low";
  next.max_completion_tokens = 1024;
  next.stream = false;
  delete next.response_format;
  return { ok: true, kind: "browser", body: next };
}

/** One counter per bucket per UTC day. The map is the caller's. */
export function allow(hits, bucket, limit, now = new Date()) {
  const day = now.toISOString().slice(0, 10);
  for (const key of [...hits.keys()]) {
    if (!key.endsWith(":" + day)) hits.delete(key);
  }
  const key = bucket + ":" + day;
  const count = (hits.get(key) || 0) + 1;
  hits.set(key, count);
  return count <= limit;
}
