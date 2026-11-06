# Remote processor driver for the RK3506 Cortex-M0 sidecar.
#
# Rockchip provides no remoteproc support for the M0 on any RK35xx part; the
# vendor AMP flow loads MCU code from U-Boot instead, which cannot reload or
# restart it from a running system. This out-of-tree driver exposes the M0
# through the standard remoteproc interface
# (/sys/class/remoteproc/remoteproc0) so firmware can be loaded, started,
# stopped and restarted at runtime.
#
# How it starts the core (see docs/CORTEX-M0.md for the full trace):
#   1. ATF SMC  SIP_MCU_CFG(BUSMCU_0, MCU_CODE_START_ADDR, 0xFFF84000)
#      maps the SRAM as the M0's TCM at M0-address 0 and sets the entry point.
#   2. writel(0x00060004, PMU + 0x00C) releases the M0 reset.
# Stop re-asserts it with 0x00060002.
#
# Built as a module on purpose: unlike a PHY driver there is no probe race,
# and being able to unload/reload it is useful during bring-up.

SUMMARY = "Remoteproc driver for the RK3506 Cortex-M0"
DESCRIPTION = "Out-of-tree remoteproc driver exposing the Rockchip RK3506 \
Cortex-M0 sidecar via /sys/class/remoteproc, allowing bare-metal firmware to \
be loaded, started, stopped and restarted from Linux."
HOMEPAGE = "https://github.com/nvitya/rk3506-mcu"

LICENSE = "GPL-2.0-only"
LIC_FILES_CHKSUM = "file://../LICENSE;md5=64f00dfeea9fddbf2f4afe31c2967377"

SRC_URI = " \
    git://github.com/nvitya/rk3506-mcu;protocol=https;branch=main \
    file://0001-use-32k-code-tcm-window.patch \
    file://0002-restart-without-reloading-tcm.patch \
    file://0003-publish-m0-heartbeat-via-sysfs.patch \
    file://0004-fix-panic-on-module-unload.patch \
    file://0005-mailbox-doorbell-for-the-vuart-bridge.patch \
    file://0006-probe-whether-the-tcm-accepts-writes.patch \
"
SRCREV = "606d60451964697a31b5b6d0a0993f84d00fc541"
PV = "1.0+git"

# The module lives in a subdirectory of the repo.
S = "${UNPACKDIR}/${BP}/rk3506_rproc"

inherit module

DEPENDS += "virtual/kernel"

COMPATIBLE_MACHINE = "^(luckfox-lyra-pi-a|luckfox-lyra-pi-b|luckfox-lyra-plc)$"

# module.bbclass's default do_configure runs `make clean` in ${S}, which hits
# the upstream Makefile's clean target -> `make -C ./kernel-source/ clean` ->
# fails, because that symlink only exists in the author's own tree. We drive
# the kernel build ourselves below, so configure has nothing to do.
do_configure[noexec] = "1"

# The upstream Makefile hardcodes KDIR=./kernel-source and its own
# CROSS_COMPILE, so drive the kernel build directly instead of using it.
do_compile() {
    unset LDFLAGS
    oe_runmake -C ${STAGING_KERNEL_DIR} M=${S} \
        O=${STAGING_KERNEL_BUILDDIR} \
        ARCH=${ARCH} CROSS_COMPILE=${TARGET_PREFIX} \
        KCFLAGS='-Wno-error' \
        modules
}

do_install() {
    install -d ${D}${nonarch_base_libdir}/modules/${KERNEL_VERSION}/extra
    install -m 0644 ${S}/rk3506_rproc.ko \
        ${D}${nonarch_base_libdir}/modules/${KERNEL_VERSION}/extra/
}

FILES:${PN} = "${nonarch_base_libdir}/modules"

# kernel-module-split generates an RDEPENDS on the VERSION-SUFFIXED module
# name, so both spellings have to be provided or do_rootfs fails with
# "nothing provides kernel-module-rk3506-rproc-<kver>".
RPROVIDES:${PN} += " \
    kernel-module-rk3506-rproc \
    kernel-module-rk3506-rproc-${KERNEL_VERSION} \
"
