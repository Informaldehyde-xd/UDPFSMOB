/* Common shape for compressed disc-image readers (ZsoFile, ChdFile) so
 * AndroidFsBackend can handle them with one code path instead of one per
 * format. */
package com.ps2manager.udpfsserver.udpfs

interface CompressedImage {
    val totalSize: Long
    fun readAt(position: Long, length: Int): ByteArray
    fun close()
}
