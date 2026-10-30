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

// Strongest first. Text: recipe writing, JSON and planning. Vision: reading recipe photos.
const TEXT_PREFS = [
  Deno.env.get("MISE_MODEL") ?? "",
  "deepseek-ai/deepseek-v4-flash", "zhipuai/glm-5.1", "moonshotai/kimi-k2.5", "deepseek-ai/deepseek-v4-pro",
  "qwen/qwen3.5-397b-a17b", "openai/gpt-oss-120b", "nvidia/nemotron-3-super-120b-a12b", "nvidia/nemotron-3-super-120b",
  "mistralai/mistral-large-3-675b-instruct-2512",
].filter(Boolean);
const VISION_PREFS = [
  Deno.env.get("MISE_VISION_MODEL") ?? "",
  "qwen/qwen3.5-397b-a17b", "moonshotai/kimi-k2.5", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning", "nvidia/nemotron-3-nano-omni",
  "mistralai/mistral-large-3-675b-instruct-2512", "mistralai/ministral-14b-instruct-2512", "meta/llama-4-maverick-17b-128e-instruct",
].filter(Boolean);
// Any other catalogue model whose name says it reads images.
const VISION_HINT = /(vl|vision|omni|maverick|scout|ministral|gemma-3|pixtral|kimi-k2\.5)/i;
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
            recipeId: { type: "integer", description: "id from the library" },
            reason: { type: "string", description: "Why this dish, in at most eight words" },
          },
          required: ["date", "meal", "recipeId", "reason"],
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

