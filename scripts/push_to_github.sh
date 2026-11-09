#!/usr/bin/env bash
# 一键发布到 GitHub
# 用法：./scripts/push_to_github.sh <仓库名> [public|private]
# 例： ./scripts/push_to_github.sh MarketingGuard public
set -euo pipefail

REPO_NAME="${1:-MarketingGuard}"
VISIBILITY="${2:-public}"

if [ "$VISIBILITY" != "public" ] && [ "$VISIBILITY" != "private" ]; then
  echo "可见性只能是 public 或 private"
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "未检测到 gh（GitHub CLI）。请先安装："
  echo "  macOS:  brew install gh"
  echo "  Linux:  https://github.com/cli/cli/blob/trunk/docs/install_linux.md"
  echo "  Windows: winget install GitHub.cli"
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "尚未登录 GitHub，即将进入登录流程（选 GitHub.com → HTTPS → 浏览器授权）..."
  gh auth login
fi

cd "$(dirname "$0")/.."

if [ ! -d .git ]; then
  git init -b main
fi

git add -A
if ! git diff --cached --quiet; then
  git commit -m "feat: MarketingGuard v1.2 —— 无障碍+OCR 营销话术过滤器（规则三级来源）"
fi

if git remote get-url origin >/dev/null 2>&1; then
  echo "已存在 origin：$(git remote get-url origin)"
  git push -u origin main
else
  gh repo create "$REPO_NAME" "--$VISIBILITY" --source=. --remote=origin --push \
    --description "基于无障碍服务与本地 OCR 的营销话术实时过滤 Android App：规则引擎 + 四级警示 + 规则订阅进化"
fi

echo ""
echo "完成。仓库地址："
gh repo view --json url -q .url
echo ""
echo "提示：进入仓库 Settings → Topics 可添加 android, kotlin, accessibility-service, mlkit, ocr, content-filter"
