# Cortex-M0 sidecar support: remoteproc driver plus the firmware image.
#
# The M0 is exposed at /sys/class/remoteproc/remoteproc0 once the module is
# loaded. See docs/CORTEX-M0.md.

SUMMARY = "Luckfox Lyra Pi Cortex-M0 sidecar support"
LICENSE = "MIT"

inherit packagegroup

PACKAGES = "${PN}"

RDEPENDS:${PN} = " \
    rk3506-rproc \
    rk3506-m0-firmware \
    devmem2 \
"

# Kept out of `world`. This packagegroup only means anything on a Lyra Pi, and
# its dependencies say so -- rk3506-rproc is restricted by COMPATIBLE_MACHINE. A `world` build for
# an unrelated machine would otherwise fail on an unbuildable dependency, which
# is what yocto-check-layer builds to check the layer does no harm elsewhere.
EXCLUDE_FROM_WORLD = "1"
