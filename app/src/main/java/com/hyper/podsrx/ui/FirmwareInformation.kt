package com.hyper.podsrx.ui

import com.hyper.podsrx.R
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.i18n.Strings

internal data class DeviceInformationRow(val key: String, val title: String, val value: String)

internal fun firmwareRows(state: PodsState, strings: Strings): List<DeviceInformationRow> = buildList {
    add(DeviceInformationRow("firmware", strings(R.string.firmware_version), state.firmware.ifBlank {
        if (state.model.isNotBlank()) strings(R.string.firmware_not_provided) else "\u2014"
    }))
}
