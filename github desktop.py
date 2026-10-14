# -*- coding: utf-8 -*-
"""迷你 Git 桌面版 —— 单文件全中文界面，带设置与配置文件。

依赖：pip install PySide6
运行：python git_desktop.py
      也可以带路径启动：python git_desktop.py /path/to/repo

配置文件：与脚本同目录下的 git_desktop_config.json
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from dataclasses import dataclass

from PySide6.QtCore import Qt
from PySide6.QtGui import QAction, QColor, QSyntaxHighlighter, QTextCharFormat
from PySide6.QtWidgets import (
    QApplication, QComboBox, QDialog, QDialogButtonBox, QFileDialog,
    QFormLayout, QGroupBox, QHBoxLayout, QInputDialog, QLabel, QLineEdit,
    QListWidget, QListWidgetItem, QMainWindow, QMessageBox, QPlainTextEdit,
    QPushButton, QSplitter, QTabWidget, QTextEdit, QVBoxLayout, QWidget,
)


# ==========================================================================
# 零、配置文件读写
# ==========================================================================

CONFIG_FILENAME = "git_desktop_config.json"


def config_path() -> str:
    """配置文件路径：与脚本同目录。"""
    return os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        CONFIG_FILENAME)


def load_config() -> dict:
    """读取配置文件；不存在或损坏时返回空字典。"""
    try:
        with open(config_path(), "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def save_config(cfg: dict) -> None:
    """写入配置文件（失败时静默，仅打印到终端）。"""
    try:
        with open(config_path(), "w", encoding="utf-8") as fh:
            json.dump(cfg, fh, ensure_ascii=False, indent=2)
    except OSError as exc:
        print(f"[警告] 无法写入配置文件：{exc}", file=sys.stderr)


# ==========================================================================
# 一、Git 操作层
# ==========================================================================

DEFAULT_TIMEOUT = 300
NETWORK_TIMEOUT = 1800
MAX_DIFF_CHARS = 400_000


class GitError(Exception):
    """git 命令执行失败。"""


def _make_env() -> dict:
    env = os.environ.copy()
    env["GIT_TERMINAL_PROMPT"] = "0"
    env["GIT_PAGER"] = "cat"
    return env


def run_git(repo: str | None, *args: str, check: bool = True,
            timeout: int = DEFAULT_TIMEOUT, input_text: str | None = None) -> str:
    """在 repo 目录下执行 git 命令，返回 stdout。"""
    cmd = ["git", "-c", "core.quotepath=false"]
    if repo:
        cmd += ["-C", repo]
    cmd += list(args)

    kwargs = {}
    if os.name == "nt":
        kwargs["creationflags"] = 0x08000000  # CREATE_NO_WINDOW

    try:
        proc = subprocess.run(
            cmd,
            input=input_text,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            env=_make_env(),
            **kwargs,
        )
    except FileNotFoundError as exc:
        raise GitError("找不到 git 命令，请先安装 Git 并确保它在 PATH 中。") from exc
    except subprocess.TimeoutExpired as exc:
        raise GitError("git 命令执行超时。") from exc

    if check and proc.returncode != 0:
        msg = (proc.stderr or proc.stdout or "").strip()
        raise GitError(msg or f"git 退出码 {proc.returncode}")

    return proc.stdout


# --------------------------------------------------------------------------
# 数据结构
# --------------------------------------------------------------------------

@dataclass
class FileChange:
    path: str
    x: str = " "
    y: str = " "
    orig_path: str = ""

    @property
    def untracked(self) -> bool:
        return self.x == "?" and self.y == "?"

    @property
    def conflicted(self) -> bool:
        return ("U" in (self.x, self.y)
                or (self.x == "A" and self.y == "A")
                or (self.x == "D" and self.y == "D"))

    @property
    def staged(self) -> bool:
        return not self.untracked and self.x not in (" ", "?")

    @property
    def unstaged(self) -> bool:
        return self.y not in (" ", "?")


@dataclass
class Commit:
    sha: str
    short: str
    author: str
    email: str
    date: str
    subject: str
    body: str = ""


# --------------------------------------------------------------------------
# 查询
# --------------------------------------------------------------------------

def is_repo(path: str) -> bool:
    try:
        return run_git(path, "rev-parse", "--is-inside-work-tree").strip() == "true"
    except GitError:
        return False


def repo_root(path: str) -> str:
    return run_git(path, "rev-parse", "--show-toplevel").strip()


def current_branch(repo: str) -> str | None:
    try:
        name = run_git(repo, "rev-parse", "--abbrev-ref", "HEAD").strip()
    except GitError:
        return None
    return None if name == "HEAD" else name


def list_branches(repo: str) -> list[str]:
    try:
        out = run_git(repo, "branch", "--format=%(refname:short)")
    except GitError:
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


def get_remotes(repo: str) -> list[str]:
    try:
        out = run_git(repo, "remote")
    except GitError:
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


def get_remote_url(repo: str, name: str = "origin") -> str:
    """读取远程地址；不存在时返回空字符串。"""
    try:
        return run_git(repo, "remote", "get-url", name).strip()
    except GitError:
        return ""


def set_remote_url(repo: str, url: str, name: str = "origin") -> None:
    """设置远程地址。远程不存在则新建，已存在则改地址。"""
    if name in get_remotes(repo):
        run_git(repo, "remote", "set-url", name, url)
    else:
        run_git(repo, "remote", "add", name, url)


def remove_remote(repo: str, name: str) -> None:
    run_git(repo, "remote", "remove", name)


def get_upstream(repo: str) -> str | None:
    try:
        name = run_git(repo, "rev-parse", "--abbrev-ref",
                       "--symbolic-full-name", "@{u}").strip()
    except GitError:
        return None
    return name or None


def ahead_behind(repo: str) -> tuple[int, int]:
    try:
        out = run_git(repo, "rev-list", "--left-right", "--count", "HEAD...@{u}")
        ahead, behind = out.split()
        return int(ahead), int(behind)
    except (GitError, ValueError):
        return 0, 0


def get_status(repo: str) -> list[FileChange]:
    out = run_git(repo, "status", "--porcelain=v1", "-z", "--untracked-files=all")
    parts = out.split("\0")
    changes: list[FileChange] = []
    i = 0
    while i < len(parts):
        entry = parts[i]
        i += 1
        if not entry:
            continue
        x, y = entry[0], entry[1]
        path = entry[3:]
        orig = ""
        if x in ("R", "C") or y in ("R", "C"):
            if i < len(parts):
                orig = parts[i]
                i += 1
        changes.append(FileChange(path=path, x=x, y=y, orig_path=orig))
    return changes


_LOG_FORMAT = "%H%x1f%h%x1f%an%x1f%ae%x1f%ad%x1f%s%x1f%b%x1e"


def get_history(repo: str, limit: int = 200) -> list[Commit]:
    try:
        out = run_git(repo, "log",
                      f"--pretty=format:{_LOG_FORMAT}",
                      f"--max-count={limit}",
                      "--date=format:%Y-%m-%d %H:%M")
    except GitError:
        return []

    commits: list[Commit] = []
    for record in out.split("\x1e"):
        record = record.strip("\n")
        if not record.strip():
            continue
        fields = record.split("\x1f")
        if len(fields) < 7:
            continue
        commits.append(Commit(
            sha=fields[0], short=fields[1], author=fields[2],
            email=fields[3], date=fields[4], subject=fields[5],
            body=fields[6].strip(),
        ))
    return commits


# --------------------------------------------------------------------------
# 差异
# --------------------------------------------------------------------------

def _truncate(text: str, limit: int = MAX_DIFF_CHARS) -> str:
    if len(text) <= limit:
        return text
    return text[:limit] + "\n\n…（内容过大，已截断）"


def _untracked_preview(repo: str, path: str) -> str:
    full = os.path.join(repo, path)
    try:
        if os.path.getsize(full) > 1_000_000:
            return f"（文件过大，暂不预览：{path}）"
        with open(full, "r", encoding="utf-8", errors="replace") as fh:
            content = fh.read()
    except OSError as exc:
        return f"（无法读取文件：{exc}）"

    header = (f"diff --git a/{path} b/{path}\n"
              f"new file mode 100644\n"
              f"--- /dev/null\n"
              f"+++ b/{path}\n")
    body = "\n".join("+" + line for line in content.splitlines())
    return _truncate(header + body)


def get_diff(repo: str, change: FileChange) -> str:
    if change.untracked:
        return _untracked_preview(repo, change.path)

    chunks: list[str] = []
    if change.conflicted:
        chunks.append(run_git(repo, "diff", "--no-color", "--", change.path))
    else:
        if change.staged:
            chunks.append(run_git(repo, "diff", "--cached", "--no-color",
                                  "--", change.path))
        if change.unstaged:
            chunks.append(run_git(repo, "diff", "--no-color", "--", change.path))

    text = "\n".join(c for c in chunks if c.strip())
    return _truncate(text) if text.strip() else "（没有可显示的差异）"


def get_commit_diff(repo: str, sha: str) -> str:
    out = run_git(repo, "show", "--no-color", "--stat", "--patch", sha)
    return _truncate(out)


# --------------------------------------------------------------------------
# 写操作
# --------------------------------------------------------------------------

def set_staged(repo: str, path: str, staged: bool) -> None:
    if staged:
        run_git(repo, "add", "--", path)
    else:
        try:
            run_git(repo, "restore", "--staged", "--", path)
        except GitError:
            run_git(repo, "rm", "--cached", "-r", "--", path)


def stage_all(repo: str) -> None:
    run_git(repo, "add", "-A")


def unstage_all(repo: str) -> None:
    try:
        run_git(repo, "restore", "--staged", ".")
    except GitError:
        run_git(repo, "rm", "--cached", "-r", "--", ".")


def commit(repo: str, summary: str, description: str = "") -> str:
    message = summary.strip()
    if description.strip():
        message += "\n\n" + description.strip()
    return run_git(repo, "commit", "-F", "-", input_text=message)


def fetch(repo: str) -> str:
    return run_git(repo, "fetch", "--all", "--prune", timeout=NETWORK_TIMEOUT)


def pull(repo: str) -> str:
    return run_git(repo, "pull", timeout=NETWORK_TIMEOUT)


def push(repo: str, branch: str | None = None, publish: bool = False) -> str:
    if publish and branch:
        remotes = get_remotes(repo)
        remote = "origin" if "origin" in remotes else (remotes[0] if remotes else "origin")
        return run_git(repo, "push", "--set-upstream", remote, branch,
                       timeout=NETWORK_TIMEOUT)
    return run_git(repo, "push", timeout=NETWORK_TIMEOUT)


def clone(url: str, dest: str) -> str:
    run_git(None, "clone", url, dest, timeout=NETWORK_TIMEOUT)
    return dest


def checkout(repo: str, branch: str) -> str:
    return run_git(repo, "checkout", branch)


def create_branch(repo: str, name: str, checkout_it: bool = True) -> str:
    if checkout_it:
        return run_git(repo, "checkout", "-b", name)
    return run_git(repo, "branch", name)


# ==========================================================================
# 二、界面层
# ==========================================================================

class DiffHighlighter(QSyntaxHighlighter):
    """给差异文本上色：新增行绿色，删除行红色，区块头蓝色。"""

    def __init__(self, document):
        super().__init__(document)
        self.fmt_add = QTextCharFormat()
        self.fmt_add.setForeground(QColor("#1a7f37"))
        self.fmt_del = QTextCharFormat()
        self.fmt_del.setForeground(QColor("#cf222e"))
        self.fmt_hunk = QTextCharFormat()
        self.fmt_hunk.setForeground(QColor("#0969da"))
        self.fmt_meta = QTextCharFormat()
        self.fmt_meta.setForeground(QColor("#6e7781"))

    def highlightBlock(self, text: str) -> None:
        if text.startswith(("+++", "---", "diff ", "index ", "new file",
                            "deleted file", "similarity", "rename ")):
            self.setFormat(0, len(text), self.fmt_meta)
        elif text.startswith("@@"):
            self.setFormat(0, len(text), self.fmt_hunk)
        elif text.startswith("+"):
            self.setFormat(0, len(text), self.fmt_add)
        elif text.startswith("-"):
            self.setFormat(0, len(text), self.fmt_del)


STATUS_LABEL = {"M": "修改", "A": "新增", "D": "删除",
                "R": "重命名", "C": "复制", "?": "未跟踪", "U": "冲突"}


# --------------------------------------------------------------------------
# 设置对话框
# --------------------------------------------------------------------------

class SettingsDialog(QDialog):
    """集中管理远程地址与应用设置。"""

    def __init__(self, parent, repo: str | None, config: dict):
        super().__init__(parent)
        self.setWindowTitle("设置")
        self.setMinimumWidth(560)
        self.repo = repo
        self.config = config

        layout = QVBoxLayout(self)

        # ---- 远程地址 ----
        remote_group = QGroupBox("当前仓库的远程地址")
        remote_form = QFormLayout(remote_group)

        self.remote_name_edit = QLineEdit("origin")
        remote_form.addRow("远程名称：", self.remote_name_edit)

        self.remote_url_edit = QLineEdit()
        self.remote_url_edit.setPlaceholderText(
            "例如 https://github.com/用户名/仓库.git")
        remote_form.addRow("远程地址：", self.remote_url_edit)

        if repo:
            self.remote_url_edit.setText(get_remote_url(repo, "origin"))
            hint = QLabel("留空表示不修改；地址会同时写入仓库的 .git/config。")
        else:
            self.remote_name_edit.setEnabled(False)
            self.remote_url_edit.setEnabled(False)
            hint = QLabel("（尚未打开仓库，请先打开一个仓库再设置远程地址）")
        hint.setStyleSheet("color: #888;")
        hint.setWordWrap(True)
        remote_form.addRow("", hint)

        layout.addWidget(remote_group)

        # ---- 应用设置 ----
        app_group = QGroupBox("应用设置")
        app_form = QFormLayout(app_group)
        self.clone_dir_edit = QLineEdit(config.get("clone_dir", ""))
        self.clone_dir_edit.setPlaceholderText("克隆仓库时的默认父目录（可留空）")
        app_form.addRow("默认克隆目录：", self.clone_dir_edit)
        layout.addWidget(app_group)

        # ---- 配置文件位置 ----
        path_label = QLabel(f"配置文件：{config_path()}")
        path_label.setStyleSheet("color: #888; font-size: 11px;")
        path_label.setWordWrap(True)
        layout.addWidget(path_label)

        # ---- 按钮 ----
        buttons = QDialogButtonBox(
            QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("保存")
        buttons.button(QDialogButtonBox.Cancel).setText("取消")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)


# --------------------------------------------------------------------------
# 主窗口
# --------------------------------------------------------------------------

class MainWindow(QMainWindow):
    def __init__(self, config: dict):
        super().__init__()
        self.config = config
        self.setWindowTitle("迷你 Git 桌面版")

        win_cfg = config.get("window", {})
        self.resize(int(win_cfg.get("width", 1100)),
                    int(win_cfg.get("height", 700)))

        self.repo: str | None = None
        self.changes: list[FileChange] = []
        self._loading = False

        self._build_toolbar()
        self._build_ui()
        self.statusBar().showMessage("请先打开或克隆一个仓库")

    # ---------------- 界面搭建 ----------------

    def _build_toolbar(self) -> None:
        bar = self.addToolBar("主工具栏")
        bar.setMovable(False)

        for text, slot in (("打开仓库", self.open_repo_dialog),
                           ("克隆仓库", self.clone_dialog),
                           ("设置", self.open_settings)):
            act = QAction(text, self)
            act.triggered.connect(slot)
            bar.addAction(act)

        bar.addSeparator()

        self.branch_combo = QComboBox()
        self.branch_combo.setMinimumWidth(160)
        self.branch_combo.currentTextChanged.connect(self.on_branch_changed)
        bar.addWidget(self.branch_combo)

        act_new_branch = QAction("新建分支", self)
        act_new_branch.triggered.connect(self.new_branch_dialog)
        bar.addAction(act_new_branch)

        bar.addSeparator()

        for text, slot in (("获取", self.do_fetch),
                           ("拉取", self.do_pull),
                           ("推送", self.do_push),
                           ("刷新", self.refresh)):
            act = QAction(text, self)
            act.triggered.connect(slot)
            bar.addAction(act)

    def _build_ui(self) -> None:
        splitter = QSplitter(Qt.Horizontal)

        self.tabs = QTabWidget()
        self.tabs.addTab(self._build_changes_tab(), "改动")
        self.tabs.addTab(self._build_history_tab(), "历史")
        splitter.addWidget(self.tabs)

        self.diff_view = QPlainTextEdit()
        self.diff_view.setReadOnly(True)
        self.diff_view.setLineWrapMode(QPlainTextEdit.NoWrap)
        self.diff_view.setStyleSheet(
            "font-family: Consolas, 'Courier New', monospace;")
        self.diff_highlighter = DiffHighlighter(self.diff_view.document())
        splitter.addWidget(self.diff_view)

        splitter.setStretchFactor(0, 2)
        splitter.setStretchFactor(1, 3)
        self.setCentralWidget(splitter)

    def _build_changes_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)

        row = QHBoxLayout()
        btn_stage_all = QPushButton("全部暂存")
        btn_stage_all.clicked.connect(self.stage_all)
        btn_unstage_all = QPushButton("全部取消暂存")
        btn_unstage_all.clicked.connect(self.unstage_all)
        row.addWidget(btn_stage_all)
        row.addWidget(btn_unstage_all)
        row.addStretch(1)
        layout.addLayout(row)

        self.file_list = QListWidget()
        self.file_list.itemChanged.connect(self.on_file_item_changed)
        self.file_list.currentItemChanged.connect(self.on_file_selected)
        layout.addWidget(self.file_list, 1)

        self.summary_edit = QLineEdit()
        self.summary_edit.setPlaceholderText("提交摘要（必填）")
        layout.addWidget(self.summary_edit)

        self.desc_edit = QTextEdit()
        self.desc_edit.setPlaceholderText("详细描述（可选）")
        self.desc_edit.setFixedHeight(80)
        layout.addWidget(self.desc_edit)

        self.commit_btn = QPushButton("提交到当前分支")
        self.commit_btn.clicked.connect(self.do_commit)
        layout.addWidget(self.commit_btn)

        return page

    def _build_history_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        self.history_list = QListWidget()
        self.history_list.currentItemChanged.connect(self.on_history_selected)
        layout.addWidget(self.history_list)
        return page

    # ---------------- 打开 / 克隆 ----------------

    def open_repo_dialog(self) -> None:
        start = self.config.get("last_repo") or ""
        path = QFileDialog.getExistingDirectory(self, "选择仓库目录", start)
        if not path:
            return
        if not is_repo(path):
            QMessageBox.warning(self, "不是仓库",
                                "所选目录不是 Git 仓库，请选择包含 .git 的目录。")
            return
        self.load_repo(path)

    def clone_dialog(self) -> None:
        url, ok = QInputDialog.getText(self, "克隆仓库", "远程仓库地址：")
        if not ok or not url.strip():
            return
        default_parent = self.config.get("clone_dir", "")
        parent = QFileDialog.getExistingDirectory(self, "选择克隆到的父目录",
                                                  default_parent)
        if not parent:
            return
        name = os.path.basename(url.rstrip("/"))
        if name.endswith(".git"):
            name = name[:-4]
        dest = os.path.join(parent, name or "repo")

        ok, _ = self.run_op(clone, url.strip(), dest,
                            refresh=False, success="克隆完成")
        if ok:
            self.config["clone_dir"] = parent
            save_config(self.config)
            self.load_repo(dest)

    def load_repo(self, path: str) -> None:
        try:
            self.repo = repo_root(path)
        except GitError as exc:
            QMessageBox.critical(self, "错误", str(exc))
            return
        self.setWindowTitle(f"迷你 Git 桌面版 — {os.path.basename(self.repo)}")
        self.refresh()

    # ---------------- 设置 ----------------

    def open_settings(self) -> None:
        dlg = SettingsDialog(self, self.repo, self.config)
        if dlg.exec() != QDialog.Accepted:
            return

        # 1) 写远程地址（交给 git 管理，存进 .git/config）
        if self.repo:
            name = dlg.remote_name_edit.text().strip() or "origin"
            url = dlg.remote_url_edit.text().strip()
            current = get_remote_url(self.repo, name)
            if url and url != current:
                try:
                    set_remote_url(self.repo, url, name)
                except GitError as exc:
                    QMessageBox.critical(self, "保存失败", str(exc))
                    return
            elif not url and current:
                # 留空 + 原来有地址 = 删除这个远程
                ret = QMessageBox.question(
                    self, "删除远程",
                    f"地址留空，是否删除远程「{name}」？")
                if ret == QMessageBox.Yes:
                    try:
                        remove_remote(self.repo, name)
                    except GitError as exc:
                        QMessageBox.critical(self, "删除失败", str(exc))
                        return

        # 2) 写应用设置（存进脚本同目录的配置文件）
        self.config["clone_dir"] = dlg.clone_dir_edit.text().strip()
        save_config(self.config)

        self.refresh()
        self.statusBar().showMessage("设置已保存", 3000)

    # ---------------- 刷新 ----------------

    def refresh(self) -> None:
        if not self.repo:
            return
        try:
            self.changes = get_status(self.repo)
            branches = list_branches(self.repo)
            current = current_branch(self.repo)
            history = get_history(self.repo)
            ahead, behind = ahead_behind(self.repo)
            remote_url = get_remote_url(self.repo, "origin")
        except GitError as exc:
            QMessageBox.critical(self, "刷新失败", str(exc))
            return

        self._loading = True
        self.branch_combo.clear()
        if current:
            self.branch_combo.addItems(branches)
            self.branch_combo.setCurrentText(current)

        self.file_list.clear()
        for ch in self.changes:
            code = ch.y if ch.y not in (" ", "?") else ch.x
            label = STATUS_LABEL.get(code, code)
            text = f"[{label}] {ch.path}"
            if ch.orig_path:
                text += f"  ← {ch.orig_path}"
            item = QListWidgetItem(text)
            item.setData(Qt.UserRole, ch)
            item.setFlags(item.flags() | Qt.ItemIsUserCheckable)
            item.setCheckState(Qt.Checked if ch.staged else Qt.Unchecked)
            self.file_list.addItem(item)
        self._loading = False

        self.history_list.clear()
        for c in history:
            item = QListWidgetItem(f"{c.short}  {c.subject}   — {c.author}, {c.date}")
            item.setData(Qt.UserRole, c.sha)
            self.history_list.addItem(item)

        # 状态栏：把远程地址也展示出来，一眼能看到当前配置
        info = f"分支 {current or '(空)'}｜改动 {len(self.changes)} 个"
        if ahead or behind:
            info += f"｜领先 {ahead} / 落后 {behind}"
        info += f"｜远程 {remote_url or '(未设置)'}"
        self.statusBar().showMessage(info)

        # 配置文件里同步一份当前仓库的远程地址，方便查看/备份
        if remote_url:
            remotes_cache = self.config.setdefault("remotes", {})
            remotes_cache[self.repo] = remote_url
            save_config(self.config)

    # ---------------- 文件列表交互 ----------------

    def on_file_item_changed(self, item: QListWidgetItem) -> None:
        if self._loading or not self.repo:
            return
        ch: FileChange = item.data(Qt.UserRole)
        want_staged = item.checkState() == Qt.Checked
        if want_staged == ch.staged:
            return
        ok, _ = self.run_op(set_staged, self.repo, ch.path, want_staged,
                            refresh=False)
        if ok:
            self.refresh()

    def on_file_selected(self, current: QListWidgetItem, _previous) -> None:
        if current is None or not self.repo:
            return
        ch: FileChange = current.data(Qt.UserRole)
        try:
            self.diff_view.setPlainText(get_diff(self.repo, ch))
        except GitError as exc:
            self.diff_view.setPlainText(f"（无法获取差异：{exc}）")

    def stage_all(self) -> None:
        if self.repo:
            self.run_op(stage_all, self.repo, success="已全部暂存")

    def unstage_all(self) -> None:
        if self.repo:
            self.run_op(unstage_all, self.repo, success="已全部取消暂存")

    # ---------------- 提交 ----------------

    def do_commit(self) -> None:
        if not self.repo:
            QMessageBox.information(self, "提示", "请先打开一个仓库。")
            return
        summary = self.summary_edit.text().strip()
        if not summary:
            QMessageBox.warning(self, "提交", "请先填写提交摘要。")
            return
        if not any(c.staged for c in self.changes):
            ret = QMessageBox.question(self, "提交",
                                       "还没有暂存任何文件，是否全部暂存后提交？")
            if ret != QMessageBox.Yes:
                return
            if not self.run_op(stage_all, self.repo, refresh=False)[0]:
                return

        ok, _ = self.run_op(commit, self.repo, summary,
                            self.desc_edit.toPlainText(), success="提交成功")
        if ok:
            self.summary_edit.clear()
            self.desc_edit.clear()

    # ---------------- 分支 ----------------

    def on_branch_changed(self, name: str) -> None:
        if self._loading or not self.repo or not name:
            return
        if name == current_branch(self.repo):
            return
        self.run_op(checkout, self.repo, name, success=f"已切换到 {name}")

    def new_branch_dialog(self) -> None:
        if not self.repo:
            QMessageBox.information(self, "提示", "请先打开一个仓库。")
            return
        name, ok = QInputDialog.getText(self, "新建分支", "分支名：")
        if ok and name.strip():
            self.run_op(create_branch, self.repo, name.strip(),
                        success=f"已创建并切换到 {name.strip()}")

    # ---------------- 远程操作 ----------------

    def _require_remote(self) -> bool:
        """推送/拉取/获取前检查远程地址是否配置。"""
        if not self.repo:
            return False
        if not get_remotes(self.repo):
            ret = QMessageBox.question(
                self, "未配置远程",
                "当前仓库还没有设置远程地址，是否现在去设置？")
            if ret == QMessageBox.Yes:
                self.open_settings()
            return False
        return True

    def do_fetch(self) -> None:
        if self._require_remote():
            self.run_op(fetch, self.repo, success="已获取远程更新")

    def do_pull(self) -> None:
        if self._require_remote():
            self.run_op(pull, self.repo, success="拉取并合并完成")

    def do_push(self) -> None:
        if not self._require_remote():
            return
        branch = current_branch(self.repo)
        publish = get_upstream(self.repo) is None
        self.run_op(push, self.repo, branch, publish, success="推送完成")

    # ---------------- 历史 ----------------

    def on_history_selected(self, current: QListWidgetItem, _previous) -> None:
        if current is None or not self.repo:
            return
        sha = current.data(Qt.UserRole)
        try:
            self.diff_view.setPlainText(get_commit_diff(self.repo, sha))
        except GitError as exc:
            self.diff_view.setPlainText(f"（无法获取提交内容：{exc}）")

    # ---------------- 通用操作包装 ----------------

    def run_op(self, fn, *args, refresh: bool = True,
               success: str | None = None, **kwargs):
        try:
            result = fn(*args, **kwargs)
        except GitError as exc:
            QMessageBox.critical(self, "Git 错误", str(exc) or "未知错误")
            self.statusBar().showMessage("操作失败", 5000)
            return False, None
        if refresh:
            self.refresh()
        if success:
            self.statusBar().showMessage(success, 5000)
        return True, result

    # ---------------- 退出前保存 ----------------

    def closeEvent(self, event) -> None:
        self.config["window"] = {"width": self.width(), "height": self.height()}
        if self.repo:
            self.config["last_repo"] = self.repo
        save_config(self.config)
        super().closeEvent(event)


# ==========================================================================
# 三、启动入口
# ==========================================================================

def main() -> int:
    app = QApplication(sys.argv)
    config = load_config()

    win = MainWindow(config)
    win.show()

    # 优先用命令行参数；否则用上次打开过的仓库
    start_repo = None
    if len(sys.argv) > 1 and os.path.isdir(sys.argv[1]) and is_repo(sys.argv[1]):
        start_repo = sys.argv[1]
    elif config.get("last_repo") and is_repo(config["last_repo"]):
        start_repo = config["last_repo"]

    if start_repo:
        win.load_repo(start_repo)

    return app.exec()


if __name__ == "__main__":
    sys.exit(main())