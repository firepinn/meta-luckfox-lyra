# Init sequence for the Lyra PLC's 1.9" 170x320 ST7789V LCD, in the firmware
# format the kernel's generic MIPI DBI panel driver (panel-mipi-dbi) loads.
# The driver requests "<first DT compatible>.bin", i.e.
# /lib/firmware/luckfox,lyra-plc-lcd.bin.

SUMMARY = "Lyra PLC LCD (ST7789V 170x320) init firmware for panel-mipi-dbi"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://mipi-dbi-cmd.py \
    file://luckfox,lyra-plc-lcd.txt \
"

S = "${UNPACKDIR}"

inherit python3native allarch

COMPATIBLE_MACHINE = "^(luckfox-lyra-plc)$"

do_configure[noexec] = "1"

do_compile() {
    ${PYTHON} ${S}/mipi-dbi-cmd.py ${S}/luckfox,lyra-plc-lcd.txt ${B}/luckfox,lyra-plc-lcd.bin
}

do_install() {
    install -d ${D}${nonarch_base_libdir}/firmware
    install -m 0644 ${B}/luckfox,lyra-plc-lcd.bin ${D}${nonarch_base_libdir}/firmware/
}

FILES:${PN} = "${nonarch_base_libdir}/firmware/luckfox,lyra-plc-lcd.bin"
