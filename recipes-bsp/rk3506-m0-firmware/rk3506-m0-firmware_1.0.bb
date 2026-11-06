# Bare-metal Cortex-M0 firmware for the Luckfox Lyra Pi.
#
# Drives two GPIOs as TX/RX: a starting point for running a bus driver on the
# M0 sidecar rather than on Linux.
#
# TOOLCHAIN
#   This needs a real bare-metal (arm-none-eabi) compiler: the ordinary Yocto
#   Linux cross-toolchain cannot target the M0 at all -- its assembler rejects
#   -march=armv6s-m ("invalid -march= option: `armv6s-m'"), because it is built
#   for armv7-a only. Hence the dependency on gcc-arm-none-eabi-native from
#   meta-arm-toolchain, which keeps the build reproducible instead of relying
#   on a host-installed toolchain.
#
# The image is linked at 0x00000000 and must fit the M0's 32 KiB TCM window
# (enabled by a patch in the rk3506-rproc recipe); see files/m0.ld.

SUMMARY = "Cortex-M0 GPIO TX/RX firmware for Luckfox Lyra Pi"
DESCRIPTION = "Minimal bare-metal firmware for the RK3506 Cortex-M0 sidecar. \
Drives gpio0_A2 as TX and gpio0_A3 as RX at distinguishable rates, as a \
starting point for a bus driver on the M0. Installed where the \
remoteproc driver's firmware-name expects it."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://m0-gpio-bus.c \
    file://m0.ld \
"

S = "${UNPACKDIR}"

DEPENDS = "gcc-arm-none-eabi-native"

# Installed under its own name; the name the device tree asks for is a
# symlink managed by update-alternatives (see the bottom of this recipe).
M0_FIRMWARE_NAME ?= "rk3506-m0-demo.elf"

COMPATIBLE_MACHINE = "^(luckfox-lyra-pi-a|luckfox-lyra-pi-b)$"

inherit deploy

# Bare-metal: no libc, no libgcc, no host headers. -ffreestanding and
# -nostdlib keep it that way, and the linker script ASSERTs there is no .bss
# (nothing zeroes it, since there is no startup code beyond the vector table).
M0_CFLAGS ?= "-mcpu=cortex-m0 -mthumb -Os -ffreestanding -nostdlib -fno-builtin \
-ffunction-sections -fdata-sections -Wall -Wextra -Werror"

do_configure[noexec] = "1"

do_compile() {
    arm-none-eabi-gcc ${M0_CFLAGS} \
        -T ${UNPACKDIR}/m0.ld \
        -o ${B}/${M0_FIRMWARE_NAME} \
        ${UNPACKDIR}/m0-gpio-bus.c

    # Fail loudly rather than shipping something the loader will reject:
    # the rproc driver only accepts ELF device addresses under 0x8000.
    arm-none-eabi-size ${B}/${M0_FIRMWARE_NAME}
    total=$(arm-none-eabi-size -A ${B}/${M0_FIRMWARE_NAME} | awk '/^Total/ {print $2}')
    if [ -z "$total" ] || [ "$total" -gt 32768 ]; then
        bbfatal "M0 firmware is ${total} bytes, exceeds the 32 KiB TCM window"
    fi
    bbnote "M0 firmware size: ${total} bytes of 32768"
}

do_install() {
    install -d ${D}${nonarch_base_libdir}/firmware
    install -m 0644 ${B}/${M0_FIRMWARE_NAME} \
        ${D}${nonarch_base_libdir}/firmware/${M0_FIRMWARE_NAME}
}

do_deploy() {
    install -d ${DEPLOYDIR}
    install -m 0644 ${B}/${M0_FIRMWARE_NAME} ${DEPLOYDIR}/
}
addtask deploy after do_compile

FILES:${PN} = "${nonarch_base_libdir}/firmware"

# The M0 autoboots on probe, and the firmware cannot be swapped afterwards:
# the TCM mapping means remoteproc refuses a firmware change while running,
# and a reload into a mapped TCM is what patch 0002 exists to prevent. So the
# choice is fixed at probe time by the device tree's firmware-name, which is a
# constant -- "rk3506-m0.elf".
#
# Rather than have every firmware recipe fight over that one filename, each
# installs under its own name and registers as an alternative for it. Install
# only this package and you get the demo; install another firmware package with a
# higher priority and it takes over. Either can be forced at runtime with
#   update-alternatives --set rk3506-m0.elf /lib/firmware/<name>
# followed by a reboot, since the M0 is started at probe.
inherit update-alternatives

ALTERNATIVE:${PN} = "rk3506-m0.elf"
ALTERNATIVE_LINK_NAME[rk3506-m0.elf] = "${nonarch_base_libdir}/firmware/rk3506-m0.elf"
ALTERNATIVE_TARGET[rk3506-m0.elf] = "${nonarch_base_libdir}/firmware/${M0_FIRMWARE_NAME}"
ALTERNATIVE_PRIORITY = "10"

# Bare-metal ELF for a different architecture: skip the usual QA.
INSANE_SKIP:${PN} = "arch ldflags already-stripped"
EXCLUDE_FROM_SHLIBS = "1"
