#!/usr/bin/env bash
set -euo pipefail

BIN_DIR="${JDBG_BIN_DIR:-/usr/local/bin}"
LIB_DIR="${JDBG_LIB_DIR:-$HOME/.local/share/jdbg}"

if [[ ! -e "$BIN_DIR/jdbg" ]]; then
  BIN_DIR="${JDBG_BIN_DIR:-$HOME/.local/bin}"
fi

rm -f "$BIN_DIR/jdbg"
rm -f "$LIB_DIR/java-debugger.jar"

echo "Removed jdbg from $BIN_DIR"
echo "Removed jar from $LIB_DIR"
