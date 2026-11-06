# Luckfox Lyra Pi image: serial console, dual Ethernet with DHCP, OpenSSH
# with root/root, and userspace tools for every on-board peripheral.

SUMMARY = "Luckfox Lyra Pi Linux image"
DESCRIPTION = "Bootable image for the Luckfox Lyra Pi (RK3506B). Boots from \
eMMC (Model A) or microSD (Model B), brings both Ethernet ports up over DHCP, \
and allows root login over SSH with password 'root'."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

inherit core-image

# Boards with the Cortex-M0 sidecar in use (the Lyra Pi) get its firmware and
# remoteproc driver; the machine config turns this off where the M0 is unused.
LUCKFOX_HAS_M0 ??= "1"

# Board-specific additions on top of the shared set.
LUCKFOX_MACHINE_PKGS ??= ""
LUCKFOX_MACHINE_PKGS:luckfox-lyra-plc = "packagegroup-luckfox-lyra-plc"

COMPATIBLE_MACHINE = "^(luckfox-lyra-pi-a|luckfox-lyra-pi-b|luckfox-lyra-plc)$"

IMAGE_INSTALL = " \
    packagegroup-core-boot \
    packagegroup-luckfox-lyra-pi \
    packagegroup-luckfox-lyra-pi-wireless \
    ${@'packagegroup-luckfox-lyra-pi-m0' if d.getVar('LUCKFOX_HAS_M0') == '1' else ''} \
    ${LUCKFOX_MACHINE_PKGS} \
    kernel-modules \
    openssh \
    openssh-sftp-server \
    tzdata \
"

# ssh-server-openssh pulls in OpenSSH (not dropbear); allow-root-login rewrites
# sshd_config's PermitRootLogin, which otherwise defaults to prohibit-password
# and would reject password logins for root.
#
# Deliberately NOT using debug-tweaks: it implies empty-root-password, which
# would blank the root password we set below.
IMAGE_FEATURES += "ssh-server-openssh allow-root-login"

IMAGE_LINGUAS = "en-us"

# Keep the image compact — just enough headroom for first-boot writes before
# the growfs unit expands the partition to the whole medium. A big
# IMAGE_ROOTFS_EXTRA_SPACE here would only pad the image with zeroes and make
# flashing slower for no benefit.
IMAGE_ROOTFS_EXTRA_SPACE = "65536"

# --- root password -----------------------------------------------------------
# Set as a precomputed sha512-crypt hash of "root" rather than through
# extrausers' `usermod -P`: in current shadow, -P is --prefix, so passing a
# cleartext password there can silently set the wrong thing. Writing the hash
# directly is unambiguous.
#
# Reproduce with: openssl passwd -6 -salt lyrapiA0 root
#
# $rootpw is single-quoted so the shell does not expand the $6$ field, and is
# referenced without braces so bitbake does not treat it as a variable.
set_root_password() {
    rootpw='$6$lyrapiA0$Yy669CHRWRsYl4O8Kv7CpcX1jbUXOWNi.uH4hbHIDr0IE4L8gtbxjX/Ow7c/swwZdbX4RqJqFeu.oyzyURU/a/'

    if [ ! -e ${IMAGE_ROOTFS}${sysconfdir}/shadow ]; then
        bbfatal "no /etc/shadow in the rootfs — cannot set the root password"
    fi

    # '|' is safe as the sed delimiter: sha512-crypt output is [./0-9A-Za-z] only.
    sed -i "s|^root:[^:]*:|root:$rootpw:|" ${IMAGE_ROOTFS}${sysconfdir}/shadow

    if ! grep -q '^root:\$6\$' ${IMAGE_ROOTFS}${sysconfdir}/shadow; then
        bbfatal "failed to set the root password hash in /etc/shadow"
    fi
}

