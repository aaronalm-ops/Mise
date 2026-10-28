// Mise AI helpers: one Supabase Edge Function between the app and NVIDIA's hosted models
// (build.nvidia.com, OpenAI-style chat API). The NVIDIA key lives here as a secret, never in
// the app. Only signed-in accounts whose email is listed in MISE_AI_EMAILS can use it.
//
// Secrets (Supabase, Edge Functions, Secrets):
//   NVIDIA_API_KEY      your key from build.nvidia.com (starts with nvapi-)
//   MISE_AI_EMAILS      comma-separated emails allowed to use AI (your household)
//   MISE_MODEL          optional text model, default meta/llama-3.3-70b-instruct
//   MISE_VISION_MODEL   optional photo model, default mistralai/ministral-14b-instruct-2512
//
// Tasks: "photo" (photos of a recipe -> recipe), "idea" (a request -> recipe),
// "week" (the library and the week -> suggested dishes).

const MODEL = Deno.env.get("MISE_MODEL") ?? "meta/llama-3.3-70b-instruct";
const VISION_MODEL = Deno.env.get("MISE_VISION_MODEL") ?? "mistralai/ministral-14b-instruct-2512";
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
async function ask(system: string, content: unknown[], tool: typeof RECIPE_TOOL | typeof WEEK_TOOL, maxTokens = 4000, vision = false) {
  const instruction = `Reply with one JSON object only, no other text, matching this JSON schema:\n${JSON.stringify(tool.input_schema)}`;
  const messages: unknown[] = [
    { role: "system", content: `${system}\n\n${tool.description}\n${instruction}` },
    { role: "user", content },
  ];
  for (let attempt = 0; attempt < 2; attempt++) {
    const r = await fetch("https://integrate.api.nvidia.com/v1/chat/completions", {
      method: "POST",
      headers: { Authorization: `Bearer ${KEY}`, Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({ model: vision ? VISION_MODEL : MODEL, messages, max_tokens: maxTokens, temperature: 0.3, top_p: 0.9, stream: false }),
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(data?.detail ?? data?.error?.message ?? data?.title ?? `NVIDIA error ${r.status}`);
    const text = String(data?.choices?.[0]?.message?.content ?? "");
    try {
      return extract(text);
    } catch {
      messages.push({ role: "assistant", content: text }, { role: "user", content: "That was not valid JSON. Reply again with the JSON object only." });
    }
  }
  throw new Error("The AI's answer could not be read. Try again.");
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
      default:
        return json({ error: "Unknown task" }, 400);
    }
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 502);
  }
});
