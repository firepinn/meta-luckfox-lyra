# Firmware blobs for the AIC8800DC WiFi/BT chip on the Luckfox Lyra Pi W.
#
# Extracted from the Luckfox SDK. Installed in BOTH locations the driver may
# look in: the aic_load_fw default path is /lib/firmware, while parts of the
# WiFi driver append "/aic8800DC" to it. The whole set is under 500 KB, so
# shipping it twice is cheaper than getting the path wrong and having the
# bootrom patch upload fail with -ENOENT during USB probe.

SUMMARY = "AIC8800DC WiFi/BT firmware"
DESCRIPTION = "Proprietary firmware blobs for the AIC8800DC WiFi 6 / \
Bluetooth 5.2 combo chip fitted to the Luckfox Lyra Pi W variants."
LICENSE = "CLOSED"
LICENSE_FLAGS = "commercial"

SRC_URI = "file://aic8800dc_fw"

S = "${UNPACKDIR}/aic8800dc_fw"

do_configure[noexec] = "1"
do_compile[noexec] = "1"

do_install() {
    install -d ${D}${nonarch_base_libdir}/firmware/aic8800DC
    install -m 0644 ${S}/*.bin ${D}${nonarch_base_libdir}/firmware/aic8800DC/
    install -m 0644 ${S}/*.txt ${D}${nonarch_base_libdir}/firmware/aic8800DC/

    install -m 0644 ${S}/*.bin ${D}${nonarch_base_libdir}/firmware/
    install -m 0644 ${S}/*.txt ${D}${nonarch_base_libdir}/firmware/
}

FILES:${PN} = "${nonarch_base_libdir}/firmware"

COMPATIBLE_MACHINE = "^(luckfox-lyra-pi-a|luckfox-lyra-pi-b|luckfox-lyra-plc)$"

# Prebuilt vendor blobs.
INSANE_SKIP:${PN} = "already-stripped arch"
EXCLUDE_FROM_SHLIBS = "1"
