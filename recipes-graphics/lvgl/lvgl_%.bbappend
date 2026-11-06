# Lyra PLC: draw straight to the panel's fbdev (/dev/fb0, 170x320 XRGB8888)
# and read touch/keys through evdev. Large Montserrat sizes for the clock app.
PACKAGECONFIG:luckfox-lyra-plc = "fbdev"
LVGL_CONFIG_LV_MEM_SIZE:luckfox-lyra-plc = "(256 * 1024U)"
LVGL_CONFIG_LV_THEME_DEFAULT_DARK:luckfox-lyra-plc = "1"

do_configure:append:luckfox-lyra-plc() {
    for size in 20 28 48; do
        sed -r -i "s|^([[:space:]]*#define LV_FONT_MONTSERRAT_${size}[[:space:]]+)0|\11|" "${S}/lv_conf.h"
        grep -Eq "^[[:space:]]*#define LV_FONT_MONTSERRAT_${size}[[:space:]]+1" "${S}/lv_conf.h" || \
            bbfatal "could not enable LV_FONT_MONTSERRAT_${size} in lv_conf.h"
    done
}
