// SPDX-License-Identifier: MIT
/*
 * Lyra PLC clock: a large digital clock on the 170x320 LCD. Pressing the USER
 * button (adc-keys) plays a ring-burst animation; tapping the screen does too.
 *
 * Display: LVGL's Linux fbdev driver on /dev/fb0 (panel-mipi-dbi).
 * Touch:   LVGL's evdev driver on the CST816 input device.
 * Button:  read directly from the adc-keys evdev node; any key press counts.
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <lvgl/lvgl.h>

#define FBDEV        "/dev/fb0"
#define TOUCH_DEV    "/dev/input/by-path/platform-ff050000.i2c-event"
#define BUTTON_DEV   "/dev/input/by-path/platform-adc-keys-event"

static lv_obj_t *time_label;
static lv_obj_t *date_label;
static lv_obj_t *ring;
static lv_obj_t *hint_label;
static unsigned int presses;

static uint32_t tick_ms(void)
{
	struct timespec ts;

	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint32_t)(ts.tv_sec * 1000u + ts.tv_nsec / 1000000u);
}

static void update_clock(lv_timer_t *t)
{
	char buf[32];
	time_t now = time(NULL);
	struct tm tm;

	(void)t;
	localtime_r(&now, &tm);
	strftime(buf, sizeof(buf), "%H:%M", &tm);
	lv_label_set_text(time_label, buf);
	strftime(buf, sizeof(buf), ":%S", &tm);
	lv_label_set_text(hint_label, buf);
	strftime(buf, sizeof(buf), "%a %d %b %Y", &tm);
	lv_label_set_text(date_label, buf);
}

static void ring_size_cb(void *obj, int32_t v)
{
	lv_obj_set_size(obj, v, v);
	lv_obj_center(obj);
}

static void ring_opa_cb(void *obj, int32_t v)
{
	lv_obj_set_style_border_opa(obj, (lv_opa_t)v, 0);
}

static void label_y_cb(void *obj, int32_t v)
{
	lv_obj_align(obj, LV_ALIGN_CENTER, 0, v);
}

static void play_animation(void)
{
	static const lv_color_t palette[] = {
		LV_COLOR_MAKE(0x00, 0xc8, 0xff), LV_COLOR_MAKE(0xff, 0x6a, 0x00),
		LV_COLOR_MAKE(0x7c, 0xff, 0x4f), LV_COLOR_MAKE(0xff, 0x3d, 0xa8),
	};
	lv_color_t c = palette[presses++ % (sizeof(palette) / sizeof(palette[0]))];
	lv_anim_t a;

	lv_anim_delete(ring, NULL);
	lv_anim_delete(time_label, NULL);

	lv_obj_set_style_border_color(ring, c, 0);
	lv_obj_set_style_text_color(time_label, c, 0);

	/* Ring grows from the centre and fades out. */
	lv_anim_init(&a);
	lv_anim_set_var(&a, ring);
	lv_anim_set_exec_cb(&a, ring_size_cb);
	lv_anim_set_values(&a, 10, 300);
	lv_anim_set_duration(&a, 700);
	lv_anim_set_path_cb(&a, lv_anim_path_ease_out);
	lv_anim_start(&a);

	lv_anim_set_exec_cb(&a, ring_opa_cb);
	lv_anim_set_values(&a, LV_OPA_COVER, LV_OPA_TRANSP);
	lv_anim_start(&a);

	/* Time label hops up and settles back. */
	lv_anim_init(&a);
	lv_anim_set_var(&a, time_label);
	lv_anim_set_exec_cb(&a, label_y_cb);
	lv_anim_set_values(&a, -10, -40);
	lv_anim_set_duration(&a, 180);
	lv_anim_set_playback_duration(&a, 520);
	lv_anim_set_path_cb(&a, lv_anim_path_overshoot);
	lv_anim_start(&a);
}

static void screen_clicked(lv_event_t *e)
{
	(void)e;
	play_animation();
}

