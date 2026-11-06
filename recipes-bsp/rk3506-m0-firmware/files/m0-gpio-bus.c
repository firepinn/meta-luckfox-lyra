// SPDX-License-Identifier: MIT
/*
 * Minimal RK3506 Cortex-M0 firmware: drive two GPIOs as TX/RX.
 *
 * A starting point for running a bus driver on the M0 sidecar.
 * Deliberately dependency-free: no libc, no libgcc (no division, no floats,
 * no memcpy), so it can be built with the ordinary Linux cross-compiler using
 * -mcpu=cortex-m0 -mthumb -nostdlib.
 *
 * MEMORY MODEL
 *   The rk3506_rproc driver maps SRAM as the M0's TCM at M0-address 0, so this
 *   image is linked at 0x00000000, in a 32 KiB window at 0xFFF84000. Upstream
 *   disables that branch (`#if 0`), defaulting to 16 KiB at 0xFFF88000; this
 *   layer patches it on so firmware can grow past 16 KiB. The vector
 *   table must come first.
 *
 * GPIO
 *   gpio0 @ 0xFF940000, v2 controller: DR at +0x00, DDR at +0x08,
 *   EXT_PORT (input readback) at +0x70.
 *   Writes use a per-bit write-enable mask in the upper 16 bits, so touching
 *   pins 2 and 3 cannot disturb the pins Linux owns in the same bank.
 *
 *   TX = gpio0_A2 (pin 2), RX = gpio0_A3 (pin 3). Both verified UNCLAIMED by
 *   Linux pinctrl, and reserved to this node in the device tree so they stay
 *   that way. The DT also sets their mux to GPIO, so this code only has to
 *   drive direction and level.
 *
 *   For the POC both are driven as outputs at different rates, which proves
 *   independent control of two pins. In the real bus driver RX becomes an
 *   input; EXT_PORT is already the register that would read it.
 *
 * HOW TO OBSERVE IT WITHOUT A SCOPE
 *   Linux can read gpio0 EXT_PORT (0xFF940070) with devmem2 and watch bits 2
 *   and 3 change while the core runs, and stop changing when it is halted.
 */

#define GPIO0_BASE	0xFF940000u
#define GPIO_DR		0x00u
#define GPIO_DDR	0x08u

#define TX_PIN		2u
#define RX_PIN		3u

/* Per-bit write mask: value in the low half, mask in the high half. */
#define PIN_SET(pin)	(((1u << (pin)) << 16) | (1u << (pin)))
#define PIN_CLR(pin)	(((1u << (pin)) << 16) | 0u)
#define PIN_MASK_BOTH	((((1u << TX_PIN) | (1u << RX_PIN)) << 16) | \
			 ((1u << TX_PIN) | (1u << RX_PIN)))

#define REG(base, off)	(*(volatile unsigned int *)((base) + (off)))

/* Half-period in loop iterations. The M0 clock is not documented, so these
 * are chosen to be slow enough that a userspace devmem2 poll can see both
 * states rather than to hit an exact frequency.
 */
#define TX_HALF_PERIOD	400000u
#define RX_DIVIDER	5u

static void delay_loop(unsigned int iterations)
{
	/* volatile so the compiler cannot optimise the loop away */
	volatile unsigned int i = iterations;

	while (i)
		i--;
}

void _start(void)
{
	unsigned int tx_level = 0;
	unsigned int rx_level = 0;
	unsigned int ticks = 0;

	/* Both pins to output, without touching the rest of the bank. */
	REG(GPIO0_BASE, GPIO_DDR) = PIN_MASK_BOTH;

	for (;;) {
		tx_level = !tx_level;
		REG(GPIO0_BASE, GPIO_DR) = tx_level ? PIN_SET(TX_PIN)
						    : PIN_CLR(TX_PIN);

		/* RX toggles every RX_DIVIDER TX half-periods, so the two pins
		 * are clearly distinguishable from one another.
		 */
		ticks++;
		if (ticks >= RX_DIVIDER) {
			ticks = 0;
			rx_level = !rx_level;
			REG(GPIO0_BASE, GPIO_DR) = rx_level ? PIN_SET(RX_PIN)
							    : PIN_CLR(RX_PIN);
		}

		delay_loop(TX_HALF_PERIOD);
	}
}

/*
 * Cortex-M0 vector table. Must be at offset 0 of the image.
 * Only the initial stack pointer and the reset vector are needed; this
 * firmware enables no interrupts.
 *
 * The stack is placed at the top of the 32 KiB TCM window and grows down,
 * away from the code and data at the bottom.
 */
extern const void *const g_vectors[2];

__attribute__((section(".vectors"), used))
const void *const g_vectors[2] = {
	(const void *)0x00008000u,	/* initial SP: top of the 32 KiB TCM */
	(const void *)_start,		/* reset handler (thumb bit set by the
					 * compiler for a function address) */
};
