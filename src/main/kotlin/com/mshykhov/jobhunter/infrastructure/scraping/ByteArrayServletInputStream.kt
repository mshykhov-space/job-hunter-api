package com.mshykhov.jobhunter.infrastructure.scraping

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import java.io.ByteArrayInputStream

class ByteArrayServletInputStream(body: ByteArray) : ServletInputStream() {
    private val input = ByteArrayInputStream(body)

    override fun read(): Int = input.read()

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int = input.read(bytes, offset, length)

    override fun isFinished(): Boolean = input.available() == 0

    override fun isReady(): Boolean = true

    override fun setReadListener(readListener: ReadListener) {
        if (isFinished) readListener.onAllDataRead() else readListener.onDataAvailable()
    }
}
