# RK3506 Cortex-M0 sidecar — mechanism notes

Researched from https://github.com/nvitya/rk3506-mcu (pinned in `refs/rk3506-mcu`)
and cross-checked against our own kernel tree: what it takes to run a
real-time bus driver on the M0.

## Verified against OUR kernel (vendor 6.6.89), not just the upstream project

| Requirement | Status in our build |
|---|---|
| `CONFIG_REMOTEPROC` | **already `=y`** — no kernel config change needed |
| `HCLK_M0`, `STCLK_M0` | present in `rockchip,rk3506-cru.h` |
| `SRST_H_M0`, `SRST_M0_JTAG`, `SRST_HRESETN_M0_AC` | present |
| `mcu@` node in `rk3506.dtsi` | **absent — we must add it** |

## How the M0 is actually started

Start is two steps:

1. An **ATF SMC call** — `SIP_MCU_CFG (0x82000028)` with
   `BUSMCU_0_ID (0x00)` / `MCU_CODE_START_ADDR (0x01)` and the entry address.
   This maps the SRAM as TCM at M0 address 0 and sets the code start.

   **Careful with the window:** `rk3506_rproc.c` keeps the 32 KiB @
   `0xFFF84000` variant behind `#if 0`, so the ACTIVE one is **16 KiB @
   `0xFFF88000`** — the upstream README documents the disabled variant. The
   driver ioremaps these hardcoded values and ignores the DT `reg`.
2. A single PMU register write to release the M0 reset:
   `writel(0x00060004, PMU + 0x00C)`  (`PMU_INT_MASK_CON`:
   `mcu_rst_dis_cfg=1`, `glb_int_mask_mcu=0`)

Stop asserts it again: `writel(0x00060002, PMU + 0x00C)`.

So start/stop is cheap and repeatable; only the code-address mapping needs the
secure call. There is no open-source alternative to that SMC — it lives in
Rockchip's closed ARM Trusted Firmware.

## Memory map — this dictates how firmware must be linked

`my_da_to_va()` in the driver translates ELF device addresses:

| ELF `da` range | Maps to | Notes |
|---|---|---|
| `0x00000000` – `0x00003FFF` | SRAM `0xFFF88000` + offset | **link firmware at 0x0**, 16 KB max |
| `0x03C00000` (1 MB) | DDR shared memory | for M0 <-> Linux data exchange |

The firmware image must begin with the **Cortex-M0 vector table**. Loading uses
the standard `rproc_elf_load_segments` / `rproc_elf_sanity_check` /
`rproc_elf_get_boot_addr`, so a plain ELF works and **no resource table is
required**.

The 1 MB DDR window at `0x03C00000` is the natural transport for a bus
driver's frames.

## What the "TCM" actually is, and the cost of the 32 KiB window

Cortex-M0 has no architectural TCM (that is an M7/R-series feature). "TCM mode"
is Rockchip's term for mapping a slice of the SoC's **on-chip SRAM** into the
M0's low address space so the core sees code at address 0 -- which the Cortex-M
boot model requires, since it fetches the initial SP and reset vector from 0x0.
The ATF SMC `MCU_CODE_START_ADDR` programs that address map. "Tightly coupled"
here means on-chip SRAM rather than DDR, not the strict ARM sense.

Mainline U-Boot puts that SRAM at `CFG_IRAM_BASE = 0xFFF80000`.

The two code windows in `rk3506_rproc.c` are **not** 16 KiB versus a bigger
region bolted on top -- note the arithmetic:

```
16 KiB option:  0xFFF88000 + 0x4000 = 0xFFF8C000
32 KiB option:  0xFFF84000 + 0x8000 = 0xFFF8C000
```

Both **end at the same address**. The 32 KiB option extends the base *downward*,
consuming `0xFFF84000-0xFFF87FFF`. That is exactly the region the upstream
author wants to keep free -- his TODO is to load code at `0xFFF88000` precisely
so `0xFFF81000-0xFFF87FFF` remains available for M0 <-> Linux data exchange.
That is why 16 KiB is his default, even though his README describes `0xFFF84000`
as a working configuration.

