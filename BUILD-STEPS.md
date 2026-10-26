# MISE build steps (Phase 1)

> Written 2026-10-26. Baby steps, assume no background. Same pattern as Lumen: Claude writes code and pushes; GitHub compiles; Aaron installs and tests. Every command is spelt out in full. Read with PROJECT-BRIEF.md, SPEC-PHASE1.md, BRANDING.md and AGENTS.md.

## What Phase 1 is
The core loop on both phones: recipes with servings and swaps, a weekly plan, one merged grocery list shared live between Aaron and Janice, and a pantry. Manual recipe entry only. No AI, no Folio import, no store comparison yet.

## Milestones
Each milestone ends with an APK on GitHub Releases and a short test sheet. We do not start the next one until the sheet passes.

| # | Milestone | What you can do at the end |
|---|---|---|
| M0 | Skeleton | Install Mise. Stone theme, light and dark, four tabs plus centre Add, About screen with the maker credit. Empty screens. |
| M1 | Recipes (on the phone only) | Add, edit and view recipes. Servings stepper scales quantities. Swap chips (e.g. protein) change ingredients. Drafts are kept. |
| M2 | Plan and list (on the phone only) | Put dishes on days and meals, choose servings and swaps, build the merged grocery list, tick items off, add one-off items. Staples hidden when you have them. |
| M3 | Household and live sync | Sign in. Aaron and Janice join one household. Recipes, plan, list and pantry appear on both phones. A tick on one phone shows on the other within seconds. Works offline in the store and catches up later. |
| M4 | Pantry and polish | Pantry with have / low / out feeding the list. Empty, offline and error states. Bigger text, TalkBack and Back behaviour checked. |

Why this order: the maths (scaling, swaps, merging units) is the heart of the app, so it is proven on one phone first. Sync is the riskiest part, so it gets its own milestone.

## Part A: Aaron, once (about 15 minutes)

1. GitHub repository. Go to github.com, sign in. Click the plus (top right), New repository. Name: mise. Public. Leave every checkbox off. Create. Check: an empty repo page with "Quick setup".
2. GitHub token for Claude. Avatar, Settings, Developer settings, Personal access tokens, Fine-grained tokens, Generate new token. Name: mise-claude. Expiration: 90 days. Repository access: Only select repositories, pick mise. Repository permissions: Actions (Read), Contents (Read and write), Metadata (Read), Secrets (Read and write), Workflows (Read and write). Generate. Copy the token (starts with github_pat_). Paste it in the chat once when Claude asks.
3. Supabase project (needed from M3, can wait). Go to supabase.com, sign in with the account Folio uses. New project. Name: mise. Region: the same one Folio uses. Set a database password and save it in your password manager. Check: the project dashboard opens. Then Project Settings, API: copy the Project URL and the anon public key into the chat. Never paste the service_role key.
4. Janice (needed from M3). She only needs her email address ready; Claude will add an invite step in the app.

## Part B: Claude, automatic (per milestone)
5. Write the code in Projects\Mise\app following SPEC-PHASE1.md and BRANDING.md.
6. Create a signing key in Projects\Mise\keys (keep that folder private) and add it to GitHub as four secrets: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS (mise), KEY_PASSWORD.
7. Push, watch the GitHub Actions build, fix and push again until it is green.
8. Tag the release (v0.1.0 for M0, v0.2.0 for M1 and so on). GitHub publishes the APK.
9. From M3: create the database tables and security rules in Supabase (household-only access), and turn on live updates for the grocery list.

## Part C: install (both phones, about 5 minutes)
10. On the phone, open Chrome and go to github.com/<your username>/mise/releases. Tap the newest mise APK.
11. Allow Chrome to install unknown apps once. Accept the Play Protect scan. Install. Check: Mise is in the app drawer.
12. Updates install the same way over the top. Data stays.

## Part D: test sheets
M0
1. Mise opens on Plan. Four tabs and the centre Add work. The selected tab lifts in a graphite disc.
2. Switch the phone to dark mode. Mise turns black with silver. No colour anywhere except the cyan in the maker mark.
3. Settings, About: "by Aaron Almeida" with the mark, after the version.

M1
1. Add Chicken biryani for 4 with chicken / mutton / prawn swaps. Save. It appears in Recipes.
2. Servings 4 to 6: rice 500 g becomes 750 g. Back to 4: 500 g.
3. Choose prawn: chicken disappears, prawn 600 g appears.
4. Start a new recipe, type a title, press Back, choose Keep editing. Then Discard. Then start again, close the app mid-way: the draft is still there.

M2
1. Plan biryani (6 servings) and dal on Monday, fish curry on Tuesday. Build list.
2. Onion appears once with the total from all three dishes.
3. 500 g plus 1 kg of the same item shows 1.5 kg.
4. Tick Tomato: it leaves the Pending view and shows under All.
5. Add "Milk" by hand. Salt (a staple you have) is not on the list.

M3
1. Both phones signed in, same household. Janice adds a recipe; it appears on Aaron's phone.
2. Janice ticks Onion; Aaron sees it ticked with a J within 5 seconds.
3. Aaron turns on aeroplane mode, ticks three items, turns it off. Janice sees all three.
4. A different account cannot see your household's data.

M4
1. Set Ghee to Low: it appears on the next list. Set it to Have: it does not.
2. Largest text size in Samsung settings: nothing cut off, the bottom bar never covers content.
3. TalkBack reads every button and quantity.
4. Offline: a clear "Offline, will sync" line, never a spinner.

## Kill rule
Phase 1 passes when every M3 line passes on both S23 Ultra and S25+. If M3 has not passed after 30 build cycles or 3 weeks, we simplify sync (list-only live sharing) before going further.
