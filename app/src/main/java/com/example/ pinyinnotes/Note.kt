package com.example.pinyinnotes

data class Note(
    val id: Long,
    var name: String,
    var content: String = ""
)
