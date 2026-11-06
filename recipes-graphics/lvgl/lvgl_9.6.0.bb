SUMMARY = "Light and Versatile Graphics Library"
DESCRIPTION = "LVGL is an OSS graphics library to create embedded GUI"
HOMEPAGE = "https://lvgl.io/"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://LICENCE.txt;md5=4570b6241b4fced1d1d18eb691a0e083"

# v9.6.0, the last v9 release. meta-oe (wrynose) ships 9.4.0; its patches are all
# upstream by now. 9.6 installs its public headers under include/lvgl/, puts
# the lv_conf.h it was built with next to lv_conf_internal.h, and ships a
# pkg-config file (Cflags carry LV_CONF_INCLUDE_SIMPLE): applications use
# `pkg-config --cflags --libs lvgl` and #include <lvgl/lvgl.h>.
SRC_URI = " \
    git://github.com/lvgl/lvgl;protocol=https;branch=release/v9.6 \
    file://0001-build-cmake-generate-proper-.so-links-when-installin.patch \
"
SRCREV = "80ca777e37a2b176770726a02e07a6fb79ef0b39"

inherit cmake pkgconfig python3native

PACKAGECONFIG ??= "drm"
PACKAGECONFIG[drm] = ",,libdrm"
PACKAGECONFIG[fbdev] = ",,"

LVGL_CONFIG_USE_DRM = "${@bb.utils.contains('PACKAGECONFIG', 'drm', '1', '0', d)}"
LVGL_CONFIG_USE_FBDEV = "${@bb.utils.contains('PACKAGECONFIG', 'fbdev', '1', '0', d)}"
LVGL_CONFIG_USE_EVDEV = "${@bb.utils.contains_any('PACKAGECONFIG', 'drm fbdev', '1', '0', d)}"
LVGL_CONFIG_LV_MEM_SIZE ?= "(64 * 1024U)"
LVGL_CONFIG_LV_THEME_DEFAULT_DARK ?= "0"

EXTRA_OECMAKE = " \
    -DBUILD_SHARED_LIBS=ON \
    -DLV_BUILD_INSTALL=ON \
    -DCONFIG_LV_BUILD_DEMOS=OFF \
    -DCONFIG_LV_BUILD_EXAMPLES=OFF \
    -DLV_FETCH_DEPENDENCIES=OFF \
"

# lv_conf.h at the top of the source tree is the one the build picks up and
# installs. Start from the template and set what the image needs.
do_configure:prepend() {
    cp -L "${S}/lv_conf_template.h" "${S}/lv_conf.h"
    sed -r -i \
        -e 's|^#if 0 /\* Set this to "1" to enable content \*/|#if 1 /* enabled by ${PN} */|' \
        -e "s|^([[:space:]]*#define LV_MEM_SIZE[[:space:]]).*|\1${LVGL_CONFIG_LV_MEM_SIZE}|" \
        -e "s|^([[:space:]]*#define LV_USE_LINUX_DRM[[:space:]]).*|\1${LVGL_CONFIG_USE_DRM}|" \
        -e "s|^([[:space:]]*#define LV_USE_LINUX_FBDEV[[:space:]]).*|\1${LVGL_CONFIG_USE_FBDEV}|" \
        -e "s|^([[:space:]]*#define LV_USE_EVDEV[[:space:]]).*|\1${LVGL_CONFIG_USE_EVDEV}|" \
        -e "s|^([[:space:]]*#define LV_THEME_DEFAULT_DARK[[:space:]]).*|\1${LVGL_CONFIG_LV_THEME_DEFAULT_DARK}|" \
        "${S}/lv_conf.h"
    grep -q '^#if 1 /\* enabled by' "${S}/lv_conf.h" || bbfatal "lv_conf_template.h changed: cannot enable lv_conf.h"
    # a define the template no longer has would be skipped silently (9.6 has
    # no LV_COLOR_DEPTH any more: the display sets its colour format)
    for kv in "LV_MEM_SIZE ${LVGL_CONFIG_LV_MEM_SIZE}" \
              "LV_USE_LINUX_DRM ${LVGL_CONFIG_USE_DRM}" \
              "LV_USE_LINUX_FBDEV ${LVGL_CONFIG_USE_FBDEV}" \
              "LV_USE_EVDEV ${LVGL_CONFIG_USE_EVDEV}" \
              "LV_THEME_DEFAULT_DARK ${LVGL_CONFIG_LV_THEME_DEFAULT_DARK}"; do
        grep -Fq "#define $kv" "${S}/lv_conf.h" || bbfatal "lv_conf.h: could not set ${kv%% *}"
    done
}

FILES:${PN}-dev += "${libdir}/cmake/lvgl"