# --- networking --------------------------------------------------------------
# Both RMII GMACs get predictable names (end0/end1) under systemd. A single
# wildcard match covers them regardless of which port enumerates first, so the
# board is reachable over whichever cable is plugged in.
configure_networkd() {
    install -d ${IMAGE_ROOTFS}${sysconfdir}/systemd/network
    cat > ${IMAGE_ROOTFS}${sysconfdir}/systemd/network/20-wired.network <<'EOFNET'
[Match]
Name=en* eth*

[Network]
DHCP=yes
LLMNR=yes
MulticastDNS=yes

[DHCP]
UseDomains=yes
RouteMetric=10
# Identify by MAC, not by the machine-id-based DUID: each RAUC slot has its
# own machine-id, so with a DUID every slot switch got a new DHCP lease and
# the box moved to another IP after each update.
ClientIdentifier=mac
EOFNET

    # systemd-networkd and -resolved are built but not enabled by default.
    install -d ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/multi-user.target.wants
    ln -sf ${systemd_system_unitdir}/systemd-networkd.service \
        ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/multi-user.target.wants/systemd-networkd.service
    ln -sf ${systemd_system_unitdir}/systemd-resolved.service \
        ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/multi-user.target.wants/systemd-resolved.service

    install -d ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/sockets.target.wants
    ln -sf ${systemd_system_unitdir}/systemd-networkd.socket \
        ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/sockets.target.wants/systemd-networkd.socket
}

# --- grow the last partition --------------------------------------------------
# The last wic partition is /data on every board (after fixed-size root
# partitions: one on the Lyra Pi, the RAUC A/B pair on the PLC); expand it to
# the whole eMMC/SD on first boot.
LUCKFOX_GROWFS_MOUNT ??= "/data"
#
# The resize logic lives in a script rather than inline in ExecStart because
# systemd performs its own $VAR expansion on ExecStart arguments: an inline
# `$(findmnt ...)` / `$dev` would be substituted (to empty) before /bin/sh ever
# saw it, and the unit would fail in a confusing way.
install_growfs() {
    install -d ${IMAGE_ROOTFS}${sbindir}
    cat > ${IMAGE_ROOTFS}${sbindir}/luckfox-growfs <<'EOFSH'
#!/bin/sh
# Grow the last partition and its filesystem to fill the boot medium, once.
set -eu

MNT="${1:-/}"
STAMP="$MNT/.luckfox-growfs.done"
[ -e "$STAMP" ] && exit 0

dev=$(findmnt -no SOURCE "$MNT")
name=$(basename "$dev")
disk="/dev/$(lsblk -no PKNAME "$dev")"
part=$(cat "/sys/class/block/$name/partition")

# sfdisk moves the partition end in the table without rereading it (parted
# refuses to touch a mounted partition); resizepart then tells the kernel the
# new size, and resize2fs grows the mounted filesystem online.
start=$(cat "/sys/class/block/$name/start")
echo ", +" | sfdisk --no-reread --no-tell-kernel -N "$part" "$disk"
size=$(sfdisk --dump "$disk" | sed -n "s|^$dev : start= *$start, size= *\([0-9]*\).*|\1|p")
[ -n "$size" ] || { echo "could not read the new size of $dev" >&2; exit 1; }
resizepart "$disk" "$part" "$size"
resize2fs "$dev"

touch "$STAMP"
EOFSH
    chmod 0755 ${IMAGE_ROOTFS}${sbindir}/luckfox-growfs

    install -d ${IMAGE_ROOTFS}${systemd_system_unitdir}
    cat > ${IMAGE_ROOTFS}${systemd_system_unitdir}/luckfox-growfs.service <<EOFSVC
[Unit]
Description=Grow ${LUCKFOX_GROWFS_MOUNT} to fill the boot medium
DefaultDependencies=no
RequiresMountsFor=${LUCKFOX_GROWFS_MOUNT}
After=systemd-remount-fs.service
Before=sysinit.target
ConditionPathExists=!${@d.getVar('LUCKFOX_GROWFS_MOUNT').rstrip('/')}/.luckfox-growfs.done

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=${sbindir}/luckfox-growfs ${LUCKFOX_GROWFS_MOUNT}

[Install]
WantedBy=sysinit.target
EOFSVC

    install -d ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/sysinit.target.wants
    ln -sf ${systemd_system_unitdir}/luckfox-growfs.service \
        ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/sysinit.target.wants/luckfox-growfs.service
}

