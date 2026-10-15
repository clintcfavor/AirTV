package dev.airtv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Publishes the _airplay._tcp and _raop._tcp Bonjour services through Android's
 * own mDNS stack so iPhones, iPads and Macs can find the TV.
 */
class ServiceAdvertiser(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val active = mutableMapOf<Int, NsdManager.RegistrationListener>()

    @Synchronized
    fun register(service: Int, name: String, type: String, port: Int, keys: Array<String>, values: Array<String>) {
        unregister(service)
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            setPort(port)
            keys.indices.forEach { setAttribute(keys[it], values[it]) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered ${info.serviceName} ($type) on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "registration of $type failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "unregistered $type")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "unregistration of $type failed: $errorCode")
            }
        }
        active[service] = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun unregister(service: Int) {
        active.remove(service)?.let { runCatching { nsd.unregisterService(it) } }
    }

    @Synchronized
    fun unregisterAll() {
        active.keys.toList().forEach { unregister(it) }
    }

    private companion object {
        const val TAG = "AirTV-nsd"
    }
}
