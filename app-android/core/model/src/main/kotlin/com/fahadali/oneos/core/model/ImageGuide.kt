package com.fahadali.oneos.core.model

import java.util.Locale

/**
 * docs/download-images.md (0.4.42, owner: "people are confused which GZ to download"): for every
 * system image, the file to download (name, size, SHA-256) and every phone this build switches
 * with it, from [Profiles.BUILT_IN] itself, so the list is never out of step with the app.
 * ImageGuideTest compares the checked-in file with [markdown].
 */
object ImageGuide {
    fun markdown(images: List<ImageRef> = Images.ALL, profiles: List<PhoneProfile> = Profiles.BUILT_IN): String {
        val out = StringBuilder()
        out.append("# Which system image to download\n\n")
        out.append("Generated from the app's phone list (`ImageGuide`); `ImageGuideTest` keeps it in step. ")
        out.append("Download only the `.gz` of a system your phone is listed under, then pick it in OneOS. ")
        out.append("OneOS checks the file's SHA-256 before it uses it.\n\n")
        out.append("Downloads: hosted images have a resumable SourceForge link and are also in the Google Drive folder ")
            .append(Images.SHARED_FOLDER_URL).append(". Locally provided images have no download link; transfer the file to the phone and pick it in OneOS.\n")
        for (image in images) {
            val phones = profiles.filter { it.enabled }.mapNotNull { p -> p.choice(image.id)?.let { p to it } }
            out.append("\n## ").append(image.label).append("\n\n")
            out.append("- File: `").append(image.gzName).append("` (")
                .append(String.format(Locale.ROOT, "%.2f GB", image.gzBytes / 1e9)).append(")\n")
            out.append("- SHA-256: `").append(image.gzSha256).append("`\n")
            image.mirrorUrl?.let { out.append("- Download: ").append(it).append("\n") }
                ?: out.append("- Download: local file only; transfer `").append(image.gzName).append("` to the phone and pick it in OneOS.\n")
            if (phones.isEmpty()) {
                out.append("- Phones: none in this build\n")
                continue
            }
            out.append("- Phones:\n")
            for ((p, c) in phones) {
                out.append("  - ").append(p.label).append(" (").append(p.modelPrefix).append(", ").append(p.socModel).append(")")
                if (!c.tested) out.append(", not tested yet")
                out.append("\n")
            }
        }
        return out.toString()
    }
}
