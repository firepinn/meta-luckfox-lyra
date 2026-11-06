#!/bin/bash
# Flash a built Luckfox Lyra Pi image.
#
#   ./scripts/flash.sh sd /dev/sdX     Model B -- write image to microSD
#   ./scripts/flash.sh emmc            Model A -- write eMMC over USB-C (maskrom)
#
# Model A has no card slot, so its eMMC is written over USB-C with the board in
# maskrom mode: hold BOOT while plugging the USB-C cable in.
set -euo pipefail

LAYER=$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)
WS=$(dirname "$LAYER")
MODE=${1:-}

img_for() {
    local machine=$1
    local d="$WS/build/tmp/deploy/images/$machine"
    local f="$d/luckfox-image-$machine.rootfs.wic"
    [ -f "$f" ] || f="$d/luckfox-image-$machine.wic"
    [ -f "$f" ] || { echo "No .wic in $d -- build it first." >&2; exit 1; }
    echo "$f"
}

case "$MODE" in
sd)
    DEV=${2:-}
    [ -n "$DEV" ] || { echo "usage: $0 sd /dev/sdX" >&2; exit 1; }
    [ -b "$DEV" ] || { echo "$DEV is not a block device" >&2; exit 1; }

    IMG=$(img_for luckfox-lyra-pi-b)

    # Refuse anything that looks like a system disk.
    if lsblk -no MOUNTPOINT "$DEV" 2>/dev/null | grep -qE '^/($|boot|home)'; then
        echo "REFUSING: $DEV has system mountpoints." >&2
        exit 1
    fi

    echo "Image : $IMG"
    echo "Target: $DEV  ($(lsblk -dno SIZE,MODEL "$DEV" 2>/dev/null))"
    echo
    lsblk "$DEV"
    echo
    read -rp "This ERASES $DEV. Type YES to continue: " ans
    [ "$ans" = "YES" ] || { echo "Aborted."; exit 1; }

    for p in $(lsblk -lno NAME "$DEV" | tail -n +2); do
        sudo umount "/dev/$p" 2>/dev/null || true
    done

    if command -v bmaptool >/dev/null && [ -f "${IMG}.bmap" ]; then
        sudo bmaptool copy --bmap "${IMG}.bmap" "$IMG" "$DEV"
    else
        sudo dd if="$IMG" of="$DEV" bs=4M conv=fsync status=progress
    fi
    sync
    echo "Done. Insert the card in the Lyra Pi and power on."
    ;;

emmc)
    IMG=$(img_for luckfox-lyra-pi-a)
    DEPLOY=$(dirname "$IMG")
    LOADER=$(ls "$DEPLOY"/rk3506b_spl_loader.bin 2>/dev/null | head -1 || true)

    command -v rkdeveloptool >/dev/null || {
        echo "rkdeveloptool not found. Install from" >&2
        echo "  https://github.com/rockchip-linux/rkdeveloptool" >&2
        exit 1
    }

    echo "Put the board in maskrom mode: hold BOOT, plug in USB-C, release."
    echo
    rkdeveloptool list || true
    read -rp "Board listed as Maskrom above? Type YES to flash: " ans
    [ "$ans" = "YES" ] || { echo "Aborted."; exit 1; }

    if [ -n "$LOADER" ]; then
        echo "Loading DDR init + SPL: $(basename "$LOADER")"
        rkdeveloptool db "$LOADER"
        sleep 2
    else
        echo "WARNING: no rk3506b_spl_loader.bin in $DEPLOY." >&2
    fi

    echo "Writing $IMG to eMMC..."
    rkdeveloptool wl 0 "$IMG"
    rkdeveloptool rd
    echo "Done. The board should reboot into Linux."
    ;;

*)
    sed -n '2,8p' "$0"
    exit 1
    ;;
esac
