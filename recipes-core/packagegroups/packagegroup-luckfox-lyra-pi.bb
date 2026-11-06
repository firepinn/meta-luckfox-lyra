# Userspace tooling to exercise every Lyra Pi peripheral from the target.

SUMMARY = "Luckfox Lyra Pi board support tools"
DESCRIPTION = "Command-line tools for each peripheral exposed by the Luckfox \
Lyra Pi: dual Ethernet, eMMC/SD, USB, audio (ES8311), CAN, and the I2C / SPI \
/ PWM / ADC / GPIO signals on the 40-pin header."
LICENSE = "MIT"

inherit packagegroup

PACKAGES = "${PN}"

RDEPENDS:${PN} = " \
    ethtool \
    iproute2 \
    e2fsprogs \
    e2fsprogs-resize2fs \
    parted \
    mmc-utils \
    util-linux \
    usbutils \
    alsa-utils \
    alsa-states \
    can-utils \
    i2c-tools \
    spitools \
    libgpiod-tools \
    evtest \
    devmem2 \
    net-tools \
    rng-tools \
"
