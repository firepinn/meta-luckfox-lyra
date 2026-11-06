#!/bin/sh
# Expose a CDC-NCM network interface on the USB-C port via configfs, so the
# board is reachable over the cable alone. The board end (usb0) runs a DHCP
# server from systemd-networkd; the host gets an address automatically.
set -eu

G=/sys/kernel/config/usb_gadget/luckfox
UDC=$(ls /sys/class/udc | head -n1)
[ -n "$UDC" ] || { echo "no UDC: USB-C controller not in device mode" >&2; exit 1; }

mountpoint -q /sys/kernel/config || mount -t configfs none /sys/kernel/config
[ -d "$G" ] && exit 0

serial=$(tr -d '\0' < /proc/device-tree/serial-number 2>/dev/null || true)
[ -n "$serial" ] || serial=$(cat /etc/machine-id)

mkdir -p "$G"
echo 0x1d6b > "$G/idVendor"     # Linux Foundation
echo 0x0104 > "$G/idProduct"    # Multifunction Composite Gadget
echo 0x0200 > "$G/bcdUSB"
mkdir -p "$G/strings/0x409"
echo "Luckfox" > "$G/strings/0x409/manufacturer"
cat /proc/device-tree/model | tr -d '\0' > "$G/strings/0x409/product"
echo "$serial" > "$G/strings/0x409/serialnumber"

mkdir -p "$G/configs/c.1/strings/0x409"
echo "NCM" > "$G/configs/c.1/strings/0x409/configuration"
echo 250 > "$G/configs/c.1/MaxPower"

mkdir -p "$G/functions/ncm.usb0"
ln -s "$G/functions/ncm.usb0" "$G/configs/c.1/"

echo "$UDC" > "$G/UDC"
