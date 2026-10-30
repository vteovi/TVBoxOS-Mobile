#!/usr/bin/env bash
set -euo pipefail

skip_version_check=false
if [[ "${1:-}" == "--skip-version-check" ]]; then
  skip_version_check=true
  shift
fi

tag_name="${1:?Usage: sync_release_metadata.sh [--skip-version-check] vX.Y.Z}"
version="${tag_name#v}"
repository="${GITHUB_REPOSITORY:-vteovi/TVBoxOS-Mobile}"
app_version=$(sed -nE "s/^[[:space:]]*versionName '([^']+)'/\1/p" app/build.gradle)

if [[ "$tag_name" != v* || ! "$version" =~ ^[0-9]+(\.[0-9]+)+$ ]]; then
  echo "Expected a tag in the form vX.Y.Z, got: $tag_name" >&2
  exit 1
fi

if [[ "$skip_version_check" == false && "$app_version" != "$version" ]]; then
  echo "Tag $tag_name does not match app version $app_version" >&2
  exit 1
fi

# 下载加速前缀：gh.xxooo.cf 已不可用（连接超时），默认改用 gh-proxy.com，
# 需要换线路时用 APK_ACCEL 覆盖，例如 APK_ACCEL=https://ghfast.top/
accel="${APK_ACCEL:-https://gh-proxy.com/}"
apk_base="${accel}https://github.com/${repository}/releases/download/${tag_name}"
apk_url="${apk_base}/TVBox-Mobile-v${version}-arm64v8.apk"
apk_url_armv7a="${apk_base}/TVBox-Mobile-v${version}-armv7a.apk"

# changelog: 从 更新记录.md 抽当前版本的条目行, 供应用内更新弹窗展示
changelog=""
if [[ -f 更新记录.md ]]; then
  changelog=$(awk -v ver="$version" '
    index($0, "### " ver " ") == 1 { inside = 1; next }
    inside && index($0, "### ") == 1 { inside = 0 }
    inside && index($0, "- ") == 1 { sub(/^- /, ""); print }
  ' 更新记录.md)
fi
changelog_escaped=""
if [[ -n "$changelog" ]]; then
  changelog_escaped=$(printf '%s\n' "$changelog" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/$/\\n/' | tr -d '\n' | sed -e 's/\\n$//')
fi

if [[ -n "$changelog_escaped" ]]; then
  printf '{\n  "version": "%s",\n  "apk_url": "%s",\n  "apk_url_armv7a": "%s",\n  "changelog": "%s"\n}\n' \
    "$version" "$apk_url" "$apk_url_armv7a" "$changelog_escaped" > update.json
else
  printf '{\n  "version": "%s",\n  "apk_url": "%s",\n  "apk_url_armv7a": "%s"\n}\n' \
    "$version" "$apk_url" "$apk_url_armv7a" > update.json
fi

if ! grep -q '<!-- release-download' README.md; then
  echo "README download section was not found" >&2
  exit 1
fi

# 下载表里两个架构的链接分别回写：arm64v8 用 apk_url，armv7a 用 apk_url_armv7a
url_pattern='s|(https?://[^/[:space:]]+/)?https://github\.com/[^/[:space:]]+/[^/[:space:]]+/releases/download/v[0-9.]+/TVBox-Mobile-v[0-9.]+-arm64v8\.apk|'"$apk_url"'|g'
sed -i -E "$url_pattern" README.md
url_pattern='s|(https?://[^/[:space:]]+/)?https://github\.com/[^/[:space:]]+/[^/[:space:]]+/releases/download/v[0-9.]+/TVBox-Mobile-v[0-9.]+-armv7a\.apk|'"$apk_url_armv7a"'|g'
sed -i -E "$url_pattern" README.md

# 链接文字里的文件名（表格中显示的那一列）也一并更新到当前版本
sed -i -E "s|TVBox-Mobile-v[0-9.]+-arm64v8\.apk|TVBox-Mobile-v${version}-arm64v8.apk|g" README.md
sed -i -E "s|TVBox-Mobile-v[0-9.]+-armv7a\.apk|TVBox-Mobile-v${version}-armv7a.apk|g" README.md

if ! grep -q "TVBox Mobile v${version}" README.md; then
  release_date=$(date -u +%Y/%m/%d)
  entry=">* **${release_date} TVBox Mobile v${version}：** 同步发布 Android APK、应用内更新清单和下载链接。\n>"
  temp_file=$(mktemp)
  while IFS= read -r line || [[ -n "$line" ]]; do
    printf '%s\n' "$line"
    if [[ "$line" == "## 📝 更新记录" ]]; then
      printf '\n%b\n' "$entry"
    fi
  done < README.md > "$temp_file"
  mv "$temp_file" README.md
fi