# --- WiFi / Bluetooth ---------------------------------------------------------
# The AIC8800DC is USB-attached, so there is no DT node to trigger a modprobe;
# the two modules are loaded explicitly at boot. On a non-W board they load,
# find no a69c:88dc device, and do nothing.
#
# Optionally bake in credentials by setting these in conf/local.conf:
#   LUCKFOX_WIFI_SSID = "my-ssid"
#   LUCKFOX_WIFI_PSK  = "my-psk"
#   LUCKFOX_WIFI_COUNTRY = "BE"
# Left unset, a commented template is installed and wpa_supplicant stays
# disabled, so nothing fails on a board with no radio.
LUCKFOX_WIFI_SSID ?= ""
LUCKFOX_WIFI_PSK ?= ""
LUCKFOX_WIFI_COUNTRY ?= "00"

configure_wireless() {
    install -d ${IMAGE_ROOTFS}${sysconfdir}/modules-load.d
    cat > ${IMAGE_ROOTFS}${sysconfdir}/modules-load.d/aic8800.conf <<'EOFMOD'
# AIC8800DC WiFi (USB). aic_load_fw uploads firmware; aic8800_fdrv claims the
# device and exposes wlan0. Bluetooth comes from the in-kernel btusb driver.
aic_load_fw
aic8800_fdrv
EOFMOD

    # DHCP on wlan0, at a higher metric than wired so Ethernet wins when both
    # are up.
    install -d ${IMAGE_ROOTFS}${sysconfdir}/systemd/network
    cat > ${IMAGE_ROOTFS}${sysconfdir}/systemd/network/25-wireless.network <<'EOFWL'
[Match]
Name=wlan*

[Network]
DHCP=yes
LLMNR=yes
MulticastDNS=yes
IgnoreCarrierLoss=3s

[DHCP]
UseDomains=yes
RouteMetric=20
ClientIdentifier=mac
EOFWL

    install -d ${IMAGE_ROOTFS}${sysconfdir}/wpa_supplicant
    if [ -n "${LUCKFOX_WIFI_SSID}" ]; then
        # Quoted heredoc: bitbake still substitutes ${...}, but the shell does
        # not touch a PSK containing $ or backticks.
        # Built with printf rather than a heredoc: a wpa_supplicant network
        # block closes with "}" in column 0, which bitbake's shell-function
        # parser would read as the end of this function. Quoting it here keeps
        # the brace out of column 0 in the recipe while still emitting it.
        #
        # The values are substituted by bitbake, not the shell, so a PSK
        # containing $ or a backtick is passed through literally.
        printf '%s\n' \
            "ctrl_interface=/run/wpa_supplicant" \
            "update_config=1" \
            "country=${LUCKFOX_WIFI_COUNTRY}" \
            "" \
            "network={" \
            "	ssid=\"${LUCKFOX_WIFI_SSID}\"" \
            "	psk=\"${LUCKFOX_WIFI_PSK}\"" \
            "}" \
            > ${IMAGE_ROOTFS}${sysconfdir}/wpa_supplicant/wpa_supplicant-nl80211-wlan0.conf
        chmod 0600 ${IMAGE_ROOTFS}${sysconfdir}/wpa_supplicant/wpa_supplicant-nl80211-wlan0.conf

        install -d ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/multi-user.target.wants
        ln -sf ${systemd_system_unitdir}/wpa_supplicant-nl80211@.service \
            ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/multi-user.target.wants/wpa_supplicant-nl80211@wlan0.service
    else
        cat > ${IMAGE_ROOTFS}${sysconfdir}/wpa_supplicant/wpa_supplicant-nl80211-wlan0.conf <<'EOFWPA2'
ctrl_interface=/run/wpa_supplicant
update_config=1
country=00

# No credentials were baked in at build time. Add a network and enable the
# service to bring WiFi up:
#
#   wpa_passphrase "my-ssid" "my-psk" >> \
#       /etc/wpa_supplicant/wpa_supplicant-nl80211-wlan0.conf
#   systemctl enable --now wpa_supplicant-nl80211@wlan0
EOFWPA2
        chmod 0600 ${IMAGE_ROOTFS}${sysconfdir}/wpa_supplicant/wpa_supplicant-nl80211-wlan0.conf
    fi
}