**Why taking it is free for us:** we do not need SRAM for data exchange, because
this layer reserves 1 MiB of DDR at `0x03C00000` for that. The author had no
such region, so for him the SRAM was the only option.

What is *not* verified: whether the closed-source ATF/OP-TEE blob keeps anything
in `0xFFF84000-0xFFF87FFF` at runtime. Nothing in the Linux device tree claims
SRAM (the only `psram` nodes are external DSMC PSRAM), and the upstream author
ran code at that base, so the risk is low -- but it is not proven. If the M0
misbehaves only in the 32 KiB configuration, this is the first thing to suspect.

## Constraints to design around

1. **SRAM becomes inaccessible to Linux once the M0 is running.** The SMC that
   maps TCM takes the SRAM away from the A7 side, so firmware cannot be
   hot-patched in place while running. Sequence must be stop -> load -> start.
   This is the upstream project's main open issue.
2. **Linux gates "unused" clocks and will kill M0 firmware.** The Rockchip AMP
   example relies on peripheral clocks (UART4/TIMER5) left on by U-Boot; once
   Linux disables them the M0 stalls. Two ways out:
   - have the M0 firmware enable its own peripheral clocks, or
   - reference those clocks from the device tree so Linux keeps them enabled.
   **For our GPIO TX/RX firmware this is the main trap** — the GPIO bank clock
   must be kept alive, and the pins must be reserved so Linux pinctrl/gpio does
   not claim them.
3. **SWD debugging is not available** (undocumented enable path), so bring-up
   has to be observable another way — hence driving GPIOs, which we can watch
   externally, plus the SRAM/shared-memory counters readable via `devmem`.

## Firmware plan for the POC

Write minimal bare-metal M0 code rather than depend on the upstream project's
external VIHAL library (that repo ships only prebuilt ELFs, and only for the
Lyra Plus):

- linker script at `0x00000000`, vector table first, 16 KiB ceiling
- reset handler: enable GPIO bank clock, set pinmux, then toggle two pins
- a heartbeat counter in SRAM/shared memory as a second, scope-free proof

## Observed on hardware (Luckfox Lyra Pi B, vendor 6.6.89)

**Load + start works.** With the `mcu@fff84000` node present, `insmod
rk3506_rproc.ko` auto-loads `/lib/firmware/rk3506-m0.elf` and releases the
core:

```
/sys/class/remoteproc/remoteproc0/state    = running
/sys/class/remoteproc/remoteproc0/firmware = rk3506-m0.elf
```

and the firmware demonstrably executes — gpio0 `ext_port` (0xFF940070) shows
bits 2 and 3 changing between samples, driven by the M0 with Linux doing
nothing:

```
ext_port=0x00C00003  tx(bit2)=0 rx(bit3)=0
ext_port=0x00C0000F  tx(bit2)=1 rx(bit3)=1
ext_port=0x00D80007  tx(bit2)=1 rx(bit3)=0
```

**Stop and restart work** -- after the fix in
`recipes-kernel/rk3506-rproc/files/0002-restart-without-reloading-tcm.patch`.

Before that patch, writing `stop` then `start` to `remoteproc0/state` killed the
system outright: no network and no serial output at all, requiring a power
cycle. The cause was not stopping the core but remoteproc *reloading* it:
`rk3506_rproc_start()` asks the secure firmware to map the SRAM as the M0's TCM,
which takes that SRAM away from the A7 and does not give it back on stop, so the
next `rproc_elf_load_segments()` wrote into an unreachable window and took an
unrecoverable external abort.

Since the image is still resident in SRAM after a stop, a restart does not need
it rewritten. Loading once and skipping subsequent loads makes the lifecycle
work. Measured on hardware, sampling gpio0 `ext_port` (0xFF940070) fast enough
to beat the ~13 ms toggle:

