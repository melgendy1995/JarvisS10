/** Keyless news, Wikipedia, and an optional premium provider. No secrets in source. */

const NEWS = "https://news.google.com/rss/search";

export function isWikiQuestion(text) {
  const q = String(text || "").trim();
  if (!q) return false;
  if (/\b(price|prices|news|score|scores|today|latest|won|weather|gold|silver|match|headline|headlines)\b/i.test(q)) {
    return false;
  }
  if (/اخبار|سعر|اليوم|ذهب|فض|مبار/.test(q)) return false;
  if (/\bwho (is|was|are|were)\b/i.test(q)) return true;
  if (/\bwhat (is|was|are|were)\b/i.test(q)) return true;
  if (/(?:^|\s)(?:من هو|من هي|ما هو|ما هي|ماهو|ماهي)(?:\s|$)/.test(q)) return true;
  return false;
}

export function parseRss(xml, limit = 5, defaultSource = "") {
  const items = [];
  const blocks = String(xml || "").match(/<item\b[^>]*>[\s\S]*?<\/item>/gi) || [];
  for (const block of blocks) {
    if (items.length >= limit) break;
    const rawTitle = textOf(block, "title");
    if (!rawTitle) continue;
    const source = textOf(block, "source") || sourceFromTitle(rawTitle) || defaultSource;
    const title = cleanTitle(rawTitle, source);
    items.push({
      title,
      source: source || defaultSource || "News",
      date: textOf(block, "pubDate"),
      link: textOf(block, "link"),
      kind: "news",
      extract: stripTags(textOf(block, "description")).slice(0, 300),
    });
  }
  return items;
}

export async function gather(query, env, fetchImpl = fetch) {
  const q = String(query || "").trim().slice(0, 240);
  if (!q) return [];
  const arabic = /[\u0600-\u06FF]/.test(q);
  const [news, wiki, web] = await Promise.all([
    newsItems(q, arabic, fetchImpl),
    isWikiQuestion(q) ? wikiItem(q, arabic, fetchImpl) : Promise.resolve(null),
    premiumItems(q, env || {}, fetchImpl),
  ]);
  const extra = wiki ? [wiki] : [];
  let combined = dedupe([...web, ...extra, ...news]);
  if (!combined.length && !extra.length) {
    const fallbackWiki = await wikiItem(q, arabic, fetchImpl);
    if (fallbackWiki) combined = [fallbackWiki];
  }
  return combined.slice(0, 8);
}

function newsUrl(query, lang) {
  const hl = lang === "ar" ? "ar" : "en-SA";
  const ceid = lang === "ar" ? "SA:ar" : "SA:en";
  return `${NEWS}?q=${encodeURIComponent(query)}&hl=${hl}&gl=SA&ceid=${ceid}`;
}

const BROWSER_HEADERS = {
  "user-agent":
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
  accept: "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
  "accept-language": "en-US,en;q=0.9,ar;q=0.8",
  "sec-ch-ua": '"Chromium";v="124", "Google Chrome";v="124", "Not-A.Brand";v="99"',
  "sec-ch-ua-mobile": "?0",
  "sec-ch-ua-platform": '"Windows"',
  "sec-fetch-dest": "document",
  "sec-fetch-mode": "navigate",
  "sec-fetch-site": "none",
  "sec-fetch-user": "?1",
  "upgrade-insecure-requests": "1",
};

const FALLBACK_FEEDS = {
  ar: [
    { url: "https://feeds.bbci.co.uk/arabic/rss.xml", source: "BBC Arabic" },
  ],
  en: [
    { url: "https://www.arabnews.com/rss.xml", source: "Arab News" },
    { url: "https://feeds.bbci.co.uk/news/world/middle_east/rss.xml", source: "BBC News" },
  ],
};

async function newsItems(query, arabic, fetchImpl) {
  const first = arabic ? "ar" : "en";
  const second = arabic ? "en" : "ar";
  const [primary, secondary] = await Promise.all([
    rss(query, first, fetchImpl),
    rss(query, second, fetchImpl),
  ]);
  return dedupe([...primary, ...secondary]).slice(0, 5);
}

async function rss(query, lang, fetchImpl) {
  try {
    const response = await fetchImpl(newsUrl(query, lang), {
      headers: BROWSER_HEADERS,
      signal: AbortSignal.timeout(6000),
    });
    if (response.ok) {
      const xml = await response.text();
      const items = parseRss(xml);
      if (items.length) {
        console.log(`[search] Google RSS success lang=${lang} count=${items.length}`);
        return items;
      }
    } else {
      console.warn(`[search] Google RSS status=${response.status} lang=${lang}`);
    }
  } catch (err) {
    console.warn(`[search] Google RSS fetch failed lang=${lang}: ${err.message || err}`);
  }
  return fallbackRss(query, lang, fetchImpl);
}

