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

The PLK110 package set is rebuilt from this pinned image by [the image-specific overlay workflow](https://github.com/orumanayurpanjayat-ux/OneColor/actions/workflows/plk110-overlays.yml). It contains PLK110's own ColorOS framework and service patches, camera ABI overlays, guest `vold`/incremental pair, safety guards, and APEX payloads. The guest helper and PermissionController compatibility APK are shared OneOS files. SurfaceFlinger is generated from this exact image when the session starts.

Previous package files were built from the Find X9 Ultra IN image. The PLK110 package now has its own source directory and hashes; it excludes X9U camera, Theme Store, AI, and SystemUI overlays. No S24 Ultra boot or hardware test has been performed with this target, so the picker continues to mark it experimental and untested. A different ColorOS image or hash is not accepted by this entry and needs its own image and patch verification.
