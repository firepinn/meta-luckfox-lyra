# WiFi 6 / Bluetooth 5.2 support for the Luckfox Lyra Pi W variants.
#
# Installed unconditionally: the AIC8800DC is USB-attached, so on a non-W board
# the modules simply find no device. That keeps one image valid for both.

SUMMARY = "Luckfox Lyra Pi WiFi/Bluetooth support"
LICENSE = "MIT"

inherit packagegroup

PACKAGES = "${PN}"

RDEPENDS:${PN} = " \
    aic8800dc-wifi-bt \
    aic8800dc-firmware \
    wpa-supplicant \
    iw \
    wireless-regdb-static \
    bluez5 \
"

# Kept out of `world`. This packagegroup only means anything on a Lyra Pi, and
# its dependencies say so -- the AIC8800DC firmware needs the commercial
# LICENSE_FLAGS_ACCEPTED flag. A `world` build for
# an unrelated machine would otherwise fail on an unbuildable dependency, which
# is what yocto-check-layer builds to check the layer does no harm elsewhere.
EXCLUDE_FROM_WORLD = "1"
