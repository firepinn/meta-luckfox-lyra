SUMMARY = "RAUC A/B bootchooser script for the Lyra PLC"
DESCRIPTION = "U-Boot boot.scr that picks rootfs slot A or B from the \
BOOT_ORDER/BOOT_x_LEFT environment and boots its kernel and device tree."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://boot.cmd"
S = "${UNPACKDIR}"

DEPENDS = "u-boot-tools-native"

inherit deploy nopackages

COMPATIBLE_MACHINE = "^(luckfox-lyra-plc)$"
PACKAGE_ARCH = "${MACHINE_ARCH}"

do_configure[noexec] = "1"

do_compile() {
    sed "s|@DTB@|${LUCKFOX_DTB}|" ${S}/boot.cmd > ${B}/boot.cmd.out
    mkimage -A arm -O linux -T script -C none -n "Lyra PLC RAUC boot" \
        -d ${B}/boot.cmd.out ${B}/boot.scr
}

do_deploy() {
    install -D -m 0644 ${B}/boot.scr ${DEPLOYDIR}/boot.scr
}
addtask deploy after do_compile before do_build
