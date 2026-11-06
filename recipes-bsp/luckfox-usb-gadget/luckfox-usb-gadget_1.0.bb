SUMMARY = "USB-C network gadget for Luckfox RK3506 boards"
DESCRIPTION = "Configures a CDC-NCM gadget on the USB-C port at boot, with the \
board at 192.168.7.1 and a DHCP server for the host, so the board is reachable \
over the USB cable without any network."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://luckfox-usb-gadget.sh \
    file://luckfox-usb-gadget.service \
    file://30-usb-gadget.network \
"
S = "${UNPACKDIR}"

inherit systemd allarch

SYSTEMD_SERVICE:${PN} = "luckfox-usb-gadget.service"

do_install() {
    install -D -m 0755 ${S}/luckfox-usb-gadget.sh ${D}${sbindir}/luckfox-usb-gadget
    install -D -m 0644 ${S}/luckfox-usb-gadget.service ${D}${systemd_system_unitdir}/luckfox-usb-gadget.service
    install -D -m 0644 ${S}/30-usb-gadget.network ${D}${sysconfdir}/systemd/network/30-usb-gadget.network
}

FILES:${PN} += "${systemd_system_unitdir} ${sysconfdir}/systemd/network"
