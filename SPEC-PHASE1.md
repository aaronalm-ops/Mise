# Mise - Phase 1 Spec (draft v0, 26 Oct 2026)

## Stack
| Layer | Tool |
|---|---|
| App | Kotlin + Jetpack Compose, Material 3 |
| Backend | Supabase (Postgres, Auth, Storage, Realtime) |
| Offline | Room, synced to Supabase via WorkManager |
| Live shared list | Supabase Realtime on grocery_items |
| Distribution | Sideload APK (same as Folio) |

## Sharing model
A household (Aaron + Janice). Every row belongs to a household_id. RLS: a user sees rows only for households they are a member of.

## Data model
- households (id, name)
- household_members (household_id, user_id)
- ingredients: shared catalogue so "onion" merges across recipes
  (id, name, aliases[], aisle, default_unit, is_staple)
- units: conversion table (g, kg, ml, l, tsp, tbsp, cup, piece, pinch, "to taste")
- recipes
  (id, household_id, title, cuisine, tags[], base_servings, prep_mins, cook_mins,
   photo_url, source [manual | folio | photo | ai], status [draft | approved], notes)
- recipe_steps (recipe_id, position, text)
- variant_slots: a swappable choice, e.g. "Protein" (id, recipe_id, name)
- variant_options: e.g. Chicken / Mutton / Prawn (id, slot_id, label, is_default, step_note)
- recipe_ingredients
  (recipe_id, ingredient_id, qty, unit, prep_note, optional,
   option_id null = always used, set = only when that option is chosen)
- meal_plan_entries
  (household_id, date, meal [breakfast | lunch | dinner | snack], recipe_id, servings, chosen_options[])
- pantry_items (household_id, ingredient_id, status [have | low | out], updated_at)
- grocery_lists (household_id, week_start, generated_at)
- grocery_items
  (list_id, ingredient_id, qty, unit, aisle, status [pending | have | bought],
   is_manual, source_entries[])

## Grocery list logic
1. Take the plan entries for the chosen days.
2. For each entry: base ingredients + ingredients of the chosen options.
3. Scale: qty x (servings / base_servings).
4. Merge the same ingredient, converting units where possible.
5. Staples marked "have" in pantry are hidden; staples marked low/out appear.
6. Tick "have" or "bought"; list shows pending only by default.
7. Manual add for one-off items (e.g. tissues, milk).

## Screens (bottom nav: Plan, Recipes, List, Pantry)
1. **Plan**: week strip, meal slots per day, tap to add a dish, servings and variant chips per entry, "Build grocery list" button.
2. **Recipes**: search, filter by tag/cuisine, cards with photo. Detail: servings stepper (quantities update live), variant chips, ingredients, steps, "Add to plan".
3. **Add / edit recipe**: title, servings, ingredients (autocomplete from catalogue), variant slots, steps. Drafts saved.
4. **List**: grouped by aisle, tick to remove, shows who ticked, live on both phones, works offline in the store.
5. **Pantry**: staples and stock with have / low / out.

## Out of Phase 1
Folio import, photo to recipe, AI recipes, AI week suggestion (Phase 2). Store comparison (Phase 3).
