import assert from "node:assert/strict";
import test from "node:test";
import { allow, prepareChat } from "../src/policy.js";
import { gather, isWikiQuestion, parseRss } from "../src/search.js";

test("browser search is only gpt-oss and forces the documented shape", () => {
  const prepared = prepareChat({
    model: "openai/gpt-oss-120b",
    messages: [{ role: "user", content: "latest news" }],
    tools: [{ type: "browser_search" }],
    max_completion_tokens: 4096,
    reasoning_effort: "high",
  });
  assert.equal(prepared.ok, true);
  assert.equal(prepared.kind, "browser");
  assert.equal(prepared.body.tool_choice, "required");
  assert.equal(prepared.body.reasoning_effort, "low");
  assert.equal(prepared.body.max_completion_tokens, 1024);
  assert.deepEqual(prepared.body.tools, [{ type: "browser_search" }]);
  assert.equal(prepared.body.stream, false);

  const qwen = prepareChat({
    model: "qwen/qwen3.8-27b",
    messages: [],
    tools: [{ type: "browser_search" }],
  });
  assert.equal(qwen.ok, false);
  assert.equal(qwen.status, 403);

  const mixed = prepareChat({
    model: "openai/gpt-oss-20b",
    tools: [{ type: "browser_search" }, { type: "function", function: { name: "weather" } }],
  });
  assert.equal(mixed.ok, false);

  const structured = prepareChat({
    model: "openai/gpt-oss-120b",
    tools: [{ type: "browser_search" }],
    response_format: { type: "json_object" },
  });
  assert.equal(structured.ok, false);

  const chat = prepareChat({
    model: "qwen/qwen3.8-27b",
    tools: [{ type: "function", function: { name: "weather" } }],
  });
  assert.equal(chat.ok, true);
  assert.equal(chat.kind, "chat");
  assert.equal(chat.body.tools.length, 1);
});

test("browser and search caps are separate from chat", () => {
  const hits = new Map();
  const now = new Date("2026-10-24T12:00:00Z");
  for (let i = 0; i < 100; i++) assert.equal(allow(hits, "browser", 100, now), true);
  assert.equal(allow(hits, "browser", 100, now), false);
  for (let i = 0; i < 300; i++) assert.equal(allow(hits, "search", 300, now), true);
  assert.equal(allow(hits, "search", 300, now), false);
  assert.equal(allow(hits, "chat", 1200, now), true);
  allow(hits, "browser", 100, new Date("2026-10-25T00:30:00Z"));
  assert.equal(hits.has("browser:2026-10-24"), false);
  assert.equal(hits.get("browser:2026-10-25"), 1);
});

test("rss keeps the top five items with source and date", () => {
  const blocks = [];
  for (let i = 1; i <= 6; i++) {
    blocks.push(
      `<item><title>Headline ${i} &amp; more - Source ${i}</title>` +
        `<link>https://news.example/${i}</link>` +
        `<pubDate>Sat, 24 Oct 2026 0${i}:00:00 GMT</pubDate>` +
        `<source url="https://source.example">Source ${i}</source>` +
        `<description><![CDATA[<p>Detail ${i}</p>]]></description></item>`,
    );
  }
  const items = parseRss(`<rss><channel>${blocks.join("")}</channel></rss>`);
  assert.equal(items.length, 5);
  assert.equal(items[0].title, "Headline 1 & more");
  assert.equal(items[0].source, "Source 1");
  assert.equal(items[0].date, "Sat, 24 Oct 2026 01:00:00 GMT");
  assert.equal(items[0].link, "https://news.example/1");
  assert.equal(items[0].extract, "Detail 1");
  assert.equal(items[4].title, "Headline 5 & more");
});

test("wikipedia is only for who or what is", () => {
  assert.equal(isWikiQuestion("who is Ibn Sina"), true);
  assert.equal(isWikiQuestion("what is a dirham"), true);
  assert.equal(isWikiQuestion("ما هو الهلال"), true);
  assert.equal(isWikiQuestion("who won the last Al Hilal match"), false);
  assert.equal(isWikiQuestion("what is the price of gold today"), false);
  assert.equal(isWikiQuestion("latest news about Saudi Arabia"), false);
  assert.equal(isWikiQuestion("كم سعر الذهب اليوم"), false);
});

test("gather uses news and skips premium keys that are unset", async () => {
  const calls = [];
  const items = await gather("latest news about Saudi Arabia", {}, fakeFetch(calls));
  assert.equal(items.length, 2);
  assert.equal(items[0].source, "Arab News");
  assert.ok(calls.some((call) => call.includes("hl=en-SA") && call.includes("gl=SA")));
  assert.ok(calls.some((call) => call.includes("hl=ar") && call.includes("gl=SA")));
  assert.equal(calls.some((call) => call.includes("tavily") || call.includes("brave")), false);
  assert.equal(calls.some((call) => call.includes("wikipedia")), false);
});

test("a tavily key is used and never copied into the result", async () => {
  const calls = [];
  const secret = "tvly-test-secret-value";
  const items = await gather("who is Ibn Sina", { TAVILY_API_KEY: secret }, fakeFetch(calls, secret));
  assert.ok(items.some((item) => item.kind === "web" && item.title === "Premium hit"));
  assert.ok(items.some((item) => item.kind === "wiki" && item.source === "Wikipedia"));
  assert.equal(JSON.stringify(items).includes(secret), false);
  assert.ok(calls.some((call) => call.includes("api.tavily.com")));
});

function fakeFetch(calls, secret) {
  return async (url, init) => {
    const href = String(url);
    calls.push(href);
    if (href.includes("news.google.com")) {
      const xml = `<rss><channel>` +
        `<item><title>Cabinet meets - Arab News</title><link>https://arabnews.com/a</link>` +
        `<pubDate>Sat, 24 Oct 2026 08:00:00 GMT</pubDate>` +
        `<source url="https://www.arabnews.com">Arab News</source></item>` +
        `<item><title>Markets open - Reuters</title><link>https://reuters.com/b</link>` +
        `<pubDate>Sat, 24 Oct 2026 07:00:00 GMT</pubDate>` +
        `<source url="https://www.reuters.com">Reuters</source></item>` +
        `</channel></rss>`;
      return { ok: true, text: async () => xml, json: async () => ({}) };
    }
    if (href.includes("api.php")) {
      return {
        ok: true,
        text: async () => "",
        json: async () => ({ query: { search: [{ title: "Ibn Sina" }] } }),
      };
    }
    if (href.includes("/page/summary/")) {
      return {
        ok: true,
        text: async () => "",
        json: async () => ({
          title: "Ibn Sina",
          extract: "A physician and philosopher.",
          timestamp: "2026-01-02T00:00:00Z",
          content_urls: { desktop: { page: "https://en.wikipedia.org/wiki/Ibn_Sina" } },
        }),
      };
    }
    if (href.includes("api.tavily.com")) {
      const body = JSON.parse(init.body);
      assert.equal(body.api_key, secret);
      return {
        ok: true,
        text: async () => "",
        json: async () => ({
          results: [{
            title: "Premium hit",
            url: "https://example.com/hit",
            content: "A short extract.",
            published_date: "2026-10-24",
          }],
        }),
      };
    }
    return { ok: false, status: 404, text: async () => "", json: async () => ({}) };
  };
}
