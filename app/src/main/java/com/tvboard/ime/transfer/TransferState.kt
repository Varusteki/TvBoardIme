package com.tvboard.ime.transfer

/** 传输层对外暴露的状态。二维码面板只依赖这个类型。 */
sealed class TransferState {

    object Idle : TransferState()
    object Starting : TransferState()
    object NoNetwork : TransferState()
    object Suspended : TransferState()

    data class Ready(val ip: String, val port: Int) : TransferState()

    data class Connected(val ip: String, val port: Int, val count: Int) : TransferState()

    val endpoint: String?
        get() = when (this) {
            is Ready -> "http://$ip:$port"
            is Connected -> "http://$ip:$port"
            else -> null
        }

    val isActive: Boolean
        get() = this is Ready || this is Connected
}
