package com.icuxika.vturbo.commons.extensions

/**
 * 格式化网速
 *
 * @param scale 保留小数点位数
 */
fun Double.toSpeed(scale: Int = 2): String {
    if (this <= 1024.0) {
        return "%.${scale}f".format(this) + " B/s"
    }
    if (this > 1024.0 && this <= 1024.0 * 1024.0) {
        val transferSpeedInKBPerSec = this / (1024).toDouble()
        return "%.${scale}f".format(transferSpeedInKBPerSec) + " KB/s"
    }
    val transferSpeedInMBPerSec = this / (1024 * 1024).toDouble()
    return "%.${scale}f".format(transferSpeedInMBPerSec) + " MB/s"
}