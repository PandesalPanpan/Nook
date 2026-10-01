# Native Note source and implementation

Inspected Figma AtvThX3LNHp8kP1iItDB5N / 2:517 through get_design_context with its screenshot on 2026-10-01.

- Source frame: 375 × 812, Night background #171412; horizontal inset 16 px.
- Brand marker 24 × 24; navigation markers 18 × 18. Existing original SVGs remain at these slots and sizes.
- Header action pill: 76 × 28, radius 14, elevated #342d28. Implementation surrounds it with a minimum 48 dp action target and a working note menu.
- Note heading: 30 px Inter Bold; editable title 24 px; context 10 px Bold orange #ff7e1d.
- Content surface: 343 × 384, radius 20, #221d1a. Paragraph text 14 px; sample second-level heading 15 px; source list text 13 px secondary.
- Reference pill: elevated surface, radius 14, 12 px text. A final standalone resolved wiki-link paragraph renders as this actual navigation action. Inline, emphasized, code, list, quote and unresolved references keep their existing Markdown path; original Markdown is preserved in storage and export.
- Backlinks: divider #453d37, 12 px heading and links. Native action rows use at least 48 dp each and stable creation order.
- Toolbar: source radius 18 / #342d28, with smaller surface pills and blue Link action. Native targets are independently at least 48 dp and wrap with available width and font scale. More formatting and Preview/Edit controls follow the primary toolbar.

Implemented in NoteScreen.kt and integration/NoteEditor.kt, using the existing Room draft, parser, linked-note, original-file and outbox paths. Saved notes initially show rendered content; empty notes open in editing mode. The title remains optional. The note menu saves, archives/restores and permanently deletes locally. Context fields are disclosed separately.

The actual production activity fixture is artifacts/visual/native-note-populated.png. Large-text component fixtures are native-note-large-360.png and native-note-large-280.png. These are flowing layouts rather than clipped replicas: title and reference content can wrap, and cards grow to retain accessible links. Native source comparison was inspected; full keyboard/TalkBack, additional Markdown block styles (including source list typography), and whole-product visual/accessibility acceptance remain open.
