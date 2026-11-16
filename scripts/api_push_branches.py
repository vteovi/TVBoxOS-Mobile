#!/usr/bin/env python3
"""github.com:443 被阻断时，用 GitHub Git Data API 把本地提交"重放"到远端分支。

原理：对每个待推送的本地提交，把它改动过的文件内容上传成 blob、用 base_tree 拼出新的
tree、再以远端父提交为 parent 创建 commit，最后把分支 ref 指过去。

代价：GitHub 会归一化提交时间（转 UTC），因此重放后的 commit sha 与本地不同，
推完必须 `git fetch` + `git reset --hard origin/<branch>` 对齐。

用法：
  python scripts/api_push_branches.py <远端已知的公共父提交sha> <分支名>=<本地tip> [...]

示例：
  python scripts/api_push_branches.py 9352586 master=86b685b dev=86b685b 2.34.2=47fcfef

同一本地 tip 对应多个分支时只重放一次（结果复用），ref 不存在就创建、存在就更新。
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
    """关掉 core.quotepath，避免中文路径被转义成 \\346\\233\\264"""
    return git("-c", "core.quotepath=false", *args)


def load_token():
    out = subprocess.run(
        ["git", "credential", "fill"],
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
    req.add_header("User-Agent", "tvbox-branch-move")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            body = r.read().decode()
            return json.loads(body) if body.strip() else {}
    except urllib.error.HTTPError as e:
        raise SystemExit("HTTP %s %s %s\n%s" % (e.code, method, path, e.read().decode()[:900]))


def remote_blobs(tree_sha):
    """远端已有 blob：path -> sha，用来避免重复上传内容相同的文件"""
    out = {}
    page = api("GET", "/git/trees/%s?recursive=1" % tree_sha)
    for e in page.get("tree", []):
        if e["type"] == "blob":
            out[e["path"]] = e["sha"]
    return out


def parse_changes(commit):
    """git diff-tree -z 的输出：status \\0 path \\0 ..."""
    raw = git("diff-tree", "--no-commit-id", "--name-status", "-r", "-z", commit)
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
    meta, _, _ = line.partition("\t")
    return meta.split()


def replay(base_remote_sha, local_tip):
    remote_parent = base_remote_sha
    remote_tree = api("GET", "/git/commits/" + base_remote_sha)["tree"]["sha"]
    known = remote_blobs(remote_tree)

    commits = [c for c in gitq("rev-list", "--reverse", "%s..%s" % (base_remote_sha, local_tip)).split() if c]
    if not commits:
        print("  (没有需要重放的提交)")
        return remote_parent

    for c in commits:
        subject = gitq("log", "-1", "--format=%s", c).strip()
        entries = []
        for status, path in parse_changes(c):
            if status.startswith("D"):
                entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
                continue
            meta = ls_entry(c, path)
            if not meta or len(meta) < 3 or meta[1] != "blob":
                continue  # 子模块等特殊条目不动
            mode, _, blob = meta
            if known.get(path) != blob:
                content = git("cat-file", "blob", blob, binary=True)
                blob = api("POST", "/git/blobs",
                           {"content": base64.b64encode(content).decode(), "encoding": "base64"})["sha"]
                known[path] = blob
            entries.append({"path": path, "mode": mode, "type": "blob", "sha": blob})

        if entries:
            remote_tree = api("POST", "/git/trees",
                              {"base_tree": remote_tree, "tree": entries})["sha"]

        author_name, author_email = (gitq("log", "-1", "--format=%an%x00%ae", c).strip().split("\x00") + ["", ""])[:2]
        payload = {
            "message": gitq("log", "-1", "--format=%B", c).rstrip("\n") + "\n",
            "tree": remote_tree,
            "parents": [remote_parent],
            "author": {"name": author_name, "email": author_email,
                       "date": gitq("log", "-1", "--format=%aI", c).strip()},
            "committer": {"name": author_name, "email": author_email,
                          "date": gitq("log", "-1", "--format=%cI", c).strip()},
        }
        remote_parent = api("POST", "/git/commits", payload)["sha"]
        print("  %s -> %s  %s" % (c[:8], remote_parent[:8], subject[:60]))

    return remote_parent


def upsert_ref(branch, sha):
    ref = "refs/heads/" + branch
    existing = None
    try:
        # 注意路径是 /git/ref/heads/<branch>，不是 /git/ref/refs/heads/<branch>
        existing = api("GET", "/git/ref/heads/" + branch)["object"]["sha"]
    except SystemExit:
        existing = None
    if existing is None:
        api("POST", "/git/refs", {"ref": ref, "sha": sha})
        print("created %s = %s" % (branch, sha[:8]))
    elif existing == sha:
        print("unchanged %s = %s" % (branch, sha[:8]))
    else:
        api("PATCH", "/git/refs/heads/" + branch, {"sha": sha, "force": False})
        print("updated %s: %s -> %s" % (branch, existing[:8], sha[:8]))


def main():
    global TOKEN
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    TOKEN = load_token()
    base_remote = sys.argv[1]
    specs = [s.split("=", 1) for s in sys.argv[2:]]
    resolved = {}
    for branch, local_tip in specs:
        if local_tip not in resolved:
            print("replay %s (base %s)" % (local_tip[:8], base_remote[:8]))
            resolved[local_tip] = replay(base_remote, local_tip)
        upsert_ref(branch, resolved[local_tip])


if __name__ == "__main__":
    main()
