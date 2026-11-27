#!/usr/bin/env python3
"""自动提取 Git 提交说明与 APK 校验元数据，生成 GitHub Releases 更新日志。

吸收 MovieClaw 发版体系优点，支持三通道全自动发版：
1. 日常推分支（main / feat/*）：自动截取上一正式版标签（如 v0.3.0）到 HEAD 的全部提交（含标题与正文细项），
   归类生成中文更新说明，更新到 `car-opt-latest` 预发布直链。
2. 推送版本标签（`vX.Y.Z`）或 `release-kick/vX.Y.Z` 点火分支：校验版本号一致，优先融合
   `docs/changelog/vX.Y.Z.md` 或 `CHANGELOG.md` 对应章节 + 自动截取提交记录，两段式原子发布正式版。
"""
import argparse
import datetime
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

CONVENTIONAL_RE = re.compile(
    r"^(?P<type>feat|fix|perf|refactor|test|ci|build|chore|docs|style)(?:\s*&\s*(?:feat|fix|perf))?"
    r"(?:\((?P<scope>[^)]+)\))?!?\s*[:：]\s*(?P<rest>.+)$",
    re.IGNORECASE,
)


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, stderr=subprocess.DEVNULL).strip()


def try_git(*args: str) -> str | None:
    try:
        return git(*args)
    except subprocess.CalledProcessError:
        return None


def read_gradle_version() -> str:
    gradle_file = ROOT / "app" / "build.gradle.kts"
    if gradle_file.is_file():
        m = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_file.read_text(encoding="utf-8"))
        if m:
            return m.group(1)
    return "0.3.0"


def find_base_tag(is_version_tag: bool, current_tag: str) -> str | None:
    if is_version_tag and try_git("rev-parse", "-q", "--verify", f"refs/tags/{current_tag}"):
        return try_git("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", f"{current_tag}^")
    return try_git("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", "HEAD")


def extract_changelog_section(tag: str, version: str) -> str | None:
    standalone = ROOT / "docs" / "changelog" / f"{tag}.md"
    if standalone.is_file():
        text = standalone.read_text(encoding="utf-8").strip()
        if text:
            return text

    changelog = ROOT / "CHANGELOG.md"
    if not changelog.is_file():
        return None
    lines = changelog.read_text(encoding="utf-8").splitlines()
    header_re = re.compile(rf"^##\s+v?{re.escape(version)}(?:\s|（|\(|$)")
    collecting = False
    buf: list[str] = []
    for line in lines:
        if header_re.match(line):
            collecting = True
            continue
        if collecting:
            if line.startswith("## "):
                break
            buf.append(line)
    text = "\n".join(buf).strip()
    return text or None


def classify_commit(subject: str) -> tuple[str, str]:
    m = CONVENTIONAL_RE.match(subject.strip())
    if m:
        ctype = m.group("type").lower()
        scope = m.group("scope")
        rest = m.group("rest").strip()
        prefix = f"**[{scope}]** " if scope else ""
        display = f"{prefix}{rest}"
        if "perf" in subject[:15].lower() or ctype == "perf":
            return "perf", display
        if ctype == "feat":
            return "feat", display
        if ctype == "fix":
            return "fix", display
        return "other", display

    s = subject.strip()
    if s.startswith(("新增", "添加", "支持", "增加")):
        return "feat", s
    if s.startswith(("优化", "改进", "提升", "精简", "瘦身")):
        return "perf", s
    if s.startswith(("修复", "解决", "修正")):
        return "fix", s
    return "other", s


def collect_commits(rev_range: str) -> list[dict[str, str]]:
    fmt = "%H%x1f%h%x1f%s%x1f%b%x1e"
    raw = try_git("log", rev_range, "--no-merges", f"--format={fmt}")
    if raw is None:
        raw = try_git("log", "-n", "20", "--no-merges", f"--format={fmt}") or ""
    commits: list[dict[str, str]] = []
    for rec in raw.split("\x1e"):
        rec = rec.strip()
        if not rec:
            continue
        parts = rec.split("\x1f")
        if len(parts) < 3:
            continue
        commits.append(
            {
                "sha": parts[0].strip(),
                "short": parts[1].strip(),
                "subject": parts[2].strip(),
                "body": parts[3].strip() if len(parts) > 3 else "",
            }
        )
    return commits


def format_size(num_bytes: int) -> str:
    return f"{num_bytes / (1024 * 1024):.2f} MB"