| phase | `state` | low byte of ext_port |
|---|---|---|
| running | `running` | `03 07 0B 0F` -- all four (tx,rx) combinations |
| stopped | `offline` | `0F` only -- frozen, core halted |
| restarted | `running` | `03 07 0B 0F` -- toggling resumes |
| restarted again | `running` | `03 07 0B 0F` -- repeatable |

Uptime after two full cycles: 106 s, no hang.

Note on measurement: sampling at 1 s intervals showed a *static* value and
looked like the firmware was dead. It was aliasing -- the toggle period is
~13 ms. Sample faster than the signal before concluding anything from it.

### Remaining limitation: loading *different* firmware

Running M0 code out of **SRAM is a dead end** if firmware must be reloadable at
runtime. The Rockchip U-Boot code that this flow is derived from documents a
second option:

```
/* address map: map 0 to sram, enable TCM mode for sram
 * 0xfff84000 for sram
 * 0x03e00000 for ddr */
```

so `MCU_CODE_START_ADDR` accepts a **DDR** address. Loading the firmware into a
reserved DDR region instead should avoid the lockout entirely: DDR stays
accessible to Linux, so stop -> reload -> start becomes safe and repeatable.
That is the direction to take for a bus driver, where updating firmware
without a reboot matters.

Cost: DDR is slower than TCM and is not tightly coupled, so hard-real-time bus
timing would need measuring rather than assuming.

## Where to look for a "release the SRAM" call (and why there may not be one)

Reloading *different* M0 firmware without a reboot needs the secure firmware to
hand the SRAM back. Findings on where that would come from:

**1. The TEE owns the mapping — confirmed.** rkbin's own release notes
(`doc/release/RK3506_EN.md`, fetched with the blobs) list under
`rk3506_tee_v2.01.bin`: *"Increase MCU map region size."* So the MCU map region
is a TEE feature and it changes between blob versions. We ship
`rk3506_tee_v2.10.bin`, newer than that. The notes do not name any SMC.

**2. It is not in open source.** Upstream TF-A's
`plat/rockchip/common/rockchip_sip_svc.c` has **zero** MCU references — the MCU
SIP handling lives only in Rockchip's closed BL31. The vendor kernel header
exposes just four sub-configs (`CODE_START_ADDR`, `EXPERI_START_ADDR`,
`SRAM_START_ADDR`, `EXSRAM_START_ADDR`), none documented as releasing anything.

**3. The structural reason.** The mapping control is a *secure* register:
`SGRF_BASE = 0xff210000` on this SoC (mainline U-Boot), and nothing in the Linux
device tree maps the SGRF at all, because non-secure Linux cannot touch it. That
is why an SMC is required rather than a direct write.

**4. Which is why Rockchip loads the M0 from U-Boot.** U-Boot SPL runs secure,
so `fit_standalone_release()` can poke SGRF and CRU directly. The vendor AMP flow
therefore loads MCU firmware in the bootloader, and "reload" means reboot. A
Linux-side release call may simply never have been implemented, because the
intended design does not need one.

### Places still worth checking

| Source | Why |
|---|---|
| **RK3506 TRM** — `Rockchip_RK3506_TRM_Part_1_V1.2-20250811.pdf` at `github.com/DeciHD/rockchip_docs/tree/main/rk3506` | Authoritative. Would name the mapping register and confirm whether it is secure-only. This revision is newer than the one the rk3506-mcu author saw (he reported SRAM mapping control missing from his copy). |
| Rockchip's `u-boot` `next-dev`, other SoCs' `fit_standalone_release()` | Sibling SoCs may call sub-configs this one does not. |
| The `nvitya/rk3506-mcu` issue tracker | The author explicitly asks for help on exactly this and would likely hear from Rockchip. |
| Newer rkbin TEE blobs | Upstream rkbin now ships **`rk3506_tee_v2.50.bin`** (plus DDR v1.08, SPL v1.12, usbplug v1.04); this layer pins the commit carrying v2.10. The map region already changed once, so a later blob may add a release path. Worth a try before disassembly. |
| Disassembling the TEE blob | Last resort; the SMC dispatch table is in there. |

