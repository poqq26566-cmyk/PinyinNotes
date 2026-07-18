package com.example.pinyinnotes

import android.net.Uri

data class Category(override val name: String, val uri: Uri) : NamedItem
