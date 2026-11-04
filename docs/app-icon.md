# Car and lightning launcher icon

The owner selected concept 1, **Car + energy**, on 4 November 2026. The original
concept was generated with the built-in imagegen tool. The shipping icon is a
native Android VectorDrawable adaptation, not a scaled screenshot.

- Selected raster reference: `assets/launcher-car-lightning-reference.png`.
- Standalone vector preview: `assets/launcher-car-lightning.svg`.
- Android foreground: `../app/src/main/res/drawable/ic_foreground.xml`.
- Android monochrome: `../app/src/main/res/drawable/ic_monochrome.xml`.
- Independent charcoal adaptive background: `launcher_background` in
  `../app/src/main/res/values/launcher_colors.xml`.
- The normal and round manifest icons use the same adaptive resource.

Artwork uses a 108 dp viewport, with a centered 0.91 scale for mask safety. The
preview uses the central 72 dp launcher viewport; extra artwork area belongs to
adaptive-icon movement. Ivory body, amber bolt, transparent windows/lights/grille;
the themed layer cuts the bolt out of the body. No manufacturer badge is used.
The old battery icon is replaced in the drawable resources. A square, circle
and themed comparison is under ignored `artifacts/launcher-preview.png`.

## Generation prompt

The following prompt produced the selected reference through the built-in tool:

```text
Use case: logo-brand. Create ONE polished Android launcher icon concept for eCMPilot, an independent open-source local electric-car companion app for charging, preconditioning and vehicle status. Square image, icon fills the square as a finished clean app icon, generous adaptive-icon safe margins around the central mark. Crisp vector-like geometric design, excellent legibility at 48px, very few shapes, high contrast, professional distinctive visual identity. No words, no captions, no watermarks, no automaker badge, no Opel or Stellantis logo. Flat or very restrained tonal depth, no photorealism, no busy circuit-board pattern, no 3D chrome mockup. This is a brainstorming preview, not a launcher asset installation. Direction 1: a bold minimal FRONT VIEW of a compact electric hatchback, drawn as one elegant ivory silhouette with a small central amber lightning-shaped negative-space detail. Dark charcoal #18181A background, warm amber #EFB16C accent. Clever integrated car/electricity symbol, balanced wide stance, clean windshield and two simple headlamp marks. Recognizable as a car rather than just a battery.
```
