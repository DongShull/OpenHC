#!/usr/bin/env python3
"""三段式版本号（X.Y.Z = 大版本.正式版.测试预发布版）自动推算与 Git 提交日志提取工具。

版本号语义：
  - 第 1 位 X：大版本（Major）
  - 第 2 位 Y：正式版代次（Official）
  - 第 3 位 Z：测试预发布版序号
      * Z == 0（如 0.3.0、0.4.0、1.0.0） → 正式版（Official Release，GitHub Latest 绿标）
      * Z >= 1（如 0.3.1、0.3.2、0.3.3） → 测试预发布版（Pre-release，每次推送自动递增 Z！）

支持阶段：
  1. `--mode resolve`（构建前执行）：自动推算本次要构建并发布的 `X.Y.Z` 与 `versionCode`，
     供 `./gradlew -Popenhc.versionName=... -Popenhc.versionCode=...` 直接打进 APK 内部。
  2. `--mode notes`（构建并校验 APK 后执行）：按正式版/测试预发布版自动截取对应区间的 Git 提交说明
     （含标题与多行正文）及 APK SHA-256 元数据，生成完整的 GitHub Release 更新日志。
"""
import argparse
import datetime
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

SEMVER_TAG_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")

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


def parse_xyz(text: str) -> tuple[int, int, int]:
    m = SEMVER_TAG_RE.match(text.strip())
    if not m:
        raise ValueError(f"版本号必须符合 X.Y.Z（大版本.正式版.测试预发布版），实际为：{text}")
    return int(m.group(1)), int(m.group(2)), int(m.group(3))


def compute_version_code(x: int, y: int, z: int) -> int:
    return x * 10000 + y * 100 + z


def read_gradle_base_xyz() -> tuple[int, int, int]:
    gradle_file = ROOT / "app" / "build.gradle.kts"
    if gradle_file.is_file():
        m = re.search(r'versionName\s*=\s*overrideVersionName\s*\?:\s*"([^"]+)"', gradle_file.read_text(encoding="utf-8"))
        if not m:
            m = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_file.read_text(encoding="utf-8"))
        if m:
            return parse_xyz(m.group(1))
    return (0, 3, 0)


def list_all_version_tags() -> list[tuple[int, int, int, str]]:
    raw = try_git("tag", "-l", "v*") or ""
    result: list[tuple[int, int, int, str]] = []
    for line in raw.splitlines():
        t = line.strip()
        m = SEMVER_TAG_RE.match(t)
        if m and t.startswith("v"):
            result.append((int(m.group(1)), int(m.group(2)), int(m.group(3)), t))
    result.sort()
    return result


def resolve_target_xyz(ref_name: str, ref_type: str, tag_override: str) -> tuple[int, int, int]:
    explicit = tag_override.strip()
    if explicit:
        return parse_xyz(explicit)
    if ref_type == "tag" and SEMVER_TAG_RE.match(ref_name):
        return parse_xyz(ref_name)

    bx, by, bz = read_gradle_base_xyz()
    all_tags = list_all_version_tags()
    same_series = [z for (x, y, z, _) in all_tags if x == bx and y == by]

    # 如果 app/build.gradle.kts 升到了新的 X.Y.0 且仓库还没有 vX.Y.0 标签 → 本次直接发正式版 X.Y.0！
    if not same_series and bz == 0:
        return (bx, by, 0)

    # 否则在当前 X.Y 系列下自动递增第 3 位测试预发布版序号 Z（如 0.3.0 -> 0.3.1 -> 0.3.2…）
    max_existing_z = max(same_series) if same_series else 0
    next_z = max(max_existing_z + 1, bz if bz not in same_series else max_existing_z + 1)
    return (bx, by, next_z)


