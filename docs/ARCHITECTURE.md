# Drishti Saathi: 3D frames, live 3D try-on and face avatar

This note covers the 3D pipeline added on branch `feature-3d-avatar`. The 2D try-on is unchanged and stays the automatic fallback.

## Pipeline at a glance

```
Kaggle product photos (front _0, side _2)            laptop, offline
        │  tools/frames_to_glb.py  (opencv, shapely, trimesh, pillow)
        ▼
generated/frames3d/  glb/<sku>.glb · thumb/<sku>.jpg · frames_catalog.json   (git-ignored)
        │                                   │
        │ tools/bundle_frames3d.py          │ FastAPI  GET /catalog, /catalog/{id}, /catalog/{id}.glb, /catalog/{id}/thumb
        ▼                                   ▼
APK assets/frames3d (30 frames)      phone downloads on demand → LRU disk cache (~100 MB) + in-memory cache
        └──────────────┬────────────────────┘
                       ▼
   Catalogue screen → 3D viewer → live 3D try-on (camera) / face avatar (photo on the phone)
                       │
          Filament 1.74 + gltfio (GLB), one sun + flat ambient light, PCF soft shadows
```

## 1. Frames from photos ("photo-extruded" hybrid)

`tools/frames_to_glb.py`, for each product:

1. **Cut-out.** The front photo is separated from its white background using a colour distance to the background. Only darkening and hue count, so bright floor reflections drop out.
   - Lens openings are the enclosed background regions. On each side, the convex hull of the large holes becomes the lens, which also removes temples seen through clear lenses.
   - Half rims are sealed with a larger closing (radius 6, then 10).
   - **Sunglasses** have no holes because the tinted lenses are opaque. Their lens is the filled outline shrunk by a rim width and split at the bridge.
   - **Floor shadow:**
     - Neutral grey pixels at the bottom that are unlike the rim colour are stripped.
     - Under silver or clear rims, the band below each lens is trimmed to the side-rim thickness.
     - Frames still showing a flat shadow tab (a sudden step in the bottom edge) are rejected.
   - Every reject is logged with its reason in `generated/frames3d/rejected.log`.
2. **Front.** The outline minus the lens openings is smoothed (a moving average removes the pixel staircase) and extruded to 4 mm.
   - Units are mm. The origin is the bridge centre. Axes: +X is the wearer's right, +Y is up, +Z points towards the face.
   - The cut-out photo is UV-mapped onto the front and back faces.
   - The 4 mm edges get a solid colour sampled from the rim, because a planar photo would streak there.
3. **Lenses.** Separate thin parts, clear by default. For sunglasses they are tinted with the photo, at about 88% opacity.
4. **Temples.** Separate nodes whose origin is the hinge at the outer edge. Length is 140 mm, with a fixed 28° ear bend over the last 35 mm.
   - Acetate temples are 3 × 4.5 mm bars; metal temples are 2 mm round rods.
   - Their colour comes from the side photo (`_2`).
   - The app swings them about the hinge to the head width.
5. **Contact shadow** (MEGANE idea, simplified). A quad 8 mm behind the front, shifted 2.5 mm down. Its texture is a blurred, 47%-dark copy of the outline, so it lands on the skin under the rims. The viewer hides it.
6. **Scale.** The Kaggle data has no sizes, so the front is set to 135 mm wide, and A, DBL and B are derived from it. Entries say `dimensionsSource: "estimated"`. Real shop frames (photo plus printed size such as 52-18-140) go into the same format as `"measured"`.
7. **Auto-tags** (all estimated):
   - shape: round, oval, rectangle, square, cat-eye, aviator or geometric, from circularity, rectangularity, width at ¼ and ¾ height, and where the top of the lens sits;
   - rim: full or half, from the closing needed;
   - colour: nearest named colour in Lab, with "tortoise" for brown with high spread;
   - material: acetate if the rim is thicker than 2.2 mm, else metal. For sunglasses, aviators are metal and the rest acetate.
