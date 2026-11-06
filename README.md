# meta-luckfox-lyra

Yocto BSP layer for the **Luckfox Lyra Pi** (Rockchip RK3506B). Builds a
bootable image with OpenSSH, both Ethernet ports on DHCP,
driver coverage for the on-board peripherals, and a working Cortex-M0 sidecar.

Self-contained: the layer pins its own dependencies and carries its own build
templates, so you can delete everything except this repository, re-clone, and
rebuild.

## Quick start

```bash
git clone <this-repo> meta-luckfox-lyra
./meta-luckfox-lyra/scripts/setup.sh        # clones pinned deps, generates build/conf
. ./openembedded-core/oe-init-build-env build
bitbake luckfox-image
```

`setup.sh` clones bitbake, openembedded-core, meta-yocto, meta-openembedded,
meta-arm and meta-rauc as siblings at **pinned revisions** (Yocto 6.0
"wrynose"; there is no poky combined repository any more) and generates
`build/conf` from `conf/templates/default/`. It is idempotent, and it will not overwrite an existing `build/conf`.

This is a **pure BSP layer** (machines + recipes, no distro of its own), which
is what `yocto-check-layer` wants and keeps it easy to drop into an existing
distro. The distro-level policy the image needs — systemd, no x11/wayland, no
ptest — therefore lives in `conf/templates/default/local.conf.sample`, which is
part of the layer, so regenerating `build/conf` reproduces it exactly.

Prefer kas? `kas build meta-luckfox-lyra/kas/luckfox-lyra-pi.yml` — same
revisions.

Resulting layout:

```
<workspace>/
  bitbake/              pinned (yocto-6.0.4)
  openembedded-core/    pinned (yocto-6.0.4)
  meta-yocto/           pinned (yocto-6.0.4; meta-poky)
  meta-openembedded/    pinned (wrynose)
  meta-arm/             pinned (wrynose; meta-arm-toolchain)
  meta-rauc/            pinned (wrynose)
  meta-luckfox-lyra/    this layer
  build/                generated from conf/templates/default/
```

Host packages needed (Fedora/Nobara): `gawk make wget tar bzip2 gzip python3
unzip perl patch diffutils diffstat git cpp gcc gcc-c++ glibc-devel texinfo
chrpath ccache socat python3-pexpect findutils which file cpio python3-pip xz
python3-GitPython python3-jinja2 SDL-devel rpcgen mesa-libGL-devel zstd lz4
libacl rsync bc dtc` plus the usual `perl-*` modules.

## Machines

The RK3506 has a single SDMMC controller, wired to *either* eMMC or the card
slot depending on the model, so each needs its own machine:

| Machine | Model | Boot medium | Device tree |
|---|---|---|---|
| `luckfox-lyra-pi-a` | A / A-W | 8 GB eMMC (no card slot) | `rk3506b-luckfox-lyra-pi-w.dtb` |
| `luckfox-lyra-pi-b` | B / B-W | microSD (no eMMC) | `rk3506b-luckfox-lyra-pi-w-sd.dtb` |

Both boot the `-w` device tree, which is a strict superset of the plain one —
it only adds the wireless module's BT power-enable GPIO (`gpio1_PC5`, unused
otherwise). That is inert with no module fitted, so **one image covers W and
non-W boards**. Both DTBs ship in `/boot` if you would rather point extlinux at
the other.

## Flashing

```bash
./scripts/flash.sh sd /dev/sdX     # Model B
./scripts/flash.sh emmc            # Model A, over USB-C in maskrom mode
```

