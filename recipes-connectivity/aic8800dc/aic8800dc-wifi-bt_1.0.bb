# Out-of-tree AIC8800DC WiFi driver for the Luckfox Lyra Pi W.
#
# The chip is USB-attached (a69c:88dc) behind the on-board CH334F hub, because
# RK3506 has no SDIO peripheral. The Luckfox SDK ships an SDIO-only vendor
# driver which therefore cannot work here; this uses the community USB-clean
# fork, which claims that VID:PID and defaults to CONFIG_USB_SUPPORT/USB_BT.
#
# Builds two modules: aic_load_fw (firmware loader) and aic8800_fdrv (claims
# the USB device and exposes wlan0). Bluetooth is handled by the in-kernel
# btusb driver, so there is no separate AIC BT module.
#
# NOTE: this needs usb20_otg1 in host mode — see the board DTS.

SUMMARY = "AIC8800DC WiFi USB driver (USB-clean fork)"
DESCRIPTION = "Out-of-tree kernel modules for the AIC8800DC WiFi 6 chip over \
USB, as fitted to the Luckfox Lyra Pi W variants."
HOMEPAGE = "https://github.com/Kiborgik/aic8800dc-linux-patched"

LICENSE = "GPL-3.0-or-later"
LIC_FILES_CHKSUM = "file://LICENSE;md5=db95b6e40dc7d26d8308b6b7375637b6"

SRC_URI = "git://github.com/Kiborgik/aic8800dc-linux-patched;protocol=https;branch=main"
SRCREV = "ef97a943cde794131160f4591538297e3f5fe1a8"


inherit module

DEPENDS += "virtual/kernel"

COMPATIBLE_MACHINE = "^(luckfox-lyra-pi-a|luckfox-lyra-pi-b|luckfox-lyra-plc)$"

# The upstream Makefile defaults to CONFIG_PLATFORM_UBUNTU=y, i.e. plain Linux
# with no Android cfg80211 cutoffs — correct for this 6.1 kernel.
do_configure:append() {
    # Use request_firmware() rather than the driver's manual filp_open() of
    # aic_fw_path/name: that path is empty until rwnx_init_aic() runs, but the
    # firmware is needed earlier during the USB probe's bootrom-patch upload,
    # so the manual path always fails with -ENOENT.
    sed -i 's|^\s*CONFIG_USE_FW_REQUEST\s*[:=]*=\s*n|CONFIG_USE_FW_REQUEST = y|' \
        ${S}/drivers/aic8800/aic8800_fdrv/Makefile \
        ${S}/drivers/aic8800/aic_load_fw/Makefile
}

# DEBUG_PREFIX_MAP in KCFLAGS: the driver logs __FILE__, which otherwise bakes
# the absolute build path into the .ko (QA "contains reference to TMPDIR").
do_compile() {
    unset LDFLAGS
    oe_runmake -C ${STAGING_KERNEL_DIR} M=${S}/drivers/aic8800 \
        O=${STAGING_KERNEL_BUILDDIR} \
        ARCH=${ARCH} CROSS_COMPILE=${TARGET_PREFIX} \
        KCFLAGS='-Wno-error ${DEBUG_PREFIX_MAP}' \
        modules
}

do_install() {
    install -d ${D}${nonarch_base_libdir}/modules/${KERNEL_VERSION}/extra
    install -m 0644 ${S}/drivers/aic8800/aic_load_fw/aic_load_fw.ko \
        ${D}${nonarch_base_libdir}/modules/${KERNEL_VERSION}/extra/
    install -m 0644 ${S}/drivers/aic8800/aic8800_fdrv/aic8800_fdrv.ko \
        ${D}${nonarch_base_libdir}/modules/${KERNEL_VERSION}/extra/
}

FILES:${PN} = "${nonarch_base_libdir}/modules"

RDEPENDS:${PN} += "aic8800dc-firmware"

RPROVIDES:${PN} += " \
    kernel-module-aic-load-fw \
    kernel-module-aic-load-fw-${KERNEL_VERSION} \
    kernel-module-aic8800-fdrv \
    kernel-module-aic8800-fdrv-${KERNEL_VERSION} \
"
