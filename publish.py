"""Publish Plain apps: bump version, build, GitHub release, rebuild Plain Setup, sync source, push.

    python publish.py music maps        # release these apps (+ a new Plain Setup that bundles them)
    python publish.py all               # every enabled app
    python publish.py all --bump        # force a new version even if the code didn't change
    python publish.py setup             # only rebuild/release Plain Setup
    python publish.py --sync-only       # copy source into src/ and push, no releases
    options: --notes "text"  --no-push  --dry-run (build + copy only; no releases, no git)

Each app's project lives next to this repo (A:/Projects/<project>, see catalog.json).
Releases are tagged <key>-v<versionName> with one asset (<file>.apk). apps.json is the live
catalog Plain Setup reads; Plain Setup is always released last so /releases/latest is Setup.
Signing: every release build signs with A:/Projects/PlainSigning (never commit that folder).
"""
import argparse, datetime, hashlib, json, os, re, shutil, subprocess, sys

REPO_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECTS = os.path.dirname(REPO_DIR)
DIST = os.path.join(REPO_DIR, "dist")
SKIP_DIRS = {"build", ".gradle", ".idea", ".kotlin", "captures", ".cxx", "logs"}
SKIP_FILES = {"local.properties", ".DS_Store"}
SKIP_EXT = {".apk", ".aab", ".keystore", ".jks", ".hprof", ".log"}


def run(cmd, cwd=None, check=True, capture=False):
    print("  $", " ".join(cmd) if isinstance(cmd, list) else cmd, flush=True)
    r = subprocess.run(cmd, cwd=cwd, shell=isinstance(cmd, str), text=True, encoding="utf-8",
                       capture_output=capture)
    if check and r.returncode != 0:
        if capture:
            print(r.stdout, r.stderr)
        sys.exit(f"FAILED ({r.returncode}): {cmd}")
    return r


def load(path, default):
    if not os.path.exists(path):
        return default
    with open(path, encoding="utf-8-sig") as f:
        return json.load(f)


def save(path, data):
    with open(path, "w", encoding="utf-8", newline="\n") as f:  # no BOM: phones parse this
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def read_versions(build_file):
    s = open(build_file, encoding="utf-8").read()
    code = int(re.search(r"versionCode\s*=\s*(\d+)", s).group(1))
    name = re.search(r'versionName\s*=\s*"([^"]+)"', s).group(1)
    min_sdk = int(re.search(r"minSdk\s*=\s*(\d+)", s).group(1))
    return code, name, min_sdk


def write_versions(build_file, code, name):
    s = open(build_file, encoding="utf-8").read()
    s = re.sub(r"versionCode\s*=\s*\d+", f"versionCode = {code}", s, count=1)
    s = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{name}"', s, count=1)
    open(build_file, "w", encoding="utf-8", newline="\n").write(s)


def bump_name(name):
    parts = name.split(".")
    if parts[-1].isdigit():
        parts[-1] = str(int(parts[-1]) + 1)
        return ".".join(parts)
    return name + ".1"


def gradle(project_dir, tasks):
    run([os.path.join(project_dir, "gradlew.bat"), *tasks, "--console=plain", "-q"], cwd=project_dir)


def tag_exists(repo, tag):
    return run(["gh", "release", "view", tag, "--repo", repo], check=False, capture=True).returncode == 0


def build_app(app, live_by_key, force_bump, dry):
    """Bumps (if needed), builds and copies the APK to dist/. Returns the new live entry."""
    proj = os.path.join(PROJECTS, app["project"])
    bf = os.path.join(proj, app["buildFile"])
    code, name, min_sdk = read_versions(bf)
    published = live_by_key.get(app["key"], {}).get("versionCode", 0)
    if code <= published or force_bump:
        new_code = max(code, published) + 1
        new_name = bump_name(name) if (published or force_bump) else name
        print(f"  version {name} ({code}) -> {new_name} ({new_code})")
        if not dry:
            write_versions(bf, new_code, new_name)
        code, name = new_code, new_name
    print(f"== {app['name']} {name} ({code})", flush=True)
    gradle(proj, app["tasks"])
    os.makedirs(DIST, exist_ok=True)
    out = os.path.join(DIST, app["file"])
    shutil.copyfile(os.path.join(proj, app["apk"]), out)
    tag = f"{app['key']}-v{name}"
    return {
        "key": app["key"], "name": app["name"], "package": app["package"],
        "versionCode": code, "versionName": name, "file": app["file"], "tag": tag,
        "url": f"https://github.com/{CAT['repo']}/releases/download/{tag}/{app['file']}",
        "size": os.path.getsize(out), "sha256": sha256(out), "minSdk": min_sdk, "summary": app["summary"], "summary_tr": app.get("summary_tr", ""),
    }