**Checked, and it is not there.** Rockchip's current public `next-dev`
`rockchip_smccc.h` lists every `SIP_MCU_CFG` sub-config, and the list has in
fact grown since the vendor tree this layer builds against -- it now also has
`MCU_UNCACHE_START_ADDR` (0x05) and `MCU_UNCACHE_END_ADDR` (0x06). Every one
of them, new ones included, configures an *address*. There is no stop, release
or unmap verb in Rockchip's public interface, which is consistent with their
own flow loading the M0 from (secure) U-Boot and never reloading it. So the
answer to "where is the undocumented reload command" is, as far as Rockchip's
own headers go: there isn't one -- and the way out is below, using a documented
sub-config rather than an undocumented one.

### The route that avoids the question

Run the M0 from **DDR** (`MCU_CODE_START_ADDR` accepts a DDR address — Rockchip's
own U-Boot comment gives `0x03e00000`). No TCM mapping is created, the SRAM is
never taken away, and reload works normally.

The reason to prefer TCM was jitter, and measurement weakens that objection.
`hclk_m0` is **187.5 MHz**, and SysTick counts that core clock -- *not* the
24 MHz `stclk_m0`, as first assumed here; clearing `CSR.CLKSOURCE` does not
change the count rate on this part. So a 25 us pulse is 4687 SysTick counts at
5.3 ns resolution against a +/-12 us budget: over three orders of magnitude of
margin. Cache-less DDR fetch would eat into that, but there is a great deal to
eat.

A cheap experiment, if a power cycle is acceptable on failure: re-issue
`SIP_MCU_CFG`/`MCU_CODE_START_ADDR` with a DDR address while the core is stopped,
and see whether the SRAM becomes A7-writable again.


## The M0's only diagnostic: the heartbeat block

This core has no console, no debugger attached, and no watchdog it is allowed
to use -- the SoC WDT belongs to the A7, and Cortex-M0 has no core-local one.
Firmware can therefore wedge with nothing noticing, and a hang during init is
completely opaque.

So firmware publishes a status block at the start of the shared DDR window
(`mcu_shmem@3c00000`) and the rproc driver exposes it (patch 0003):

    # cat /sys/devices/platform/fff84000.mcu/m0_status
    version=1 seq=2078588 loops=1039294 micros=36135992 restart_cause=0 \
      stage=99 isr_ticks=18664580 isr_max_cycles=130

The window is reserved `no-map`, so it is not System RAM, and `read()` on
`/dev/mem` fails with `EFAULT` ("Bad address" from `dd`). `mmap()` does work --
`devmem2 0x03c00000 w` returns the magic -- so this is a convenience, not the
only way in. It is worth having anyway: it needs no root `/dev/mem` access and
no offset arithmetic, it retries torn reads, and it puts the field layout in
one place shared with the firmware instead of in whoever's script reads it.

| Field | Meaning |
|---|---|
| `magic` | absent until firmware reaches its watchdog init; shown instead of the rest when unset |
| `loops` | main-loop iterations -- what a watchdog would have been kicked by |
| `micros` | the M0's own microsecond clock; compare against `/proc/uptime` to check its timebase |
| `stage` | last HAL init step entered, in call order; a value that sticks names the function that did not return |
| `isr_ticks` | SysTick ISR entries, so a live ISR can be told from a wedged one even before the main loop starts |
| `isr_max_cycles` | worst observed ISR duration in core clocks |
| `vuart_resyncs` | crossed vuart ring index pairs the M0 has repaired |

`stage` and `isr_ticks` between them separate the two failure modes that
otherwise look identical from the A7: a hung main thread with a healthy ISR,
and a hung ISR. Both were hit while porting a bus PHY to the M0, and neither
was diagnosable without this.

The A7 side can act on it, too: `loops` standing still while `isr_ticks`
advances means the firmware is stuck, and remoteproc can stop and restart the
core -- a strictly stronger recovery than a self-reset, since it can reload the
firmware rather than merely restart it.
