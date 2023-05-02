package com.example.uberclonerider.Callback

import com.example.uberclonerider.Model.DriverGeoModel

interface FirebaseDriverInfoListener {
    fun onDriverInfoLoadSuccess(driverGeoModel: DriverGeoModel?)
}