HIST_KEYS = ("versionCode", "versionName", "tag", "url", "size", "sha256", "minSdk")


def with_history(new, old):
    """Carries the previous release into new["history"] (newest first) so Setup can roll back."""
    hist = list((old or {}).get("history", []))
    if old and old.get("versionCode") != new["versionCode"]:
        hist.insert(0, {k: old[k] for k in HIST_KEYS if k in old})
    seen, out = {new["versionCode"]}, []
    for h in hist:
        if h["versionCode"] not in seen:
            seen.add(h["versionCode"]); out.append(h)
    new["history"] = out
    return new


def release(entry, notes, latest, dry):
    if dry:
        print(f"  (dry run) would release {entry['tag']}")
        return
    if tag_exists(CAT["repo"], entry["tag"]):
        sys.exit(f"Release {entry['tag']} already exists. Bump the version (or use --bump).")
    body = notes or f"{entry['name']} {entry['versionName']}"
    run(["gh", "release", "create", entry["tag"], os.path.join(DIST, entry["file"]),
         "--repo", CAT["repo"], "--target", "main",
         "--title", f"{entry['name']} {entry['versionName']}", "--notes", body,
         f"--latest={'true' if latest else 'false'}"])


def ensure_dist(entry):
    """Plain Setup bundles every app's current APK; fetch any that aren't in dist/."""
    path = os.path.join(DIST, entry["file"])
    if os.path.exists(path) and sha256(path) == entry["sha256"]:
        return path
    os.makedirs(DIST, exist_ok=True)
    run(["gh", "release", "download", entry["tag"], "--repo", CAT["repo"], "-p", entry["file"], "-D", DIST, "--clobber"])
    if sha256(path) != entry["sha256"]:
        sys.exit(f"Downloaded {entry['file']} doesn't match apps.json")
    return path


def mirror(src, dst):
    """Copies a project's source into src/<name>, skipping build output, APKs, keys and local files."""
    keep = set()
    for root, dirs, files in os.walk(src):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith(".")]
        rel = os.path.relpath(root, src)
        if rel.replace("\\", "/").endswith("src/main/assets/apks"):
            continue
        for f in files:
            if f in SKIP_FILES or os.path.splitext(f)[1].lower() in SKIP_EXT:
                continue
            if f.startswith(".") and f != ".gitignore":
                continue
            r = os.path.normpath(os.path.join(rel, f))
            keep.add(r)
            d = os.path.join(dst, r)
            os.makedirs(os.path.dirname(d), exist_ok=True)
            shutil.copy2(os.path.join(root, f), d)
    for root, dirs, files in os.walk(dst):  # drop files deleted at the source
        for f in files:
            r = os.path.normpath(os.path.relpath(os.path.join(root, f), dst))
            if r not in keep:
                os.remove(os.path.join(root, f))


def sync_sources(apps):
    done = set()
    for a in apps:
        if a["src"] in done:
            continue
        done.add(a["src"])
        src = os.path.join(PROJECTS, a["project"])
        if os.path.isdir(src):
            mirror(src, os.path.join(REPO_DIR, "src", a["src"]))
    for pkg, name in (("update", "PlainUpdate.kt"), ("lang", "PlainLang.kt")):
        shutil.copyfile(os.path.join(PROJECTS, "PlainSetup", "app", "src", "main", "java", "app", "plain", pkg, name),
                        os.path.join(REPO_DIR, "shared", name))