Model A has no card slot; hold **BOOT** while plugging in USB-C and flash with
[`rkdeveloptool`](https://github.com/rockchip-linux/rkdeveloptool). The
maskrom loader (`rk3506b_spl_loader.bin`) is generated into the deploy
directory by the `rkbin` recipe.

## Logging in

* **Serial:** UART0 on the 40-pin header, **115200 8N1**, device `ttyS0`. The
  Lyra DTS leaves Rockchip's fiq-debugger disabled, so this is a plain 8250
  port and *not* `ttyFIQ0`.
* **SSH:** `ssh root@<ip>` with a key: set
  `LUCKFOX_ROOT_AUTHORIZED_KEYS = "/path/to/key.pub"` in `conf/local.conf`.

Root login follows the Yocto defaults: root has no password, so neither the
console nor ssh accepts a password for it, and OpenSSH lets root in with a key
only. For a bench board, Yocto's development features open it up:

```
EXTRA_IMAGE_FEATURES += "allow-empty-password empty-root-password allow-root-login"
```

(root with an empty password on the console and over ssh -- never on an
untrusted network).

## How much is mainline?

| Component | Source | Mainline? |
|---|---|---|
| Build system | Yocto **6.0 wrynose** (OE-Core + meta-poky) + meta-openembedded | Upstream |
| U-Boot | **v2026.04** + in-flight RK3506 board support | Mainline-based |
| Kernel | Rockchip vendor **6.6.89** (`rockchip-linux/kernel`) | Vendor BSP |
| DDR init / OP-TEE | Rockchip `rkbin` blobs | Proprietary (unavoidable) |

**Why the kernel is not mainline.** Mainline has RK3506 `clk`, `reset`,
`pinctrl` *and* the GMAC driver (`rockchip,rk3506-gmac`), but **no device tree
at all** — no `rk3506.dtsi` in `master` or `linux-next`. So nothing upstream
boots this SoC. The missing piece for mainline is essentially just the DT.

**Why U-Boot is mainline.** Mainline already carries the RK3506 SoC port
(`arch/arm/mach-rockchip/rk3506/`, `include/configs/rk3506_common.h`); only the
board DT and defconfig are still in flight. This builds U-Boot 2026.04 plus
that work rather than Rockchip's vendor **2017.09**.

The DDR blob is not optional: the RK3506 BootROM cannot bring up DRAM itself.

## Boot chain

```
BootROM
  -> u-boot-rockchip.bin @ sector 64 (32 KiB) -- one binman-packed blob:
       TPL = rkbin DDR init, SPL = mainline U-Boot SPL, ITB = U-Boot proper
  -> /extlinux/extlinux.conf on the boot partition
  -> zImage + rk3506b-luckfox-lyra-pi*.dtb
  -> systemd
```

Layout (`files/wic/luckfox-lyra-pi.wks`): U-Boot raw at 32 KiB, `/boot` vfat at
16 MiB, `/` ext4 after it, grown to the medium on first boot.

## Peripheral status

Verified against the official schematic and the device tree.

**On-board and enabled:** eMMC/microSD, dual 10/100 Ethernet (`gmac0`+`gmac1`,
DHCP via networkd), USB-C OTG (`otg0`, gadget), 2x USB-A + `USB2` header
(`otg1`, host, behind the CH334F hub), ES8311 audio on the 3.5 mm jack, SARADC,
thermal, UART0 console, user LED, watchdog.

**Drivers built in, application-specific wiring left to you:** MIPI DSI (needs
a panel DT; the connector's `vcc3v3_lcd` rail is wired), CAN and RS-485 (**no
transceiver is fitted** — these are RMIO pin functions on the header, so an
external transceiver is required), the 40-pin header (RK3506 uses remappable
IO, so functions are assigned in software), M.2 4G LTE (USB-attached; `option`,
`qmi_wwan`, PPP built in), RTC (drivers built in; the DTS declares no RTC node).

**WiFi/BT:** AIC8800DC, **USB-attached** behind the CH334F hub (RK3506 has no
SDIO), so the Luckfox SDK's SDIO-only driver cannot work here. Uses the
USB-clean community driver plus firmware. Installed unconditionally — harmless
on a non-W board, where the modules find no device.

## Fixes carried by this layer

Each is documented in place with the measured evidence, because they are all
non-obvious.

1. **Ethernet: CH182H2 PHY power-cycle quirk** (`recipes-kernel/linux/files/ch182h.c`).
   Both ports came up dead from cold boot: PHYs answered MDIO, clocks were
   correct, autoneg was enabled — but `ANLPAR` stayed `0x0001`, i.e. nothing
   received. The part needs an analog power-down -> power-up transition before
   it will operate, and nothing in the boot path performs one (`phy_start` ->
   `genphy_resume` only *clears* `BMCR_PDOWN`, which was never set). A proper
   PHY driver applies the cycle in `.config_init`, which phylib calls from
   `phy_init_hw()` during `phy_attach`. Built in, not a module: `stmmac` is
   built-in, so a late-loading PHY module would lose the race to Generic PHY.
   Verified on hardware from a cold boot: `PHY [stmmac-0:01] driver
   [WCH CH182H]`, both ports `Link is Up - 100Mbps/Full` ~17 s in, with no
   userspace involvement.
2. **`clk_mac_refout` missing from the GMAC nodes.** `dwmac-rk` requests it for
   RMII + `clock_in_out = "output"` and enables it in `rk_gmac_powerup()`
   *before* the PHY reset, but the vendor DTS never supplies it. Necessary but
   not sufficient on its own.
3. **`usb20_otg1` set to `host`.** The vendor DTS in circulation says `otg`,
   but that port feeds the CH334F hub — a hub cannot enumerate in peripheral
   mode, which killed the USB-A ports *and* WiFi. The official Luckfox SDK also
   uses `host`, confirming this.
4. **cpufreq.** `vdd_cpu` had `vin-supply = <&vdd_cpu>` — pointing at itself, so
   the regulator never resolved and cpufreq-dt failed. It also declared a
   0.5–1.5 V *range* on a `regulator-fixed`, which reports only the minimum;
   no OPP accepts 0.5 V. Now a fixed 900 mV, chosen from the OPP table rather
   than guessed. There is no adjustable CPU rail on this board, so only
   frequency scales.
5. **adc-keys** had no child key node, so the driver returned `-EINVAL`. Also
   lowered `keyup-threshold` from 1800 mV to 1700 mV: channel 1 idles at
   ~1793 mV, *below* the vendor threshold, which would latch a phantom press.
6. **`CLK_MAC_OUT` rate conflict.** The vendor DTS assigned three different
   rates (25/25/**12.5** MHz) to one shared clock. Made consistent. The PHY
   nodes keep their `clocks` reference — that is what holds the clock enabled.

## Cortex-M0 sidecar

`docs/CORTEX-M0.md` has the full mechanism trace. In short: an out-of-tree
remoteproc driver exposes the M0 at `/sys/class/remoteproc/remoteproc0`;
firmware is linked at `0x00000000`, max **32 KiB**, and loaded into SRAM which
the driver maps as the M0's TCM via an ATF SMC. A 1 MiB DDR window at
`0x03C00000` is reserved for M0 <-> Linux data exchange.

Load, start, stop and restart are all verified working on hardware, repeatably,
with the M0 driving both pins while Linux is idle. Restart needed a fix: the SMC
that maps SRAM as TCM does not hand it back on stop, so remoteproc's
unconditional reload wrote into an unreachable window and hard-hung the A7. The
image is still resident after a stop, so the driver now loads once and skips
subsequent loads. Loading *different* firmware still needs a reboot — see
`docs/CORTEX-M0.md`.

The bundled MIT-licensed demo firmware drives `gpio0_A2` (TX) and `gpio0_A3`
(RX) at different rates — a starting point for a bus driver on the M0. Both pins were confirmed unclaimed by Linux pinctrl and are reserved in
the DT.

Because the M0 has no console, no attached debugger and no watchdog it may use,
firmware publishes a status block in the shared DDR window and the driver
exposes it at `/sys/devices/platform/fff84000.mcu/m0_status` — main-loop
counter, the M0's own microsecond clock, the last init step it entered and the
worst observed ISR duration. It is the only diagnostic this core has. (The
window is reserved `no-map`, so `read()` on `/dev/mem` fails with `EFAULT`;
`mmap` works, so `devmem2` can reach it too -- the sysfs attribute is the
convenient interface, not the only one.) Format and use in
`docs/CORTEX-M0.md`.

`mcu@fff84000` also holds the clocks and pinctrl for the peripherals the M0
owns, the same way Rockchip's `rk3506-amp.dtsi` does for its remote core —
necessary because an APB access from the M0 to an unclocked peripheral does not
fault, it stalls the bus and wedges the core silently.

Building M0 firmware needs a bare-metal toolchain: the Yocto Linux
cross-assembler cannot target the M0 (`invalid -march= option: 'armv6s-m'`),
hence the `meta-arm-toolchain` dependency.

## Provenance

The Lyra Pi device trees, kernel defconfig and U-Boot defconfig come from
[`agrif/debian-lyra`](https://github.com/agrif/debian-lyra); its U-Boot fork is
where the RK3506 mainline U-Boot work lives. The ethernet portion of those DTs
was diffed against the official Luckfox SDK and is **byte-identical**. Recipe
structure follows
[`OOHehir/luckfox-lyra-ultra-yocto`](https://github.com/OOHehir/luckfox-lyra-ultra-yocto)
for the sibling board. The M0 remoteproc driver is
[`nvitya/rk3506-mcu`](https://github.com/nvitya/rk3506-mcu). The `rkbin`
revision is the one both of the first two projects pin independently.

Board schematic: `Hardware/Schematic/Luckfox-Lyra-Pi.pdf` in
[`LuckfoxTECH/Luckfox-Lyra-docs`](https://github.com/LuckfoxTECH/Luckfox-Lyra-docs).

## Maintainer and patches

Maintainer: firepinn

Open a pull request or an issue.
Please keep the layer's existing conventions:

- One logical change per patch, with the commit message saying *why* rather
  than restating the diff.
- Anything carried against an upstream project goes in as a real patch file in
  `recipes-*/*/files/`, with a commit message stating the symptom, the cause and
  the evidence -- not as a `sed` in a recipe task. The three patches under
  `recipes-kernel/rk3506-rproc/files/` are the pattern to follow.
- Hardware claims need hardware evidence. Every fix listed above was verified on
  a board, and several earlier attempts were discarded because measurement
  contradicted them; the register dumps and timings in the commit messages and
  in `docs/` are there so the next person can re-check rather than re-derive.
- Run `yocto-check-layer` before sending. It needs the dependency layers passed
  explicitly and its own build directory:

      yocto-check-layer --dependency \
          ../meta-openembedded/meta-oe ../meta-arm/meta-arm-toolchain \
          -- ../meta-luckfox-lyra

  Do not set `MACHINE` in the environment when running it: it builds `world`
  for the default machine first, and a Lyra Pi `MACHINE` makes that baseline
  fail before the layer is even tested.


## License

Layer metadata is MIT (`COPYING.MIT`). Three things in the tree are not:

- `recipes-kernel/linux/files/*.dts*` and `rk3506_luckfox_defconfig` are derived
  from Rockchip / Luckfox sources and carry their original
  `GPL-2.0-or-later OR MIT` dual license.
- `recipes-kernel/linux/files/ch182h.c` is `GPL-2.0-only`, as a Linux driver
  must be.
- `recipes-connectivity/aic8800dc/files/` holds redistributable-at-best
  proprietary AIC8800DC firmware blobs, vendored so that wireless works out of
  the box on the `-W` board variants.

That last one is the real obstacle to publishing this layer as-is: the blobs
have no clear redistribution grant, and vendoring binaries into layer metadata
is not how upstream layers ship firmware. The fix is to fetch them from a
declared upstream URL with checksums instead, at the cost of the recipe needing
network access. Nothing else in the layer depends on them -- the non-wireless
A and B boards never pull that recipe in.
