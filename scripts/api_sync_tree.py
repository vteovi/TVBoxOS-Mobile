#!/usr/bin/env python3
"""把本地某个提交的最终 tree 一次性同步到远端分支（适合 API 重放超时的场景）。

用法：
  python scripts/api_sync_tree.py <base_remote_sha> <local_tip> <parent2_remote_sha|-> <branch>

在 base_remote_sha 的 tree 上，仅覆盖 local_tip 相对 base 有差异的文件，
创建一个新提交（父 = [base_remote_sha, parent2_remote_sha]，第二个为 '-' 时省略），
再把 branch ref 指过去。这样只上传少量变动文件，不受历史提交数量影响。
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


def api(method, path, payload=None, retries=3):
    import time
    data = None if payload is None else json.dumps(payload).encode()
    last = None
    for attempt in range(retries):
        req = urllib.request.Request(API + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + TOKEN)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("Content-Type", "application/json")
        req.add_header("User-Agent", "tvbox-sync-tree")
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                body = r.read().decode()
                return json.loads(body) if body.strip() else {}
        except (urllib.error.URLError, OSError, urllib.error.HTTPError) as e:
            last = e
            if attempt < retries - 1:
                time.sleep(2 * (attempt + 1))
                continue
    raise SystemExit("API %s %s failed: %s" % (method, path, last))


def parse_changes(base, tip):
    raw = git("diff-tree", "--no-commit-id", "--name-status", "-r", "-z", base, tip)
    parts = raw.split("\0")
    out = []
    i = 0
    while i + 1 < len(parts):
        status, path = parts[i], parts[i + 1]
        if status:
            out.append((status, path))
        i += 2
    return out


def ls_entry(commit, path):
    line = gitq("ls-tree", commit, "--", path).strip()
    if not line:
        return None
    meta = line.partition("\t")[0]
    return meta.split()


def main():
    global TOKEN
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    TOKEN = load_token()
    base, tip, parent2, branch = sys.argv[1:5]

    base_commit = api("GET", "/git/commits/" + base)
    remote_tree = base_commit["tree"]["sha"]

    entries = []
    for status, path in parse_changes(base, tip):
        if status.startswith("D"):
            entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
            continue
        meta = ls_entry(tip, path)
        if not meta or len(meta) < 3 or meta[1] != "blob":
            continue
        mode, _, blob = meta
        content = git("cat-file", "blob", blob, binary=True)
        new_blob = api("POST", "/git/blobs",
                       {"content": base64.b64encode(content).decode(), "encoding": "base64"})["sha"]
        entries.append({"path": path, "mode": mode, "type": "blob", "sha": new_blob})
        print("  blob %s -> %s  %s" % (blob[:8], new_blob[:8], path[:50]))

    print("changed entries:", len(entries))
    if entries:
        remote_tree = api("POST", "/git/trees", {"base_tree": remote_tree, "tree": entries})["sha"]
    print("new tree:", remote_tree)

    parents = [base]
    if parent2 != "-":
        parents.append(parent2)
    author_name, author_email = (gitq("log", "-1", "--format=%an%x00%ae", tip).strip().split("\x00"))[:2]
    payload = {
        "message": gitq("log", "-1", "--format=%B", tip).rstrip("\n") + "\n",
        "tree": remote_tree,
        "parents": parents,
        "author": {"name": author_name, "email": author_email,
                   "date": gitq("log", "-1", "--format=%aI", tip).strip()},
        "committer": {"name": author_name, "email": author_email,
                      "date": gitq("log", "-1", "--format=%cI", tip).strip()},
    }
    sha = api("POST", "/git/commits", payload)["sha"]
    print("new commit:", sha)
    api("PATCH", "/git/refs/heads/" + branch, {"sha": sha, "force": False})
    print("updated", branch, "=", sha)


if __name__ == "__main__":
    main()
