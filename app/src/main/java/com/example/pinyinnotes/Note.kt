package com.example.pinyinnotes

import android.net.Uri

data class Note(override val name: String, val uri: Uri, val lastModified: Long = 0L) : NamedItem
