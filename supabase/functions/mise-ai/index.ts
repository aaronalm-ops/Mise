// Mise AI helpers: one Supabase Edge Function between the app and NVIDIA's hosted models
// (build.nvidia.com, OpenAI-style chat API). The NVIDIA key lives here as a secret, never in
// the app. Only signed-in accounts whose email is listed in MISE_AI_EMAILS can use it.
//
// Secrets (Supabase, Edge Functions, Secrets):
//   NVIDIA_API_KEY      your key from build.nvidia.com (starts with nvapi-)
//   MISE_AI_EMAILS      comma-separated emails allowed to use AI (your household)
//   MISE_MODEL          optional: force one text model (otherwise the best available is picked)
//   MISE_VISION_MODEL   optional: force one photo model (otherwise the best available is picked)
//
// NVIDIA retires models often, so no model is hard-wired: each run reads the live catalogue and
// uses the first available model from the preference lists below, skipping any that fail.
//
// Tasks: "photo" (photos of a recipe -> recipe), "idea" (a request -> recipe),
// "week" (the library and the week -> suggested dishes).

// Model families, best first. NVIDIA renames versions often (v4, v4.1...), so these match by
// pattern against the live catalogue, newest version first within a family.
// Text: fast, reliable at JSON. Vision: reads recipe photos.
const TEXT_FAMILIES = [
  /openai\/gpt-oss-120b/i, /deepseek-v[\d.]+-flash/i, /qwen3[\d.]*-\d+b-a\d+b(?!.*(vl|coder|thinking))/i, /glm-[\d.]+(?!.*v)/i,
  /kimi-k[\d.]+(?!.*thinking)/i, /mistral-(large|medium)/i, /nemotron-3-super/i, /deepseek-v[\d.]+(?!.*flash)/i,
];
const VISION_FAMILIES = [
  /qwen[\d.]*-?vl/i, /qwen3\.5/i, /kimi-k2\.[5-9]/i, /nemotron-3-nano-omni/i, /mistral-(large|medium)-3/i, /ministral/i, /gemma-3/i, /llama-4-maverick/i, /(vision|omni|pixtral|-vl)/i,
];
// Planning the week from a short list needs speed more than size: small, fast models first.
const QUICK_FAMILIES = [
  /gpt-oss-20b/i, /qwen3[\d.]*-(next-)?\d+b-a3b(?!.*(vl|coder|thinking))/i, /nemotron.*nano(?!.*(vl|omni))/i, /mistral-small/i,
  /llama-3\.3-70b/i, /openai\/gpt-oss-120b/i, /deepseek-v[\d.]+-flash/i, /glm-[\d.]+-flash/i,
];
const SKIP = /(embed|guard|reward|safety|rerank|parse|coder|math|retriev|translate|audio|asr|tts|clip)/i;

const KEY = Deno.env.get("NVIDIA_API_KEY") ?? "";
const ALLOWED = (Deno.env.get("MISE_AI_EMAILS") ?? "").split(",").map((s) => s.trim().toLowerCase()).filter(Boolean);
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const ANON = Deno.env.get("SUPABASE_ANON_KEY") ?? "";

const CUISINES = ["Indian", "Goan", "Pakistani", "Indo-Chinese", "Chinese", "Thai", "Indonesian", "Japanese", "Italian", "American", "British", "Turkish", "Fusion", "International"];
const DISH_TYPES = ["Curry", "Dal", "Dry and roast", "Rice", "Noodles", "Pasta", "Stir fry", "Soup and stew", "Finger food", "Sandwich and burger", "Pizza and savoury bake", "Salad", "Breakfast", "Dessert and sweet bake", "Drink", "Sauce and dip"];
const UNITS = ["g", "kg", "ml", "l", "tsp", "tbsp", "cup", "pc", "clove", "can", "pack", "bunch", "sprig", "pinch", "to taste"];

const HOUSE = `You help a household in the UAE with their recipe and meal-planning app, Mise.
They cook Indian and Goan food most often, plus Italian, Indo-Chinese and quick weeknight dishes.
Write in British English. Use metric amounts and only these units: ${UNITS.join(", ")}.
Garlic is counted in cloves; onions, tomatoes and eggs in pc; herbs for garnish in bunch (0.25 bunch for a garnish), curry leaves in sprig.
Every ingredient gets a quantity unless it is "to taste" (then qty is empty).`;

