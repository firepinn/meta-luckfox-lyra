SUMMARY = "Mount the persistent data partition at /data"
DESCRIPTION = "Every Luckfox Lyra board has a 'data' partition after its root \
filesystem (grown to fill the medium on first boot). It holds what must \
survive an image update or a RAUC slot switch: application settings and the \
SSH host keys. The system journal is kept there too."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://data.mount \
    file://journal-on-data.service \
    file://10-persistent.conf \
"
# UNPACKDIR on Yocto 5.1+, WORKDIR before: this recipe parses on both.
S = "${@d.getVar('UNPACKDIR') or d.getVar('WORKDIR')}"

inherit systemd allarch
SYSTEMD_SERVICE:${PN} = "data.mount journal-on-data.service"

do_install() {
    install -D -m 0644 ${S}/data.mount ${D}${systemd_system_unitdir}/data.mount
    install -d ${D}/data
    install -D -m 0644 ${S}/journal-on-data.service ${D}${systemd_system_unitdir}/journal-on-data.service
    install -D -m 0644 ${S}/10-persistent.conf ${D}${systemd_unitdir}/journald.conf.d/10-persistent.conf
}

FILES:${PN} = "${systemd_system_unitdir} ${systemd_unitdir}/journald.conf.d /data"
