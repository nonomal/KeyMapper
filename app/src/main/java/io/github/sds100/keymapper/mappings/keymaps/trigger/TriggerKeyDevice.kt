package io.github.sds100.keymapper.mappings.keymaps.trigger

import io.github.sds100.keymapper.system.devices.InputDeviceInfo
import kotlinx.serialization.Serializable

/**
 * Created by sds100 on 21/02/2021.
 */

@Serializable
sealed class TriggerKeyDevice : Comparable<TriggerKeyDevice> {
    override fun compareTo(other: TriggerKeyDevice) =
        this.javaClass.name.compareTo(other.javaClass.name)

    @Serializable
    object Internal : TriggerKeyDevice()

    @Serializable
    object Any : TriggerKeyDevice()

    @Serializable
    data class External(val descriptor: String, val name: String) : TriggerKeyDevice() {
        override fun compareTo(other: TriggerKeyDevice): Int {
            if (other !is External) {
                return super<TriggerKeyDevice>.compareTo(other)
            }

            return compareValuesBy(
                this,
                other,
                { it.name },
                { it.descriptor },
            )
        }
    }

    fun isSameDevice(other: TriggerKeyDevice): Boolean {
        if (other is External && this is External) {
            return other.descriptor == this.descriptor
        } else {
            return true
        }
    }

    fun isSameDevice(device: InputDeviceInfo): Boolean {
        if (this is External && device.isExternal) {
            return device.descriptor == this.descriptor
        } else {
            return true
        }
    }
}
