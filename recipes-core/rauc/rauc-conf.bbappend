# Board RAUC config. The keyring (development CA certificate) is not part of
# the layer: point FILESEXTRAPATHS at a directory holding ca.cert.pem from the
# build's local.conf, e.g.
#   FILESEXTRAPATHS:prepend:pn-rauc-conf := "${TOPDIR}/conf/rauc-keys:"
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
