# Generates the extlinux config that mainline U-Boot's bootstd/extlinux
# bootmeth reads from /extlinux/extlinux.conf on the boot partition.
#
# Written by this recipe rather than left to wic's bootimg-partition
# auto-generator: that generator emits an `fdtdir` entry and relies on U-Boot
# guessing the DTB filename, whereas the board DTB is named explicitly here.

SUMMARY = "extlinux boot configuration for Luckfox Lyra Pi"
DESCRIPTION = "Deploys extlinux.conf naming the board device tree, root device \
and console for the Luckfox Lyra Pi boot partition."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

inherit deploy nopackages

COMPATIBLE_MACHINE = "^(luckfox-lyra-pi-a|luckfox-lyra-pi-b|luckfox-lyra-plc)$"

# The generated file embeds the machine's DTB name and root device.
PACKAGE_ARCH = "${MACHINE_ARCH}"

do_fetch[noexec] = "1"
do_unpack[noexec] = "1"
do_patch[noexec] = "1"
do_configure[noexec] = "1"
do_install[noexec] = "1"

LUCKFOX_BOOTARGS ?= "root=${LUCKFOX_ROOT_DEV} rootfstype=ext4 rootwait rw \
console=${KERNEL_CONSOLE} earlycon=uart8250,mmio32,0xff0a0000,115200n8 panic=10"

do_compile() {
    if [ -z "${LUCKFOX_DTB}" ]; then
        bbfatal "LUCKFOX_DTB is not set for ${MACHINE}"
    fi

    cat > ${B}/extlinux.conf <<EOFCFG
default luckfox
label luckfox
    kernel /zImage
    fdt /${LUCKFOX_DTB}
    append ${LUCKFOX_BOOTARGS}
EOFCFG
}

do_deploy() {
    install -d ${DEPLOYDIR}
    install -m 0644 ${B}/extlinux.conf ${DEPLOYDIR}/extlinux.conf
}

addtask deploy after do_compile before do_build
