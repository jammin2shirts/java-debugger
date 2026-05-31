#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BIN_DIR="${JDBG_BIN_DIR:-/usr/local/bin}"
LIB_DIR="${JDBG_LIB_DIR:-$HOME/.local/share/jdbg}"
JAR_DEST="$LIB_DIR/java-debugger.jar"

if [[ ! -w "$BIN_DIR" ]]; then
  BIN_DIR="${JDBG_BIN_DIR:-$HOME/.local/bin}"
fi

mkdir -p "$BIN_DIR"
mkdir -p "$LIB_DIR"

pushd "$PROJECT_ROOT" > /dev/null
mvn -q -DskipTests clean package
JAR_FILE="$(ls -1 target/java-debugger-*.jar | head -n 1)"
cp "$JAR_FILE" "$JAR_DEST"
popd > /dev/null

{
  echo '#!/usr/bin/env bash'
  echo 'set -euo pipefail'
  printf 'JDBG_JAR="${JDBG_JAR:-%s}"\n' "$JAR_DEST"
  echo 'exec java -jar "$JDBG_JAR" "$@"'
} > "$BIN_DIR/jdbg"

chmod +x "$BIN_DIR/jdbg"

echo "Installed jdbg"
echo "  binary: $BIN_DIR/jdbg"
echo "  jar:    $JAR_DEST"
if [[ "$BIN_DIR" == "$HOME/.local/bin" ]]; then
  echo "Add to PATH if needed: export PATH=\"$HOME/.local/bin:$PATH\""
fi
