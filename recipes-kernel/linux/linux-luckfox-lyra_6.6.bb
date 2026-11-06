# Rockchip vendor BSP kernel 6.6 for the Luckfox Lyra Pi (RK3506B).
#
# Mainline carries the RK3506 clk, reset and pinctrl drivers (v6.19+) but no
# device tree for the SoC, so there is nothing upstream that boots this board.
# The kernel therefore comes from Rockchip's vendor tree; the Luckfox Lyra Pi
# board DTs are supplied out-of-tree by this recipe.
#
# SRCREV is the revision that agrif/debian-lyra validates the Lyra Pi DTs
# against — at this commit the ARM DTs live under arch/arm/boot/dts/rockchip/.

SUMMARY = "Rockchip vendor BSP kernel 6.6 for Luckfox Lyra Pi (RK3506B)"
DESCRIPTION = "Linux 6.6.89 from Rockchip's vendor tree with the Luckfox Lyra Pi \
device trees (Model A eMMC and Model B microSD) and config fragments covering \
dual Ethernet, USB, audio, CAN, DSI, and the 40-pin header."
HOMEPAGE = "https://github.com/rockchip-linux/kernel"

LICENSE = "GPL-2.0-only"
LIC_FILES_CHKSUM = "file://COPYING;md5=6bc538ed5bd9a7fc9398086aedcd7e46"

# nobranch=1: the pinned revision is not guaranteed to stay at the tip of any
# named vendor branch. The SHA is fetchable directly from GitHub.
SRC_URI = " \
    git://github.com/rockchip-linux/kernel.git;protocol=https;nobranch=1 \
    file://rk3506_luckfox_defconfig \
    file://rk3506-luckfox-lyra-ultra.dtsi \
    file://rk3506b-luckfox-lyra-pi.dts \
    file://rk3506b-luckfox-lyra-pi-sd.dts \
    file://rk3506b-luckfox-lyra-pi-w.dts \
    file://rk3506b-luckfox-lyra-pi-w-sd.dts \
    file://rk3506b-luckfox-lyra-plc.dts \
    file://luckfox-lyra-pi.cfg \
    file://wifi-bt.cfg \
    file://ch182h.c \
    file://phy-ch182h.cfg \
    file://systemd.cfg \
    file://hynitron-cst816x.c \
    file://touch-cst816x.cfg \
    file://lyra-plc.cfg \
    file://0001-serial-8250_dwlib-use-em485-when-rts-gpios-drives-DE.patch \
    file://0002-serial-8250-em485-stop-tx-in-PTIME-mode.patch \
"
SRCREV = "1ba51b059f25533c5529b7f68186190b47d6a7b3"

# Must match the tree's Makefile VERSION.PATCHLEVEL.SUBLEVEL, otherwise OE
# installs modules under the wrong /lib/modules/<ver> and they never load.
# Kept exact so the kernel-version sanity check does its job -- an earlier
# revision of this recipe said 6.1 with KERNEL_VERSION_SANITY_SKIP set, which
# silently hid the mismatch.
LINUX_VERSION ?= "6.6.89"
# No LINUX_VERSION_EXTENSION: that is applied by kernel-yocto, and this recipe
# drives its own do_configure. The vendor defconfig leaves
# CONFIG_LOCALVERSION empty, so `uname -r` is a plain 6.6.89 and modules
# install to /lib/modules/6.6.89 -- which is what makes the out-of-tree
# AIC8800 modules loadable. Setting it here would only be misleading.
PV = "${LINUX_VERSION}+git"

DEPENDS += "openssl-native util-linux-native lz4-native"

inherit kernel

# Must come after `inherit kernel` — the class sets S itself, to
# STAGING_KERNEL_DIR. The git fetcher unpacks to ${UNPACKDIR}/${BP}
# (BB_GIT_DEFAULT_DESTSUFFIX); do_symlink_kernsrc then moves that tree to
# STAGING_KERNEL_DIR, exactly as it did with the old WORKDIR/git.
S = "${UNPACKDIR}/${BP}"

COMPATIBLE_MACHINE = "^(luckfox-lyra-pi-a|luckfox-lyra-pi-b|luckfox-lyra-plc)$"

# The vendor tree has buildpaths baked into its packaged source.
INSANE_SKIP:${PN}-src += "buildpaths"

