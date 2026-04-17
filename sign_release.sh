#!/bin/bash
set -e

# Sparrow PGP Signing Script
# This script generates a manifest.txt with SHA256 hashes for all binaries in a directory
# and signs it with GPG.

show_help() {
    echo "Usage: $0 [directory] [signer_name]"
    echo "  [directory]: Directory containing the binaries to sign (defaults to current directory)"
    echo "  [signer_name]: Optional name of the signer (e.g. 'floppy') to append to the signature filename"
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

# Detect SHA256 tool
if command -v sha256sum >/dev/null 2>&1; then
    SHA_TOOL="sha256sum"
elif command -v shasum >/dev/null 2>&1; then
    SHA_TOOL="shasum -a 256"
else
    echo "Error: Neither sha256sum nor shasum was found. Please install one of them."
    exit 1
fi

# Generate hashes
for ext in "${EXTENSIONS[@]}"; do
    # Use find to handle files with spaces or unusual names, but keep it simple for standard releases
    find . -maxdepth 1 -type f -name "*.$ext" | sort | while read -r file; do
        filename=$(basename "$file")
        echo "Processing $filename..."
        $SHA_TOOL "$filename" >> "$MANIFEST_FILE"
    done
done

if [ ! -f "$MANIFEST_FILE" ]; then
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
    
    # We use --detach-sign and --armor to create a .asc file
    # We also use --clear-sign as an alternative if the user prefers, 
    # but Sparrow's DownloadVerifierDialog supports detached signatures.
    gpg --detach-sign --armor --output "$MANIFEST_FILE.$SIG_SUFFIX" "$MANIFEST_FILE"
    
    echo ""
    echo "Success! Created:"
    echo "  - $MANIFEST_FILE"
    echo "  - $MANIFEST_FILE.$SIG_SUFFIX"
else
    echo "Warning: gpg not found. Please sign manifest.txt manually:"
    echo "  gpg --detach-sign --armor manifest.txt"
fi
