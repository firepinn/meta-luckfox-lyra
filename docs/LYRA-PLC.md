# Luckfox Lyra PLC — hardware map

Machine: `luckfox-lyra-plc` · Device tree: `rk3506b-luckfox-lyra-plc.dts`

Luckfox publishes no schematic, board DTS or kernel source for the PLC (the
Lyra SDK `Luckfox_Lyra_SDK_202610.tar.gz` has no PLC entry). Everything here was
read off a running board (Luckfox Debian, kernel 6.1.99): its live device tree,
debugfs pinmux/gpio, `/userdata/plc_config.json`, and a disassembly of the
board's `fb_st7789v` driver. Wiki and app sources are cross-references:

- https://wiki.luckfox.com/Luckfox-Lyra-PLC/OpenPLC-Pinout (terminals, headers)
- https://github.com/luckfox-eng29/plc_app (Luckfox's WebUI/PLC runtime, GPL-2.0)
- https://github.com/Luckfox-eng51/luckfox-lyra-plc-examples (RS485 test tool)

## SoC-side map

| Function | Linux device | SoC pins | Notes |
|---|---|---|---|
| RS232 | `/dev/ttyS1` (uart1) | TX GPIO0_B2 (rm_io10), RX GPIO0_B3 (rm_io11) | |
| RS485 / RS422 | `/dev/ttyS2` (uart2) | TX GPIO0_B6 (rm_io14), RX GPIO0_C0 (rm_io16) | One port; mode set by wiring |
| RS485 driver enable | RTS of ttyS2 | **GPIO0_B7** (gpio0 line 15, `RS485_DE`) | Active high, kernel-driven (em485). On at boot; `rs485ctl /dev/ttyS2 off` for RS422 |
| CAN (FD) | `can0` | TX GPIO0_B4 (rm_io12), RX GPIO0_B5 (rm_io13) | Luckfox default 250 kbit/s |
| Relay 0 | gpio0 line 17, `RELAY0` | GPIO0_C1 | Active high, board pull-down |
| Relay 1 | gpio0 line 18, `RELAY1` | GPIO0_C2 | Active high, board pull-down |
| LCD (ST7789V, 170x320) | DRM `panel-mipi-dbi`, `/dev/fb0` (XRGB8888) | SPI0: CLK GPIO0_A1, MOSI GPIO0_A4, CS0 GPIO0_A3; DC GPIO0_A2; RST GPIO0_A0 | Write-only SPI. Column offset 35, row 0. RST must be `GPIO_ACTIVE_HIGH` in DT |
| LCD backlight | `/sys/class/backlight/backlight` | PWM0 ch2 on GPIO0_A5 (rm_io5) | 25 µs period, inverted |
| Touch (CST816S) | input "Hynitron CST816x Series Touchscreen" | I2C1 @0x15 (SCL GPIO0_B1, SDA GPIO0_B0); INT GPIO0_A7 (falling); RST GPIO0_A6 (low) | |
| RTC (TCS8563, PCF8563-compatible) | `rtc0` | I2C2 @0x51 | RTC battery not included (Luckfox spec): "low voltage detected" until one is fitted |
| Temp/humidity (SHTC3) | hwmon `shtc3` | I2C2 @0x70 | |
| I2C2 (expansion header too) | `/dev/i2c-2` | SCL GPIO1_C3 (rm_io28), SDA GPIO1_D1 (rm_io29) | |
| I2C0 (empty) | `/dev/i2c-0` | SCL GPIO1_D2 (rm_io30), SDA GPIO1_D3 (rm_io31) | The header's "GPIO" pair |
| Debug console | `ttyS0` (uart0) | TX GPIO0_C6, RX GPIO0_C7 | 3.3 V, on the debug header |
| Ethernet ETH0/ETH1 | gmac0 / gmac1 | RMII, CH182H PHYs @MDIO 1; resets GPIO1_C0 (low) / GPIO1_C1 (high) | Same as Lyra Pi. PoE (kit) on ETH1 |
| WiFi/BT (AIC8800DC) | USB `a69c:88dc` behind CH334 hub on otg1 | BT power GPIO1_C5 | |
| USB-C | otg0, peripheral | | Power, adb/gadget, maskrom flashing |
| USB-A | otg1 via hub, host | | |
| Status LED | `work-led`, heartbeat | GPIO1_A0 | |
| USER button | SARADC ch1 (adc-keys, `KEY_VENDOR` = 360) | | Confirmed. Our mainline U-Boot does **not** enter loader mode on it; use the serial console + `rockusb 0 mmc 0` / `ums 0 mmc 0` |
| CPU rail | pwm-regulator | PWM0 ch0 on GPIO0_C5 (rm_io21) | 0.71–1.205 V; must be PWM, not fixed |
| eMMC 8 GB | `mmcblk0` | SDMMC 4-bit | No SD slot |

## Screw terminals (from the Luckfox pinout page)

Power/relay block, 1→11: DC+, DC+, DC−, DC−, PE, RELAY0 NO, COM, NC,
RELAY1 NO, COM, NC. Input 7–24 V DC (or 5 V on USB-C). Relay contacts
10 A 250 VAC / 10 A 30 VDC.

Comms block, 1→11: RS232_TX, RS232_RX, SGND, CAN_L, CAN_H, SGND, RS422_RB,
RS422_RA, RS422_TB/RS485_B, RS422_TA/RS485_A, PE.

