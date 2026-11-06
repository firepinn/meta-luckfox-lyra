# Lyra PLC RAUC A/B bootchooser (U-Boot script, standard RAUC uboot scheme).
#
# Slot A = mmcblk0p2, slot B = mmcblk0p3; each slot carries its own kernel and
# device tree in /boot. BOOT_ORDER lists slots by priority; each try of a slot
# costs one BOOT_x_LEFT. rauc-mark-good.service resets the counter once Linux
# is up, so a slot that keeps failing runs out of tries and the next one boots.

test -n "${BOOT_ORDER}" || env set BOOT_ORDER "A B"
test -n "${BOOT_A_LEFT}" || env set BOOT_A_LEFT 3
test -n "${BOOT_B_LEFT}" || env set BOOT_B_LEFT 3

env set bootpart
env set raucslot
for BOOT_SLOT in "${BOOT_ORDER}"; do
  if test "x${bootpart}" != "x"; then
    # already picked a slot
  elif test "x${BOOT_SLOT}" = "xA"; then
    if test ${BOOT_A_LEFT} -gt 0; then
      echo "RAUC: trying slot A (${BOOT_A_LEFT} tries left)"
      setexpr BOOT_A_LEFT ${BOOT_A_LEFT} - 1
      env set bootpart 2
      env set raucslot A
    fi
  elif test "x${BOOT_SLOT}" = "xB"; then
    if test ${BOOT_B_LEFT} -gt 0; then
      echo "RAUC: trying slot B (${BOOT_B_LEFT} tries left)"
      setexpr BOOT_B_LEFT ${BOOT_B_LEFT} - 1
      env set bootpart 3
      env set raucslot B
    fi
  fi
done

if test -n "${bootpart}"; then
  env save
else
  echo "RAUC: no slot has tries left, resetting counters"
  env set BOOT_A_LEFT 3
  env set BOOT_B_LEFT 3
  env save
  reset
fi

load mmc 0:${bootpart} ${kernel_addr_r} /boot/zImage || reset
load mmc 0:${bootpart} ${fdt_addr_r} /boot/@DTB@ || reset
env set bootargs "root=/dev/mmcblk0p${bootpart} rootfstype=ext4 rootwait rw console=ttyS0,115200 earlycon=uart8250,mmio32,0xff0a0000,115200n8 panic=10 rauc.slot=${raucslot}"
bootz ${kernel_addr_r} - ${fdt_addr_r}
reset
