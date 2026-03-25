package io.github.blad3mak3r.cardio.protocol

enum class DescribeTarget(val byte: Byte) {
    STATEMENT('S'.code.toByte()),
    PORTAL('P'.code.toByte());
}