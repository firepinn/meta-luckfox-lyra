# Rockchip proprietary boot blobs for RK3506B.
#
# The BootROM cannot initialise DDR on its own, so the DDR init blob is
# mandatory — it becomes the TPL stage inside U-Boot's u-boot-rockchip.bin.
# OP-TEE (rk3506_tee) is likewise a binary drop from Rockchip.
#
# SRCREV is the commit pinned by BOTH the Luckfox Lyra Ultra Yocto BSP and
# agrif/debian-lyra, i.e. two independent working RK3506 boot chains.

SUMMARY = "Rockchip binary boot blobs for RK3506B"
DESCRIPTION = "Proprietary DDR init, SPL, OP-TEE and USB downloader blobs \
from Rockchip's rkbin repository, plus the generated maskrom SPL loader \
used by rkdeveloptool to flash eMMC over USB-C."
HOMEPAGE = "https://github.com/rockchip-linux/rkbin"

LICENSE = "CLOSED"
LICENSE_FLAGS = "commercial"

SRC_URI = "git://github.com/rockchip-linux/rkbin.git;protocol=https;branch=master"
SRCREV = "74213af1e952c4683d2e35952507133b61394862"


COMPATIBLE_MACHINE = "^(luckfox-lyra-pi-a|luckfox-lyra-pi-b|luckfox-lyra-plc)$"

inherit deploy

# RK3506B blob selection. The DDR blob is the "b" silicon variant; the SPL,
# usbplug and TEE blobs are shared with RK3506G2.
RKBIN_DDR     = "bin/rk35/${RKBIN_DDR_BIN}"
RKBIN_SPL     = "bin/rk35/rk3506_spl_v1.11.bin"
RKBIN_USBPLUG = "bin/rk35/rk3506_usbplug_v1.03.bin"
RKBIN_TEE     = "bin/rk35/${RKBIN_TEE_BIN}"
RKBIN_MINIALL = "RKBOOT/RK3506BMINIALL.ini"

# Name of the maskrom loader produced by boot_merger.
RKBIN_LOADER = "rk3506b_spl_loader.bin"

do_configure[noexec] = "1"

# boot_merger is a prebuilt x86_64 host binary shipped in the rkbin tree.
# It packs the DDR init + SPL blobs into the single loader that the BootROM
# accepts over USB in maskrom mode. Best-effort: if it cannot run, the
# individual blobs are still deployed and the loader can be built by hand.
do_compile() {
    cd ${S}

    if [ ! -f "${S}/${RKBIN_MINIALL}" ]; then
        bbfatal "MINIALL ini missing at ${RKBIN_MINIALL} — rkbin SRCREV may be stale"
    fi
    for blob in "${RKBIN_DDR}" "${RKBIN_SPL}" "${RKBIN_USBPLUG}" "${RKBIN_TEE}"; do
        if [ ! -f "${S}/$blob" ]; then
            bbfatal "Required rkbin blob missing: $blob at SRCREV ${SRCREV}"
        fi
    done

    if [ -x "${S}/tools/boot_merger" ]; then
        # boot_merger resolves the paths inside the ini relative to the
        # rkbin root, so it must run from there.
        if "${S}/tools/boot_merger" "${S}/${RKBIN_MINIALL}"; then
            merged=$(find ${S} -maxdepth 2 -name 'rk3506*loader*.bin' | head -n1)
            if [ -n "$merged" ]; then
                cp "$merged" ${S}/${RKBIN_LOADER}
                bbnote "Generated maskrom loader from $merged"
            else
                bbwarn "boot_merger succeeded but no loader binary was found"
            fi
        else
            bbwarn "boot_merger failed; maskrom loader not generated"
        fi
    else
        bbwarn "tools/boot_merger not executable; maskrom loader not generated"
    fi
}

do_install() {
    install -d ${D}${datadir}/rkbin
    install -m 0644 ${S}/${RKBIN_DDR}     ${D}${datadir}/rkbin/
    install -m 0644 ${S}/${RKBIN_TEE}     ${D}${datadir}/rkbin/
    install -m 0644 ${S}/${RKBIN_USBPLUG} ${D}${datadir}/rkbin/
}

do_deploy() {
    install -d ${DEPLOYDIR}
    install -m 0644 ${S}/${RKBIN_DDR}     ${DEPLOYDIR}/
    install -m 0644 ${S}/${RKBIN_SPL}     ${DEPLOYDIR}/
    install -m 0644 ${S}/${RKBIN_USBPLUG} ${DEPLOYDIR}/
    install -m 0644 ${S}/${RKBIN_TEE}     ${DEPLOYDIR}/
    install -m 0644 ${S}/${RKBIN_MINIALL} ${DEPLOYDIR}/

    if [ -f "${S}/${RKBIN_LOADER}" ]; then
        install -m 0644 ${S}/${RKBIN_LOADER} ${DEPLOYDIR}/
    fi
}

addtask deploy after do_install

FILES:${PN} = "${datadir}/rkbin"

# Prebuilt vendor blobs, not built from source.
INSANE_SKIP:${PN} = "already-stripped"
EXCLUDE_FROM_SHLIBS = "1"
