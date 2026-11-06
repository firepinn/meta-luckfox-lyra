#!/bin/bash
# Build the Luckfox Lyra Pi image for one or both machines.
#
#   ./scripts/build-image.sh                                  # machine from local.conf
#   ./scripts/build-image.sh luckfox-lyra-pi-b
#   ./scripts/build-image.sh luckfox-lyra-pi-a luckfox-lyra-pi-b
set -o pipefail

LAYER=$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)
WS=$(dirname "$LAYER")

[ -f "$WS/openembedded-core/oe-init-build-env" ] || {
    echo "openembedded-core not found at $WS/openembedded-core -- run scripts/setup.sh first." >&2
    exit 1
}

cd "$WS" || exit 1
# shellcheck disable=SC1091
. ./openembedded-core/oe-init-build-env build > /dev/null || exit 1

# No machines given: just build whatever local.conf selects.
if [ $# -eq 0 ]; then
    exec bitbake luckfox-image
fi

rc=0
for m in "$@"; do
    echo "=========== BUILDING $m ==========="
    if MACHINE="$m" bitbake luckfox-image; then
        echo "=========== $m OK ==========="
    else
        echo "=========== $m FAILED ==========="
        rc=1
    fi
done
exit $rc
