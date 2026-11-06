SUMMARY = "Luckfox Lyra PLC boot-time board configuration"
DESCRIPTION = "Loads the LCD panel driver at boot, unblocks Bluetooth, and \
provides rs485ctl to show or switch the kernel RS-485 mode of a serial port."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://lyra-plc-panel.conf \
    file://80-lyra-plc-bluetooth.rules \
    file://rs485ctl.c \
    file://fw_env.config \
"
S = "${UNPACKDIR}"

PACKAGE_ARCH = "${MACHINE_ARCH}"

COMPATIBLE_MACHINE = "^(luckfox-lyra-plc)$"

do_compile() {
    ${CC} ${CFLAGS} ${LDFLAGS} -Wall -Wextra -Werror -o ${B}/rs485ctl ${S}/rs485ctl.c
}

do_install() {
    install -D -m 0644 ${S}/fw_env.config ${D}${sysconfdir}/fw_env.config
    install -D -m 0755 ${B}/rs485ctl ${D}${bindir}/rs485ctl
    install -D -m 0644 ${S}/lyra-plc-panel.conf ${D}${sysconfdir}/modules-load.d/lyra-plc-panel.conf
    install -D -m 0644 ${S}/80-lyra-plc-bluetooth.rules ${D}${nonarch_base_libdir}/udev/rules.d/80-lyra-plc-bluetooth.rules
}

FILES:${PN} = "${bindir} ${sysconfdir} ${nonarch_base_libdir}/udev/rules.d"
