#!/usr/bin/env python3
"""把本地 merge 提交按「原父提交列表」重放到远端（github.com 被阻断时用）。

与 api_push_branches.py 的区别：
  * 支持多父提交（merge commit），父提交按本地顺序逐个映射到远端 sha
  * 变更集用 `git diff-tree <base> <tip>` 直接算，不依赖逐提交的 diff-tree（merge 提交默认无输出）

用法：
  python scripts/push_merge_via_api.py <base_remote_sha> <merge_local_sha> <parent2_remote_sha> <branch>
"""
import base64
import json
import subprocess
import sys
import urllib.error
import urllib.request

REPO = "vteovi/TVBoxOS-Mobile"
API = "https://api.github.com/repos/" + REPO
TOKEN = None


def git(*args, binary=False):
    r = subprocess.run(["git"] + list(args), capture_output=True)
    if r.returncode != 0:
        raise SystemExit("git %s failed: %s" % (" ".join(args), r.stderr.decode("utf-8", "replace")[:500]))
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def gitq(*args):
    return git("-c", "core.quotepath=false", *args)


def load_token():
    out = subprocess.run(["git", "credential", "fill"],
                         input=b"protocol=https\nhost=github.com\n\n",
                         capture_output=True, check=True).stdout.decode()
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    raise SystemExit("git credential 没拿到 token")


def api(method, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Content-Type", "application/json")
    req.add_header("User-Agent", "tvbox-merge-push")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            body = r.read().decode()
            return json.loads(body) if body.strip() else {}
    except urllib.error.HTTPError as e:
        raise SystemExit("HTTP %s %s %s\n%s" % (e.code, method, path, e.read().decode()[:900]))


def remote_blobs(tree_sha):
    out = {}
    page = api("GET", "/git/trees/%s?recursive=1" % tree_sha)
    for e in page.get("tree", []):
        if e["type"] == "blob":
            out[e["path"]] = e["sha"]
    return out


def main():
    global TOKEN
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    TOKEN = load_token()
    base, tip, parent2, branch = sys.argv[1:5]

    remote_tree = api("GET", "/git/commits/" + base)["tree"]["sha"]
    known = remote_blobs(remote_tree)

    raw = git("diff-tree", "--no-commit-id", "--name-status", "-r", "-z", base, tip)
    parts = raw.split("\0")
    entries = []
    i = 0
    while i + 1 < len(parts):
        status, path = parts[i], parts[i + 1]
        i += 2
        if not status:
            continue
        if status.startswith("D"):
            entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
            continue
        line = gitq("ls-tree", tip, "--", path).strip()
        if not line:
            continue
        meta, _, _ = line.partition("\t")
        fields = meta.split()
        if len(fields) < 3 or fields[1] != "blob":
            continue
        mode, _, blob = fields
        if known.get(path) != blob:
            content = git("cat-file", "blob", blob, binary=True)
            blob = api("POST", "/git/blobs",
                       {"content": base64.b64encode(content).decode(), "encoding": "base64"})["sha"]
            known[path] = blob
        entries.append({"path": path, "mode": mode, "type": "blob", "sha": blob})
    print("changed entries:", len(entries))

    if entries:
        remote_tree = api("POST", "/git/trees", {"base_tree": remote_tree, "tree": entries})["sha"]

    author_name, author_email = (gitq("log", "-1", "--format=%an%x00%ae", tip).strip().split("\x00"))[:2]
    payload = {
        "message": gitq("log", "-1", "--format=%B", tip).rstrip("\n") + "\n",
        "tree": remote_tree,
        "parents": [base, parent2],
        "author": {"name": author_name, "email": author_email,
                   "date": gitq("log", "-1", "--format=%aI", tip).strip()},
        "committer": {"name": author_name, "email": author_email,
                      "date": gitq("log", "-1", "--format=%cI", tip).strip()},
    }
    sha = api("POST", "/git/commits", payload)["sha"]
    print("replayed ->", sha, "local:", tip, "MATCH" if sha == tip else "DIFF")
    api("PATCH", "/git/refs/heads/" + branch, {"sha": sha, "force": False})
    print("updated", branch, "=", sha)


if __name__ == "__main__":
    main()
