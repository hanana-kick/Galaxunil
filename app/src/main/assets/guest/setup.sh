#!/bin/sh
set -eu
echo "[setup-steps] 3"
echo "[setup] Updating signed Debian repositories"
apt-get -o APT::Update::Error-Mode=any update
echo "[setup] Installing desktop runtime and AppStream reader"
DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends install \
  ca-certificates python3-yaml dbus pulseaudio pulseaudio-utils xkb-data libxkbcommon-tools \
  mesa-libgallium libgl1-mesa-dri libegl1 mesa-utils fonts-dejavu-core \
  fonts-noto-cjk librsvg2-bin
echo "[setup] Creating private desktop session"
if ! id user >/dev/null 2>&1; then useradd -m -u 1000 -U -s /bin/bash user; fi
mkdir -p /home/user /media/sdcard
xkbcli compile-keymap --layout us > /run/droidnative/keymap.xkb.tmp
mv /run/droidnative/keymap.xkb.tmp /run/droidnative/keymap.xkb
echo "[setup-end]"