def find_comparison_tags(x: int, y: int, z: int) -> tuple[str | None, str | None]:
    """返回 (上一紧邻版本标签 prev_tag, 上一正式版标签 official_base_tag)。"""
    current_tuple = (x, y, z)
    all_tags = list_all_version_tags()
    earlier = [item for item in all_tags if (item[0], item[1], item[2]) < current_tuple]
    if not earlier:
        return None, None

    if z == 0:
        # 正式版（如 0.4.0）：基准取上一个 Z==0 的正式版（如 v0.3.0），汇总整个周期所有测试版改动
        earlier_official = [item for item in earlier if item[2] == 0]
        base = earlier_official[-1][3] if earlier_official else earlier[-1][3]
        return base, base
    else:
        # 测试预发布版（如 0.3.2）：紧邻上一版是 v0.3.1，本代正式版基线是 v0.3.0
        prev_tag = earlier[-1][3]
        same_official = [item for item in earlier if item[0] == x and item[1] == y and item[2] == 0]
        if same_official:
            official_base = same_official[-1][3]
        else:
            any_official = [item for item in earlier if item[2] == 0]
            official_base = any_official[-1][3] if any_official else prev_tag
        return prev_tag, official_base


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
        subj = parts[2].strip()
        if subj == "Update README with note on project status":
            continue
        commits.append(
            {
                "sha": parts[0].strip(),
                "short": parts[1].strip(),
                "subject": subj,
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


def render_grouped_commits(commits: list[dict[str, str]], repo: str) -> str:
    buckets: dict[str, list[str]] = {"feat": [], "perf": [], "fix": [], "other": []}
    for c in commits:
        kind, clean = classify_commit(c["subject"])
        buckets[kind].append(format_commit_item(c, repo, clean))

    titles = [
        ("feat", "#### ✨ 新增功能"),
        ("perf", "#### ⚡ 性能与体验优化"),
        ("fix", "#### 🐛 问题修复"),
        ("other", "#### 🔧 工程与测试"),
    ]
    parts = [heading + "\n" + "\n".join(buckets[key]) for key, heading in titles if buckets[key]]
    return "\n\n".join(parts)


def write_outputs(github_output: str, kv: dict[str, str]) -> None:
    if not github_output:
        return
    with open(github_output, "a", encoding="utf-8") as f:
        for k, v in kv.items():
            f.write(f"{k}={v}\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["resolve", "notes"], default="notes")
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--ref-name", default=os.environ.get("GITHUB_REF_NAME", ""))
    parser.add_argument("--ref-type", default=os.environ.get("GITHUB_REF_TYPE", "branch"))
    parser.add_argument("--tag-override", default="")
    parser.add_argument("--version", default="")
    parser.add_argument("--sha", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--dist-dir", default="dist")
    parser.add_argument("--out-notes", default="")
    parser.add_argument("--github-output", default=os.environ.get("GITHUB_OUTPUT", ""))
    args = parser.parse_args()

    ref_name = args.ref_name or (try_git("rev-parse", "--abbrev-ref", "HEAD") or "main")
    full_sha = args.sha or (try_git("rev-parse", "HEAD") or "")
    short_sha = full_sha[:7] if full_sha else (try_git("rev-parse", "--short", "HEAD") or "unknown")

    if args.version.strip():
        x, y, z = parse_xyz(args.version.strip())
    else:
        x, y, z = resolve_target_xyz(ref_name, args.ref_type, args.tag_override)

    version_name = f"{x}.{y}.{z}"
    version_code = compute_version_code(x, y, z)
    release_tag = f"v{version_name}"
    is_prerelease = z > 0
    prerelease_str = "true" if is_prerelease else "false"
    release_title = f"OpenHC {version_name}（测试预发布版）" if is_prerelease else f"OpenHC {version_name}"
    release_apk_name = f"OpenHC-{version_name}.apk"
    debug_apk_name = f"OpenHC-{version_name}-debug.apk"

    if args.mode == "resolve":
        print(
            f"版本推算结果：{version_name} (versionCode={version_code}, "
            f"tag={release_tag}, {'测试预发布版' if is_prerelease else '正式版'})"
        )
        write_outputs(
            args.github_output,
            {
                "version_name": version_name,
                "version_code": str(version_code),
                "release_tag": release_tag,
                "release_title": release_title,
                "prerelease": prerelease_str,
                "release_apk_name": release_apk_name,
                "debug_apk_name": debug_apk_name,
            },
        )
        return 0

    prev_tag, official_base = find_comparison_tags(x, y, z)
    bj_tz = datetime.timezone(datetime.timedelta(hours=8))
    now_str = datetime.datetime.now(bj_tz).strftime("%Y-%m-%d %H:%M:%S UTC+8")

    sections: list[str] = []
    if not is_prerelease:
        sections.append(
            f"**正式版（`{version_name}`）** · 版本号编码 `{version_code}` · 提交 `{short_sha}` · 构建时间 `{now_str}`\n\n"
            f"> 直接点击下方 **Assets** 中的 **`{release_apk_name}`** 即可下载安装（无需解压 `.zip`），支持同签名版本直接覆盖升级。"
        )
    else:
        sections.append(
            f"**测试预发布版（`{version_name}`，`{x}.{y}` 系列第 {z} 版）** · 版本号编码 `{version_code}` · 提交 `{short_sha}` · 构建时间 `{now_str}`\n\n"
            f"> 直接点击下方 **Assets** 里的 `.apk` 文件即可下载安装（无需解压 `.zip`）：\n"
            f"> - **推荐车机安装**：`{release_apk_name}`（R8 瘦身 + 正式签名 + 内置连接参数，后续版本可直接覆盖安装）\n"
            f"> - **调试排查备用**：`{debug_apk_name}`"
        )

    curated = extract_changelog_section(release_tag, version_name)
    if curated:
        sections.append(f"### 📋 版本说明\n{curated}")

    inc_range = f"{prev_tag}..HEAD" if prev_tag else "HEAD"
    inc_commits = collect_commits(inc_range)
    if inc_commits:
        inc_grouped = render_grouped_commits(inc_commits, args.repo)
        if inc_grouped:
            heading = (
                f"### 📝 正式版更新记录（对比 `{prev_tag}` → `{release_tag}`）"
                if (not is_prerelease and prev_tag)
                else f"### 📌 本版新增改动（对比 `{prev_tag}` → `{release_tag}`）"
                if prev_tag
                else "### 📝 本版更新记录"
            )
            sections.append(f"{heading}\n\n{inc_grouped}")

    # 当 Z >= 2 时，除了展示相对上一测试版 vX.Y.(Z-1) 的增量，还附带自正式版 vX.Y.0 以来的累计汇总
    if is_prerelease and official_base and official_base != prev_tag:
        cum_commits = collect_commits(f"{official_base}..HEAD")
        cum_grouped = render_grouped_commits(cum_commits, args.repo)
        if cum_grouped:
            sections.append(
                f"<details>\n<summary>📚 展开查看 {x}.{y}.x 测试周期累计全部改动（对比正式版 {official_base} → {release_tag}）</summary>\n\n"
                f"{cum_grouped}\n\n</details>"
            )

    dist_path = ROOT / args.dist_dir
    meta_file = dist_path / "apk-metadata.json"
    if meta_file.is_file():
        meta = json.loads(meta_file.read_text(encoding="utf-8"))
        rows = ["| 安装包文件 | 版本 (Code) | 大小 | SHA-256 校验和 | 说明 |", "| :--- | :---: | :---: | :---: | :--- |"]
        v_col = f"`{meta.get('versionName')}` (`{meta.get('versionCode')}`)"
        for item in meta.get("files", []):
            is_dbg = item["name"].endswith("-debug.apk")
            desc = "完整调试版（备用）" if is_dbg else "R8 瘦身正式签名版（推荐车机安装）"
            rows.append(f"| `{item['name']}` | {v_col} | {format_size(item['size'])} | `{item['sha256'][:16]}…` | {desc} |")
        id_badge = "✅ 已内置" if meta.get("hasIdentity") else "⚠️ 未内置"
        cert_fp = meta.get("certificateSha256", "")[:16]
        sections.append(
            "### 📦 安装包校验清单\n"
            + "\n".join(rows)
            + f"\n\n- **连接参数 `openhc-identity.json`**：{id_badge} · **最低系统**：Android API {meta.get('minSdk', 23)}+\n"
            + f"- **签名证书 SHA-256 前缀**：`{cert_fp}…`（完整校验值见附件 `checksums.txt`）"
        )

    compare_base = official_base or prev_tag
    if args.repo and compare_base:
        sections.append(f"**完整代码对比**：https://github.com/{args.repo}/compare/{compare_base}...{release_tag}")

    if args.out_notes:
        notes_path = Path(args.out_notes)
        notes_path.parent.mkdir(parents=True, exist_ok=True)
        notes_path.write_text("\n\n".join(sections) + "\n", encoding="utf-8")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
