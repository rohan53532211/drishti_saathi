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

## 4. Live 3D try-on

- **Placement** (`engine/tryon3d/Fit3D`, pure Kotlin, unit-tested):
  - The bridge's nose rest sits on landmark 6, the nose bridge, 1.5 mm lower.
  - Yaw and pitch come from the facial transformation matrix. Roll comes from the pupils.
  - Scale in view px per mm comes from the PD, corrected for yaw. In order of preference: card-calibrated PD, then face width, then the 63 mm default. The source is shown on screen; the last two are labelled "approximate".
  - The camera is orthographic in view pixels (weak perspective, like the 2D try-on). The model is placed in the un-mirrored image, and the whole TextureView is mirrored to match the front preview, so triangle winding never flips.
- **Temple bend** (MEGANE idea, simplified): splay = atan((head width / 2 − 2 mm − hinge x) / 98 mm), clamped to −6…+18°. Head width is the distance between landmarks 234 and 454. This is a fixed bend from the head width, not a live per-frame bend.
- **Occluder:** an invisible ellipsoid head proxy (writes depth only, drawn first) hides the temples behind the head.
- **Smoothing:** one-euro filters on position, scale, angles and head width. They restart when the face is lost.
- **Fit text:** "Frame 132 mm, face about 136 mm (good width)".
- **Fallback to 2D** (`Render3DFallback`), with no error dialog, when:
  - Filament cannot load;
  - a GLB fails;
  - the phone renders fewer than 15 fps over 3 s, after a 1.5 s grace period.

## 5. Simplified face avatar (phone only)

- **Consent first:** "A 3D preview of your face is made on this phone only and deleted with the session."
- **Capture:** the front view only, with the head within ±10° yaw and ±12° pitch and a spoken prompt. The tracker's `grabFrame()` returns the analysed image and its landmarks from the *same* frame, so the texture lines up.
- **Geometry** (`AvatarMesh`):
  - MediaPipe's 468 points on the canonical face-mesh topology (898 triangles, Apache-2.0, `assets/face_mesh_triangles.json`), scaled to mm with the PD.
  - The origin is the nose bridge, matching the frame anchors.
  - A skin-coloured shell is stitched to the face outline (36 points): a strip to an ellipse, then a half ellipsoid to the back. This lets the avatar turn ±60°.
  - Labelled "Simplified 3D preview, not a scan".
- **Texture:** the front photo, cropped to the face, projected through the landmark UVs. Side-view blending is not done (future work).
- **Rendering:** the avatar is written to an in-memory GLB by `GlbWriter`, so Filament needs no precompiled materials.
- **Viewer:** turn ±60°, pinch zoom, any catalogue frame on it with the same anchors as live try-on, side-by-side compare of two frames, and session favourites.
- **Privacy:**
  - Photo and landmarks are stored only in `filesDir/avatar`, encrypted with AES-GCM under an Android Keystore key.
  - They are deleted on a new session, on consent, on "Delete my 3D face", and at app start.
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
- **Avatar:** front view only. No hair, ears or real back of the head (the shell is an ellipsoid). The scale is only as good as the PD.
- **Live try-on:** weak perspective, an ellipsoid occluder, and the temple bend fixed from the head width rather than live per-frame bending. Not checked on many phones yet.
- **APK:** +8.4 MB over the 2D-only debug build (55.4 → 63.8 MB like for like): Filament and gltfio native code 5.6 MB (arm64-v8a and armeabi-v7a), the 30 bundled frames 2.4 MB, code 0.4 MB.