static void build_ui(void)
{
	lv_obj_t *scr = lv_screen_active();

	lv_obj_set_style_bg_color(scr, lv_color_black(), 0);
	lv_obj_set_style_bg_opa(scr, LV_OPA_COVER, 0);
	lv_obj_set_scrollable(scr, false);
	lv_obj_add_event_cb(scr, screen_clicked, LV_EVENT_CLICKED, NULL);

	ring = lv_obj_create(scr);
	lv_obj_remove_style_all(ring);
	lv_obj_set_style_radius(ring, LV_RADIUS_CIRCLE, 0);
	lv_obj_set_style_border_width(ring, 6, 0);
	lv_obj_set_style_border_opa(ring, LV_OPA_TRANSP, 0);
	lv_obj_set_clickable(ring, false);
	ring_size_cb(ring, 10);

	time_label = lv_label_create(scr);
	lv_obj_set_style_text_font(time_label, &lv_font_montserrat_48, 0);
	lv_obj_set_style_text_color(time_label, lv_color_white(), 0);
	lv_obj_align(time_label, LV_ALIGN_CENTER, 0, -10);

	hint_label = lv_label_create(scr);
	lv_obj_set_style_text_font(hint_label, &lv_font_montserrat_28, 0);
	lv_obj_set_style_text_color(hint_label, lv_palette_main(LV_PALETTE_GREY), 0);
	lv_obj_align(hint_label, LV_ALIGN_CENTER, 0, 36);

	date_label = lv_label_create(scr);
	lv_obj_set_style_text_font(date_label, &lv_font_montserrat_20, 0);
	lv_obj_set_style_text_color(date_label, lv_palette_lighten(LV_PALETTE_BLUE, 2), 0);
	lv_obj_align(date_label, LV_ALIGN_BOTTOM_MID, 0, -24);

	lv_obj_t *title = lv_label_create(scr);
	lv_label_set_text(title, "Lyra PLC");
	lv_obj_set_style_text_font(title, &lv_font_montserrat_20, 0);
	lv_obj_set_style_text_color(title, lv_palette_main(LV_PALETTE_GREY), 0);
	lv_obj_align(title, LV_ALIGN_TOP_MID, 0, 20);

	update_clock(NULL);
	lv_timer_create(update_clock, 250, NULL);
}

/* Drain the button device; return 1 if a key went down. */
static int button_pressed(int fd)
{
	struct input_event ev;
	int pressed = 0;

	while (read(fd, &ev, sizeof(ev)) == (ssize_t)sizeof(ev))
		if (ev.type == EV_KEY && ev.value == 1)
			pressed = 1;
	return pressed;
}

int main(void)
{
	lv_display_t *disp;
	int btn_fd;

	lv_init();
	lv_tick_set_cb(tick_ms);

	disp = lv_linux_fbdev_create();
	if (!disp) {
		fprintf(stderr, "lyra-plc-clock: cannot create fbdev display\n");
		return 1;
	}
	lv_linux_fbdev_set_file(disp, FBDEV);

	if (!lv_evdev_create(LV_INDEV_TYPE_POINTER, TOUCH_DEV))
		fprintf(stderr, "lyra-plc-clock: no touch input at %s\n", TOUCH_DEV);

	btn_fd = open(BUTTON_DEV, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
	if (btn_fd < 0)
		fprintf(stderr, "lyra-plc-clock: no button at %s: %s\n", BUTTON_DEV, strerror(errno));

	build_ui();

	for (;;) {
		uint32_t wait = lv_timer_handler();
		struct pollfd pfd = { .fd = btn_fd, .events = POLLIN, .revents = 0 };

		if (wait > 20 || wait == LV_NO_TIMER_READY)
			wait = 20;
		if (btn_fd >= 0) {
			if (poll(&pfd, 1, (int)wait) > 0 && button_pressed(btn_fd))
				play_animation();
		} else {
			usleep(wait * 1000);
		}
	}
	return 0;
}
