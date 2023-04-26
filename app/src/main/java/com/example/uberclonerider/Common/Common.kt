package com.example.uberclonerider.Common

import com.example.uberclonerider.Model.RiderModel

object Common {

    val RIDER_INFO_REFERENCE: String="Riders"
    var currentRider: RiderModel? = null

    fun buildWelcomeMessage(): String {
        return StringBuilder("Добро пожаловать, ")
            .append(currentRider!!.firstName)
            .append(" ")
            .append(currentRider!!.lastName)
            .toString()
    }
}