8. **Export.** One GLB per frame: JPEG texture, PNG shadow, about 60–110 KB, under 300 KB without Draco or meshopt. Plus a 320 px thumbnail and one catalogue entry. Prices are placeholder ranges by category, marked `priceSource: "demo"`.
9. **Fallback generator.** `parametric_glb()` builds a plain frame from A-DBL-temple alone, for frames without a usable photo.
10. **Order.** Products are ranked by how well the left lens mirrors onto the right (IoU), and the best are generated first. Current run: the best 300 eyeglasses and 50 sunglasses.

`tools/glb_preview.py` renders front, 45° and side views in software, reading the GLB back, so the output can be checked without a GPU.

## 2. Catalogue delivery

- **APK:** `tools/bundle_frames3d.py` copies 30 frames (24 eyeglasses and 6 sunglasses, spread over the shapes) to `app/src/main/assets/frames3d/`, for offline demos.
- **Backend:** `backend/app/catalog.py` serves:
  - `GET /catalog`, with filters `category, shape, rim, colour, material, price=budget|mid|premium, size=small|medium|large`, `sort=quality|price|-price` and paging;
  - `GET /catalog/{id}`;
  - `GET /catalog/{id}.glb`;
  - `GET /catalog/{id}/thumb`.

  The folder is `CATALOG_DIR`, defaulting to `generated/frames3d`. The same `X-Bridge-Token` applies as for the rest of the bridge. This is separate from the Sarvam code.
- **Phone (`CatalogRepository`):** bundled frames, plus server frames when a shop server is set. GLBs and thumbnails download on demand into `cacheDir/frames3d`, managed by `LruIndex` (~100 MB). The last GLBs stay in memory (24 MB), so switching frames is instant once a frame has been seen.

## 3. Catalogue and viewer (app)

- **Catalogue:** grid with search, category, filters (shape, rim, colour, material, price band, size band) and sort.
  - "Recommended for you" puts each catalogue frame through the existing `FrameRanker`, via `Catalog3D.toEngineFrame` and `DrishtiEngine.rankFrames`. It uses face width, PD, budget, style, colour, look and lens thickness from the current result.
  - The other sorts are by price.
- **Viewer:** a Filament perspective view. Drag turns the frame 360°, pinch zooms. It shows sizes with an "estimated" or "measured" label, and material and colour marked "estimated".

## 4. Live 3D try-on (canonical face space)

The method follows MediaPipe's face_effect / face geometry sample. Code: `engine/tryon3d/GlassesFit.kt`, pure Kotlin and unit-tested.

- **Placement, done once per frame model** (`GlassesFit.place`), in MediaPipe's canonical face space (cm; +x is the subject's left, +y up, +z out of the face):
  - The bridge sits `tryOn.bridgeForwardMm` (11 mm) in front of the nasion (landmark 168).
  - The pupils sit at `tryOn.pupilHeightRatio` (55%) of the lens height, measured from the lens bottom; `tryOn.verticalOffsetMm` shifts the whole frame.
  - Each temple swings, tilts and stretches (×0.85–1.3) so its knee reaches the ear point. The ear point is `earBehindNasionMm` (95 mm) behind the nasion and `earBelowPupilMm` (5 mm) below the pupils, at landmark 234 or 454 width.
  - Scale: frame mm → face units × canonical PD / measured PD. Without a measured PD the canonical PD (63.6 mm) is used, and the screen says "approximate".
- **Pose, every frame** (`FacePose.solve`):
  - Rotation comes from the facial transformation matrix (either memory layout, orthonormalised).
  - Translation is solved with the real Camera2 focal length, so the canonical nasion lands exactly on landmark 168 and the canonical pupils span the observed iris distance (a few exact-perspective iterations). This makes placement independent of the field of view MediaPipe assumes internally.
- **Projection:** built from the camera intrinsics (`ViewIntrinsics.projection`), mapping camera space straight onto the FILL_CENTER preview pixels.
- **Mirroring:** the renderer draws the un-mirrored image and the whole view is mirrored like the preview, so glasses turn with the head and triangle winding never flips.
- **Occlusion:** an invisible head (`HeadOccluder`): the canonical face mesh moved rigidly by the pose (pushed back 3 mm), a head ellipsoid and two ear blocks. It writes depth only and is drawn first.
- **Smoothing:** one-euro filters on translation and on the rotation quaternion.
- **Device checks → 3D try-on alignment check:**
  - green dots: detected pupils and nasion;
  - magenta circles: the frame's lens centres and pupil point;
  - yaw, pitch and roll readout.
