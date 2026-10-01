# Area detail source inspection

Inspected through Figma get_design_context on 2026-09-30, including the returned screenshot. File AtvThX3LNHp8kP1iItDB5N, node 2:432.

- Mobile frame: 375 × 812; Night background #171412.
- Content inset: 16 px; heading 30 px Inter Bold; secondary copy 12 px.
- Standards surface: 343 × 174, radius 20; heading 15 px.
- Active projects heading: 15 px. Each project row is 343 × 58, radius 16, background #221d1a, with 12 px between rows.
- Project row: 32 × 32 source SVG at x=14, y=13; text starts x=58, uses 13 px Inter Bold.
- Recurring responsibility: elevated pill, 28 px height, 12 px text. Implementation touch targets must remain accessible.

Original project-row markers downloaded to native assets/figma/2-432-imgEllipse1.svg through imgEllipse3.svg. RecordContext.kt renders their 32 px root geometry. Full Area detail visual acceptance remains pending; this change adds related-work navigation to the existing editor.
