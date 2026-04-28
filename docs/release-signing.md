# Binary Release Signing

To ensure the integrity and authenticity of `sparrow-joinstr` releases, we use a PGP signing process. This document explains how maintainers can sign releases locally without exposing their private keys to GitHub.

## Overview

1.  **Multiple Binaries**: The build process produces artifacts for Linux, macOS, and Windows.
2.  **Manifest File**: A `manifest.txt` is generated containing the SHA256 hashes of all binaries.
3.  **PGP Signature**: The `manifest.txt` is signed with a PGP key resulting in `manifest.txt.asc`.
4.  **Verification**: Users can verify any binary by checking its hash against the signed manifest.

## Maintainer Workflow

### 1. Run the Build
Trigger the `Package` workflow manually on GitHub (via `Actions` -> `Package` -> `Run workflow`).

### 2. Download the Release Bundle
Once the workflow completes, download the artifact named `Sparrow-Release-Bundle`. This contains all platform-specific binaries in a single zip file.

> **Important:** Both maintainers must sign the **same CI-produced artifact bundle**. Do not build locally and sign separately — the builds are not guaranteed to be bit-for-bit reproducible across machines or environments.

### 3. Sign the Binaries
Extract the bundle and run the `sign_release.sh` script. Pass your GPG key ID as the third argument to ensure the correct key is used:

```bash
# Extract the bundle
unzip Sparrow-Release-Bundle.zip -d release-v2.3.2-joinstr.0.1.0

# Run the signing script with signer name and key ID
./sign_release.sh release-v2.3.2-joinstr.0.1.0 [signer_name] [key_id]
```

The script will:
- Generate `manifest.txt` with SHA256 hashes.
- Prompt you to sign it using your local GPG key to create `manifest.txt.[signer_name].asc`.

### 4. Create a GitHub Release
Create a new Release on GitHub and upload:
- All binaries from the `release-v2.3.2-joinstr.0.1.0` folder.
- The `manifest.txt` file.
- The `manifest.txt.[signer_name].asc` signature file(s).

## Multi-Signer Workflow (QcMrHyde & floppy)

Both `QcMrHyde` and `floppy` should sign the **same `Sparrow-Release-Bundle` artifact** downloaded from CI. Each maintainer downloads the same bundle and signs it with their own key, producing separate `.asc` files for the same `manifest.txt`.

### 1. Floppy Signs:
```bash
./sign_release.sh release-v2.3.2-joinstr.0.1.0 floppy <floppy_key_id>
# Produces: manifest.txt and manifest.txt.floppy.asc
```

### 2. QcMrHyde Signs:
```bash
./sign_release.sh release-v2.3.2-joinstr.0.1.0 qcmrhyde <qcmrhyde_key_id>
# Produces: manifest.txt.qcmrhyde.asc (same manifest.txt as above)
```

Users can then verify the release against either maintainer's signature.

## Verification for Users

Users can verify the release using the following steps:

1.  **Import the Developer Key**:
    ```bash
    gpg --import developer_key.asc
    ```
2.  **Verify the Manifest**:
    ```bash
    gpg --verify manifest.txt.asc manifest.txt
    ```
3.  **Verify the Binary Hash**:
    ```bash
    sha256sum -c manifest.txt --ignore-missing
    ```

Sparrow Wallet also includes a "Verify Download" tool in the UI that can automate this process if the manifest and signature are provided.