- **Fallback to 2D** (`Render3DFallback`), with no error dialog, when Filament cannot load, a GLB fails, or the phone draws fewer than 15 fps over 3 s.

## 5. Simplified face avatar (phone only)

- **Consent first:** "A 3D preview of your face is made on this phone only and deleted with the session."
- **Guided capture** (`AvatarCaptureGate`):
  - Steps: FRONT (|yaw| < 5°), TURN LEFT (yaw 30–40°), TURN RIGHT (yaw −30 to −40°), with |pitch| < 12°.
  - Each photo is taken automatically after the pose is held for 0.5 s, with the face fully in the picture and enough light.
  - On screen: a guide arrow, a live yaw bar and a hold ring. The prompt is spoken once per step, and each photo has its own Retake.
- **Photos:** full-colour JPEGs from CameraX ImageCapture (≥1920 px short side, MAXIMIZE_QUALITY). MediaPipe runs again on those same photos (IMAGE mode), so landmarks and pixels match. A photo with almost no colour (chroma < 4) is refused.
- **Landmarks** (`AvatarBuilder.fuse`):
  - Each view's landmarks are brought into canonical face space with that view's pose: pixel rays cut at the posed canonical depth.
  - The views are averaged per vertex, weighted by view (front ×2) and by how squarely each view sees the point.
- **Head shape** (`HeadTemplate`, `HeadDeform`, `AvatarBuilder.buildHead`):
  - **Template:** the MakeHuman base mesh "hm08" (head and neck cut from the body, 4,369 vertices). Source: `makehuman/data/3dobjs/base.obj` in github.com/makehumancommunity/makehuman. **Licence: CC0 1.0 Universal.** The file header says it was "explicitly released as CC0 in september 2020", and the repository's LICENSE.md lists "The base mesh and proxies" among the CC0 assets. The notice ships in `assets/licenses/CC0-MakeHuman-head.txt`. FLAME, DECA and FitMe are not used (licences).
  - **Offline, once** (`tools/head_template.py` → `assets/head_template.json`, 0.3 MB): the template is similarity-aligned to MediaPipe's canonical face (pupils, nose tip), then fitted with an RBF (φ(r) = r plus affine, skull anchors fixed) until the 468 canonical landmarks lie on its surface (mean 0.03 mm, max 1.06 mm). It stores each landmark's template triangle and barycentric weights, a region per vertex (face, ear, scalp, other skin), the ear rest points, the skull anchors and cylindrical UVs (the face gets most of the atlas; the seam is at the back of the head).
  - **On the phone:** a per-axis scale about the face centre (width from the 234–454 face width, height from 10–152, depth from the nose tip to the ears as seen in the side views), then an RBF displacement that puts the template's landmark points on the customer's averaged landmarks. The RBF is pinned to zero at the skull anchors, so skull, ears, back of head and neck stay smooth and proportional. Three corrective passes on the same factorisation bring every landmark within 2 mm (tested). True size still comes from the PD (`GlassesFit` scales the glasses by anchors-PD / measured PD).
- **Texture:** a 2048² sRGB atlas with mipmaps, baked by projective texturing.
  - Every vertex is projected into each photo. On the face, each photo's own landmark positions correct that projection: their residuals ride on the same RBF and fade to zero on the skull, so the face is as sharp as the photo.
  - Texels blend the views by facing^4 (front ×2). A view is skipped beyond 75° on the face and beyond 55° elsewhere, since near the silhouette a photo shows background. Side photos are white-balanced to the front on shared cheek points.
  - Ears and neck come from the photos that see them. The scalp is a hair colour (the median above the forehead in all three photos). Anything no photo sees well is skin (the median on the cheeks). Nothing is grey.
  - If the template cannot be used, the app falls back to the face-only avatar (face mesh, shell and simple ears).