Termination: three slide switches on the side, labelled RS485, RS422, CAN,
positions marked "120R" (terminator on) / "NC" (off). Not software-visible.
RS485 terminates A/B (terminals 9/10, also RS422 TX pair), RS422 the RX pair
RB/RA (7/8), CAN terminates CAN_H/CAN_L (5/4). Turn one on only when the PLC
is at a bus end; check with a meter (≈120 Ω across the pair, power off).

## Headers next to USB-C (3.3 V)

| Header | Pins |
|---|---|
| Debug | UART0 TX (GPIO0_C6), RX (GPIO0_C7), GND |
| I2C | I2C2 SCL (GPIO1_C3), SDA (GPIO1_D1), 3V3, GND — shared with RTC/SHTC3 |
| GPIO | GPIO1_D2, GPIO1_D3, 3V3, GND (also I2C0 if enabled) |

## Testing the field buses later

- RS232: `stty -F /dev/ttyS1 115200 raw -echo` both ends, or TX→RX loopback.
- RS485: kernel drives DE, so any tool works: `stty -F /dev/ttyS2 115200 raw`.
  Luckfox's own test: `rs485_test_plc -d /dev/ttyS2 ... --rs485 off --txen-line 15`
  (that bypasses the kernel; turn kernel RS485 off first if you use it).
- CAN: `ip link set can0 type can bitrate 250000 && ip link set can0 up`,
  `candump can0` / `cansend can0 123#DEADBEEF`. Self-test without a partner:
  `ip link set can0 type can bitrate 250000 loopback on`.
- Relays: `gpioset -c gpiochip0 RELAY0=1` (libgpiod 2) — audible click.

## Board-specific fixes in this layer (found while bringing the PLC up)

- **LCD dark with SPI traffic flowing**: panel reset must be `GPIO_ACTIVE_HIGH`
  in the DT (the MIPI DBI helpers drive reset as 1 = run).
- **LCD driver never autoloads**: the SPI modalias is `spi:lyra-plc-lcd`, so
  `/etc/modules-load.d/lyra-plc-panel.conf` loads `panel-mipi-dbi`.
- **RS485 DE never toggled**: the RK hardware RS-485 engine drives the UART's
  own RTS pin, which UART2 doesn't have on the PLC. Patch 0001 uses em485 when
  `rts-gpios` is set; patch 0002 fixes em485's TX-empty test under the Rockchip
  PTIME mode (LSR.THRE inverted; read DW USR.TFE). Measured: DE lo idle, hi
  while sending, lo again after.
- **Ethernet hot-plug**: a CH182H that sat without a cable never links when
  one is plugged in later. The CH182H driver power-cycles the PHY and restarts
  autoneg after 3 s without link (Luckfox does the same in phylib, SDK commit
  d20fb7ec6). WCH's public datasheet documents no power-saving register; the
  vendor register manual (CH182DS2) would be needed for a root-cause fix.
- **Bluetooth soft-blocked at boot**: udev rule unblocks it.

## Access and recovery

- USB-C is a CDC-NCM network gadget: board at `192.168.7.1`, host gets DHCP.
- Debug UART: U-Boot at 1500000 baud, kernel console at 115200.
- Reflash without buttons: stop U-Boot's countdown on the debug UART, then
  `ums 0 mmc 0` (eMMC appears as a USB disk on the host; `dd` the `.wic`).
  `rockusb 0 mmc 0` enumerates as 2207:350f, but rkdeveloptool 1.32 hangs/fails
  against this U-Boot.
- Never write the `.wic` over the running system's root partition: the board
  ends up in a reboot loop.
- Flash without root on the host: stop U-Boot, `dhcp`, and let it pull the
  image's mapped chunks over TFTP and `mmc write` them
  (`scripts/uboot-flash.py`, server `scripts/tftpd.py` in Docker on
  udp/69; firewalld blocks port 69 for a host process).

## Updates: RAUC A/B

eMMC layout (`files/wic/luckfox-lyra-plc-rauc.wks`):

| Where | What |
|---|---|
| 32 KiB | `u-boot-rockchip.bin` (raw; not updated by RAUC) |
| 14 MiB, 14.125 MiB | U-Boot environment + redundant copy, 128 KiB each |
| p1 `boot` (vfat) | `boot.scr` only: the RAUC bootchooser |
| p2 `rootfs_a`, p3 `rootfs_b` (ext4, 1.5 GiB) | slots A/B, each with its own `/boot/zImage` + dtb |
| p4 `data` (ext4) | `/data`, persistent across updates; grown to the rest of the eMMC on first boot |

Boot: U-Boot `bootflow scan` runs `boot.scr`, which walks `BOOT_ORDER`
("A B"), spends one `BOOT_x_LEFT` try (3 per slot) and boots that slot with
`rauc.slot=X`. `rauc-mark-good.service` resets the counter once Linux is up;
a slot that keeps failing runs out of tries and the other one boots.

Update:

    bitbake luckfox-plc-bundle        # signed verity bundle of luckfox-image
    scp .../luckfox-plc-bundle-luckfox-lyra-plc.raucb root@plc:/data/
    ssh root@plc 'rauc install /data/luckfox-plc-bundle-luckfox-lyra-plc.raucb && reboot'
    ssh root@plc 'rauc status'       # booted slot, which is good/active

Signing keys are development keys in `build-plc/conf/rauc-keys/` (outside the
layer); `ca.cert.pem` is the keyring on the target. Replace them for anything
beyond development.

Hand-deploying a file: it goes into the *booted* slot only (`/lib/firmware`,
`/boot/<dtb>` inside the rootfs). The next RAUC update replaces that slot's
contents, so anything that must survive goes in `/data` or into the image.
