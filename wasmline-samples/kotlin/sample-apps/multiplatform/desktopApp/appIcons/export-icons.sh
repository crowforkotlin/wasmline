#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
source_svg="$script_dir/wasmline-icon.svg"
linux_png="$script_dir/LinuxIcon.png"
macos_icns="$script_dir/MacosIcon.icns"
windows_ico="$script_dir/WindowsIcon.ico"
work_dir="$(mktemp -d)"

cleanup() {
    rm -rf "$work_dir"
}
trap cleanup EXIT

sips -s format png "$source_svg" --out "$work_dir/icon-1024.png" >/dev/null
sips -z 512 512 "$work_dir/icon-1024.png" --out "$linux_png" >/dev/null

iconset_dir="$work_dir/wasmline.iconset"
mkdir -p "$iconset_dir"
for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$work_dir/icon-1024.png" --out "$iconset_dir/icon_${size}x${size}.png" >/dev/null
    retina_size=$((size * 2))
    sips -z "$retina_size" "$retina_size" "$work_dir/icon-1024.png" --out "$iconset_dir/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$iconset_dir" -o "$macos_icns"

windows_sizes=(16 24 32 48 64 128 256)
ffmpeg_inputs=()
ffmpeg_maps=()
for index in "${!windows_sizes[@]}"; do
    size="${windows_sizes[$index]}"
    frame="$work_dir/windows-${size}.png"
    sips -z "$size" "$size" "$work_dir/icon-1024.png" --out "$frame" >/dev/null
    ffmpeg_inputs+=(-i "$frame")
    ffmpeg_maps+=(-map "$index:v:0")
done
ffmpeg -hide_banner -loglevel error -y "${ffmpeg_inputs[@]}" "${ffmpeg_maps[@]}" -c:v png "$windows_ico"