const ING = {
  type: "object",
  properties: {
    name: { type: "string", description: "Ingredient name, capitalised, singular or plural as usually written, no amount" },
    qty: { type: "string", description: "Number as text, e.g. 500, 1.5, 0.25. Empty for to taste." },
    unit: { type: "string", enum: UNITS },
    note: { type: "string", description: "Preparation or remark, e.g. finely chopped. Add 'est.' when you estimated the amount." },
  },
  required: ["name", "qty", "unit", "note"],
};

const RECIPE_TOOL = {
  name: "save_recipe",
  description: "Save one recipe in Mise's format.",
  input_schema: {
    type: "object",
    properties: {
      title: { type: "string", description: "Short dish name in sentence case" },
      cuisine: { type: "string", enum: CUISINES },
      dishType: { type: "string", enum: DISH_TYPES },
      serves: { type: "integer", minimum: 1, maximum: 24 },
      prep: { type: "string", description: "Prep minutes as digits, or empty" },
      cook: { type: "string", description: "Cook minutes as digits, or empty" },
      tags: { type: "string", description: "Optional facets, comma separated, from: Vegetarian, Quick, Air fryer, Oven, Pressure cooker" },
      ings: { type: "array", items: ING, description: "Ingredients always used" },
      slots: {
        type: "array",
        description: "Swaps, only when the recipe offers a real choice (e.g. Protein: Chicken or Prawn). Usually empty.",
        items: {
          type: "object",
          properties: {
            name: { type: "string" },
            options: {
              type: "array",
              items: {
                type: "object",
                properties: { label: { type: "string" }, isDefault: { type: "boolean" }, note: { type: "string" }, ings: { type: "array", items: ING } },
                required: ["label", "isDefault", "note", "ings"],
              },
            },
          },
          required: ["name", "options"],
        },
      },
      steps: { type: "array", items: { type: "string" }, description: "Clear numbered-style steps without the numbers" },
      notes: { type: "string", description: "Tips, serving ideas, and anything you were unsure about" },
    },
    required: ["title", "cuisine", "dishType", "serves", "prep", "cook", "tags", "ings", "slots", "steps", "notes"],
  },
};

