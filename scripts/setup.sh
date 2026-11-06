#!/bin/bash
# Bootstrap a complete build environment for the Luckfox Lyra Pi from nothing
# but this layer.
#
#   git clone <this layer>  meta-luckfox-lyra
#   ./meta-luckfox-lyra/scripts/setup.sh
#   . ./openembedded-core/oe-init-build-env build
#   bitbake luckfox-image
#
# Clones the dependency layers as siblings of this one at pinned revisions and
# generates build/conf from the layer's own templates, so the layer is the
# single source of truth. Re-running is safe: existing clones are fetched and
# checked out to the pinned revision rather than re-cloned.
set -euo pipefail

LAYER=$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)
WS=$(dirname "$LAYER")

# Pinned so a rebuild months from now produces the same thing. Bump
# deliberately, not by accident.
#
# Yocto 6.0 "wrynose". There is no poky combined repository any more: bitbake,
# openembedded-core and meta-yocto (meta-poky) are separate checkouts, and
# oe-init-build-env lives in openembedded-core and finds bitbake as its sibling.
# bitbake/oe-core/meta-yocto are pinned to the yocto-6.0.4 release tags; the
# other layers have no point-release tags, so they are pinned to their wrynose
# branch heads at the time of the port.
BB_URL="https://git.openembedded.org/bitbake";             BB_BRANCH="2.18";    BB_REV="51fd94a67fa17650a9ff623492005972bbc7d26f"  # yocto-6.0.4
CORE_URL="https://git.openembedded.org/openembedded-core"; CORE_BRANCH="wrynose"; CORE_REV="0f03c732a0a2d0506b41b1abe60b3f368b2300dc"  # yocto-6.0.4
YOCTO_URL="https://git.yoctoproject.org/meta-yocto";       YOCTO_BRANCH="wrynose"; YOCTO_REV="7c25b11156553145fa6b241a2d4f65d94438f804"  # yocto-6.0.4
OE_URL="https://git.openembedded.org/meta-openembedded";   OE_BRANCH="wrynose";  OE_REV="c734c2cb9713dbd6d50697a9f6c25ebdf3ae589e"
ARM_URL="https://git.yoctoproject.org/meta-arm";           ARM_BRANCH="wrynose"; ARM_REV="673e8d7c5dcd14de58092eb73e6ecc5eee5c1735"
RAUC_URL="https://github.com/rauc/meta-rauc";              RAUC_BRANCH="wrynose"; RAUC_REV="38da0e5866a953e664001ba90b5edad68d68cd5d"

# LAYERSERIES_COMPAT in conf/layer.conf must agree with these branches.
clone_at() {
    local url=$1 branch=$2 rev=$3 dir="$WS/$4"
    if [ -d "$dir/.git" ]; then
        echo ">> $4: fetching"
        git -C "$dir" fetch --quiet origin "$branch" || git -C "$dir" fetch --quiet origin
    else
        echo ">> $4: cloning"
        git clone --quiet -b "$branch" "$url" "$dir"
    fi
    git -C "$dir" checkout --quiet "$rev"
    echo "   $4 @ $(git -C "$dir" rev-parse --short HEAD)"
}

echo "Workspace: $WS"
clone_at "$BB_URL"    "$BB_BRANCH"    "$BB_REV"    bitbake
clone_at "$CORE_URL"  "$CORE_BRANCH"  "$CORE_REV"  openembedded-core
clone_at "$YOCTO_URL" "$YOCTO_BRANCH" "$YOCTO_REV" meta-yocto
clone_at "$OE_URL"    "$OE_BRANCH"    "$OE_REV"    meta-openembedded
clone_at "$ARM_URL"   "$ARM_BRANCH"   "$ARM_REV"   meta-arm
clone_at "$RAUC_URL"  "$RAUC_BRANCH"  "$RAUC_REV"  meta-rauc

# Generate build/conf from this layer's templates. oe-init-build-env only
# applies TEMPLATECONF when conf/ does not already exist, so leave an existing
# build directory alone rather than silently overwriting a user's edits.
if [ -e "$WS/build/conf/local.conf" ]; then
    echo
    echo ">> $WS/build/conf already exists, leaving it untouched."
    echo "   Delete it and re-run to regenerate from the layer templates."
else
    echo
    echo ">> generating build/conf from layer templates"
    ( cd "$WS" && TEMPLATECONF="$LAYER/conf/templates/default" \
        . ./openembedded-core/oe-init-build-env build >/dev/null )
    echo "   created $WS/build/conf"
fi

cat <<EOM

Done. To build:

    cd $WS
    . ./openembedded-core/oe-init-build-env build
    bitbake luckfox-image

Board is selected in build/conf/local.conf (MACHINE).
EOM
