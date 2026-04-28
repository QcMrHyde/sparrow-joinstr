#!/bin/bash
set -e

# Sparrow PGP Signing Script
# This script generates a manifest.txt with SHA256 hashes for all binaries in a directory
# and signs it with GPG.

show_help() {
    echo "Usage: $0 [directory] [signer_name] [key_id]"
    echo "  [directory]:   Directory containing the binaries to sign (defaults to current directory)"
    echo "  [signer_name]: Optional name of the signer (e.g. 'floppy') to append to the signature filename"
    echo "  [key_id]:      Optional GPG key ID or fingerprint to sign with (e.g. 'ABCDEF1234567890')"
    echo "                 Defaults to GPG's default key. Recommended for multi-maintainer setups."
    echo ""
    echo "This script will:"
    echo "1. Generate manifest.txt with SHA256 hashes of all Sparrow binaries."
    echo "2. Prompt to sign manifest.txt with your GPG key."
}

if [[ "$1" == "--help" || "$1" == "-h" ]]; then
    show_help
    exit 0
fi

TARGET_DIR="${1:-.}"
SIGNER_NAME="${2}"
KEY_ID="${3}"
SIG_SUFFIX="asc"
if [ -n "$SIGNER_NAME" ]; then
    SIG_SUFFIX="$SIGNER_NAME.asc"
fi

if [ ! -d "$TARGET_DIR" ]; then
    echo "Error: Directory $TARGET_DIR does not exist."
    exit 1
fi

cd "$TARGET_DIR"

echo "Generating manifest.txt for binaries in $TARGET_DIR..."

# Define binary extensions to include
EXTENSIONS=("zip" "tar.gz" "msi" "deb" "rpm" "dmg")

# Create temporary file for manifest
MANIFEST_FILE="manifest.txt"
rm -f "$MANIFEST_FILE"

# Detect SHA256 tool — stored as an array to avoid word-splitting issues
if command -v sha256sum >/dev/null 2>&1; then
    SHA_CMD=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
    SHA_CMD=(shasum -a 256)
else
    echo "Error: Neither sha256sum nor shasum was found. Please install one of them."
    exit 1
fi

# Generate hashes
for ext in "${EXTENSIONS[@]}"; do
    while IFS= read -r file; do
        filename=$(basename "$file")
        echo "Processing $filename..."
        "${SHA_CMD[@]}" "$filename" >> "$MANIFEST_FILE"
    done < <(find . -maxdepth 1 -type f -name "*.$ext" | sort)
done

# Use -s (non-empty) so a failed hash that produced no output is also caught
if [ ! -s "$MANIFEST_FILE" ]; then
    echo "No binaries found to sign in $TARGET_DIR."
    exit 0
fi

echo ""
echo "Manifest generated in $MANIFEST_FILE"
cat "$MANIFEST_FILE"
echo ""

# Sign the manifest
if command -v gpg >/dev/null 2>&1; then
    echo "Signing manifest.txt with GPG..."
    echo "You may be prompted for your PGP passphrase."

    # Build the gpg command; use --local-user when a key ID is supplied so the
    # correct key is chosen in multi-maintainer setups with multiple keys in the ring.
    GPG_ARGS=(--detach-sign --armor --output "$MANIFEST_FILE.$SIG_SUFFIX")
    if [ -n "$KEY_ID" ]; then
        GPG_ARGS+=(--local-user "$KEY_ID")
    fi
    GPG_ARGS+=("$MANIFEST_FILE")

    gpg "${GPG_ARGS[@]}"

    echo ""
    echo "Success! Created:"
    echo "  - $MANIFEST_FILE"
    echo "  - $MANIFEST_FILE.$SIG_SUFFIX"
else
    echo "Warning: gpg not found. Please sign manifest.txt manually:"
    echo "  gpg --detach-sign --armor manifest.txt"
fi