# Stage the out-of-tree board DTs. No Makefile edit is needed: OE builds each
# KERNEL_DEVICETREE entry as an explicit make target and kbuild has a generic
# %.dtb: %.dts pattern rule, so the files just have to be present.
do_configure:prepend() {
    dtsdir="${S}/arch/arm/boot/dts/rockchip"
    if [ ! -d "$dtsdir" ]; then
        bbfatal "Expected $dtsdir to exist at SRCREV ${SRCREV} — vendor tree layout changed"
    fi
    for dt in ${UNPACKDIR}/*.dtsi ${UNPACKDIR}/*.dts; do
        [ -f "$dt" ] || continue
        install -m 0644 "$dt" "$dtsdir/"
    done

    # Add the CH182H PHY driver. It must be built in (not a module): stmmac is
    # built-in here, so a late-loading PHY module would lose the race and
    # phylib would keep the already-bound Generic driver.
    phydir="${S}/drivers/net/phy"
    install -m 0644 ${UNPACKDIR}/ch182h.c "$phydir/ch182h.c"

    if ! grep -q "CONFIG_CH182H_PHY" "$phydir/Makefile"; then
        printf 'obj-$(CONFIG_CH182H_PHY)\t+= ch182h.o\n' >> "$phydir/Makefile"
    fi

    if ! grep -q "config CH182H_PHY" "$phydir/Kconfig"; then
        # Insert before an existing entry so it lands inside the PHY menu
        # rather than after the closing endif.
        sed -i '/^config DAVICOM_PHY$/i \
config CH182H_PHY\
\ttristate "WCH CH182H PHY"\
\thelp\
\t  Supports the WCH CH182H 10/100 PHY. The part needs an analog\
\t  power-down/power-up cycle at init before it will operate; this\
\t  driver applies that quirk in .config_init.\
' "$phydir/Kconfig"
    fi
    grep -q "config CH182H_PHY" "$phydir/Kconfig" || bbfatal "failed to register CH182H_PHY in phy Kconfig"

    # Hynitron CST816S touch (Lyra PLC LCD), backported from mainline. The
    # vendor tree's hynitron_cstxxx/hyn drivers cover other Hynitron families.
    tsdir="${S}/drivers/input/touchscreen"
    install -m 0644 ${UNPACKDIR}/hynitron-cst816x.c "$tsdir/hynitron-cst816x.c"

    if ! grep -q "CONFIG_TOUCHSCREEN_HYNITRON_CST816X" "$tsdir/Makefile"; then
        printf 'obj-$(CONFIG_TOUCHSCREEN_HYNITRON_CST816X)\t+= hynitron-cst816x.o\n' >> "$tsdir/Makefile"
    fi

    if ! grep -q "config TOUCHSCREEN_HYNITRON_CST816X" "$tsdir/Kconfig"; then
        sed -i '/^config TOUCHSCREEN_HYNITRON_CSTXXX$/i \
config TOUCHSCREEN_HYNITRON_CST816X\
\ttristate "Hynitron CST816x touchscreen support"\
\tdepends on I2C\
\thelp\
\t  Hynitron CST816S single-touch controller (backported from\
\t  mainline Linux).\
' "$tsdir/Kconfig"
    fi
    grep -q "config TOUCHSCREEN_HYNITRON_CST816X" "$tsdir/Kconfig" || bbfatal "failed to register TOUCHSCREEN_HYNITRON_CST816X in touchscreen Kconfig"
}

# Config fragments merged on top of the Luckfox defconfig. The PLC adds its
# LCD, touch, RTC/sensor and CAN on top of the shared set.
LUCKFOX_KERNEL_CFGS = "luckfox-lyra-pi.cfg wifi-bt.cfg phy-ch182h.cfg systemd.cfg"
LUCKFOX_KERNEL_CFGS:append:luckfox-lyra-plc = " lyra-plc.cfg touch-cst816x.cfg"

# Symbols that would silently cost a PLC peripheral if a fragment failed to
# apply; checked in addition to the shared list below.
LUCKFOX_KERNEL_REQUIRED_SYMS = ""
LUCKFOX_KERNEL_REQUIRED_SYMS:luckfox-lyra-plc = "CONFIG_DRM_PANEL_MIPI_DBI CONFIG_TOUCHSCREEN_HYNITRON_CST816X \
    CONFIG_REGULATOR_PWM CONFIG_RTC_DRV_PCF8563 CONFIG_SENSORS_SHTC1 CONFIG_CANFD_RK3576 \
    CONFIG_BACKLIGHT_PWM CONFIG_DRM_FBDEV_EMULATION CONFIG_USB_DWC2_DUAL_ROLE CONFIG_USB_CONFIGFS_NCM \
    CONFIG_DM_VERITY CONFIG_SQUASHFS CONFIG_SQUASHFS_ZLIB CONFIG_BLK_DEV_LOOP"


# kernel.bbclass leaves defconfig handling to kernel-yocto, which we do not
# use here. Apply the Luckfox defconfig, merge the fragments, then let
# olddefconfig resolve dependencies non-interactively.
do_configure() {
    install -d ${B}
    cp ${UNPACKDIR}/rk3506_luckfox_defconfig ${B}/.config

    for cfg in ${LUCKFOX_KERNEL_CFGS}; do
        ${S}/scripts/kconfig/merge_config.sh -m -O ${B} ${B}/.config "${UNPACKDIR}/$cfg"
    done

    oe_runmake -C ${S} O=${B} olddefconfig

    # merge_config.sh warns but does not fail when a symbol cannot be set
    # (unmet dependency, renamed symbol). These are the ones that would
    # silently cost us a peripheral, so check them explicitly.
    for sym in CONFIG_STMMAC_ETH CONFIG_DWMAC_ROCKCHIP CONFIG_MMC_DW_ROCKCHIP \
               CONFIG_EXT4_FS CONFIG_USB_DWC2 CONFIG_SND_SOC_ES8311 \
               CONFIG_I2C_RK3X CONFIG_ROCKCHIP_SARADC \
               CONFIG_CFG80211 CONFIG_MAC80211 CONFIG_BT CONFIG_BT_HCIBTUSB \
               CONFIG_MODULES CONFIG_CH182H_PHY; do
        if ! grep -q "^${sym}=[ym]" ${B}/.config; then
            bbwarn "$sym is not enabled in the final kernel config"
        fi
    done

    for sym in ${LUCKFOX_KERNEL_REQUIRED_SYMS}; do
        if ! grep -q "^${sym}=[ym]" ${B}/.config; then
            bbfatal "$sym is required for ${MACHINE} but is not enabled in the final kernel config"
        fi
    done

    if ! grep -q "^CONFIG_EXT4_FS=y" ${B}/.config; then
        bbfatal "CONFIG_EXT4_FS must be built in — the rootfs is ext4 and there is no initramfs"
    fi
}