# --- root SSH key ---------------------------------------------------------------
# Optional: point this at a public key file in conf/local.conf to log in as
# root without the password, e.g.
#   LUCKFOX_ROOT_AUTHORIZED_KEYS = "/home/me/.ssh/id_ed25519.pub"
LUCKFOX_ROOT_AUTHORIZED_KEYS ?= ""

install_root_authorized_keys() {
    keys="${LUCKFOX_ROOT_AUTHORIZED_KEYS}"
    [ -n "$keys" ] || return 0
    [ -r "$keys" ] || bbfatal "LUCKFOX_ROOT_AUTHORIZED_KEYS=$keys is not readable"
    install -d -m 0700 ${IMAGE_ROOTFS}${ROOT_HOME}/.ssh
    install -m 0600 "$keys" ${IMAGE_ROOTFS}${ROOT_HOME}/.ssh/authorized_keys
}
# Rebuild the rootfs when the key file changes.
do_rootfs[file-checksums] += "${@'%s:True' % d.getVar('LUCKFOX_ROOT_AUTHORIZED_KEYS') if d.getVar('LUCKFOX_ROOT_AUTHORIZED_KEYS') else ''}"

ROOTFS_POSTPROCESS_COMMAND += "set_root_password; configure_networkd; configure_wireless; install_growfs; install_root_authorized_keys; "

# --- SSH host keys on /data ---------------------------------------------------
# /etc belongs to the image: each RAUC slot (PLC) or each reflash (Lyra Pi) got
# a new host key, and ssh clients then refused the "changed" host. sshd_check_keys
# generates whatever HostKey points at, so point it into /data (shared by both
# slots) and generate only once /data is mounted.
persist_ssh_host_keys() {
    sed -i 's|^HostKey /etc/ssh/|HostKey /data/ssh/|' ${IMAGE_ROOTFS}${sysconfdir}/ssh/sshd_config
    grep -q '^HostKey /data/ssh/' ${IMAGE_ROOTFS}${sysconfdir}/ssh/sshd_config || \
        bbfatal "persist_ssh_host_keys: no HostKey line in sshd_config to move"
    for unit in sshdgenkeys.service sshd.service sshd@.service; do
        install -d ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/$unit.d
        printf '[Unit]\nRequiresMountsFor=/data\n' > ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/$unit.d/data-host-keys.conf
    done
}
ROOTFS_POSTPROCESS_COMMAND += "persist_ssh_host_keys; "

# --- hardware watchdog ---------------------------------------------------------
# systemd feeds /dev/watchdog while it runs and keeps it armed through a reboot:
# a hung system resets after RuntimeWatchdogSec, a reboot that stalls on the way
# down after RebootWatchdogSec. Boards without an enabled watchdog ignore this.
configure_watchdog() {
    install -d ${IMAGE_ROOTFS}${sysconfdir}/systemd/system.conf.d
    printf '[Manager]\nRuntimeWatchdogSec=30s\nRebootWatchdogSec=60s\n' \
        > ${IMAGE_ROOTFS}${sysconfdir}/systemd/system.conf.d/10-watchdog.conf
}
ROOTFS_POSTPROCESS_COMMAND += "configure_watchdog; "
IMAGE_INSTALL:append = " luckfox-data"
