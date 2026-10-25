package com.hyper.podsrx

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.hyper.podsrx.device.DeviceRepository
import com.hyper.podsrx.ui.HyperPodsScreen

open class MainActivity:ComponentActivity() {
    protected open val externalSettings=false
    private val permissionRequest=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(DeviceRepository.permissionsGranted())DeviceRepository.start()
        else DeviceRepository.message("连接耳机需要附近设备权限")
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val requested=intent.getStringExtra("device_address")
        if(externalSettings&&requested!=null&&requested!=DeviceRepository.state.value.address){finish();return}
        setContent {HyperPodsScreen(externalSettings,initialPage=intent.getStringExtra("page"),onConnect={connect()},onOverlay={
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
        },onSystemBluetooth={openSystemBluetooth()},onFinish={finish()})}
    }
    override fun onResume() {
        super.onResume()
        DeviceRepository.refreshLocalName()
        if(DeviceRepository.permissionsGranted()&&DeviceRepository.state.value.address.isNotBlank()&&!DeviceRepository.state.value.running)DeviceRepository.start()
    }
    private fun connect() {
        if(DeviceRepository.permissionsGranted())DeviceRepository.start()
        else permissionRequest.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.POST_NOTIFICATIONS))
    }
    private fun openSystemBluetooth() {
        val address=DeviceRepository.state.value.address
        if(address.isNotBlank()&&DeviceRepository.permissionsGranted()) {
            val details=Intent("com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS")
                .setPackage("com.android.settings")
                .putExtra("android.provider.extra.BLUETOOTH_ADDRESS",address)
                .putExtra(":settings:show_fragment_args",Bundle().apply{putString("device_address",address)})
            if(runCatching{startActivity(details)}.isSuccess)return
        }
        runCatching{startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))}
            .onFailure{DeviceRepository.message("无法打开系统蓝牙，请从系统设置进入")}
    }
}
class DeviceSettingsActivity:MainActivity(){override val externalSettings=true}
