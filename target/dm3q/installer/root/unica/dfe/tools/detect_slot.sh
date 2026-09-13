#!/sbin/sh

# detect_slot.sh - POSIX shell version of detect_slot.py

for s in _a _b ""; do
  if [ -b "/dev/block/mapper/vendor$s" ]; then
    echo "$s"
    exit 0
  fi
done

echo "[-] No active slot detected" >&2
exit 1