- **Rendering:** PBR-neutral tone mapping, 4× MSAA, a soft key light with a shadow, a fill light, rough (non-shiny) skin. Labelled "Simplified 3D preview, not a scan".
- **Viewer:**
  - The 3D view takes 60% of the height, with labels below it. Drag turns ±90°, pinch zooms.
  - Frame carousel underneath. Glasses use the same `GlassesFit.place` as live try-on, with the avatar's own anchors; on the template head the temple tips rest on its ear roots (`FaceAnchors.earRestR/L`, 1.5 mm clearance).
  - Compare shows two side-by-side views that turn together. Compare / Retake / Delete are icon buttons.
- **Saved frames:** "Save" stores the frame in Room with the session (`saved_frames`, DB v6) and is deleted with it. Only frame facts are stored (id, name, size, price), nothing about the customer. They appear on the Results screen and in both PDFs (shop copy with thumbnails; the Hindi customer copy as name, size and price, with no power numbers), and under Shop tools > Frame catalogue > Saved frames, grouped by session.
- **Privacy:**
  - The 3 photos and their landmarks are stored only in `filesDir/avatar`, AES-GCM encrypted under an Android Keystore key.
  - They are deleted on a new session, on consent, on "Delete 3D face", and at app start.
  - The mesh and texture exist only in memory.
  - A unit test checks that the bridge, PDF and database code never touch the avatar.

## References

| Reference | Used for | Notes |
|---|---|---|
| MediaPipe Face Landmarker (already in the app) | 478 landmarks, facial transformation matrix, canonical face mesh topology | Apache-2.0 |
| MEGANE (Li et al., CVPR 2023) | **Ideas only, no code:** bridge on the nose point, temples bent to the head width, lenses and frame as separate parts, soft contact shadow | All four are simple approximations here |
| BeeAR (github.com/mergeos-bounties/BeeAR) | Catalogue fields (id, name, category, style, price, GLB) and PD-based scale, as reference | MIT; no code copied |
| Kaggle Glasses dataset | Product photos the frames are built from | Licence unknown: **demo use only**, generated files git-ignored |

**Not used, and why:**
- **Ostgot glasses-catalog (Hugging Face):** only 5 GLB models (Arizona brand, made with TRELLIS.2), no sizes, and no licence stated, so they can't be redistributed.
- **FLAME, DECA, FitMe:** research or non-commercial licences, and they need a GPU and setup time. They are *future work* for a full personalised avatar with hair, ears and a true head shape.
- **COLMAP, TRELLIS.2, photogrammetry:** the Kaggle views are 4 uncalibrated photos of thin, shiny frames. Thin metal parts and reflective lenses break feature matching, and 4 views are far too few for accurate geometry. Photo extrusion plus measured sizes is more truthful for fitting.

## Limitations

- **Sizes:** all Kaggle frame sizes are estimated (135 mm front); the true size of each product is unknown. Material and colour tags are guesses and are labelled "estimated". Prices are placeholders.
- **Frame shape:** fronts are flat extrusions, with no wrap or bevel. Temples are straight bars with a fixed bend. Nose pads are not modelled.
- **Cut-outs:** some products are rejected, including clear frames whose lens edge cannot be found, and shadow tabs. Silver rims can still show a faint shadow edge.
- **Avatar:** the head is a generic (MakeHuman) skull bent to the face, not the customer's own skull; hair is a flat colour on the scalp with no volume or hairstyle; the ears are the template's ears, coloured from the side photos, not their real shape. Depth comes from the canonical face, adjusted by the side views. The scale is only as good as the PD.
- **Live try-on:** the occluder is the canonical face moved rigidly (not rebuilt from the live landmarks each frame). The temple bend comes from fixed ear points, not from detecting the real ears.
- **APK:** +8.4 MB over the 2D-only debug build (55.4 → 63.8 MB like for like): Filament and gltfio native code 5.6 MB (arm64-v8a and armeabi-v7a), the 30 bundled frames 2.4 MB, code 0.4 MB.