const WEEK_TOOL = {
  name: "suggest_week",
  description: "Suggest dishes for the empty slots of the week, chosen only from the library.",
  input_schema: {
    type: "object",
    properties: {
      plan: {
        type: "array",
        items: {
          type: "object",
          properties: {
            date: { type: "string", description: "ISO date from the given days" },
            meal: { type: "string", enum: ["breakfast", "lunch", "dinner", "snack"] },
            recipeId: { type: "integer", description: "id of the main dish from the library" },
            sideId: { type: "integer", description: "id of a rice or bread side from the library to cook with a curry, or 0 for none" },
            servings: { type: "integer", description: "Servings to cook, following the kitchen profile (bigger when the cook covers extra meals)" },
            covers: {
              type: "array",
              description: "Later meals this cook also covers as leftovers (e.g. the next day's lunch), within the next three days. Empty when none.",
              items: { type: "object", properties: { date: { type: "string" }, meal: { type: "string", enum: ["breakfast", "lunch", "dinner", "snack"] } }, required: ["date", "meal"] },
            },
            reason: { type: "string", description: "Why this dish, in at most eight words" },
          },
          required: ["date", "meal", "recipeId", "sideId", "servings", "covers", "reason"],
        },
      },
      summary: { type: "string", description: "One short sentence about the week's balance" },
    },
    required: ["plan", "summary"],
  },
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/**
 * Pulls the first complete JSON object out of a model reply (drops thinking, code fences and
 * chatter). Braces are matched, skipping any inside strings, so text after the object is ignored.
 */
function extract(text: string): unknown {
  const t = text.replace(/<think>[\s\S]*?<\/think>/g, "").replace(/```(?:json)?/g, "");
  let start = t.indexOf("{");
  while (start >= 0) {
    let depth = 0, inStr = false, esc = false;
    for (let i = start; i < t.length; i++) {
      const ch = t[i];
      if (inStr) {
        if (esc) esc = false;
        else if (ch === "\\") esc = true;
        else if (ch === '"') inStr = false;
      } else if (ch === '"') inStr = true;
      else if (ch === "{") depth++;
      else if (ch === "}" && --depth === 0) {
        try { return JSON.parse(t.slice(start, i + 1)); } catch { break; }
      }
    }
    start = t.indexOf("{", start + 1);
  }
  throw new Error("no json");
}

/** NVIDIA's live model list, refreshed every ten minutes. A failed read is never cached. */
let catalogue: string[] = [];
let catalogueAt = 0;
async function available(): Promise<string[]> {
  if (catalogue.length > 0 && Date.now() - catalogueAt < 10 * 60_000) return catalogue;
  try {
    const r = await fetch("https://integrate.api.nvidia.com/v1/models", { headers: { Authorization: `Bearer ${KEY}` }, signal: AbortSignal.timeout(8000) });
    const d = await r.json();
    const ids = (d?.data ?? []).map((m: { id: string }) => m.id).filter((x: unknown) => typeof x === "string");
    if (ids.length > 0) { catalogue = ids; catalogueAt = Date.now(); }
  } catch {
    // Keep the last good list (or none); the next request tries again.
  }
  return catalogue;
}

/** The models to try, in order: preferred ones that exist, then (for photos) any image reader. */
async function candidates(vision: boolean, task = vision ? "photo" : "text"): Promise<string[]> {
  const forced = Deno.env.get(vision ? "MISE_VISION_MODEL" : "MISE_MODEL");
  const live = (await available()).filter((m) => !SKIP.test(m) && !retired.has(m));
  const families = vision ? VISION_FAMILIES : task === "week" ? [...QUICK_FAMILIES, ...TEXT_FAMILIES] : TEXT_FAMILIES;
  const ranked: string[] = [];
  // The model that last answered this kind of task, if it was quick, goes first.
  const g = good.get(task);
  if (g && g.ms < 40_000) ranked.push(g.model);
  for (const f of families) {
    // Newest version first within a family (v4.1 before v4).
    live.filter((m) => f.test(m)).sort((x, y) => y.localeCompare(x, undefined, { numeric: true })).forEach((m) => { if (!ranked.includes(m)) ranked.push(m); });
  }
  if (forced) ranked.unshift(forced);
  return [...new Set(ranked)].filter((m) => !retired.has(m));
}

/** The last model that answered each task well, and how long it took. */
const good = new Map<string, { model: string; ms: number }>();

const retired = new Set<string>();

/** One model: one try, plus one retry if the JSON is unreadable or the setting is refused. Throws a short reason. */
async function tryModel(model: string, messages: unknown[], maxTokens: number, signal: AbortSignal): Promise<Record<string, unknown>> {
  const convo = [...messages];
  let plain = false;
  for (let attempt = 0; attempt < 2; attempt++) {
    let r: Response;
    try {
      r = await fetch("https://integrate.api.nvidia.com/v1/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${KEY}`, Accept: "application/json", "Content-Type": "application/json" },
        body: JSON.stringify({
          model, messages: convo, max_tokens: maxTokens, temperature: 0.3, top_p: 0.9, stream: false,
          // Answer directly: no long hidden reasoning. Dropped if a model rejects the setting.
          ...(plain ? {} : { chat_template_kwargs: { thinking: false, enable_thinking: false } }),
        }),
        signal,
      });
    } catch {
      throw new Error("too slow");
    }
    const data = await r.json().catch(() => ({}));
    if (!r.ok) {
      const msg = String(data?.detail ?? data?.error?.message ?? data?.title ?? `error ${r.status}`);
      if (r.status === 401 || r.status === 403) throw new Error("KEY");
      if (r.status === 429) throw new Error("LIMIT");
      if (r.status === 400 && !plain && /kwargs|template|unexpected|extra/i.test(msg)) { plain = true; continue; }
      // Listed but not callable on this account, retired, or unable to read images: skip for good.
      if (r.status === 404 || r.status === 410 || /end of life|not found|no longer|not support|does not support image/i.test(msg)) retired.add(model);
      throw new Error(/not found/i.test(msg) ? "not on this account" : msg.slice(0, 60));
    }
    // Some reasoning models put the answer in reasoning_content when content is empty.
    const msg = data?.choices?.[0]?.message ?? {};
    const text = String(msg.content || msg.reasoning_content || "");
    try {
      const out = extract(text) as Record<string, unknown>;
      out._model = model;
      return out;
    } catch {
      convo.push({ role: "assistant", content: text }, { role: "user", content: "That was not valid JSON. Reply again with the JSON object only." });
    }
  }
  throw new Error("unreadable answer");
}

/**
 * Asks for one JSON object matching the tool's schema. One model starts; if it has not answered
 * within STAGGER_MS (or fails), the next starts too, up to three at once. The first good answer
 * wins and the rest are stopped, so a slow model never holds things up, and a quick answer costs
 * one call, not three. Everything finishes within about 100 seconds, inside the app's wait.
 */
