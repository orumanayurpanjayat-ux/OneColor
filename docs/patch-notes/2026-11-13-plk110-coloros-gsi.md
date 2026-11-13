# Experimental PLK110 ColorOS 17 GSI

OneOS accepts this exact ColorOS GSI archive on the Snapdragon Galaxy S24 Ultra (SM8650):

- File: `XGSI_PLK110_17.0.0.100_arm64.zip`
- Download: [Pixeldrain](https://pixeldrain.com/u/VqJvx5SD)
- ZIP size: 3,029,357,770 bytes
- ZIP SHA-256: `e3b19a42f4770e2788903c679e8e2a7116bc28e4b272c7597c5e6c3e2ac4fb73`
- ZIP member: one `system.img` entry
- `system.img` size: 6,644,899,840 bytes
- `system.img` SHA-256: `43593ee631299f710fb091bc7f635627e91bd81fca7a8ab759ee3573cd621314`

The archive pin matches Pixeldrain's published file metadata. The image hash and ext4 signature were independently checked from the archive stream. During setup OneOS requires that the ZIP contain exactly one `system.img`, validates the archive CRC, ext4 magic, archive hash, and unpacked image hash, and streams the image without storing a second unpacked copy.

This option uses a separate image-bound package set whose file hashes are identical to `x9u-in-sultra`, as requested. Those framework, camera, telephony, and other patched files were built from the Find X9 Ultra IN image; they were not rebuilt for PLK110. No S24 Ultra boot or hardware test has been performed with this target. The picker labels it experimental and does not mark it tested. A different ColorOS image or hash is not accepted by this entry and needs its own image and patch verification.
