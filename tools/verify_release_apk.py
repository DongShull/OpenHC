#!/usr/bin/env python3
"""安装包完整性与签名守门员（参考 MovieClaw 的 build-release-metadata.py）：

在发布到 GitHub Releases 前，强制对构建出的正式 APK 做校验：
1. `aapt2 dump badging`：核对 applicationId、versionName、versionCode、minSdkVersion，且禁止 debuggable；
2. ZIP / DEX 结构自检：确认 `assets/openhc-identity.json` 已打进包内且非空，确认 ADB 辅助入口类
   `HotspotSwitchMain` 与 `ScanSwitchMain` 未被 R8 误裁；
3. `apksigner verify --verbose --print-certs`：核对 APK 签名有效，提取证书 SHA-256 指纹（配置了正式密钥时禁止 Debug 证书）；
4. 计算 dist 目录下全部 APK 的 SHA-256 校验和，生成 `checksums.txt` 与 `apk-metadata.json` 供发版说明读取。
"""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_release_apk(
    apk: Path,
    aapt2: str,
    apksigner: str,
    require_release_cert: bool,
    expected_version: str,
    expected_code: int,
) -> dict:
    badging = subprocess.check_output([aapt2, "dump", "badging", str(apk)], text=True)
    pkg_match = re.search(r"^package: (.+)$", badging, re.MULTILINE)
    if not pkg_match:
        raise ValueError(f"无法读取 APK 包头信息：{apk}")
    fields = dict(re.findall(r"(\w+)='([^']*)'", pkg_match.group(1)))
    if fields.get("name") != "io.github.gcbond.openhc":
        raise ValueError(f"包名异常：{fields.get('name')}")
    if "application-debuggable" in badging:
        raise ValueError("正式版 Release APK 不允许带 application-debuggable 标记")
    if expected_version and fields.get("versionName") != expected_version:
        raise ValueError(f"APK 内置 versionName={fields.get('versionName')} 与目标版本 {expected_version} 不一致")
    if expected_code > 0 and int(fields.get("versionCode", "0")) != expected_code:
        raise ValueError(f"APK 内置 versionCode={fields.get('versionCode')} 与目标编码 {expected_code} 不一致")

    sdk_match = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.MULTILINE)
    if sdk_match is None:
        raise ValueError("APK 缺少 minSdkVersion")

    has_identity = False
    with zipfile.ZipFile(apk, "r") as zf:
        names = set(zf.namelist())
        if "assets/openhc-identity.json" in names:
            identity_bytes = zf.read("assets/openhc-identity.json")
            if len(identity_bytes) < 50:
                raise ValueError("assets/openhc-identity.json 内容异常过短")
            has_identity = True
        elif require_release_cert:
            raise ValueError("正式安装包缺少 assets/openhc-identity.json，装上车机无法连接手机，拒绝发版")

        dex_blobs = [zf.read(n) for n in names if n.startswith("classes") and n.endswith(".dex")]
        for required_cls in (b"HotspotSwitchMain", b"ScanSwitchMain"):
            if not any(required_cls in blob for blob in dex_blobs):
                raise ValueError(f"R8 裁剪检查失败：classes.dex 中缺少 {required_cls.decode()}")

    sig_out = subprocess.check_output(
        [apksigner, "verify", "--verbose", "--print-certs", str(apk)],
        text=True,
    )
    cert_match = re.search(
        r"(?:Signer #1|V2 Signer|V3 Signer):? certificate SHA-256 digest: ([0-9a-fA-F]{64})",
        sig_out,
    )
    if cert_match is None:
        raise ValueError("无法提取 APK 签名证书 SHA-256 指纹")
    if require_release_cert and "CN=Android Debug" in sig_out:
        raise ValueError("正式发版必须使用正式签名密钥，拒绝 Android Debug 证书")

    return {
        "applicationId": fields["name"],
        "versionName": fields["versionName"],
        "versionCode": int(fields["versionCode"]),
        "minSdk": int(sdk_match.group(1)),
        "hasIdentity": has_identity,
        "certificateSha256": cert_match.group(1).lower(),
        "isDebugCert": "CN=Android Debug" in sig_out,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist-dir", type=Path, required=True)
    parser.add_argument("--release-apk", type=Path, required=True)
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--expected-version", default="")
    parser.add_argument("--expected-code", type=int, default=0)
    parser.add_argument("--require-release-cert", action="store_true")
    args = parser.parse_args()

    meta = verify_release_apk(
        args.release_apk,
        args.aapt2,
        args.apksigner,
        args.require_release_cert,
        args.expected_version,
        args.expected_code,
    )

    apks = sorted(args.dist_dir.glob("*.apk"))
    checksum_lines: list[str] = []
    files_meta: list[dict] = []
    for apk in apks:
        digest = sha256_file(apk)
        checksum_lines.append(f"{digest}  {apk.name}")
        files_meta.append(
            {
                "name": apk.name,
                "size": apk.stat().st_size,
                "sha256": digest,
            }
        )

    (args.dist_dir / "checksums.txt").write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")
    meta["files"] = files_meta
    (args.dist_dir / "apk-metadata.json").write_text(
        json.dumps(meta, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(
        f"APK 校验通过：{meta['applicationId']} {meta['versionName']} ({meta['versionCode']})，"
        f"连接参数={'已内置' if meta['hasIdentity'] else '未内置'}，证书指纹={meta['certificateSha256'][:16]}…"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
