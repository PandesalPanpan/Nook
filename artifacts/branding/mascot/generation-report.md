# Nook mascot generation record

Generated 2026-10-02 for the Nook mascot feature.

## Product context and directions

Nook is an offline-first second brain for capturing thoughts quickly, then clarifying, organizing, and reviewing them later. Its existing Android and web UI use the MarticioUI Night palette: warm charcoal background, warm ivory text, orange brand accent, teal support accent, rounded surfaces, and Inter typography.

- **A — Dormouse:** thoughts tucked somewhere safe; a compact joined head and body with two generous circular ears.
- **B — Squirrel:** useful fragments collected for later; a small round body with one broad curled tail.
- **C — Owl:** quiet memory and thoughtful review; a rounded body with a broad face and oversized circular eyes.

## Generation record

Six independent built-in image_gen calls were made, one per image. No image references, retries, edits, or model-generated contact sheets were used. All outputs are separate native 1254 × 1254 PNGs. The image tool exposed image_url and output_hint only; it exposed no provider or model name, so the precise model cannot be verified or claimed. The runtime accepted only the main prompt, so exclusions were delivered as a natural-language Constraints line.

All prompts used the installed ip-as-logo prompt skeleton. These values were substituted per candidate:

| Candidate | Direction and product rationale | Corner | Subject and color mapping | Original path | Dimensions | Bytes | SHA-256 |
| --- | --- | --- | --- | --- | ---: | ---: | --- |
| A1 | Dormouse: safe, cozy storage for quick thoughts | Lower-left | Dormouse with paired circular ears; ivory body #F5EEE7, cinnamon ears and facial marks #C87550, Night background #171412 | candidates/A1.png | 1254 × 1254 | 1,125,729 | 2B7AC1F13BE28BB6C3B058E8A255415282D1C0DA9F39BAD11C8FEA246B59B1F0 |
| A2 | Dormouse: safe, cozy storage for quick thoughts | Lower-right | Dormouse with paired circular ears; golden-oat body #D6AD73, muted-teal ears and facial marks #3FAE9B, Night background #171412 | candidates/A2.png | 1254 × 1254 | 1,128,784 | A01E5A3302A79AAD3646886421E0A0C9D69EAF4604B034D2EA1E755B722E1129 |
| B1 | Squirrel: gathering useful things to sort later | Lower-left | Squirrel with broad curled tail; ivory body #F5EEE7, Nook-orange tail and facial marks #FF7E1D, Night background #171412 | candidates/B1.png | 1254 × 1254 | 1,145,953 | 0B3FA4793F02234D80B35F65F7999B4E930939D7EF0B48A8B906254E404305BE |
| B2 | Squirrel: gathering useful things to sort later | Lower-right | Squirrel with broad curled tail; cinnamon body #C87550, muted-teal tail and facial marks #3FAE9B, Night background #171412 | candidates/B2.png | 1254 × 1254 | 1,051,986 | 8D97E9308A678E24255EAD2D68BFF1A80B735D8A9260CD061F6C15B65DCD58CE |
| C1 | Owl: quiet memory and thoughtful review | Lower-left | Owl with broad face and paired circular eyes; teal body #3FAE9B, ivory face and facial marks #F5EEE7, Night background #171412 | candidates/C1.png | 1254 × 1254 | 1,111,227 | 1EA7F5DF020D1AD1273C4C0E0BE6A7F19F71227501BF8833F46E9C74059CCAFE |
| C2 | Owl: quiet memory and thoughtful review | Lower-right | Owl with broad face and paired circular eyes; golden-oat body #D6AD73, soft blue face and facial marks #8EB4EE, Night background #171412 | candidates/C2.png | 1254 × 1254 | 1,165,739 | 6CA1331FF9398CF363F5DA26E308A66FBC4BD05B7993A8139D06EFC1F389D608 |

Every prompt assigned A1/B1/C1 to the lower-left and A2/B2/C2 to the lower-right. Each requested exactly two IP base colors plus the solid #171412 background. The shared exact exclusions line was:

> Constraints: Use no text or watermark. Add no borders, frames, cards, or presentation masks. Include one character only, with no extra subjects or scenery. Use no fragile lines, sharp tips, unnecessary outlines, tiny details, or decorative marks. Add no photorealistic material, dramatic bevel, glossy hotspot, deep occlusion, extrusion, strong three-dimensional rendering, or external cast shadow. Keep the background solid and uniform, with no texture, vignette, or lighting variation.

## Production selection

**A1 is the production mascot.** At 32 × 32, its paired circular ears and joined round silhouette still read as a small mouse, while the face remains calm and simple. Its ivory and cinnamon color masses sit naturally against Nook's existing warm-dark canvas and restrained orange accents. The squirrel candidates make the large tail the dominant read; the owl candidates rely on larger eyes and feel more juvenile. A1 best supports Nook's cozy, quietly helpful personality without changing the product's visual hierarchy.

The character remains unnamed; no natural short name emerged.

The six original files above are retained unchanged. A1 is also the high-resolution source asset. The production PNG was downscaled from A1 with high-quality bicubic resampling and saved losslessly; it is 512 × 512, 280,639 bytes, SHA-256 D5E5A623E391586DC471EBE884E67C31F121BADFF4E5F4856DA2A26862BDE079. Identical local copies are bundled at:

- Android: apps/android/app/src/main/res/drawable-nodpi/nook_mascot.png
- Web: apps/web/public/mascot/nook-mascot.png
- Shared production master: nook-mascot-512.png

The image keeps its generated warm-dark background, which blends with Nook's current Night welcome screen. The mascot appears only on the Android and web welcome screens; both instances describe the character to assistive technology. Review of the darker elevated Inbox panel exposed a visible matte edge, so the mascot is not placed there. Nook currently ships only the Night palette; the same matte would stand out on a light surface, so the asset needs a transparent or light-surface treatment before Nook adds a light theme. The Android adaptive launcher icon was inspected and left unchanged.

Visual review evidence is in `screenshots/`: the Android welcome screen was captured from the published v0.2.2 APK on a clean Pixel 8 API 36 emulator at 1080 × 2400, and the web welcome screen was checked at 1440 × 900 and 320 × 760. The image stays proportional and visible on both screen sizes; the welcome content scrolls on the narrow web viewport without clipping. Nook has no light theme today, so only its supported warm-dark surface is used in product.

