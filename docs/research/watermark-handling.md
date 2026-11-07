# Watermarks & handles on collected images

## Problem
Collected memes often carry a watermark or creator handle (Pinterest/Instagram marks, an "@username", a logo, or an
AI-generator tag). The first live batch baked "@theangryop" into a finished sticker. These look unprofessional, can be
someone's brand, and shrink the usable image.

## How it works today
- The worker reads on-image text with OCR (`imaging/ocr.py`) and uses it for tags/captions; `naming.py` filters out
  obvious watermark lines so they don't become the sticker's name.
- There is **no detection of where** a watermark sits and **no removal**. The crop step (`crop` from analysis) can cut
  a corner mark only if the AI's crop box happens to exclude it.
- Result: a watermark that sits over the subject survives into the sticker.

## What research says (open source; links below)
Two stages — **detect the region**, then **inpaint it away**.
- **Detect**
  - *Florence-2* (Microsoft, open) — open-vocabulary detection; "WatermarkRemover-AI" pairs Florence-2 detection with
    LaMa removal end-to-end.
  - *Diffusion-Dynamics/watermark-segmentation* (PyTorch, `segmentation-models-pytorch`) — pixel masks for logo-style
    watermarks; closest fit for a mask.
  - *YOLOv8 watermark detectors* (e.g. HF `nsfw_watermarks_yolov8`) — fast bounding boxes, but Ultralytics' license is
    restrictive; check before shipping.
  - For **text** handles ("@name"), our existing OCR already gives boxes — cheapest detector we already run.
- **Remove (inpaint)**
  - *IOPaint* (Apache-2.0) — self-hosted, bundles **LaMa** and others; has a batch CLI (`iopaint run --model lama
    --image <dir> --mask <dir> --output <dir>`). Best general fit.
  - *LaMa* — resolution-robust large-mask inpainting; the workhorse behind the above.
  - *DeMark* (Python) — template match / alpha-inversion for a *known fixed* logo (e.g. a site's corner mark),
    cheaper and cleaner than ML when the mark is constant.

## Gaps
1. No watermark detection or removal at all.
2. The 10–12 MB download + decode happens with no awareness of marks.
3. Heavy ML (LaMa/Florence) is too big for the 1 GB Oracle VM and slow for the free Groq-only worker box.

## Resolution path (easiest first)
1. **Cheapest, ship first — OCR-box + crop/cover.** We already OCR. If a short text token (an "@handle", a known site
   name, or a lone word in a corner band) is detected near an edge, **crop it out**; if it's over the subject, **cover
   it** with a small matched-colour bar or blur. No new model. Catches most meme handles like "@theangryop".
2. **Known-logo templates (DeMark-style).** Keep a tiny set of frequent source marks (Pinterest, common meme-site
   logos) and remove by template match — fast, deterministic, no ML.
3. **Prefer cleaner sources.** Reddit `i.redd.it` originals and Imgflip are usually mark-free vs Pinterest re-uploads;
   weight collection toward them (ties to `animated-stickers.md` and HANDNAVIGATE_PLAN.md).
4. **Full ML removal, opt-in, offline batch.** Run IOPaint+LaMa (+ Florence-2 detection) as a separate batch tool on a
   bigger machine (not the VM/worker), feeding cleaned images back as sources. Admin button "clean watermark" per image
   using the same tool.
5. **Reject, don't publish, when unsure.** If a watermark overlaps the subject and can't be cleanly removed, hold the
   sticker for review rather than shipping a defaced one.

## Rights caveat (important)
Removing a watermark does **not** grant rights to the image, and stripping a creator's mark can increase legal/ethical
exposure. Keep this to our own/licensed/CC content and reaction memes handled under the takedown flow; never advertise
"we remove watermarks from any image". This aligns with the AGENTS.md rights guardrail. Decision for the owner.

## Sources
- IOPaint (LaMa, batch, Apache-2.0): https://github.com/jnzlab/IOPaint · lama-cleaner: https://www.pypi.org/project/lama-cleaner/0.18.0/
- WatermarkRemover-AI (Florence-2 + LaMa): https://www.scriptbyai.com/watermark-detection-removal
- DeMark (template/alpha/LaMa/SD): https://pypi.org/project/demark/
- watermark-segmentation (masks): https://github.com/Diffusion-Dynamics/watermark-segmentation
- YOLOv8 watermark detector: https://huggingface.co/mnemic/nsfw_watermarks_yolov8
- Watermark Anything (Meta, localized): https://arxiv.org/html/2411.07231v2