// Answer straight away: long hidden reasoning is what made answers take minutes.
const DIRECT = "Reasoning: low. Do not think step by step at length; answer directly. /no_think";
type AskOptions = { task?: string; budgetMs?: number; staggerMs?: number };
async function ask(system: string, content: unknown[], tool: typeof RECIPE_TOOL | typeof WEEK_TOOL, maxTokens = 4000, vision = false, opts: AskOptions = {}) {
  const task = opts.task ?? (vision ? "photo" : "text");
  const STAGGER_MS = opts.staggerMs ?? 15_000;
  const instruction = `Reply with one JSON object only, no other text, matching this JSON schema:\n${JSON.stringify(tool.input_schema)}`;
  const messages: unknown[] = [
    { role: "system", content: `${system}\n\n${tool.description}\n${instruction}\n${DIRECT}` },
    { role: "user", content },
  ];
  const started = Date.now();
  const deadline = started + (opts.budgetMs ?? 100_000);
  const queue = await candidates(vision, task);
  if (queue.length === 0) throw new Error("No AI model is available right now. Try again later.");
  const tried: string[] = [];
  const reasons: string[] = [];
  const stop = new AbortController();
  const timer = setTimeout(() => stop.abort(), deadline - Date.now());
  try {
    return await new Promise<Record<string, unknown>>((resolve, reject) => {
      let running = 0, done = false;
      let wake: ReturnType<typeof setTimeout> | undefined;
      const finish = () => {
        if (done || running > 0 || queue.length > 0 && deadline - Date.now() > 8_000) return;
        done = true;
        if (reasons.includes("KEY")) return reject(new Error("The NVIDIA key was refused. Check the NVIDIA_API_KEY secret."));
        if (reasons.length > 0 && reasons.every((r) => r === "LIMIT")) return reject(new Error("NVIDIA's free limit was reached for now. Try again in a minute."));
        reject(new Error(tried.length ? `The AI could not answer (${tried.slice(0, 6).join("; ")}). Try again.` : "No AI model is available right now. Try again later."));
      };
      const next = () => {
        clearTimeout(wake);
        if (done) return;
        while (queue.length > 0 && retired.has(queue[0])) queue.shift();
        if (queue.length === 0 || running >= 3 || deadline - Date.now() < 8_000) return finish();
        const m = queue.shift()!;
        running++;
        tryModel(m, messages, maxTokens, stop.signal).then((out) => {
          if (done) return;
          done = true;
          good.set(task, { model: m, ms: Date.now() - started });
          resolve(out);
        }, (e) => {
          running--;
          const why = e instanceof Error ? e.message : String(e);
          reasons.push(why);
          tried.push(`${m.split("/").pop()}: ${why}`);
          if (why === "KEY") { queue.length = 0; return finish(); }
          next(); // a failure starts the next model straight away
        });
        // No answer yet after a while: start one more alongside.
        wake = setTimeout(next, STAGGER_MS);
      };
      next();
      // Out of time: no new models; the running ones stop and report, then finish() answers.
      stop.signal.addEventListener("abort", () => { queue.length = 0; clearTimeout(wake); finish(); });
    });
  } finally {
    clearTimeout(timer);
    stop.abort();
  }
}

