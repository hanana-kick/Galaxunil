"""Read apt-acquired DEP-11 catalog as inert data, never command-line templates."""
import glob
import json
import re
from pathlib import Path
import yaml

PKG = re.compile(r"[a-z0-9][a-z0-9+.-]{1,127}\Z")
DESKTOP = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,159}\.desktop\Z")

def local(record, key):
    value = record.get(key)
    if isinstance(value, dict): value = value.get("C", value.get("en", ""))
    return value if isinstance(value, str) else ""

def records(files):
    seen = set()
    loader = getattr(yaml, "CSafeLoader", yaml.SafeLoader)
    for path in files:
        if path.stat().st_size > 64 * 1024 * 1024:
            raise ValueError("AppStream index exceeds 64 MiB")
        with path.open(encoding="utf-8") as f:
            for d in yaml.load_all(f, Loader=loader):
                if not isinstance(d, dict) or d.get("Type") not in {"desktop-application", "desktop-app"}: continue
                package = d.get("Package")
                if isinstance(package, list): package = next(iter(package), None)
                if not isinstance(package, str) or not PKG.fullmatch(package): continue
                name = local(d, "Name")
                if not name: continue
                launchable = d.get("Launchable")
                desktop = launchable.get("desktop-id", []) if isinstance(launchable, dict) else []
                if not isinstance(desktop, list): desktop = [desktop]
                desktop = [x for x in [*desktop, d.get("ID")] if isinstance(x, str) and DESKTOP.fullmatch(x)]
                if (package, name) in seen: continue
                seen.add((package, name))
                cats = d.get("Categories")
                cats = [s for s in cats if isinstance(s, str)][:16] if isinstance(cats, list) else []
                yield {"package":package, "name":name[:100], "summary":local(d,"Summary")[:250], "desktop":sorted(set(desktop)), "categories":cats}

def main():
    files = sorted(Path(p) for p in glob.glob("/var/lib/apt/lists/*_dists_trixie_main_dep11_Components-*.yml"))
    if not files: raise ValueError("No APT AppStream index. Refresh the signed repository.")
    for n, row in enumerate(records(files)):
        if n >= 6000: raise ValueError("Too many AppStream rows")
        print(json.dumps(row, ensure_ascii=False))

if __name__ == "__main__":
    main()
