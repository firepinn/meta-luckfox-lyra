/* SPDX-License-Identifier: MIT
 *
 * Link the RK3506 Cortex-M0 firmware at address 0.
 *
 * The rk3506_rproc driver maps the SRAM at 0xFFF84000 as the M0's TCM at
 * M0-address 0 (via an ATF SMC), and its da_to_va() only accepts ELF device
 * addresses below RK3506_MCU_TCM_SIZE for that window. So the image must be
 * linked at 0, with the vector table first.
 *
 * Size: 32 KiB @ 0xFFF84000. Upstream ships that branch behind `#if 0` (making
 * the default a 16 KiB window at 0xFFF88000); this layer patches it on, so
 * firmware can grow past 16 KiB. See the rk3506-rproc recipe's patch.
 */
MEMORY
{
	TCM (rwx) : ORIGIN = 0x00000000, LENGTH = 32K
}

ENTRY(_start)

SECTIONS
{
	.text : {
		KEEP(*(.vectors))	/* vector table must come first */
		*(.text*)
		*(.rodata*)
	} > TCM

	.data : {
		*(.data*)
	} > TCM

	/* Kept only to catch accidental use: this firmware has no startup code
	 * to zero .bss, so anything landing here would be uninitialised.
	 */
	.bss (NOLOAD) : {
		*(.bss*)
		*(COMMON)
	} > TCM

	ASSERT(SIZEOF(.bss) == 0,
	       "firmware has .bss but no startup code to zero it")
}
