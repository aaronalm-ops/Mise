# Mise - Project Brief (draft v0, 26 Oct 2026)

Name: Mise, from "mise en place" (everything prepped and in its place before you cook).

## Problem
Weekly meal planning gets procrastinated because we do not know what to cook. Groceries and cooking end up last minute.

## Core idea
One app holding all our recipes. Pick meals for the week (or a day), get one combined grocery list, tick off what we already have, shop only for what is pending.

## Features by phase

### Phase 1: Plan and list (the core loop)
- Recipe library: dish, base servings, ingredients with quantity and unit, steps
- Serving scaler: change servings, quantities recalculate
- Variations: swap slots (e.g. protein: chicken / mutton / prawn), each swap changes the ingredient list
- Weekly planner: assign dishes to days and meals
- Grocery list: merges the same ingredient across dishes, groups by aisle
- Pantry tick-off: mark what we have; final list shows only pending items
- Staples list (salt, oil, basic spices) assumed in stock unless flagged low
- Add a new recipe manually (for improvised dishes)

### Phase 2: AI helpers
- Import from Folio: pull recipe notes, AI turns messy notes into structured recipes, we review and approve
- Photo to recipe: snap a ready-to-cook paste or packet, AI builds a recipe around it
- "Give me a recipe for X": AI drafts it, saved as a draft we can edit
- Suggest the week: AI proposes a plan from our library (variety, what is already in the pantry)

### Phase 3: Where to buy
- Compare Amazon Now, Instashop, Lulu, Carrefour, in-store
- Inputs: delivery and service fees, minimum order, delivery speed, item prices
- Output: best single store or a sensible split basket
- Known risk: these apps have no public price APIs; scraping breaks their terms and is fragile. Likely approach is store rules plus prices we log from past orders or receipts.

## Decided (26 Oct 2026)
- Phases and design choices agreed
- Users: Aaron and Janice on one shared live list
- Name: Mise
- Platform: native Android (Kotlin + Jetpack Compose), Supabase backend, same stack as Folio
- Design: Lumen type system (Manrope / Inter / JetBrains Mono), approved Instinct lifted navigation v2, maker credit per Aaron Almeida Brand. Accent: matte Stone, chosen by Aaron 26 Oct 2026 (graphite #3A3D41 on warm off-white by day, silver #C7CBD1 on black by night, fine grain, no gloss or gradients, no colour accent; no cyan or basil in the product UI). Mockup: MOCKUP.html v4
- Centre Add action in the bottom bar: kept (Aaron, 26 Oct 2026). Opens Add recipe, Add list item, and later Photo to recipe. This is the product decision BRANDING.md refers to.
- Phones: Samsung Galaxy S23 Ultra (Aaron), S25+ (Janice)

## Build status
- M0 skeleton released 27 Oct 2026: v0.1.0 on github.com/aaronalm-ops/Mise/releases (all five M0 checks passed on Aaron's phone)
- M1 recipes released 27 Oct 2026: v0.2.0 (scaling unit tests pass on CI; not yet checked on a phone)

- Folio import released 27 Oct 2026: v0.4.0, Settings > Import recipes (file kept in Projects\Mise-import, never in this public repo)
- M3 sharing released 27 Oct 2026: v0.5.0. Uses Folio's Supabase project (mise_* tables, supabase/mise-setup.sql); server details come from GitHub secrets

## Open questions
- Where the Folio recipes sit (one folder or scattered) and roughly how many
