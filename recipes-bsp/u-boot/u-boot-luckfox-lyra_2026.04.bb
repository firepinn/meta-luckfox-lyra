# Mainline-based U-Boot 2026.04 for the Luckfox Lyra Pi (RK3506B).
#
# Mainline U-Boot already carries RK3506 SoC support (arch/arm/mach-rockchip/
# rk3506 + rk3506_common.h) but no board DT or defconfig yet. This pins the
# tree the RK3506 upstreaming work lives in, which adds the Lyra board DTs
# (rk3506b-luckfox-lyra-pi.dts + -u-boot.dtsi) on top of v2026.04.
#
# Boot chain: BootROM -> TPL (rkbin DDR init) -> SPL -> OP-TEE + U-Boot proper.
# binman packs all of that into a single u-boot-rockchip.bin, which is written
# at sector 64 (32 KiB) of the boot medium.

SUMMARY = "U-Boot 2026.04 (mainline + rk3506 board support) for Luckfox Lyra Pi"
DESCRIPTION = "Mainline-based U-Boot for the Rockchip RK3506B Luckfox Lyra Pi. \
Uses the Rockchip rkbin DDR init blob as the TPL stage and rkbin OP-TEE as \
the secure payload; binman emits a single flashable u-boot-rockchip.bin."
HOMEPAGE = "https://github.com/agrif/u-boot"
SECTION = "bootloaders"

LICENSE = "GPL-2.0-or-later"
LIC_FILES_CHKSUM = "file://Licenses/README;md5=2ca5f2c35c8cc335f0a19756634782f1"

# nobranch=1: the pinned commit is the tip of the rk3506 topic work and is not
# guaranteed to stay on any one named branch.
SRC_URI = " \
    git://github.com/agrif/u-boot.git;protocol=https;nobranch=1 \
    file://rauc-env.cfg \
"

# Kconfig fragments merged over the defconfig, per machine. The PLC uses RAUC
# A/B, which needs the environment on the eMMC (see rauc-env.cfg).
LUCKFOX_UBOOT_CFG_FRAGMENTS ??= ""
LUCKFOX_UBOOT_CFG_FRAGMENTS:luckfox-lyra-plc = "rauc-env.cfg"
SRCREV = "9e4c33c6d7ecd481d357cd3ae217a5ecfa8f8c89"

PV = "2026.04+git"

B = "${WORKDIR}/build"

DEPENDS += " \
    bc-native bison-native flex-native \
    dtc-native gnutls-native openssl-native \
    swig-native python3-native python3-setuptools-native \
    python3-pyelftools-native u-boot-tools-native \
    rkbin-rk3506 \
"

inherit deploy python3native pkgconfig nopackages

PROVIDES += "virtual/bootloader u-boot"

COMPATIBLE_MACHINE = "^(luckfox-lyra-pi-a|luckfox-lyra-pi-b|luckfox-lyra-plc)$"

EXTRA_OEMAKE = 'CROSS_COMPILE=${TARGET_PREFIX} CC="${TARGET_PREFIX}gcc ${TOOLCHAIN_OPTIONS} ${DEBUG_PREFIX_MAP}" V=1'
EXTRA_OEMAKE += 'HOSTCC="${BUILD_CC} ${BUILD_CFLAGS} ${BUILD_LDFLAGS}"'
EXTRA_OEMAKE += 'STAGING_INCDIR=${STAGING_INCDIR_NATIVE} STAGING_LIBDIR=${STAGING_LIBDIR_NATIVE}'

# Rockchip blobs staged into the recipe sysroot by rkbin-rk3506.
RKBIN_DIR = "${RECIPE_SYSROOT}${datadir}/rkbin"

do_configure[cleandirs] = "${B}"

# The tree ships a per-board defconfig that already selects the right device
# tree, Ethernet, MMC and the rockusb gadget used for maskrom flashing.
#
# It is applied as .config + olddefconfig rather than via `make <name>_defconfig`
# so that any symbol left unset in the defconfig takes its Kconfig default
# non-interactively instead of stopping the build for input.
do_configure() {
    install -d ${B}
    if [ ! -f "${S}/configs/${LUCKFOX_UBOOT_DEFCONFIG}" ]; then
        bbfatal "${LUCKFOX_UBOOT_DEFCONFIG} not found in the U-Boot tree"
    fi
    cp ${S}/configs/${LUCKFOX_UBOOT_DEFCONFIG} ${B}/.config
    for frag in ${LUCKFOX_UBOOT_CFG_FRAGMENTS}; do
        ${S}/scripts/kconfig/merge_config.sh -m -O ${B} ${B}/.config ${UNPACKDIR}/$frag
    done
    oe_runmake -C ${S} O=${B} olddefconfig

    # A fragment symbol that did not stick would silently lose the A/B state.
    for frag in ${LUCKFOX_UBOOT_CFG_FRAGMENTS}; do
        grep -E '^CONFIG_[A-Z0-9_]+=' ${UNPACKDIR}/$frag | while read -r line; do
            grep -qx "$line" ${B}/.config || bbfatal "U-Boot config did not keep: $line"
        done
    done

    # Fail early and clearly rather than producing an unbootable image.
    if ! grep -q '^CONFIG_DEFAULT_DEVICE_TREE="${LUCKFOX_UBOOT_DT}"' ${B}/.config; then
        bbfatal "U-Boot device tree is not ${LUCKFOX_UBOOT_DT} — wrong defconfig?"
    fi
    if ! grep -q '^CONFIG_ROCKCHIP_RK3506=y' ${B}/.config; then
        bbfatal "CONFIG_ROCKCHIP_RK3506 is not enabled"
    fi
}

do_compile() {
    if [ ! -f "${RKBIN_DIR}/${RKBIN_DDR_BIN}" ]; then
        bbfatal "DDR init blob missing: ${RKBIN_DIR}/${RKBIN_DDR_BIN}"
    fi
    # OP-TEE is only packed when the config selects SPL_OPTEE_IMAGE, which this
    # board's defconfig does not; passing TEE= regardless is harmless and keeps
    # the recipe correct if that is turned on later. So: warn, do not fail.
    if [ ! -f "${RKBIN_DIR}/${RKBIN_TEE_BIN}" ]; then
        bbwarn "OP-TEE blob missing: ${RKBIN_DIR}/${RKBIN_TEE_BIN}"
    fi

    oe_runmake -C ${S} O=${B} \
        ROCKCHIP_TPL="${RKBIN_DIR}/${RKBIN_DDR_BIN}" \
        TEE="${RKBIN_DIR}/${RKBIN_TEE_BIN}"

    if [ ! -f "${B}/u-boot-rockchip.bin" ]; then
        bbfatal "binman did not produce u-boot-rockchip.bin"
    fi
}

do_install[noexec] = "1"

do_deploy() {
    install -d ${DEPLOYDIR}
    install -m 0644 ${B}/u-boot-rockchip.bin ${DEPLOYDIR}/u-boot-rockchip.bin

    # Individual stages, handy for partial reflashing and for debugging.
    for f in idbloader.img u-boot.itb u-boot.bin u-boot.dtb; do
        [ -f "${B}/$f" ] && install -m 0644 ${B}/$f ${DEPLOYDIR}/ || true
    done
}

addtask deploy after do_compile before do_build
