# Shared machine settings for Luckfox boards built on the Rockchip RK3506
# SoC family (triple Cortex-A7 + Cortex-M0 sidecar, 32-bit ARMv7-A).

require conf/machine/include/arm/armv7a/tune-cortexa7.inc

DEFAULTTUNE = "armv7athf-neon"

# RK3506 has no mainline device tree — only the clk/reset/pinctrl drivers
# landed upstream (v6.19+) — so the kernel is Rockchip's vendor 6.6 BSP.
# U-Boot, by contrast, IS mainline-based (2026.04 + in-flight rk3506 support).
OLDEST_KERNEL = "6.6"

PREFERRED_PROVIDER_virtual/kernel = "linux-luckfox-lyra"
PREFERRED_PROVIDER_virtual/bootloader = "u-boot-luckfox-lyra"

# UART0 @ 0xff0a0000, 8250-compatible. The Lyra DTS leaves Rockchip's
# fiq-debugger disabled, so the console is plain ttyS0, not ttyFIQ0.
SERIAL_CONSOLES = "115200;ttyS0"
KERNEL_CONSOLE = "ttyS0,115200"

KERNEL_IMAGETYPE = "zImage"

# RK3506B blob selection ("b" silicon; RK3506G2 uses the non-b DDR blob).
# Declared here so the rkbin and U-Boot recipes cannot drift apart.
RKBIN_SOC_VARIANT ?= "rk3506b"
RKBIN_DDR_BIN ?= "rk3506b_ddr_750MHz_v1.06.bin"
RKBIN_TEE_BIN ?= "rk3506_tee_v2.10.bin"

# U-Boot device tree. Mainline U-Boot has a single Lyra Pi DT (there is no
# -sd variant) since U-Boot only needs the one SDMMC controller either way.
LUCKFOX_UBOOT_DT ?= "rk3506b-luckfox-lyra-pi"

# Single SDMMC controller on RK3506, so both the eMMC and microSD variants
# enumerate as mmcblk0. p1 = boot (vfat), p2 = rootfs (ext4).
LUCKFOX_ROOT_DEV ?= "/dev/mmcblk0p2"

MACHINE_FEATURES = "usbhost usbgadget ext2 alsa rtc vfat"

MACHINE_EXTRA_RRECOMMENDS += "kernel-modules"

IMAGE_FSTYPES ?= "wic wic.bmap"

# KERNEL_DEVICETREE carries the in-tree "rockchip/" prefix, but OE flattens
# DTB names on deploy (KERNEL_DTBVENDORED=0), so the boot partition and the
# extlinux config must reference the bare basename.
# LUCKFOX_DTB_ALL ships every DTB variant for this storage type so extlinux
# can be repointed on the target without a rebuild; LUCKFOX_DTB is the one the
# generated extlinux.conf actually selects.
IMAGE_BOOT_FILES = " \
    zImage \
    ${LUCKFOX_DTB_ALL} \
    extlinux.conf;extlinux/extlinux.conf \
"

# wic otherwise injects a phantom /boot fstab entry that stalls
# local-fs.target for 90s and drops the board into emergency mode.
WIC_CREATE_EXTRA_ARGS = "--no-fstab-update"

WKS_FILE ?= "luckfox-lyra-pi.wks"

do_image_wic[depends] += " \
    u-boot-luckfox-lyra:do_deploy \
    luckfox-extlinux:do_deploy \
    virtual/kernel:do_deploy \
"

# In-tree U-Boot defconfig for this board.
LUCKFOX_UBOOT_DEFCONFIG ?= "luckfox-lyra-pi-rk3506b_defconfig"
