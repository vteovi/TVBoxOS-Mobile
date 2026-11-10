"""通过 GitHub Git Data API 推送提交（github.com:443 被阻断时的应急方案）。

用法：
  1. 本地先合并/打 tag，得到要推的提交链；
  2. 改下面的 REPO / *_OLD / *_NEW_TIP / COMMITS（顺序必须是 git rev-list --reverse 的顺序，
     父提交排在前面）；
  3. `python scripts/push_via_api.py`

原理：blob -> tree -> commit 逐个创建，每个提交都校验 tree sha 与本地完全一致，
全部成功后才移动分支 ref，因此中途失败不会破坏远端。

注意：GitHub 会把 author/committer 时间归一化成 UTC，commit sha 必然与本地不同；
推送成功后本地需要 `git fetch origin && git reset --hard origin/<branch>` 对齐，
并 `git fetch --tags --force origin` 更新 tag。首次使用前需确认远端 ref 位置，
可用匿名 API 查：GET https://api.github.com/repos/<owner>/<repo>/git/refs/heads/<branch>

凭据从 `git credential fill` 读取（无需在文件里写 token）。
"""

import base64
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

REPO = "vteovi/TVBoxOS-Mobile"
API = "https://api.github.com/repos/" + REPO
CWD = "D:/WorkBuddy/TVBoxOS-Mobile"

DEV_OLD = "c743c81471600c9a869b83e26c251b41c8d2adb5"
MAIN_OLD = "8049a465c1c023af20bbf18929f92498ecad00b1"
DEV_NEW_TIP = "831adb90e67e68e984965623665363523c99eb72"
MAIN_NEW_TIP = "7b58a21f7564e5b57846ddc4bd4b31f6db060135"

COMMITS = [
    "d4c87df38490af5a20c33e745305c4775f92a4fa",
    "407440b23e81b3d60d663f428a0d5402257a22c8",
    "b7deb54cdd2c905de504776e4a80db4b9aab0c92",
    "e7a2d9c188f67da2c3c776d6fd1487b9579758a3",
    "71fe87ec40740b1ca74ed6438faf11e9faf60d89",
    "831adb90e67e68e984965623665363523c99eb72",
    "7b58a21f7564e5b57846ddc4bd4b31f6db060135",
]


def git(*args, binary=False):
    out = subprocess.run(["git"] + list(args), cwd=CWD, capture_output=True, check=True)
    return out.stdout if binary else out.stdout.decode("utf-8")


def token():
    out = subprocess.run(
        ["git", "credential", "fill"],
        cwd=CWD,
        input=b"protocol=https\nhost=github.com\n\n",
        capture_output=True,
        check=True,
    ).stdout.decode("utf-8", "replace")
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    raise SystemExit("no github password found")


TOKEN = token()


def api(method, path, payload=None, retries=4):
    url = path if path.startswith("http") else API + path
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    for attempt in range(retries):
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", "Bearer " + TOKEN)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("Content-Type", "application/json")
        req.add_header("User-Agent", "tvbox-release-script")
        try:
            with urllib.request.urlopen(req, timeout=90) as resp:
                body = resp.read().decode("utf-8")
                return json.loads(body) if body.strip() else {}
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")
            if exc.code in (502, 503, 504) and attempt + 1 < retries:
                time.sleep(4)
                continue
            raise SystemExit("HTTP %s on %s %s\n%s" % (exc.code, method, url, detail[:900]))
        except Exception as exc:
            if attempt + 1 < retries:
                time.sleep(4)
                continue
            raise SystemExit("%s on %s %s" % (exc, method, url))


def split_z(raw):
    return [p for p in raw.split("\0") if p != ""]


def changes_between(parent, sha):
    raw = git("diff-tree", "-r", "-z", "--no-commit-id", "--name-status", parent, sha)
    items = split_z(raw)
    result = []
    idx = 0
    while idx + 1 < len(items):
        result.append((items[idx][0], items[idx + 1]))
        idx += 2
    return result


def file_mode(sha, path):
    raw = git("ls-tree", "-z", sha, "--", path)
    entry = raw.split("\0")[0]
    return entry.split(" ")[0] if entry else "100644"


MAPPING = {}


def replay(sha):
    tree_sha = git("rev-parse", sha + "^{tree}").strip()
    local_parents = git("log", "-1", "--format=%P", sha).strip().split()
    remote_parents = [MAPPING.get(p, p) for p in local_parents]

    meta = git("log", "-1", "--format=%an%x1f%ae%x1f%aI%x1f%cn%x1f%ce%x1f%cI%x1f%B", sha)
    fields = meta.split("\x1f")
    author = {"name": fields[0], "email": fields[1], "date": fields[2]}
    committer = {"name": fields[3], "email": fields[4], "date": fields[5]}
    message = fields[6].rstrip("\n")

    base = local_parents[0] if local_parents else None
    entries = []
    if base:
        for status, path in changes_between(base, sha):
            if status == "D":
                entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
                continue
            content = git("cat-file", "blob", "%s:%s" % (sha, path), binary=True)
            blob = api("POST", "/git/blobs", {
                "content": base64.b64encode(content).decode("ascii"),
                "encoding": "base64",
            })
            entries.append({
                "path": path,
                "mode": file_mode(sha, path),
                "type": "blob",
                "sha": blob["sha"],
            })

    payload = {"tree": entries}
    if base:
        payload["base_tree"] = git("rev-parse", base + "^{tree}").strip()

    new_tree = api("POST", "/git/trees", payload)
    if new_tree["sha"] != tree_sha:
        raise SystemExit("tree mismatch on %s: %s != %s" % (sha, new_tree["sha"], tree_sha))

    created = api("POST", "/git/commits", {
        "message": message,
        "tree": new_tree["sha"],
        "parents": remote_parents,
        "author": author,
        "committer": committer,
    })
    MAPPING[sha] = created["sha"]
    print("  %s -> %s  (%d files, tree ok)" % (sha[:8], created["sha"][:8], len(entries)), flush=True)
    return created["sha"]


def main():
    print("replaying %d commits" % len(COMMITS), flush=True)
    for sha in COMMITS:
        replay(sha)

    dev_new = MAPPING[DEV_NEW_TIP]
    main_new = MAPPING[MAIN_NEW_TIP]

    print("dev  %s -> %s" % (DEV_OLD[:8], dev_new), flush=True)
    print("main %s -> %s" % (MAIN_OLD[:8], main_new), flush=True)

    api("PATCH", "/git/refs/heads/dev", {"sha": dev_new, "force": False})
    print("dev ref updated", flush=True)
    api("PATCH", "/git/refs/heads/main", {"sha": main_new, "force": False})
    print("main ref updated", flush=True)

    tagger_date = git("for-each-ref", "--format=%(taggerdate:iso-strict)", "refs/tags/v2.32.7").strip()
    tag_obj = api("POST", "/git/tags", {
        "tag": "v2.32.7",
        "message": "v2.32.7 修复收藏页未滑到的分栏不显示条目数量",
        "object": main_new,
        "type": "commit",
        "tagger": {"name": "vteovi", "email": "vteovi@msn.com", "date": tagger_date or "2026-11-10T15:39:57+08:00"},
    })
    api("POST", "/git/refs", {"ref": "refs/tags/v2.32.7", "sha": tag_obj["sha"]})
    print("tag v2.32.7 created -> %s" % tag_obj["sha"], flush=True)
    print("DONE")


if __name__ == "__main__":
    main()
