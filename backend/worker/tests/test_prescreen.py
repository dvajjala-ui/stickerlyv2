"""Prescreen and intake: fingerprints, quality gates, sha256 dedupe, takedown blocking."""
from __future__ import annotations

from samples import encode, photo

from stickerworker.imaging.metrics import hamming, measure, phash_hex, prescreen_score
from stickerworker.pipeline import prescreen
from stickerworker.pipeline.fingerprints import TakedownIndex, nearest
from stickerworker.pipeline.intake import intake_folder


def test_phash_is_16_hex_and_stable_under_resize():
    image = photo(seed=3)
    a, b = phash_hex(image), phash_hex(image.resize((320, 240)))
    assert len(a) == 16 and int(a, 16) >= 0
    assert hamming(a, b) <= 8
    assert hamming(a, phash_hex(photo(seed=4))) > 8


def test_takedown_index_matches_sha_url_and_near_phash():
    image_hash = phash_hex(photo(seed=5))
    index = TakedownIndex([{"sha256": "A" * 64}, {"source_url": "https://x.example/p/1 "}, {"phash": image_hash},
                           {"phash": "not-a-hash"}])
    assert index.match(sha256="a" * 64) == "takedown:sha256"
    assert index.match(url="https://x.example/p/1") == "takedown:source_url"
    assert index.match(phash=phash_hex(photo(seed=5).resize((400, 300)))) == "takedown:phash"
    assert index.match(phash=phash_hex(photo(seed=6)), sha256="b" * 64) is None


def test_nearest_ignores_self_and_far_hashes():
    known = [("source:me", "ffffffffffffffff"), ("source:far", "0000000000000000"),
             ("sticker:near", "ffffffffffffff00")]
    assert nearest("ffffffffffffffff", known, exclude="source:me") == ("sticker:near", 8)
    assert nearest("0f0f0f0f0f0f0f0f", known) is None


def test_quality_gates():
    good = measure(photo())
    assert prescreen.quality_problem(good, prescreen_score(good)) is None
    assert prescreen.quality_problem({**good, "short_side": 120.0}, 8) == "too_small"
    assert prescreen.quality_problem({**good, "aspect": 4.0}, 8) == "extreme_aspect"
    assert prescreen.quality_problem({**good, "contrast": 2.0}, 8) == "blank"
    assert prescreen.quality_problem(good, 1.0) == "low_quality"
    assert 0 <= prescreen_score(good) <= 10


def test_text_bonus_lifts_captioned_images_up_the_queue():
    assert prescreen.text_bonus(None) == 0.0
    assert prescreen.text_bonus("") == 0.0
    assert prescreen.text_bonus("a b c") == 0.0  # no word >= 2 chars
    one, three = prescreen.text_bonus("ok"), prescreen.text_bonus("kya baat hai")
    assert 0 < one < three <= 2.0
    assert prescreen.text_bonus("when monday hits you after the weekend") == 2.0  # capped


def test_screen_boosts_score_when_the_image_has_text(ctx, store, monkeypatch):
    data = encode(photo(seed=21), "JPEG", quality=90)
    import hashlib
    sha = hashlib.sha256(data).hexdigest()
    path = f"upload/{sha[:2]}/{sha}.jpg"
    store.upload("sources", path, data, "image/jpeg")
    row = store.insert_source({"sha256": sha, "storage_path": path, "mime": "image/jpeg",
                               "bytes": len(data), "site": "upload", "status": "new"})
    from stickerworker.imaging.metrics import measure
    from PIL import Image
    raw = prescreen_score(measure(Image.open(__import__("io").BytesIO(data)).convert("RGB")))
    monkeypatch.setattr(prescreen.ocr, "read_text", lambda _img: "kya baat hai bhai")
    prescreen.run(ctx, 10)
    screened = store.source(row["id"])
    assert screened["status"] == "screened"
    assert screened["ocr_text"] == "kya baat hai bhai"
    assert screened["prescreen_score"] > raw  # the text bonus lifted it above the raw quality score


def test_screen_success_resets_attempts_for_the_next_stage(ctx, store, monkeypatch):
    # P2: screening successfully hands the next stage a fresh attempt budget, even if this row had retries.
    import hashlib
    data = encode(photo(seed=31), "JPEG", quality=90)
    sha = hashlib.sha256(data).hexdigest()
    path = f"upload/{sha[:2]}/{sha}.jpg"
    store.upload("sources", path, data, "image/jpeg")
    row = store.insert_source({"sha256": sha, "storage_path": path, "mime": "image/jpeg", "bytes": len(data),
                               "site": "upload", "status": "new", "attempts": 2})
    monkeypatch.setattr(prescreen.ocr, "read_text", lambda _img: None)  # no tesseract dependency in this test
    prescreen.run(ctx, 10)
    screened = store.source(row["id"])
    assert screened["status"] == "screened"
    assert screened["attempts"] == 0  # claim bumped 2->3, success reset it to 0


def test_intake_dedupes_by_sha_and_blocks_takedowns(store, tmp_path):
    folder = tmp_path / "export"
    (folder / "nested").mkdir(parents=True)
    data = encode(photo(seed=8), "JPEG", quality=90)
    (folder / "a.jpg").write_bytes(data)
    (folder / "nested" / "same_as_a.jpg").write_bytes(data)
    (folder / "fake.png").write_bytes(b"this is not a png at all")
    (folder / ".hidden.jpg").write_bytes(encode(photo(seed=9), "JPEG"))
    blocked = encode(photo(seed=10), "JPEG")
    (folder / "blocked.jpg").write_bytes(blocked)
    import hashlib
    store.add_takedown(reason="test", sha256=hashlib.sha256(blocked).hexdigest())
    first = intake_folder(store, folder, site="Pinterest Board", collector="handnavigate", topic="office humour")
    assert first == {"files": 4, "inserted": 1, "skipped_duplicate": 1, "skipped_unsupported": 1,
                     "skipped_takedown": 1}
    (row,) = store.rows("source_images")
    assert row["site"] == "pinterest-board" and row["topic_hint"] == "office humour"
    assert row["storage_path"].startswith(f"pinterest-board/{row['sha256'][:2]}/") and row["mime"] == "image/jpeg"
    assert store.download("sources", row["storage_path"]) == data
    again = intake_folder(store, folder)
    assert again["skipped_duplicate"] == 2 and "inserted" not in again
