#!/bin/bash
# Run a command on the Lyra Pi over the serial console and print its output.
# Auto-logs in (root/root) if the console is sitting at a login prompt.
#   ./sercmd.sh 'ip -br link' [timeout_secs]
PORT=${PORT:-/dev/ttyUSB0}
CMD=$1
T=${2:-15}
TMP=$(mktemp)
stty -F "$PORT" 115200 cs8 -cstopb -parenb raw -echo
timeout "$T" cat "$PORT" > "$TMP" &
CATPID=$!
sleep 0.4

# Wake the console and see what we're facing.
printf '\r\n' > "$PORT"
sleep 1.5
if grep -qi "login:" "$TMP"; then
    printf 'root\r\n' > "$PORT"; sleep 2.5
    printf 'root\r\n' > "$PORT"; sleep 3.5
fi

: > "$TMP.mark"
printf '%s; echo __END__\r\n' "$CMD" > "$PORT"
for i in $(seq 1 $((T*10))); do
    grep -q "__END__" "$TMP" 2>/dev/null && { sleep 0.6; break; }
    sleep 0.1
done
kill $CATPID 2>/dev/null; wait $CATPID 2>/dev/null

# Print only what came after the echoed command line, minus marker and prompt.
sed -e 's/\r$//' "$TMP" | sed -n '/; echo __END__$/,$p' \
  | sed -e '1d' -e '/^__END__$/,$d'
rm -f "$TMP" "$TMP.mark"