async function fallbackRss(query, lang, fetchImpl) {
  const feeds = FALLBACK_FEEDS[lang] || FALLBACK_FEEDS.en;
  for (const feed of feeds) {
    try {
      const response = await fetchImpl(feed.url, {
        headers: {
          accept: "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8",
          "user-agent": BROWSER_HEADERS["user-agent"],
        },
        signal: AbortSignal.timeout(5000),
      });
      if (!response.ok) continue;
      const xml = await response.text();
      const items = parseRss(xml, 10, feed.source);
      if (!items.length) continue;
      const words = query.toLowerCase().split(/\s+/).filter((w) => w.length > 2);
      const matches = items.filter((it) => {
        const text = (it.title + " " + it.extract).toLowerCase();
        return words.some((w) => text.includes(w));
      });
      console.log(`[search] Fallback RSS ${feed.source} count=${items.length} matches=${matches.length}`);
      return matches.length ? matches.slice(0, 5) : items.slice(0, 5);
    } catch (err) {
      console.warn(`[search] Fallback RSS ${feed.source} failed: ${err.message || err}`);
    }
  }
  return [];
}

async function wikiItem(query, arabic, fetchImpl) {
  try {
    const host = arabic ? "ar.wikipedia.org" : "en.wikipedia.org";
    const search = new URL(`https://${host}/w/api.php`);
    search.searchParams.set("action", "query");
    search.searchParams.set("list", "search");
    search.searchParams.set("srsearch", query);
    search.searchParams.set("utf8", "1");
    search.searchParams.set("format", "json");
    search.searchParams.set("srlimit", "1");
    const found = await fetchImpl(search.toString(), {
      headers: { accept: "application/json", "user-agent": "JarvisS10/1.0 (https://github.com/jarvis; contact@jarvis.local)" },
      signal: AbortSignal.timeout(8000),
    });
    if (!found.ok) return null;
    const data = await found.json();
    const title = data?.query?.search?.[0]?.title;
    if (!title) return null;
    const summaryUrl = `https://${host}/api/rest_v1/page/summary/${encodeURIComponent(title)}`;
    const summary = await fetchImpl(summaryUrl, {
      headers: { accept: "application/json", "user-agent": "JarvisS10/1.0 (https://github.com/jarvis; contact@jarvis.local)" },
      signal: AbortSignal.timeout(8000),
    });
    if (!summary.ok) return null;
    const page = await summary.json();
    const extract = String(page.extract || "").trim();
    if (!extract) return null;
    return {
      title: String(page.title || title),
      source: "Wikipedia",
      date: String(page.timestamp || "").slice(0, 10),
      link: String(page?.content_urls?.desktop?.page || summaryUrl),
      kind: "wiki",
      extract: extract.slice(0, 600),
    };
  } catch {
    return null;
  }
}

async function premiumItems(query, env, fetchImpl) {
  if (env.TAVILY_API_KEY) {
    const items = await tavily(query, env.TAVILY_API_KEY, fetchImpl);
    if (items.length) return items.slice(0, 5);
  }
  if (env.BRAVE_API_KEY) return (await brave(query, env.BRAVE_API_KEY, fetchImpl)).slice(0, 5);
  return [];
}

async function tavily(query, key, fetchImpl) {
  try {
    const response = await fetchImpl("https://api.tavily.com/search", {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({
        api_key: key,
        query,
        max_results: 5,
        search_depth: "basic",
      }),
      signal: AbortSignal.timeout(8000),
    });
    if (!response.ok) return [];
    const data = await response.json();
    const results = Array.isArray(data.results) ? data.results : [];
    return results.map((item) => ({
      title: String(item.title || "").trim(),
      source: hostOf(item.url) || "Tavily",
      date: String(item.published_date || "").trim(),
      link: String(item.url || "").trim(),
      kind: "web",
      extract: String(item.content || "").trim().slice(0, 400),
    })).filter((item) => item.title);
  } catch {
    return [];
  }
}

async function brave(query, key, fetchImpl) {
  try {
    const url = "https://api.search.brave.com/res/v1/web/search?count=5&q=" + encodeURIComponent(query);
    const response = await fetchImpl(url, {
      headers: {
        accept: "application/json",
        "x-subscription-token": key,
      },
      signal: AbortSignal.timeout(8000),
    });
    if (!response.ok) return [];
    const data = await response.json();
    const results = Array.isArray(data?.web?.results) ? data.web.results : [];
    return results.map((item) => ({
      title: String(item.title || "").trim(),
      source: hostOf(item.url) || "Brave",
      date: String(item.age || item.page_age || "").trim(),
      link: String(item.url || "").trim(),
      kind: "web",
      extract: String(item.description || "").trim().slice(0, 400),
    })).filter((item) => item.title);
  } catch {
    return [];
  }
}

function textOf(block, tag) {
  const match = block.match(new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)</${tag}>`, "i"));
  if (!match) return "";
  let value = match[1].trim();
  if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) value = value.slice(9, -3).trim();
  return decodeXml(stripTags(value));
}

function stripTags(value) {
  return String(value || "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
}

function decodeXml(value) {
  return value
    .replace(/&#x([0-9a-fA-F]+);/g, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, num) => String.fromCodePoint(Number(num)))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

function sourceFromTitle(title) {
  const split = title.lastIndexOf(" - ");
  if (split < 0) return "";
  return title.slice(split + 3).trim();
}

function cleanTitle(title, source) {
  const suffix = source ? " - " + source : "";
  if (suffix && title.endsWith(suffix) && title.length > suffix.length) {
    return title.slice(0, -suffix.length).trim();
  }
  return title;
}

function dedupe(items) {
  const seen = new Set();
  const out = [];
  for (const item of items) {
    const key = (item.title || "").trim().toLowerCase();
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push(item);
  }
  return out;
}

function hostOf(url) {
  try {
    return new URL(url).host.replace(/^www\./, "");
  } catch {
    return "";
  }
}
