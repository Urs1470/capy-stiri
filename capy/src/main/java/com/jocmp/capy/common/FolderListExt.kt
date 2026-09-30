package com.jocmp.capy.common

import com.jocmp.capy.Folder

// This fork: the digest's sections come first, in their own order, then the other folders alphabetically.
fun List<Folder>.sortedByTitle() =
    sortedWith(compareBy(DigestFolderOrder) { it.title })