/** Pulls the JSON object out of a model reply (drops thinking, code fences and chatter). */
function extract(text: string): unknown {
  const t = text.replace(/<think>[\s\S]*?<\/think>/g, "").replace(/```(?:json)?/g, "");
  const a = t.indexOf("{"), b = t.lastIndexOf("}");
  if (a < 0 || b <= a) throw new Error("no json");
  return JSON.parse(t.slice(a, b + 1));
}

/**
 * Asks the model for one JSON object matching the tool's schema. Retries once if the reply is
 * not valid JSON.
 */
let catalogue: string[] | null = null;
async function available(): Promise<string[]> {
  if (catalogue) return catalogue;
  try {
    const r = await fetch("https://integrate.api.nvidia.com/v1/models", { headers: { Authorization: `Bearer ${KEY}` }, signal: AbortSignal.timeout(8000) });
    const d = await r.json();
    catalogue = (d?.data ?? []).map((m: { id: string }) => m.id);
  } catch {
    catalogue = [];
  }
  return catalogue!;
}

/** The models to try, in order: preferred ones that exist, then (for photos) any image reader. */
async function candidates(vision: boolean): Promise<string[]> {
  const live = await available();
  const prefs = vision ? VISION_PREFS : TEXT_PREFS;
  if (live.length === 0) return prefs;
  const picked = prefs.filter((m) => live.includes(m));
  if (vision) picked.push(...live.filter((m) => VISION_HINT.test(m) && !picked.includes(m)));
  else picked.push(...live.filter((m) => /(deepseek-v|glm-|kimi-k|qwen3|gpt-oss|nemotron-3-(super|ultra))/i.test(m) && !/(embed|guard|reward|coder|vl)/i.test(m) && !picked.includes(m)));
  return picked.length ? picked : prefs;
}

const retired = new Set<string>();

async function ask(system: string, content: unknown[], tool: typeof RECIPE_TOOL | typeof WEEK_TOOL, maxTokens = 4000, vision = false) {
  const instruction = `Reply with one JSON object only, no other text, matching this JSON schema:\n${JSON.stringify(tool.input_schema)}`;
  const messages: unknown[] = [
    { role: "system", content: `${system}\n\n${tool.description}\n${instruction}` },
    { role: "user", content },
  ];
  // The app waits up to two minutes and Supabase stops a function at about 150 seconds, so the
  // whole answer must come within about 100 seconds: each model gets a share, then the next.
  const deadline = Date.now() + 100_000;
  const tried: string[] = [];
  const models = (await candidates(vision)).filter((m) => !retired.has(m)).slice(0, 3);
  for (const model of models) {
    const left = deadline - Date.now();
    if (left < 15_000) break;
    const convo = [...messages];
    let outcome = "";
    for (let attempt = 0; attempt < 2 && !outcome; attempt++) {
      let r: Response;
      try {
        r = await fetch("https://integrate.api.nvidia.com/v1/chat/completions", {
          method: "POST",
          headers: { Authorization: `Bearer ${KEY}`, Accept: "application/json", "Content-Type": "application/json" },
          body: JSON.stringify({
            model, messages: convo, max_tokens: maxTokens, temperature: 0.3, top_p: 0.9, stream: false,
            // Answer directly: no long hidden reasoning (models that do not know these ignore them).
            chat_template_kwargs: { thinking: false, enable_thinking: false },
          }),
          signal: AbortSignal.timeout(Math.min(70_000, deadline - Date.now())),
        });
      } catch {
        outcome = "too slow";
        break;
      }
      const data = await r.json().catch(() => ({}));
      if (!r.ok) {
        const msg = String(data?.detail ?? data?.error?.message ?? data?.title ?? `error ${r.status}`);
        if (r.status === 401 || r.status === 403) throw new Error("The NVIDIA key was refused. Check the NVIDIA_API_KEY secret.");
        if (r.status === 429) throw new Error("NVIDIA's free limit was reached for now. Try again in a minute.");
        // Retired, missing or unable to read images: never try it again this session.
        if (r.status === 404 || r.status === 410 || /end of life|not found|no longer/i.test(msg)) retired.add(model);
        outcome = msg.slice(0, 80);
        break;
      }
      const text = String(data?.choices?.[0]?.message?.content ?? "");
      try {
        const out = extract(text) as Record<string, unknown>;
        out._model = model;
        return out;
      } catch {
        convo.push({ role: "assistant", content: text }, { role: "user", content: "That was not valid JSON. Reply again with the JSON object only." });
        if (attempt === 1) outcome = "unreadable answer";
      }
    }
    tried.push(`${model.split("/").pop()}: ${outcome || "failed"}`);
  }
  const lastError = tried.length ? `The AI could not answer (${tried.join("; ")}). Try again.` : "No AI model is available right now. Try again later.";
  throw new Error(lastError);
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Use POST" }, 405);
  if (!KEY) return json({ error: "AI is not set up yet: the NVIDIA_API_KEY secret is missing." }, 500);

  // Who is asking: the signed-in Mise account.
  const auth = req.headers.get("Authorization") ?? "";
  const who = await fetch(`${SUPABASE_URL}/auth/v1/user`, { headers: { Authorization: auth, apikey: ANON } });
  if (!who.ok) return json({ error: "Please sign in again (Settings, Household)." }, 401);
  const user = await who.json();
  const email = String(user?.email ?? "").toLowerCase();
  if (!ALLOWED.includes(email)) return json({ error: "This account is not set up for AI helpers yet." }, 403);

  let body: Record<string, unknown>;
  try { body = await req.json(); } catch { return json({ error: "Bad request" }, 400); }

  try {
    switch (body.task) {
      case "photo": {
        const images = (body.images as { media_type: string; data: string }[] | undefined) ?? [];
        if (images.length === 0 || images.length > 4) return json({ error: "Send one to four photos." }, 400);
        const content: unknown[] = [{
          type: "text",
          text: `These photos show one recipe (a cookbook page, a handwritten card, a screenshot or a packet). ` +
            `Transcribe it faithfully into Mise's format: keep the author's amounts and steps, convert imperial to metric, ` +
            `and where an amount is missing, estimate a sensible one and add "est." to its note. ` +
            `If the photos do not show a recipe, return a title of "Not a recipe" and explain in notes.` +
            (body.hint ? `\nThe cook's note: ${String(body.hint).slice(0, 300)}` : ""),
        }];
        images.forEach((i) => content.push({ type: "image_url", image_url: { url: `data:${i.media_type};base64,${i.data}` } }));
        return json({ recipe: await ask(HOUSE, content, RECIPE_TOOL, 4000, true) });
      }
      case "idea": {
        const ask = String(body.prompt ?? "").slice(0, 600);
        if (!ask.trim()) return json({ error: "Say what you would like to cook." }, 400);
        const serves = Number(body.serves ?? 4);
        const text = `Write one reliable, home-cookable recipe for ${serves} servings for this request: "${ask}". ` +
          `Use ingredients easy to buy in UAE supermarkets (Lulu, Carrefour, Spinneys). Give exact amounts. ` +
          `Mention in notes any shortcut or swap worth knowing.`;
        return json({ recipe: await ask(HOUSE, [{ type: "text", text }], RECIPE_TOOL) });
      }
      case "week": {
        const library = (body.library as unknown[]) ?? [];
        const text = `Plan the empty slots of this week from the library only.\n` +
          `Days and meals to fill (ISO date: meals): ${JSON.stringify(body.slots)}\n` +
          `Already planned this week: ${JSON.stringify(body.planned ?? [])}\n` +
          `Library (id, title, dishType, cuisine, lastHad): ${JSON.stringify(library)}\n` +
          `Their wishes for this week: ${String(body.wishes ?? "none").slice(0, 400)}\n` +
          `Rules: vary dish types and cuisines across the week; do not repeat a dish; prefer dishes not had for a while; ` +
          `quick dishes on busy weekdays, bigger cooks at the weekend; a curry pairs with a rice or bread already in the library only if it makes sense. ` +
          `Use only ids from the library and only the given slots.`;
        return json(await ask(HOUSE, [{ type: "text", text }], WEEK_TOOL, 2500));
      }
      case "status": {
        const text = (await candidates(false)).filter((m) => !retired.has(m));
        const vision = (await candidates(true)).filter((m) => !retired.has(m));
        return json({ text: text.slice(0, 3), vision: vision.slice(0, 3), catalogue: (await available()).length });
      }
      default:
        return json({ error: "Unknown task" }, 400);
    }
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 502);
  }
});
