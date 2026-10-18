#!/bin/sh
# Retain the entry point for older Toolbox callers without a competing writer.
echo "FAIL: legacy GEM layout writes are disabled." >&2
echo "Use NAV > Navigation settings > Carplay Altscreen for layout and phone ETA." >&2
echo "Existing preferences and legacy files were not changed." >&2
exit 2