def format_commit_item(c: dict[str, str], repo: str, clean_title: str) -> str:
    link = f"[`{c['short']}`](https://github.com/{repo}/commit/{c['sha']})" if repo else f"`{c['short']}`"
    lines = [f"- {clean_title}（{link}）"]
    if c["body"]:
        for raw_line in c["body"].splitlines():
            stripped = raw_line.strip()
            if not stripped or stripped.lower().startswith(("co-authored-by:", "signed-off-by:")):
                continue
            if stripped.startswith(("-", "*")):
                lines.append(f"  {stripped}")
            else:
                lines.append(f"  - {stripped}")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--ref-name", default=os.environ.get("GITHUB_REF_NAME", ""))
    parser.add_argument("--ref-type", default=os.environ.get("GITHUB_REF_TYPE", "branch"))
    parser.add_argument("--tag-override", default="")
    parser.add_argument("--sha", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--dist-dir", default="dist")
    parser.add_argument("--out-notes", required=True)
    parser.add_argument("--github-output", default=os.environ.get("GITHUB_OUTPUT", ""))
    args = parser.parse_args()

    effective_tag = args.tag_override.strip()
    ref_name = effective_tag or args.ref_name or (try_git("rev-parse", "--abbrev-ref", "HEAD") or "main")
    full_sha = args.sha or (try_git("rev-parse", "HEAD") or "")
    short_sha = full_sha[:7] if full_sha else (try_git("rev-parse", "--short", "HEAD") or "unknown")
    is_version_tag = bool(effective_tag) or (args.ref_type == "tag" and bool(re.match(r"^v\d+\.\d+", ref_name)))

    gradle_ver = read_gradle_version()
    if is_version_tag:
        release_tag = ref_name
        version_label = ref_name.lstrip("v")
        title = f"OpenHC {version_label}"
        prerelease = "false" if "-" not in version_label else "true"
        apk_stem = f"OpenHC-{version_label}"
    else:
        release_tag = "car-opt-latest"
        version_label = gradle_ver
        title = f"OpenHC {gradle_ver} 车机增强版（{short_sha}）"
        prerelease = "true"
        apk_stem = f"OpenHC-{gradle_ver}-car-opt"

    base_tag = find_base_tag(is_version_tag, release_tag)
    rev_range = f"{base_tag}..HEAD" if base_tag else "HEAD"
    commits = collect_commits(rev_range)

    bj_tz = datetime.timezone(datetime.timedelta(hours=8))
    now_str = datetime.datetime.now(bj_tz).strftime("%Y-%m-%d %H:%M:%S UTC+8")

    sections: list[str] = []
    if is_version_tag:
        sections.append(
            f"**正式发布版** · 基于提交 `{short_sha}` 全自动校验签名构建（{now_str}）。\n"
            f"直接点击下方 **Assets** 中的 `{apk_stem}-release.apk` 即可下载安装（无需解压 `.zip`）。"
        )
    else:
        sections.append(
            f"**分支自动构建版** · 分支 `{ref_name}` · 提交 `{short_sha}` · 构建时间 `{now_str}`\n\n"
            f"> 直接点击下方 **Assets** 里的 `.apk` 文件即可下载安装（无需解压 `.zip`）：\n"
            f"> - **推荐车机安装**：`{apk_stem}-release.apk`（R8 瘦身 + 正式签名 + 内置连接参数）\n"
            f"> - **调试排查备用**：`{apk_stem}-debug.apk`"
        )

    if commits and not is_version_tag:
        latest = commits[0]
        _, latest_clean = classify_commit(latest["subject"])
        sections.append("### 📌 本次最新提交\n" + format_commit_item(latest, args.repo, latest_clean))

    if is_version_tag:
        curated = extract_changelog_section(release_tag, version_label)
        if curated:
            sections.append(f"### 📋 版本说明\n{curated}")

    buckets: dict[str, list[str]] = {"feat": [], "perf": [], "fix": [], "other": []}
    for c in commits:
        if c["subject"] == "Update README with note on project status":
            continue
        kind, clean = classify_commit(c["subject"])
        buckets[kind].append(format_commit_item(c, args.repo, clean))

    range_hint = f"（对比 `{base_tag}` → `{short_sha}`）" if base_tag else ""
    grouped_parts: list[str] = []
    titles = [
        ("feat", "#### ✨ 新增功能"),
        ("perf", "#### ⚡ 性能与体验优化"),
        ("fix", "#### 🐛 问题修复"),
        ("other", "#### 🔧 工程与测试"),
    ]
    for key, heading in titles:
        if buckets[key]:
            grouped_parts.append(heading + "\n" + "\n".join(buckets[key]))

    if grouped_parts:
        sections.append(f"### 📝 自动截取提交记录汇总{range_hint}\n\n" + "\n\n".join(grouped_parts))

    dist_path = ROOT / args.dist_dir
    meta_file = dist_path / "apk-metadata.json"
    if meta_file.is_file():
        meta = json.loads(meta_file.read_text(encoding="utf-8"))
        rows = ["| 安装包文件 | 大小 | SHA-256 校验和 | 说明 |", "| :--- | :---: | :--- | :--- |"]
        for item in meta.get("files", []):
            desc = "R8 瘦身正式签名版（推荐车机安装）" if "release" in item["name"] else "完整调试版（备用）"
            rows.append(f"| `{item['name']}` | {format_size(item['size'])} | `{item['sha256'][:16]}…` | {desc} |")
        id_badge = "✅ 已内置" if meta.get("hasIdentity") else "⚠️ 未内置"
        cert_fp = meta.get("certificateSha256", "")[:16]
        sections.append(
            "### 📦 安装包校验清单\n"
            + "\n".join(rows)
            + f"\n\n- **连接参数 `openhc-identity.json`**：{id_badge} · **最低系统**：Android API {meta.get('minSdk', 23)}+\n"
            + f"- **签名证书 SHA-256 前缀**：`{cert_fp}…`（完整校验值见附件 `checksums.txt`）"
        )

    if args.repo and base_tag:
        compare_ref = release_tag if (is_version_tag and not effective_tag) else full_sha
        sections.append(f"**完整代码对比**：https://github.com/{args.repo}/compare/{base_tag}...{compare_ref}")

    notes_path = Path(args.out_notes)
    notes_path.parent.mkdir(parents=True, exist_ok=True)
    notes_path.write_text("\n\n".join(sections) + "\n", encoding="utf-8")

    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8") as f:
            f.write(f"release_tag={release_tag}\n")
            f.write(f"release_title={title}\n")
            f.write(f"prerelease={prerelease}\n")
            f.write(f"apk_stem={apk_stem}\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
