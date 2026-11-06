# RAUC update bundle for the Lyra PLC: the whole root filesystem (with its
# /boot kernel and device tree) for the inactive A/B slot.
#
#   bitbake luckfox-plc-bundle
#   scp tmp/deploy/images/luckfox-lyra-plc/luckfox-plc-bundle-luckfox-lyra-plc.raucb root@plc:/data/
#   ssh root@plc 'rauc install /data/luckfox-plc-bundle-luckfox-lyra-plc.raucb && reboot'
#
# Signing: RAUC_KEY_FILE / RAUC_CERT_FILE are set in the build's local.conf and
# must chain to the CA installed on the target (/usr/lib/rauc/ca.cert.pem).

SUMMARY = "Lyra PLC RAUC update bundle"
LICENSE = "MIT"

inherit bundle

COMPATIBLE_MACHINE = "^(luckfox-lyra-plc)$"

RAUC_BUNDLE_COMPATIBLE = "luckfox-lyra-plc"
RAUC_BUNDLE_FORMAT = "verity"
RAUC_BUNDLE_DESCRIPTION = "Luckfox Lyra PLC root filesystem"
RAUC_BUNDLE_VERSION = "${DISTRO_VERSION}-${DATETIME}"
RAUC_BUNDLE_VERSION[vardepsexclude] = "DATETIME"

RAUC_BUNDLE_SLOTS = "rootfs"
RAUC_SLOT_rootfs = "luckfox-image"
RAUC_SLOT_rootfs[fstype] = "ext4"