def git_push(message, no_push):
    run(["git", "add", "-A"], cwd=REPO_DIR)
    if run(["git", "diff", "--cached", "--quiet"], cwd=REPO_DIR, check=False).returncode == 0:
        print("  nothing to commit")
    else:
        run(["git", "commit", "-q", "-m", message], cwd=REPO_DIR)
    if not no_push:
        run(["git", "push", "-q", "origin", "main"], cwd=REPO_DIR)


def main():
    global CAT
    CAT = load(os.path.join(REPO_DIR, "catalog.json"), None)
    ap = argparse.ArgumentParser()
    ap.add_argument("apps", nargs="*")
    ap.add_argument("--notes", default="")
    ap.add_argument("--bump", action="store_true")
    ap.add_argument("--no-push", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--sync-only", action="store_true")
    a = ap.parse_args()

    apps = [x for x in CAT["apps"] if x.get("enabled", True)]
    by_key = {x["key"]: x for x in apps}
    live_path = os.path.join(REPO_DIR, "apps.json")
    live = load(live_path, {"repo": CAT["repo"], "apps": []})
    live_by_key = {x["key"]: x for x in live["apps"]}
    for k, e in live_by_key.items():  # catalog texts win, also for apps not rebuilt this time
        if k in by_key:
            e["summary"] = by_key[k]["summary"]
            e["summary_tr"] = by_key[k].get("summary_tr", "")

    if a.sync_only:
        sync_sources(apps)
        git_push("Sync source", a.no_push)
        return

    wanted = [k for k in by_key if k != "setup"] if "all" in a.apps else [k for k in a.apps if k != "setup"]
    unknown = [k for k in wanted if k not in by_key]
    if unknown:
        sys.exit(f"Unknown or disabled app(s): {unknown}. Known: {list(by_key)}")
    if not wanted and "setup" not in a.apps:
        ap.print_help()
        return
    if not a.dry_run:
        run(["gh", "auth", "status"], capture=True)

    done = []
    for k in wanted:
        e = build_app(by_key[k], live_by_key, a.bump, a.dry_run)
        release(e, a.notes, latest=False, dry=a.dry_run)
        live_by_key[k] = with_history(e, live_by_key.get(k))
        done.append(f"{e['name']} {e['versionName']}")

    # Plain Setup: bundle every current app APK + the catalog, then release it as the latest.
    setup = by_key["setup"]
    assets = os.path.join(PROJECTS, setup["project"], "app", "src", "main", "assets")
    apk_dir = os.path.join(assets, "apks")
    shutil.rmtree(apk_dir, ignore_errors=True)
    os.makedirs(apk_dir)
    bundled = []
    for x in apps:
        e = live_by_key.get(x["key"])
        if x["key"] == "setup" or not e:
            continue
        shutil.copyfile(ensure_dist(e) if not a.dry_run else os.path.join(DIST, e["file"]), os.path.join(apk_dir, e["file"]))
        bundled.append({k: v for k, v in e.items() if k != "history"})  # rollback is online-only
    save(os.path.join(assets, "apps.json"), {"repo": CAT["repo"], "apps": bundled})
    s = build_app(setup, live_by_key, True, a.dry_run)  # a new Setup every time (it carries the APKs)
    release(s, "Install this on a new phone, then pick the Plain apps you want. Bundles: " +
            ", ".join(f"{e['name']} {e['versionName']}" for e in bundled), latest=True, dry=a.dry_run)
    live_by_key["setup"] = with_history(s, live_by_key.get("setup"))
    done.append(f"Plain Setup {s['versionName']}")

    if a.dry_run:
        print("Dry run done:", ", ".join(done))
        return
    order = [x["key"] for x in CAT["apps"]]
    live = {"repo": CAT["repo"], "updated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "apps": sorted(live_by_key.values(), key=lambda e: order.index(e["key"]) if e["key"] in order else 99)}
    save(live_path, live)
    sync_sources(apps)
    git_push("Release: " + ", ".join(done), a.no_push)
    print("Published:", ", ".join(done))


if __name__ == "__main__":
    main()
