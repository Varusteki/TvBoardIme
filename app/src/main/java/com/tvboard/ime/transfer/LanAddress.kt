package com.tvboard.ime.transfer

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/**
 * 局域网地址解析。
 *
 * 多网卡是这里的常态：电视通常同时存在 wlan0 / eth0 / p2p0（Wi-Fi Direct）与若干虚拟网卡。
 * 只保留私有网段（10/172.16-31/192.168）的 IPv4，并优先 wlan/eth。
 */
object LanAddress {

    data class Local(val address: Inet4Address, val ifName: String)

    @Suppress("DEPRECATION")
    fun all(context: Context): List<Local> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val result = ArrayList<Local>()
        try {
            for (network in cm.allNetworks) {
                val props = cm.getLinkProperties(network) ?: continue
                val ifName = props.interfaceName ?: ""
                for (linkAddress in props.linkAddresses) {
                    val addr = linkAddress.address
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || !addr.isSiteLocalAddress) continue
                    if (result.any { it.address.hostAddress == addr.hostAddress }) continue
                    result.add(Local(addr, ifName))
                }
            }
        } catch (ignored: Throwable) {
            // 权限缺失或系统异常：返回空，上层展示"未连接网络"
        }
        result.sortBy { if (isPreferredInterface(it.ifName)) 0 else 1 }
        return result
    }

    fun current(context: Context): Local? = all(context).firstOrNull()

    private fun isPreferredInterface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("ap")
    }
}
