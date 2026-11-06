"""Command line: `python -m stickerworker run|intake|pending`."""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

from .ai import AI, Budget, FakeAI, make_groq
from .config import Env
from .pipeline.context import Context
from .pipeline.intake import intake_folder
from .pipeline.report import write_local_report
from .pipeline.runner import STAGES, run_stages
from .settings import parse_settings
from .store import DryRunStore, LocalStore, Store, StoreError, SupabaseStore
from .timeutil import ist_day_start, utcnow


def _utf8_stdout() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure:
            reconfigure(encoding="utf-8", errors="replace")


def load_dotenv(path: Path | None = None, environ: dict[str, str] | None = None) -> int:
    """Fills unset variables from the repository's .env (KEY=VALUE lines); real environment variables win."""
    target = os.environ if environ is None else environ
    file = path or Path(__file__).resolve().parents[3] / ".env"
    if not file.is_file():
        return 0
    loaded = 0
    for line in file.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.split(" #", 1)[0].strip().strip('"').strip("'")
        if key and value and key not in target:
            target[key] = value
            loaded += 1
    return loaded


def _print(data: Any) -> None:
    print(json.dumps(data, indent=2, ensure_ascii=False, default=str))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="stickerworker", description="StickerDock v2 AI sticker factory worker")
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run", help="run pipeline stages")
    run.add_argument("--stage", choices=("all", *STAGES), default="all")
    run.add_argument("--limit", type=int, help="max sources per queue stage (default per stage)")
    run.add_argument("--dry-run", action="store_true", help="read only: record planned writes, use FakeAI")
    run.add_argument("--local", type=Path, metavar="DIR", help="offline run on a folder (SQLite + files in --out)")
    run.add_argument("--out", type=Path, help="output folder for --local and --dry-run (default backend/worker/out)")
    intake = sub.add_parser("intake", help="push a local folder into Supabase source_images")
    intake.add_argument("folder", type=Path)
    intake.add_argument("--site", default="upload", help="source site label, e.g. upload, pinterest, reddit")
    intake.add_argument("--collector", default="handnavigate")
    intake.add_argument("--topic", help="topic hint for every file (else sidecar topic_hint or file name)")
    intake.add_argument("--dry-run", action="store_true")
    pending = sub.add_parser("pending", help="print queue counts (for CI gating)")
    pending.add_argument("--github-output", action="store_true", help="also write work=/media= to $GITHUB_OUTPUT")
    migrate = sub.add_parser("migrate", help="apply backend/supabase/migrations to SUPABASE_DB_* (each file once)")
    migrate.add_argument("--dry-run", action="store_true", help="list the files that would be applied")
    return parser


def _supabase(env: Env) -> SupabaseStore | None:
    if not env.has_supabase:
        print("SUPABASE_URL and SUPABASE_SECRET_KEY are required (in .env or the environment), or use --local DIR.", file=sys.stderr)
        return None
    return SupabaseStore(env.supabase_url, env.supabase_key)


def _ai(env: Env, store: Store, settings_rows: dict[str, Any], *, local: bool, dry_run: bool) -> AI | None:
    if dry_run:
        return FakeAI()  # a dry run never spends AI budget
    if env.has_groq:
        settings = parse_settings(settings_rows)
        budget = Budget({"vision": settings.budgets.vision_per_day, "text": settings.budgets.text_per_day},
                        store.ai_usage_since(ist_day_start(utcnow())))
        return make_groq(env, budget, store.log_ai_call)
    if local:
        return FakeAI(store.log_ai_call)
    print("notice: GROQ_API_KEYS not set; analyze is skipped and captions fall back to stored ideas.",
          file=sys.stderr)
    return None


def cmd_run(args: argparse.Namespace, env: Env) -> int:
    out = Path(args.out or env.out_dir)
    base: Store | None
    if args.local:
        if not args.local.is_dir():
            print(f"--local: not a folder: {args.local}", file=sys.stderr)
            return 2
        base = LocalStore(out)
    else:
        base = _supabase(env)
        if base is None:
            return 2
    rows = base.load_settings()
    store: Store = DryRunStore(base, out / "dry-run") if args.dry_run else base
    ctx = Context(store=store, settings=parse_settings(rows), env=env,
                  ai=_ai(env, base, rows, local=bool(args.local), dry_run=args.dry_run), dry_run=args.dry_run)
    report: dict[str, Any] = {"store": store.kind, "ai": getattr(ctx.ai, "provider", None)}
    if args.local:
        report["intake"] = intake_folder(ctx.store, args.local, site="local", collector="local",
                                         dry_run=args.dry_run)
    ok, results = run_stages(ctx, args.stage, args.limit, offline=bool(args.local), local=bool(args.local))
    report.update(results)
    if isinstance(store, DryRunStore):
        report["planned_writes"] = store.summary()
    if isinstance(base, LocalStore) and not args.dry_run:
        report["local"] = write_local_report(base, out)
    _print(report)
    return 0 if ok else 1


def cmd_intake(args: argparse.Namespace, env: Env) -> int:
    store = _supabase(env)
    if store is None:
        return 2
    _print(intake_folder(store, args.folder, site=args.site, collector=args.collector, topic=args.topic,
                         dry_run=args.dry_run))
    return 0


def cmd_pending(args: argparse.Namespace, env: Env) -> int:
    counts = {"new": 0, "screened": 0, "analyzed": 0}
    if env.has_supabase:
        store = SupabaseStore(env.supabase_url, env.supabase_key)
        counts = store.pending_counts()
    work = any(counts.values())
    media = counts.get("new", 0) > 0 or counts.get("analyzed", 0) > 0
    _print({**counts, "work": work, "media": media})
    target = os.environ.get("GITHUB_OUTPUT")
    if args.github_output and target:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write(f"work={str(work).lower()}\nmedia={str(media).lower()}\n")
    return 0


def main(argv: list[str] | None = None) -> int:
    _utf8_stdout()
    args = build_parser().parse_args(argv)
    if not os.environ.get("STICKERWORKER_NO_DOTENV"):
        load_dotenv()
    env = Env.from_environ()
    try:
        if args.command == "run":
            return cmd_run(args, env)
        if args.command == "intake":
            return cmd_intake(args, env)
        if args.command == "migrate":
            from .migrate import apply
            done = apply(dry_run=args.dry_run)
            _print({"applied" if not args.dry_run else "would_apply": done})
            return 0
        return cmd_pending(args, env)
    except (StoreError, FileNotFoundError, RuntimeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
