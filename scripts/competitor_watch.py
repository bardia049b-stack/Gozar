#!/usr/bin/env python3
import json
import urllib.request
from datetime import datetime, timezone

REPOS = ("armanlife/arman-vpn-client",)
FEATURES = (
    "split", "routing", "dns", "udp", "game", "warp", "http/2", "http/3",
    "fragment", "openwrt", "auto-update", "cli", "panel", "multi-account",
)

def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Gozar-competitor-watch"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()

def main():
    report = {"generated_at": datetime.now(timezone.utc).isoformat(), "repositories": []}
    for repo in REPOS:
        meta = json.loads(fetch(f"https://api.github.com/repos/{repo}"))
        readme = fetch(f"https://raw.githubusercontent.com/{repo}/{meta['default_branch']}/README.md").decode("utf-8", "replace").lower()
        report["repositories"].append({
            "repo": repo,
            "sha": meta.get("pushed_at"),
            "stars": meta.get("stargazers_count"),
            "features": [feature for feature in FEATURES if feature in readme],
        })
    print(json.dumps(report, indent=2))

if __name__ == "__main__":
    main()
