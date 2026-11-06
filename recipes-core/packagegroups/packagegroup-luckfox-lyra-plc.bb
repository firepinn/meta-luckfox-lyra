# Userspace for the Lyra PLC's extra peripherals: the LCD init firmware,
# RTC/sensor and display test tools.

SUMMARY = "Luckfox Lyra PLC board support"
DESCRIPTION = "LCD init firmware for the PLC's 1.9 inch ST7789V panel, plus \
tools to exercise the display, touch, RTC, temperature/humidity sensor, \
relays, RS232/RS485 and CAN from the target."
LICENSE = "MIT"

PACKAGE_ARCH = "${MACHINE_ARCH}"

inherit packagegroup

PACKAGES = "${PN}"

RDEPENDS:${PN} = " \
    lyra-plc-lcd-firmware \
    luckfox-usb-gadget \
    lyra-plc-board-config \
    kernel-image-zimage \
    kernel-devicetree \
    rauc \
    rauc-mark-good \
    rauc-service \
    lyra-plc-clock \
    lmsensors-sensors \
    util-linux-hwclock \
    libdrm-tests \
    fbset \
    python3-core \
    rk3506-rproc \
"
