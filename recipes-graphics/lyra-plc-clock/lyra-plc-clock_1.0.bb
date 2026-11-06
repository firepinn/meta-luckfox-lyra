SUMMARY = "Clock with a button-triggered animation on the Lyra PLC LCD (LVGL)"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://main.c \
    file://lyra-plc-clock.service \
"
S = "${UNPACKDIR}"

DEPENDS = "lvgl"

inherit systemd pkgconfig

COMPATIBLE_MACHINE = "^(luckfox-lyra-plc)$"

SYSTEMD_SERVICE:${PN} = "lyra-plc-clock.service"

do_compile() {
    ${CC} ${CFLAGS} ${LDFLAGS} -Wall -Wextra -Werror \
        $(pkg-config --cflags lvgl) \
        -o lyra-plc-clock ${S}/main.c $(pkg-config --libs lvgl) -lm
}

do_install() {
    install -D -m 0755 ${B}/lyra-plc-clock ${D}${bindir}/lyra-plc-clock
    install -D -m 0644 ${S}/lyra-plc-clock.service ${D}${systemd_system_unitdir}/lyra-plc-clock.service
}
