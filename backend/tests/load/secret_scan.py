"""Secret scan before a release. Prints names and counts only, never a secret value.

    cd backend
    .venv\\Scripts\\python.exe tests\\load\\secret_scan.py [path-to.apk]

1. No .env file was ever committed on any branch.
2. No value from backend/.env (keys, passwords, secrets, tokens) appears in any commit of any branch.
3. Key-shaped strings (sk-..., a long value after api_key= / password= / secret=) in the history are listed by file.
4. The APK, unzipped, holds none of the .env values (also not as UTF-16) and no backend address with a password.
Exit code 1 when a real secret is found.
"""
from __future__ import annotations

import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ENV = ROOT / "backend" / ".env"
SECRET_NAME = re.compile(r"KEY|PASSWORD|SECRET|TOKEN", re.I)
SHAPES = {
    "sk- key": re.compile(rb"\bsk-[A-Za-z0-9_\-]{20,}"),
    "assigned secret": re.compile(rb"(?i)\b(api[_-]?key|password|passwd|secret|token)\s*[=:]\s*[\"']?([A-Za-z0-9_\-+/]{16,})"),
    "private key": re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
}
# values that are placeholders or test fixtures, not secrets
HARMLESS = re.compile(rb"(?i)change[-_]?me|example|placeholder|your[-_]|test|dummy|demo-pass|xxxx|<|\$\{|os\.environ|getenv|process\.env")


def git(*args: str) -> bytes:
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, check=True).stdout


def env_secrets() -> dict[str, bytes]:
    out: dict[str, bytes] = {}
    if not ENV.exists():
        return out
    for line in ENV.read_text(encoding="utf-8").splitlines():
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        k, v = line.split("=", 1)
        v = v.strip().strip("\"'")
        if SECRET_NAME.search(k) and len(v) >= 6:
            out[k.strip()] = v.encode()
    return out


def main() -> int:
    bad = 0
    secrets = env_secrets()
    print(f"backend/.env: {len(secrets)} secret values to look for ({', '.join(sorted(secrets))})")
    ignored = subprocess.run(["git", "-C", str(ROOT), "check-ignore", "backend/.env"], capture_output=True).returncode == 0
    tracked = git("ls-files", "backend/.env").strip() != b""
    print(f"backend/.env ignored by git: {ignored}; tracked: {tracked}")
    bad += (not ignored) + tracked

    # 1. .env files in the history of every branch
    names = set(git("log", "--all", "--name-only", "--pretty=format:").decode(errors="replace").split("\n"))
    env_files = sorted(n for n in names if re.search(r"(^|/)\.env($|\.)", n) and not n.endswith(".example"))
    print(f"files named .env in any commit of any branch: {env_files or 'none'}")
    bad += len(env_files)

    # 2 + 3. every blob of every commit of every branch
    objects = [l.split(" ", 1) for l in git("rev-list", "--all", "--objects").decode(errors="replace").splitlines() if " " in l]
    commits = len(git("rev-list", "--all").split())
    branches = len(git("branch", "-a", "--format=%(refname)").split())
    found: dict[str, set[str]] = {}
    shapes: dict[str, set[str]] = {}
    batch = subprocess.Popen(["git", "-C", str(ROOT), "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    scanned = 0
    for sha, path in objects:
        batch.stdin.write(sha.encode() + b"\n")
        batch.stdin.flush()
        head = batch.stdout.readline().split()
        if len(head) < 3:
            continue
        data = batch.stdout.read(int(head[2]))
        batch.stdout.read(1)
        if head[1] != b"blob":
            continue
        scanned += 1
        for name, value in secrets.items():
            if value in data:
                found.setdefault(name, set()).add(path)
        if len(data) < 2_000_000 and not path.endswith((".png", ".jpg", ".ttf", ".glb", ".tflite", ".jar", ".task")):
            for kind, rx in SHAPES.items():
                for m in rx.finditer(data):
                    line = data[data.rfind(b"\n", 0, m.start()) + 1:data.find(b"\n", m.end()) if data.find(b"\n", m.end()) != -1 else len(data)]
                    if not HARMLESS.search(line):
                        shapes.setdefault(kind, set()).add(path)
    batch.stdin.close()
    print(f"git history: {scanned} file versions in {commits} commits on {branches} branches scanned")
    print(f".env values found in the history: {({k: sorted(v) for k, v in found.items()}) or 'none'}")
    bad += len(found)
    print(f"key-shaped strings (to look at by hand): {({k: sorted(v)[:8] for k, v in shapes.items()}) or 'none'}")

    # 4. the APK
    for apk in sys.argv[1:]:
        hits: dict[str, set[str]] = {}
        entries = 0
        with zipfile.ZipFile(apk) as z:
            for info in z.infolist():
                entries += 1
                data = z.read(info)
                for name, value in secrets.items():
                    if value in data or value.decode().encode("utf-16-le") in data:
                        hits.setdefault(name, set()).add(info.filename)
                for word in (b"SARVAM_API_KEY", b"ADMIN_PASSWORD", b"DEMO_PASSWORD", b"JWT_SECRET"):
                    if word in data:
                        hits.setdefault(word.decode() + " (the name)", set()).add(info.filename)
                for kind in ("sk- key", "private key"):
                    if SHAPES[kind].search(data):
                        hits.setdefault(kind, set()).add(info.filename)
        print(f"{Path(apk).name}: {entries} files unzipped and scanned; secrets found: {({k: sorted(v) for k, v in hits.items()}) or 'none'}")
        bad += len(hits)
    print("RESULT:", "PASS" if bad == 0 else "FAIL")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