/** A simple daily cap per account, so a stuck loop on a phone cannot run up the bill. */
const DAILY_CAP = 80;
const usage = new Map<string, { day: string; n: number }>();
function overCap(user: string): boolean {
  const day = new Date().toISOString().slice(0, 10);
  const u = usage.get(user);
  if (!u || u.day !== day) { usage.set(user, { day, n: 1 }); return false; }
  u.n++;
  return u.n > DAILY_CAP;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Use POST" }, 405);
  if (!KEY) return json({ error: "AI is not set up yet: the NVIDIA_API_KEY secret is missing." }, 500);

  // Who is asking: the signed-in Mise account.
  const auth = req.headers.get("Authorization") ?? "";
  let who: Response;
  try {
    who = await fetch(`${SUPABASE_URL}/auth/v1/user`, { headers: { Authorization: auth, apikey: ANON }, signal: AbortSignal.timeout(8000) });
  } catch {
    return json({ error: "Mise AI could not check your sign-in. Try again." }, 503);
  }
  if (!who.ok) return json({ error: "Please sign in again (Settings, Household)." }, 401);
  const user = await who.json().catch(() => ({}));
  const email = String(user?.email ?? "").toLowerCase();
  // A listed email counts only once its owner has proved it (signed in with the emailed code).
  if (!email || !user?.email_confirmed_at || !ALLOWED.includes(email)) return json({ error: "This account is not set up for AI helpers yet." }, 403);

  let body: Record<string, unknown>;
  try { body = await req.json(); } catch { return json({ error: "Bad request" }, 400); }
  if (!body || typeof body !== "object" || Array.isArray(body)) return json({ error: "Bad request" }, 400);
  if (body.task !== "status" && overCap(String(user?.id ?? email))) return json({ error: "That is a lot of AI for one day. It is back tomorrow." }, 429);
  const kitchen = String(body.kitchen ?? "").trim().slice(0, 3000);
  const SYSTEM = kitchen ? `${HOUSE}\n\nHow this household cooks and eats (follow it closely):\n${kitchen}` : HOUSE;

  try {
    switch (body.task) {
      case "photo": {
        const images = Array.isArray(body.images) ? body.images as { media_type: unknown; data: unknown }[] : [];
        if (images.length === 0 || images.length > 4) return json({ error: "Send one to four photos." }, 400);
        // NVIDIA takes inline images up to 180,000 base64 characters each.
        const okImage = (i: { media_type: unknown; data: unknown }) => i && typeof i.data === "string" && i.data.length > 0 && i.data.length <= 180_000 &&
          /^[A-Za-z0-9+/=]+$/.test(i.data) && ["image/jpeg", "image/png", "image/webp"].includes(String(i.media_type));
        if (!images.every(okImage)) return json({ error: "A photo was too large or unreadable. Try again, or use fewer photos." }, 400);
        const content: unknown[] = [{
          type: "text",
          text: `These photos show one recipe (a cookbook page, a handwritten card, a screenshot or a packet). ` +
            `Transcribe it faithfully into Mise's format: keep the author's amounts and steps, convert imperial to metric, ` +
            `and where an amount is missing, estimate a sensible one and add "est." to its note. ` +
            `If the photos do not show a recipe, return a title of "Not a recipe" and explain in notes.` +
            (body.hint ? `\nThe cook's note: ${String(body.hint).slice(0, 300)}` : ""),
        }];
        images.forEach((i) => content.push({ type: "image_url", image_url: { url: `data:${i.media_type};base64,${i.data}` } }));
        return json({ recipe: await ask(SYSTEM, content, RECIPE_TOOL, 4000, true) });
      }
      case "idea": {
        const request = String(body.prompt ?? "").slice(0, 600);
        if (!request.trim()) return json({ error: "Say what you would like to cook." }, 400);
        const serves = Math.min(24, Math.max(1, Math.round(Number(body.serves) || 4)));
        const text = `Write one reliable, home-cookable recipe for ${serves} servings for this request: "${request}". ` +
          `Use ingredients easy to buy in UAE supermarkets (Lulu, Carrefour, Spinneys). Give exact amounts. ` +
          `Mention in notes any shortcut or swap worth knowing.`;
        return json({ recipe: await ask(SYSTEM, [{ type: "text", text }], RECIPE_TOOL) });
      }
      case "week": {
        const arr = (v: unknown, n: number) => Array.isArray(v) ? v.slice(0, n) : [];
        const library = arr(body.library, 400);
        const sides = arr(body.sides, 80);
        const slots = body.slots && typeof body.slots === "object" && !Array.isArray(body.slots) ? body.slots : {};
        if (library.length === 0 || Object.keys(slots).length === 0) return json({ error: "Nothing to plan: add recipes, or choose empty slots." }, 400);
        const servings = Math.min(24, Math.max(0, Math.round(Number(body.servings) || 0)));
        const noCook = arr(body.noCook, 14).map(String).join(", ");
        const draft = arr(body.draft, 21);
        const text = `Plan the empty slots of this week from the library only.\n` +
          `Days and meals to fill (ISO date: meals): ${JSON.stringify(slots)}\n` +
          `Already planned this week: ${JSON.stringify(arr(body.planned, 60))}\n` +
          `Main dishes (id, title, dishType, cuisine, lastHad, proteins): ${JSON.stringify(library)}\n` +
          `Sides, rice and breads (id, title): ${JSON.stringify(sides)}\n` +
          (noCook ? `No cooking on (weekday:meal): ${noCook}\n` : "") +
          (servings ? `Usual servings for one cook: ${servings}\n` : "") +
          `Wishes for this week: ${String(body.wishes ?? "none").slice(0, 400)}\n` +
          `Rules: follow the household's profile first (weekly pattern, protein rotation, sides, servings). ` +
          `Do not repeat a main dish in the week, and count what is already planned. Prefer dishes not had for a while. ` +
          `For a curry or gravy dish, set sideId to one of the sides listed, otherwise 0. ` +
          `When a cook also feeds later meals (for example the next day's lunch, or two lunches before a no-cook evening), list them in covers and raise servings to match. ` +
          `Use only ids from the lists and only the given slots.` +
          (draft.length ? `\nA draft made on the phone from the household's rules: ${JSON.stringify(draft)}\n` +
            `Improve it where the profile or the wishes call for something better, otherwise keep it. Reply with the full plan.` : "");
        // The phone already shows its own plan, so this answer has under a minute, not two.
        return json(await ask(SYSTEM, [{ type: "text", text }], WEEK_TOOL, 1200, false, { task: "week", budgetMs: 55_000, staggerMs: 8_000 }));
      }
      case "status": {
        // A tiny live test of the top models, so Settings can say what really works.
        const probe = async (vision: boolean) => {
          const list = (await candidates(vision)).slice(0, 4);
          const results = await Promise.all(list.map(async (m) => {
            const t0 = Date.now();
            try {
              await tryModel(m, [{ role: "user", content: 'Reply with {"ok": true}' }], 200, AbortSignal.timeout(20_000));
              return { model: m, ok: true, ms: Date.now() - t0 };
            } catch (e) {
              return { model: m, ok: false, why: e instanceof Error ? e.message : String(e) };
            }
          }));
          const working = results.filter((r) => r.ok).sort((a, b) => (a.ms ?? 0) - (b.ms ?? 0));
          // A trivial reply says little about a real one, so the probe never changes the order.
          return { working: working.map((r) => r.model), failed: results.filter((r) => !r.ok) };
        };
        // A real, small planning request to the quick models: how long suggestions really take.
        const plan = async () => {
          const list = (await candidates(false, "week")).slice(0, 4);
          const lib = [[1, "Butter chicken", "Curry", "Indian", "never", "chicken"], [2, "Mutton curry", "Curry", "Indian", "never", "mutton"],
            [3, "Spaghetti bolognese", "Pasta", "Italian", "never", "beef"], [4, "Nasi goreng", "Rice", "Indonesian", "never", "prawns"],
            [5, "Dal", "Dal", "Indian", "never", "vegetables"], [6, "Tandoori salmon bake", "Dry and roast", "Indian", "never", "fish"],
            [7, "Beijing beef", "Stir fry", "Chinese", "never", "beef"], [8, "Chicken pot pie", "Pizza and savoury bake", "British", "never", "chicken"]];
          const user = `Plan the empty slots from the library only.\nDays and meals to fill: {"2026-01-05":["dinner"],"2026-01-06":["dinner"],"2026-01-07":["dinner"]}\n` +
            `Main dishes (id, title, dishType, cuisine, lastHad, proteins): ${JSON.stringify(lib)}\nSides (id, title): [[9,"Jeera rice"]]\n` +
            `No cooking on: THURSDAY:dinner\nRules: no repeats, rotate proteins, curries get sideId 9. Use only these ids and slots.`;
          const sys = `${HOUSE}\n\n${WEEK_TOOL.description}\nReply with one JSON object only, no other text, matching this JSON schema:\n${JSON.stringify(WEEK_TOOL.input_schema)}\n${DIRECT}`;
          const results = await Promise.all(list.map(async (m) => {
            const t0 = Date.now();
            try {
              await tryModel(m, [{ role: "system", content: sys }, { role: "user", content: user }], 1200, AbortSignal.timeout(50_000));
              return { model: m, ok: true, ms: Date.now() - t0 };
            } catch (e) {
              return { model: m, ok: false, ms: Date.now() - t0, why: e instanceof Error ? e.message : String(e) };
            }
          }));
          const fastest = results.filter((r) => r.ok).sort((a, b) => a.ms - b.ms)[0];
          if (fastest) good.set("week", { model: fastest.model, ms: fastest.ms });
          return results;
        };
        const [text, vision, planning] = await Promise.all([probe(false), probe(true), plan()]);
        return json({ text: text.working, vision: vision.working, failed: [...text.failed, ...vision.failed], planning, catalogue: (await available()).length });
      }
      default:
        return json({ error: "Unknown task" }, 400);
    }
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 502);
  }
});
