import os
import sys
import re
import json
import urllib.request
import zipfile
import lzma
import hashlib

def get_sha256(data):
    return hashlib.sha256(data).hexdigest().upper()

def contains_ascii_or_utf16(data, needle):
    ascii_needle = needle.encode("ascii")
    utf16_needle = needle.encode("utf-16le")
    return (ascii_needle in data) or (utf16_needle in data)

def main():
    print("Checking for latest Ventoy release on GitHub...")
    try:
        req = urllib.request.Request(
            "https://api.github.com/repos/ventoy/Ventoy/releases/latest",
            headers={"User-Agent": "VentoidUpdateChecker/1.0"}
        )
        with urllib.request.urlopen(req) as res:
            release_data = json.loads(res.read().decode())
    except Exception as e:
        print(f"Error fetching latest release from GitHub API: {e}")
        sys.exit(1)

    tag_name = release_data.get("tag_name", "unknown")
    print(f"Latest release found: {tag_name}")

    zip_url = None
    zip_name = None
    for asset in release_data.get("assets", []):
        if asset["name"].endswith("-windows.zip"):
            zip_url = asset["browser_download_url"]
            zip_name = asset["name"]
            break

    if not zip_url:
        print("Error: Could not find Windows zip asset in the latest release.")
        sys.exit(1)

    temp_zip = "temp_ventoy_release.zip"
    print(f"Downloading {zip_name} from {zip_url}...")
    try:
        req = urllib.request.Request(zip_url, headers={"User-Agent": "VentoidUpdateChecker/1.0"})
        with urllib.request.urlopen(req) as response, open(temp_zip, "wb") as out_file:
            out_file.write(response.read())
    except Exception as e:
        print(f"Error downloading release archive: {e}")
        if os.path.exists(temp_zip):
            os.remove(temp_zip)
        sys.exit(1)

    print("Extracting and decompressing boot files...")
    try:
        with zipfile.ZipFile(temp_zip, "r") as z:
            boot_img_path = None
            core_img_xz_path = None
            ventoy_disk_img_xz_path = None

            for name in z.namelist():
                if name.endswith("boot/boot.img"):
                    boot_img_path = name
                elif name.endswith("boot/core.img.xz"):
                    core_img_xz_path = name
                elif name.endswith("ventoy/ventoy.disk.img.xz"):
                    ventoy_disk_img_xz_path = name

            if not (boot_img_path and core_img_xz_path and ventoy_disk_img_xz_path):
                print("Error: Could not locate all required files in the downloaded zip.")
                sys.exit(1)

            boot_img_bytes = z.read(boot_img_path)
            core_img_xz_bytes = z.read(core_img_xz_path)
            ventoy_disk_img_xz_bytes = z.read(ventoy_disk_img_xz_path)

            print("Decompressing core.img.xz...")
            core_img_bytes = lzma.decompress(core_img_xz_bytes)
            print("Decompressing ventoy.disk.img.xz...")
            ventoy_disk_img_bytes = lzma.decompress(ventoy_disk_img_xz_bytes)
    except Exception as e:
        print(f"Error extracting/decompressing zip contents: {e}")
        if os.path.exists(temp_zip):
            os.remove(temp_zip)
        sys.exit(1)

    # Clean up zip file
    if os.path.exists(temp_zip):
        os.remove(temp_zip)

    boot_sha = get_sha256(boot_img_bytes)
    core_sha = get_sha256(core_img_bytes)
    disk_sha = get_sha256(ventoy_disk_img_bytes)

    # Check Secure Boot Markers
    # fallback.efi and MokManager.efi were removed from the Ventoy EFI disk image
    # starting in v1.1.13/v1.1.14. Only BOOTX64.EFI and grubx64_real.efi are shipped.
    secure_boot_markers = ["BOOTX64.EFI", "grubx64_real.efi"]
    verified_markers = []
    missing_markers = []
    for marker in secure_boot_markers:
        if contains_ascii_or_utf16(ventoy_disk_img_bytes, marker):
            verified_markers.append(marker)
        else:
            missing_markers.append(marker)

    print("\n--- VENTOY LATEST RELEASE ANALYSIS ---")
    print(f"Version: {tag_name}")
    print(f"File boot.img size: {len(boot_img_bytes)} bytes, SHA-256: {boot_sha}")
    print(f"File core.img size: {len(core_img_bytes)} bytes, SHA-256: {core_sha}")
    print(f"File ventoy.disk.img size: {len(ventoy_disk_img_bytes)} bytes, SHA-256: {disk_sha}")
    print(f"Secure Boot chain: {'VERIFIED' if not missing_markers else 'MISSING MARKERS'}")
    if verified_markers:
        print(f"  Verified markers: {', '.join(verified_markers)}")
    if missing_markers:
        print(f"  Missing markers: {', '.join(missing_markers)}")

    # Load current hashes
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    assets_kt_path = os.path.join(project_root, "app/src/main/java/com/ventoid/app/install/InstallerAssets.kt")
    current_hashes = {}
    if os.path.exists(assets_kt_path):
        with open(assets_kt_path, "r", encoding="utf-8") as f:
            kt_content = f.read()
        
        boot_match = re.search(r'"boot/boot\.img"\s+to\s+"([A-Fa-f0-9]+)"', kt_content)
        core_match = re.search(r'"boot/core\.img"\s+to\s+"([A-Fa-f0-9]+)"', kt_content)
        disk_match = re.search(r'"ventoy/ventoy\.disk\.img"\s+to\s+"([A-Fa-f0-9]+)"', kt_content)
        
        if boot_match: current_hashes["boot/boot.img"] = boot_match.group(1).upper()
        if core_match: current_hashes["boot/core.img"] = core_match.group(1).upper()
        if disk_match: current_hashes["ventoy/ventoy.disk.img"] = disk_match.group(1).upper()

        if not (boot_match and core_match and disk_match):
            print(f"Warning: Partial parse of InstallerAssets.kt - boot:{bool(boot_match)} core:{bool(core_match)} disk:{bool(disk_match)}")
    else:
        print(f"Warning: InstallerAssets.kt not found at {assets_kt_path}")

    print("\n--- LOCAL BUNDLED ASSETS ---")
    if current_hashes:
        print(f"Local boot.img SHA-256: {current_hashes.get('boot/boot.img')}")
        print(f"Local core.img SHA-256: {current_hashes.get('boot/core.img')}")
        print(f"Local ventoy.disk.img SHA-256: {current_hashes.get('ventoy/ventoy.disk.img')}")
    else:
        print("Could not parse local bundled assets in InstallerAssets.kt")

    needs_update = False
    if current_hashes:
        if (boot_sha != current_hashes.get("boot/boot.img") or 
            core_sha != current_hashes.get("boot/core.img") or 
            disk_sha != current_hashes.get("ventoy/ventoy.disk.img")):
            needs_update = True

    if needs_update:
        print("\n[!] UPDATE AVAILABLE: The latest Ventoy release files are different from local bundled assets.")
    else:
        print("\n[*] UP TO DATE: Local bundled assets match the latest Ventoy release files.")

    # Check if run with --update flag
    if len(sys.argv) > 1 and sys.argv[1] == "--update":
        if not needs_update:
            print("No updates needed. Local assets are already up to date.")
            return

        print("\nUpdating assets...")
        
        # Write asset files
        assets_boot_dir = os.path.join(project_root, "app/src/main/assets/boot")
        assets_ventoy_dir = os.path.join(project_root, "app/src/main/assets/ventoy")
        os.makedirs(assets_boot_dir, exist_ok=True)
        os.makedirs(assets_ventoy_dir, exist_ok=True)

        with open(os.path.join(assets_boot_dir, "boot.img"), "wb") as f:
            f.write(boot_img_bytes)
        with open(os.path.join(assets_boot_dir, "core.img"), "wb") as f:
            f.write(core_img_bytes)
        with open(os.path.join(assets_ventoy_dir, "ventoy.disk.img"), "wb") as f:
            f.write(ventoy_disk_img_bytes)

        # Update README files
        clean_tag = tag_name.lstrip("v")
        with open(os.path.join(assets_boot_dir, "README.txt"), "w", encoding="utf-8") as f:
            f.write(f"boot.img and core.img from official Ventoy {clean_tag} release.\n")
            f.write("- boot.img: first 446 bytes used as MBR boot code (512 bytes).\n")
            f.write("- core.img: 2047 sectors (MBR style); used as-is (uncompressed).\n")

        with open(os.path.join(assets_ventoy_dir, "README.txt"), "w", encoding="utf-8") as f:
            f.write(f"ventoy.disk.img is a 32 MiB Ventoy EFI partition image generated from the official Ventoy {clean_tag} INSTALL tree.\n\n")
            f.write("Rebuild it with:\n\n")
            f.write(f"    VENTOY_SRC=/path/to/Ventoy-{clean_tag} bash scripts/build-ventoy-disk-img.sh\n\n")
            f.write("The script creates a FAT16 VTOYEFI image and copies the same grub, ventoy, EFI, and MOK assets that official Ventoy packages in INSTALL/ventoy_pack.sh.\n")

        # Update InstallerAssets.kt
        with open(assets_kt_path, "r", encoding="utf-8") as f:
            kt_content = f.read()

        kt_content = re.sub(
            r'("boot/boot\.img"\s+to\s+")[A-Fa-f0-9]+"',
            rf'\g<1>{boot_sha}"',
            kt_content
        )
        kt_content = re.sub(
            r'("boot/core\.img"\s+to\s+")[A-Fa-f0-9]+"',
            rf'\g<1>{core_sha}"',
            kt_content
        )
        kt_content = re.sub(
            r'("ventoy/ventoy\.disk\.img"\s+to\s+")[A-Fa-f0-9]+"',
            rf'\g<1>{disk_sha}"',
            kt_content
        )

        with open(assets_kt_path, "w", encoding="utf-8") as f:
            f.write(kt_content)

        print(f"Successfully updated local assets to Ventoy {tag_name}!")
    else:
        print("\nTo download and update local assets automatically, run:")
        print("python scripts/check_ventoy_updates.py --update")

if __name__ == "__main__":
    main()
