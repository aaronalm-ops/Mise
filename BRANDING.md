# Mise construction: Instinct branding guidance

Requested by Aaron on 26 October 2026. These are build instructions, not evidence of completed UI or a settled Mise visual identity. Read current source and specs before applying them. Do not modify unrelated work.

## Product identity and maker signature

Keep **Mise** as the product name with its own launcher icon, logo, fonts and theme. Master name B (Outfit under Instinct) is Aaron's personal identity; it does not select Mise's UI font. Retain the fixed Instinct geometry and cyan `#28D9E8`, charcoal `#18191B` on light surfaces and off-white `#F4F6F8` on dark surfaces.

Use the canonical two-color VectorDrawables in `../Aaron Almeida Brand/official/`. Render with Compose Image or an explicitly untinted painter so cyan is preserved. Never redraw the paths or uniformly tint the mark.

Place a quiet **by Aaron Almeida** credit in an existing Settings/About surface, after the app/version information. If no About surface exists yet, record the placement for that build stage rather than adding a fifth primary destination just for attribution. Keep the mark decorative beside accessible text. Translate the approved About proportions to native units: 48dp-wide mark with its 720:544 ratio, 12dp gap, 14sp text/22sp line height, normal prefix and medium name. Keep the phrase together while supporting font scaling.

## Existing planned navigation and controls

The current spec calls for **Plan, Recipes, List and Pantry**. Preserve these four destinations and their actual state/back behavior. Translate the selected lifted navigation v2 to native Compose: 48dp selected disc, 12dp rise, 4dp surface ring, 300ms position transition; state colors switch together and labels stay visible. Honor system animation/accessibility settings and real system/IME insets. Reserve measured space above the bar and lifted disc. Do not add a central Create action unless an existing product decision calls for it.

Canonical icon source: `../Aaron Almeida Brand/official/ui/navigation-v1/`. Match semantics before selecting a drawing: calendar may fit Plan; Recipes, grocery List and Pantry need clear domain glyphs. Keep useful existing domain icons when no approved drawing matches. Do not silently substitute analytics, chat or saved for unrelated food/storage concepts.

Use the approved first control family only for matching existing controls: Build grocery list, Add to plan, recipe Save, selection/focus states and existing step indicators. Preserve status colors and distinguish a selected serving/variant from a saved recipe or bought item. Do not add hamburger menus, pagination or tabs to display the kit.

## Panels v2 on real Mise tasks

The first likely task is **Add recipe**, followed by **Add to plan**. Keep long ingredient/variant/step forms scrollable with clear headings, compact choices, a visible task-specific action footer and reachable Close/Cancel. Keep serving calculations and ingredient edits in context. Long editors may remain full screens when that fits the native task better.

Guard dirty drafts on Close, Cancel and Android Back with Keep editing/Discard. Respect the spec's saved-draft behavior: do not discard a persisted recipe draft or treat it as lost merely because the panel closes. During an explicit save, prevent repeated submission, lock conflicting actions and retain the draft with a retry message on failure. Distinguish a completed local Room write queued for sync from a failed local save. Never claim household sync completed before it has.

Apply this to the real task callbacks and native sheet coordinator. Keep native focus, keyboard, TalkBack and Back behavior. Do not copy the Nibbles study adapter, fake records, failure buttons, synthetic delay or web dialog/history implementation into Android. Quick grocery/pantry ticks should remain quick actions; no confirmation panel for every tick.

## Verification before claiming completion

- Compile and run relevant existing checks for touched modules.
- Inspect light/dark and enlarged text, TalkBack labels/focus, short keyboard-visible forms, Android Back and bottom-edge clearance.
- Check on the specified S23 Ultra and S25+ when available; label emulator-only evidence accurately.
- Exercise serving scaling, selected variants, merged units, saved drafts, failed-save retry, offline ticks and shared-list updates without exposing private household data.
- Record implemented, visually checked, device checked and deployed separately. Deployment/publication require a separate instruction.

## Canonical references

- `../Aaron Almeida Brand/SIGNATURE-LAYOUT.md`
- `../Aaron Almeida Brand/SIGNATURE-UI-KIT.md`
- `../Aaron Almeida Brand/BOTTOM-NAVIGATION.md` and `official/ui/navigation-v2/` under that brand folder
- `../Aaron Almeida Brand/PANELS-V2.md`
- `../Second Brain/BRAND.md`
